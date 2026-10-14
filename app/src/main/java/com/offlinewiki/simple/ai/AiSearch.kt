package com.offlinewiki.simple.ai

import android.util.Log
import com.offlinewiki.simple.BuildConfig
import com.offlinewiki.simple.llm.GenerationOptions
import com.offlinewiki.simple.llm.LlmManager
import com.offlinewiki.simple.zim.SearchHit
import com.offlinewiki.simple.zim.ZimRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList

enum class AskStage { REWRITING, SEARCHING, READING, ANSWERING }

data class Source(val path: String, val title: String)

sealed interface AskEvent {
    data class Progress(val stage: AskStage) : AskEvent
    data class Queries(val queries: List<String>) : AskEvent
    /** The answer written so far; replaces what was shown before. */
    data class Answer(val text: String) : AskEvent
    data class Answered(val text: String, val sources: List<Source>) : AskEvent
    /** The articles do not contain the answer; [results] are the plain search results to show instead. */
    data class NotFound(val reason: String, val results: List<SearchHit>) : AskEvent
}

/**
 * Answers a question from the offline articles: the model writes search
 * queries, the ZIM full-text index finds articles, the best-matching passages
 * are picked by keyword overlap, and the model answers from those passages only.
 */
class AiSearch(private val zim: ZimRepository, private val llm: LlmManager) {

    fun ask(question: String): Flow<AskEvent> = flow {
        val started = System.currentTimeMillis()
        emit(AskEvent.Progress(AskStage.REWRITING))
        val queries = rewrite(question)
        debug { "timing rewrite=${System.currentTimeMillis() - started}ms" }
        emit(AskEvent.Queries(queries))
        debug { "queries=$queries" }

        emit(AskEvent.Progress(AskStage.SEARCHING))
        val hits = retrieve(question, queries)
        if (hits.isEmpty()) {
            emit(AskEvent.NotFound("No articles matched this question.", emptyList()))
            return@flow
        }

        emit(AskEvent.Progress(AskStage.READING))
        // Two hits can be redirects to the same article, so articles are keyed by the path they resolve to.
        val articles = LinkedHashMap<String, List<Passage>>()
        for (hit in hits) {
            if (articles.size == ARTICLES_TO_READ) break
            val content = zim.read(hit.path) ?: continue
            if (!content.mimeType.startsWith("text/html")) continue
            val path = content.redirectTo ?: hit.path
            if (path in articles) continue
            articles[path] = Passages.fromHtml(String(content.data, Charsets.UTF_8), path, hit.title)
        }
        val coverage = Passages.coverage(question, articles.values)
        debug { "coverage=$coverage articles=${articles.keys}" }
        if (coverage < MIN_COVERAGE) {
            // Checked before the model is asked: given loosely related passages a small model will make something up.
            emit(AskEvent.NotFound("No article covers this question.", hits))
            return@flow
        }
        val rank = articles.keys.withIndex().associate { it.value to it.index }
        // The best-matching article comes first in the prompt, each article in reading order.
        val chosen = Passages.select(articles.values.flatten(), question, queries, PASSAGE_CHAR_BUDGET, MAX_PASSAGES)
            .sortedWith(compareBy({ rank[it.articlePath] }, { it.index }))
        if (chosen.isEmpty()) {
            emit(AskEvent.NotFound("The articles found do not seem to talk about this.", hits))
            return@flow
        }

        emit(AskEvent.Progress(AskStage.ANSWERING))
        debug { "timing retrieval done=${System.currentTimeMillis() - started}ms promptChars=${Prompts.answer(question, chosen).length}" }
        val raw = StringBuilder()
        llm.generate(Prompts.answer(question, chosen), ANSWER_OPTIONS)
            // Small models sometimes loop; stop generating as soon as the reply starts repeating itself.
            .takeWhile { !Answers.isLooping(raw) }
            .collect { piece ->
                if (raw.isEmpty()) debug { "timing first token=${System.currentTimeMillis() - started}ms" }
                raw.append(piece)
                // Hold back the first few characters so a NOT_FOUND reply never flashes up as an answer.
                if (raw.length >= Prompts.NOT_FOUND.length && !Answers.saysNotFound(raw)) {
                    emit(AskEvent.Answer(Answers.visibleText(raw)))
                }
            }

        debug { "timing done=${System.currentTimeMillis() - started}ms answerChars=${raw.length}" }
        val text = Answers.finalText(raw)
        debug { "passages=" + chosen.joinToString(" | ") { it.articleTitle + "#" + it.index } + " raw=" + raw }
        when {
            Answers.saysNotFound(raw) || text.isBlank() ->
                emit(AskEvent.NotFound("The articles do not contain the answer to this question.", hits))
            !Answers.isGrounded(text, chosen) ->
                emit(AskEvent.NotFound("An answer could not be confirmed from the articles, so it is not shown.", hits))
            !Answers.isAboutSubject(question, text, chosen) ->
                emit(AskEvent.NotFound("The articles do not contain the answer to this question.", hits))
            else -> {
                // Worked out here from the text; a model this small does not cite its passages reliably.
                val sources = Answers.supportingPassages(text, chosen).ifEmpty { chosen.take(1) }
                    .map { Source(it.articlePath, it.articleTitle) }.distinct()
                emit(AskEvent.Answered(text, sources))
            }
        }
    }.flowOn(Dispatchers.Default)

