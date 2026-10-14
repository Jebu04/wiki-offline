package com.offlinewiki.simple.ai

import org.jsoup.Jsoup
import kotlin.math.ln

/** A short piece of one article, small enough to hand to the model. */
data class Passage(
    val articlePath: String,
    val articleTitle: String,
    val section: String?,
    /** Position inside the article; 0 is the opening paragraph. */
    val index: Int,
    val text: String,
)

object Passages {
    private const val TARGET_CHARS = 450
    private const val MAX_ARTICLE_CHARS = 40_000

    private const val REMOVE = "script, style, noscript, sup.reference, .mw-editsection, .navbox, .reflist, " +
        ".references, .noprint, .hatnote, .metadata, .mw-empty-elt, .thumbcaption .magnify"

    // Sections at the end of an article that hold links and citations rather than facts.
    private val TAIL_SECTIONS = setOf(
        "references", "other websites", "related pages", "notes", "sources", "further reading",
        "external links", "bibliography", "citations", "footnotes",
    )

    /** Strips an article's HTML down to plain text and splits it into passages. */
    fun fromHtml(html: String, path: String, title: String): List<Passage> {
        val doc = Jsoup.parse(html)
        doc.select(REMOVE).remove()

        val passages = ArrayList<Passage>()
        var section: String? = null
        var skipping = false
        var total = 0
        val buffer = StringBuilder()

        fun flush() {
            val text = buffer.toString().trim()
            buffer.setLength(0)
            if (text.length >= 40) passages += Passage(path, title, section, passages.size, text)
        }

        for (el in doc.body().select("h2, h3, p, li, tr, dd")) {
            if (total > MAX_ARTICLE_CHARS) break
            val tag = el.normalName()
            if (tag == "h2" || tag == "h3") {
                flush()
                val heading = el.text().trim()
                if (tag == "h2") skipping = heading.lowercase() in TAIL_SECTIONS
                section = heading.ifEmpty { null }
                continue
            }
            if (skipping) continue
            // A paragraph inside a list item or table row is already covered by its container.
            if (el.parents().any { it !== el && it.normalName() in setOf("li", "tr", "dd") }) continue
            val text = el.text().replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) continue
            total += text.length
            for (piece in splitLong(text)) {
                if (buffer.isNotEmpty() && buffer.length + piece.length > TARGET_CHARS) flush()
                if (buffer.isNotEmpty()) buffer.append(' ')
                buffer.append(piece)
            }
        }
        flush()
        return passages
    }

    /** Breaks a paragraph that is longer than a passage at sentence ends. */
    private fun splitLong(text: String): List<String> {
        if (text.length <= TARGET_CHARS) return listOf(text)
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (sentence in text.split(Regex("(?<=[.!?])\\s+"))) {
            if (current.isNotEmpty() && current.length + sentence.length > TARGET_CHARS) {
                out += current.toString()
                current.setLength(0)
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence.take(TARGET_CHARS * 2))
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    /**
     * The share of the question's keywords found together in the best single
     * article, from 0 to 1. A low value means no article is about the whole
     * question: its keywords are only found scattered over unrelated articles.
     */
    fun coverage(question: String, articles: Collection<List<Passage>>): Double {
        val terms = Text.keywords(question).toSet()
        if (terms.isEmpty()) return 0.0
        return articles.filter { it.isNotEmpty() }.maxOfOrNull { passages ->
            val text = (Text.keywords(passages[0].articleTitle) + passages.flatMap { Text.keywords(it.text) }).toSet()
            terms.count { it in text }.toDouble() / terms.size
        } ?: 0.0
    }

    /**
     * Ranks passages by how many of the question's keywords they contain, with
     * rarer keywords counting for more, and keeps the best ones that fit in
     * [charBudget]. Passages with no keyword in common with the question are
     * never returned.
     *
     * @param extraTerms keywords from the rewritten search queries; they count for half.
     */
    fun select(
        passages: List<Passage>,
        question: String,
        extraTerms: Collection<String>,
        charBudget: Int,
        maxPassages: Int = 6,
    ): List<Passage> {
        if (passages.isEmpty()) return emptyList()
        val questionTerms = Text.keywords(question).toSet()
        val otherTerms = extraTerms.flatMap { Text.keywords(it) }.toSet() - questionTerms
        if (questionTerms.isEmpty() && otherTerms.isEmpty()) return emptyList()

        val termSets = passages.map { Text.keywords(it.text).toSet() + Text.keywords(it.articleTitle) }
        val df = HashMap<String, Int>()
        for (terms in termSets) for (t in terms) df[t] = (df[t] ?: 0) + 1
        fun idf(term: String) = ln(1.0 + passages.size.toDouble() / (df[term] ?: 1))

        val scored = passages.indices.map { i ->
            val terms = termSets[i]
            val main = questionTerms.filter { it in terms }.sumOf { idf(it) }
            val extra = otherTerms.filter { it in terms }.sumOf { idf(it) } * 0.5
            // Opening paragraphs define the subject, so they win ties.
            val lead = if (passages[i].index == 0) 1.15 else 1.0
            // An article named after words in the question ("Sun", "Ray Charles") is probably its subject.
            val titleTerms = Text.keywords(passages[i].articleTitle)
            val subject = if (titleTerms.isNotEmpty() && questionTerms.containsAll(titleTerms)) 1.5 else 1.0
            // Without at least one of the question's own words a passage is not evidence for it.
            val score = if (main == 0.0 && questionTerms.isNotEmpty()) 0.0 else (main + extra) * lead * subject
            i to score
        }.filter { it.second > 0.0 }.sortedByDescending { it.second }

        val chosen = ArrayList<Passage>()
        var used = 0
        for ((i, _) in scored) {
            val p = passages[i]
            val cost = p.text.length + p.articleTitle.length + 8
            if (used + cost > charBudget) continue
            chosen += p
            used += cost
            if (chosen.size == maxPassages) break
        }
        return chosen
    }
}

object Text {
    private val STOPWORDS = setOf(
        "a", "an", "the", "and", "or", "but", "of", "in", "on", "at", "to", "for", "from", "by", "with", "about",
        "as", "into", "is", "are", "was", "were", "be", "been", "being", "am", "do", "does", "did", "has", "have",
        "had", "can", "could", "will", "would", "should", "may", "might", "must", "it", "its", "this", "that",
        "these", "those", "there", "their", "they", "them", "he", "she", "his", "her", "him", "we", "you", "your",
        "i", "me", "my", "our", "us", "what", "which", "who", "whom", "whose", "when", "where", "why", "how",
        "not", "no", "so", "than", "then", "too", "very", "also", "if", "any", "some", "many", "much", "more",
        "most", "tell", "please", "explain", "describe", "mean", "means",
    )

    /** Lower-cased content words with simple endings removed, so "volcanoes" matches "volcano". */
    fun keywords(text: String): List<String> = contentWords(text).map(::stem)

    /** Lower-cased words without the common ones, as written. Used to build search queries. */
    fun contentWords(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotEmpty() && it !in STOPWORDS }

    /** Crude, but applied to both sides of every comparison: "invented", "inventor" and "invention" all meet at "invent". */
    private fun stem(word: String): String {
        val base = plural(word)
        return when {
            base.length > 5 && base.endsWith("ing") -> base.dropLast(3)
            base.length > 6 && base.endsWith("ion") -> base.dropLast(3)
            base.length > 4 && base.endsWith("ed") -> base.dropLast(2)
            base.length > 5 && (base.endsWith("or") || base.endsWith("er")) -> base.dropLast(2)
            else -> base
        }
    }

    private fun plural(word: String): String = when {
        word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
        word.length > 4 && word.endsWith("oes") -> word.dropLast(2)
        word.length > 4 && (word.endsWith("ches") || word.endsWith("shes") || word.endsWith("sses") || word.endsWith("xes")) -> word.dropLast(2)
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") -> word.dropLast(1)
        else -> word
    }
}