    /** Step 1: the model turns the question into keyword queries. A plain keyword query is always included. */
    private suspend fun rewrite(question: String): List<String> {
        val reply = llm.generate(Prompts.rewrite(question), REWRITE_OPTIONS).toList().joinToString("")
        debug { "rewrite raw=$reply" }
        val fallback = Text.contentWords(question).distinct().take(6).joinToString(" ")
        // The model sometimes copies a line from the examples; keep it only if it is about this question.
        val asked = Text.keywords(question).toSet()
        val exampleLines = Prompts.REWRITE_EXAMPLES.flatMap { it.second.lines() }.map { it.lowercase() }.toSet()
        val written = Answers.parseQueries(reply).filter { query ->
            query.lowercase() !in exampleLines || Text.keywords(query).any { it in asked }
        }
        return (written + fallback).filter { it.isNotBlank() }.distinctBy { it.lowercase() }.take(MAX_QUERIES)
    }

    /**
     * Step 2: run every query against the full-text index and merge the result
     * lists, favouring articles that rank well in several. An article whose
     * title is exactly a query or a word of the question is the most likely
     * subject, so it goes to the front.
     */
    private fun retrieve(question: String, queries: List<String>): List<SearchHit> {
        val scores = LinkedHashMap<String, Double>()
        val byPath = HashMap<String, SearchHit>()
        fun add(hit: SearchHit, score: Double) {
            scores[hit.path] = (scores[hit.path] ?: 0.0) + score
            byPath.putIfAbsent(hit.path, hit)
        }
        fun titleMatch(words: String): SearchHit? {
            val wanted = Text.keywords(words)
            if (wanted.isEmpty()) return null
            return zim.suggest(words, limit = 5).firstOrNull { Text.keywords(it.title) == wanted }
        }
        for (query in queries) {
            zim.search(query, limit = HITS_PER_QUERY, withSnippets = false).forEachIndexed { rank, hit ->
                add(hit, 1.0 / (10 + rank))
            }
            titleMatch(query)?.let { add(it, TITLE_MATCH_SCORE) }
        }
        // Names made of several words in the question ("Ray Charles") beat single words ("Ray", "Charles").
        val words = Text.contentWords(question)
        val covered = HashSet<String>()
        for (size in minOf(3, words.size) downTo 1) {
            for (start in 0..words.size - size) {
                val gram = words.subList(start, start + size)
                if (size == 1 && gram[0] in covered) continue
                val hit = titleMatch(gram.joinToString(" ")) ?: continue
                add(hit, if (size > 1) TITLE_MATCH_SCORE else TITLE_MATCH_SCORE / 2)
                covered += gram
            }
        }
        return scores.entries.sortedByDescending { it.value }.map { byPath.getValue(it.key) }
    }

    private inline fun debug(message: () -> String) {
        if (BuildConfig.DEBUG) Log.d("AiSearch", message())
    }

    private companion object {
        const val MAX_QUERIES = 4
        const val HITS_PER_QUERY = 5
        const val ARTICLES_TO_READ = 8
        // About 450 tokens. On a mid-range phone the model reads roughly 45 tokens a second before it
        // can start answering, so every extra passage adds two to three seconds of waiting.
        const val PASSAGE_CHAR_BUDGET = 1900
        const val MAX_PASSAGES = 4

        const val MIN_COVERAGE = 0.75
        // Twice the score of a first-place full-text hit.
        const val TITLE_MATCH_SCORE = 0.2

        val REWRITE_OPTIONS = GenerationOptions(
            systemInstruction = Prompts.REWRITE_SYSTEM,
            examples = Prompts.REWRITE_EXAMPLES,
            temperature = 0.0,
            topK = 1,
            maxOutputTokens = 40,
        )
        val ANSWER_OPTIONS = GenerationOptions(
            systemInstruction = Prompts.ANSWER_SYSTEM,
            examples = Prompts.ANSWER_EXAMPLES,
            temperature = 0.1,
            topK = 10,
            maxOutputTokens = 220,
        )
    }
}

object Prompts {
    const val NOT_FOUND = "NOT_FOUND"

    const val REWRITE_SYSTEM =
        "You write search keywords for an encyclopedia. The user gives a question. " +
            "Reply with three lines. Each line is one short search of one to three words: " +
            "the name of the main subject first, then other important nouns. " +
            "Do not answer the question. Do not write anything else."

    val REWRITE_EXAMPLES = listOf(
        rewrite("Why do volcanoes erupt?") to "volcano\nvolcano eruption\nmagma",
        rewrite("How tall is the Eiffel Tower?") to "Eiffel Tower\nEiffel Tower height\ntower Paris",
    )

    fun rewrite(question: String) = "Question: $question"

    const val ANSWER_SYSTEM =
        "You answer questions using only the numbered passages the user gives you. " +
            "The passages come from Simple English Wikipedia. " +
            "Rules: 1. Use only facts written in the passages. Never use your own knowledge. " +
            "2. If the passages do not contain the answer, reply with exactly $NOT_FOUND and nothing else. " +
            "3. Answer in one to three short, full sentences of simple English."

    // One answer written as a full sentence and one refusal, so the model sees both outcomes.
    // Without them Gemma 3 1B tends to reply with a bare fragment such as "Canberra".
    private val EXAMPLE_PASSAGES = listOf(
        Passage("", "Honey bee", null, 0, "Honey bees live in large groups in a hive. They make honey from the nectar of flowers."),
        Passage("", "Hive", null, 0, "A hive is a place where bees live. People who keep bees are called beekeepers."),
    )
    val ANSWER_EXAMPLES = listOf(
        answer("What do honey bees make honey from?", EXAMPLE_PASSAGES) to "Honey bees make honey from the nectar of flowers.",
        answer("How fast can a honey bee fly?", EXAMPLE_PASSAGES) to NOT_FOUND,
    )

    fun answer(question: String, passages: List<Passage>): String = buildString {
        append("Passages:\n")
        passages.forEachIndexed { i, p -> append("[${i + 1}] ${p.articleTitle}: ${p.text}\n") }
        append("\nQuestion: $question")
    }
}

/** Parsing and checking of what the model writes. */
object Answers {
    // The same one to four words three times in a row, as in "honey, honey, honey".
    private val REPEATED_PHRASE = Regex("(?i)\\b(\\w+(?: \\w+){0,3})(?:,? (?:and )?\\1\\b){2,}")

    private val SENTENCE_WORDS = setOf("is", "are", "was", "were", "the", "a", "an")

    /** Model output without reasoning markers such as <think>, which some models emit. */
    private fun clean(raw: CharSequence): String =
        raw.toString().replace(Regex("(?s)<think>.*?</think>"), "").replace(Regex("</?[a-zA-Z_|]+>"), "")
            .replace("**", "").replace(REPEATED_PHRASE, "$1")

    fun parseQueries(reply: String): List<String> =
        clean(reply).lineSequence()
            .map { it.trim().trimStart('-', '*', '•').replace(Regex("^\\d+[.)]\\s*"), "").trim().trim('"', '\'') }
            .filter { !it.startsWith("Question", ignoreCase = true) && !it.endsWith(":") }
            .filter { it.isNotEmpty() && it.length <= 60 && it.split(' ').size <= 5 }
            // A line that reads like a sentence is the model answering instead of writing keywords.
            .filter { line -> !line.endsWith(".") && !line.endsWith("?") && line.lowercase().split(' ').none { it in SENTENCE_WORDS } }
            .distinct()
            .take(4)
            .toList()

    fun saysNotFound(raw: CharSequence): Boolean =
        raw.contains("NOT_FOUND", ignoreCase = true) || raw.trim().toString().equals("not found", ignoreCase = true)

    /** The answer without labels the model may add ("Answer:", "Sources: ...") or passage markers such as [2]. */
    fun visibleText(raw: CharSequence): String {
        val text = clean(raw).trim().replace(Regex("(?i)^answer\\s*:\\s*"), "")
        val cut = Regex("(?i)\\n?\\s*\\**sources?\\**\\s*:").find(text)?.range?.first ?: text.length
        val body = text.substring(0, cut).replace(Regex("\\s*\\[\\d+]"), "").trim()
        return sentences(body).distinctBy { it.lowercase() }.joinToString(" ")
    }

    /** [visibleText] for a finished reply: a sentence cut off by the length limit is dropped. */
    fun finalText(raw: CharSequence): String {
        val parts = sentences(visibleText(raw))
        val last = parts.lastOrNull() ?: return ""
        val complete = last.last() in ".!?" || parts.size == 1
        return (if (complete) parts else parts.dropLast(1)).joinToString(" ")
    }

    private fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }

    /** True once the reply repeats a whole sentence or keeps emitting reasoning markers. */
    fun isLooping(raw: CharSequence): Boolean {
        if (Regex("<think>").findAll(raw).count() > 2) return true
        if (REPEATED_PHRASE.containsMatchIn(raw)) return true
        val complete = sentences(clean(raw)).dropLast(1).map { it.lowercase() }
        return complete.size != complete.distinct().size
    }

    /**
     * A cheap check that the answer was drawn from the passages and not from
     * the model's memory. Every number in it must appear in the passages, and
     * each sentence must have most of its content words in one single passage:
     * a sentence stitched together from two unrelated passages ("Ray Charles"
     * from one, "pancakes for breakfast" from another) states something that
     * no article says.
     */
    fun isGrounded(answer: String, passages: List<Passage>): Boolean {
        val source = passages.joinToString(" ") { it.articleTitle + " " + it.text }
        val sourceDigits = Regex("\\d+").findAll(source.replace(",", "")).map { it.value }.toSet()
        val answerDigits = Regex("\\d+").findAll(answer.replace(",", "")).map { it.value }
        if (answerDigits.any { it !in sourceDigits }) return false
        return sentences(answer).all { support(it, passages).second >= MIN_SENTENCE_SUPPORT }
    }

    /**
     * False when the question names someone or something ("Ray Charles") and
     * the passages the answer rests on never mention that name: the answer is
     * then about something else, however well the passages support it.
     */
    fun isAboutSubject(question: String, answer: String, passages: List<Passage>): Boolean {
        val names = names(question)
        if (names.isEmpty()) return true
        return supportingPassages(answer, passages).any { p ->
            (Text.keywords(p.articleTitle) + Text.keywords(p.text)).containsAll(names)
        }
    }

    /** Capitalised words after the first one, as keywords. */
    private fun names(sentence: String): Set<String> =
        Regex("\\p{Lu}[\\p{L}\\p{Nd}]+").findAll(sentence.substringAfter(' ', ""))
            .flatMap { Text.keywords(it.value) }.toSet()

    /** The passages the answer rests on: for each sentence, the one that supports it best. */
    fun supportingPassages(answer: String, passages: List<Passage>): List<Passage> =
        sentences(answer).mapNotNull { support(it, passages).first }.distinct()

    /** The passage sharing the most content words with [sentence], and the share of the sentence's words it has. */
    private fun support(sentence: String, passages: List<Passage>): Pair<Passage?, Double> {
        val terms = Text.keywords(sentence).filter { it.length > 3 }.distinct()
        if (terms.isEmpty()) return null to 1.0
        // Names in the sentence (capitalised words after the first) must all be in the passage that backs it.
        val names = names(sentence)
        return passages.mapNotNull { p ->
            val text = (Text.keywords(p.articleTitle) + Text.keywords(p.text)).toSet()
            if (!text.containsAll(names)) null else p to terms.count { it in text }.toDouble() / terms.size
        }.maxWithOrNull(
            // On a tie, prefer the article the sentence names: "Alexander Graham Bell" is best backed by his own article.
            compareBy<Pair<Passage, Double>> { it.second }
                .thenBy { (p, _) -> Text.keywords(p.articleTitle).let { t -> t.isNotEmpty() && Text.keywords(sentence).containsAll(t) } },
        ) ?: (null to 0.0)
    }

    private const val MIN_SENTENCE_SUPPORT = 0.6
}
