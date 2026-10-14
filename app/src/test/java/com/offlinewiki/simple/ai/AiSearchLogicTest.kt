package com.offlinewiki.simple.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSearchLogicTest {
    private fun passage(title: String, index: Int, text: String) = Passage("p/$title", title, null, index, text)

    @Test
    fun keywordsDropStopwordsAndPlurals() {
        assertEquals(listOf("volcano", "erupt"), Text.keywords("Why do volcanoes erupt?"))
    }

    @Test
    fun relatedWordFormsShareAStem() {
        assertEquals(1, Text.keywords("invented inventor invention inventions").distinct().size)
    }

    @Test
    fun repeatedSentencesAreDetectedAndRemoved() {
        val raw = "Plants make food from light. It needs water. It needs water. It needs wa"
        assertTrue(Answers.isLooping(raw))
        assertFalse(Answers.isLooping("Plants make food from light. It needs water. It needs"))
        assertEquals("Plants make food from light. It needs water. It needs wa", Answers.visibleText(raw))
    }

    @Test
    fun repeatedPhrasesAreDetectedAndCollapsed() {
        val raw = "Bees make honey, honeycomb nectar, honeycomb nectar, honeycomb nectar, honeycomb nec"
        assertTrue(Answers.isLooping(raw))
        assertFalse(Answers.isLooping("Bees make honey, wax, and royal jelly."))
        assertEquals("Bees make honey, honeycomb nectar, honeycomb nec", Answers.visibleText(raw))
    }

    @Test
    fun coverageIsLowWhenNoSingleArticleIsAboutTheQuestion() {
        val chess = listOf(passage("Chess", 0, "Chess is a board game. The world chess championship was first won in 1886."))
        val year = listOf(passage("2031", 0, "2031 is a year in the future. A mission to Mars may happen."))
        val bell = listOf(passage("Alexander Graham Bell", 0, "Bell was an inventor. He invented the telephone in 1876."))
        assertTrue(Passages.coverage("Who won the 2031 Mars chess championship?", listOf(chess, year, bell)) < 0.75)
        assertTrue(Passages.coverage("Who invented the telephone?", listOf(chess, year, bell)) >= 0.75)
    }

    @Test
    fun parsesQueriesAndIgnoresNumberingAndChatter() {
        val reply = "Here are the searches:\n1. volcano eruption\n- \"magma\"\n\nQuestion: something else\nThis long sentence is clearly not a keyword search at all."
        assertEquals(listOf("volcano eruption", "magma"), Answers.parseQueries(reply))
    }

    @Test
    fun htmlBecomesPassagesWithoutReferencesOrScripts() {
        val html = """
            <html><body><h1>Volcano</h1>
            <p>A volcano is a mountain that has lava coming out from a magma chamber under the ground.<sup class="reference">[1]</sup></p>
            <script>var x = "should not appear";</script>
            <h2>References</h2><ul><li>Some book about volcanoes that should be skipped entirely here.</li></ul>
            </body></html>
        """.trimIndent()
        val passages = Passages.fromHtml(html, "Volcano", "Volcano")
        assertEquals(1, passages.size)
        assertTrue(passages[0].text.startsWith("A volcano is a mountain"))
        assertFalse(passages[0].text.contains("[1]"))
    }

    @Test
    fun selectsPassagesThatShareKeywordsAndRespectsBudget() {
        val passages = listOf(
            passage("Volcano", 0, "A volcano is a mountain that has lava coming out from a magma chamber under the ground."),
            passage("Volcano", 1, "Many tourists visit Hawaii every year to see the beaches and the weather is warm."),
            passage("Magma", 0, "Magma is hot liquid rock under the ground. When magma comes out of a volcano it is called lava."),
        )
        val chosen = Passages.select(passages, "What is lava?", emptyList(), charBudget = 1000)
        assertEquals(setOf("Volcano", "Magma"), chosen.map { it.articleTitle }.toSet())
        assertTrue(chosen.none { it.text.contains("tourists") })

        assertEquals(1, Passages.select(passages, "What is lava?", emptyList(), charBudget = 120).size)
        assertTrue(Passages.select(passages, "Who invented the telephone?", emptyList(), charBudget = 1000).isEmpty())
    }

    @Test
    fun detectsNotFoundAndStripsSources() {
        assertTrue(Answers.saysNotFound("NOT_FOUND"))
        assertTrue(Answers.saysNotFound(" not_found."))
        val raw = "Lava is hot liquid rock [2].\nSources: [2] [1]"
        assertEquals("Lava is hot liquid rock.", Answers.visibleText(raw))
        assertEquals("Lava is hot.", Answers.visibleText("Answer: Lava is hot. Sources: [1]"))
        assertEquals("", Answers.visibleText("Sources: [1] [2]"))
    }

    @Test
    fun rejectsAnswersWithFactsThatAreNotInThePassages() {
        val passages = listOf(passage("Ray Charles", 0, "Ray Charles was an American singer and pianist. He was born in 1930 in Albany, Georgia."))
        assertTrue(Answers.isGrounded("Ray Charles was born in 1930 in Albany, Georgia.", passages))
        assertFalse(Answers.isGrounded("Ray Charles was born in 1932.", passages))
        assertFalse(Answers.isGrounded("The telephone was invented by Alexander Graham Bell in Boston.", passages))

        // Each half is in a passage, but no single passage says it.
        val mixed = passages + passage("Breakfast", 0, "Breakfast is the first meal of the day. People often eat pancakes, waffles or toast.")
        assertFalse(Answers.isGrounded("Ray Charles ate pancakes or waffles for breakfast.", mixed))
        assertFalse(Answers.isAboutSubject("What did Ray Charles eat for breakfast?", "Pancakes, waffles or toast.", mixed))
        assertTrue(Answers.isAboutSubject("When was Ray Charles born?", "He was born in 1930 in Albany.", mixed))
        assertEquals(listOf("Breakfast"), Answers.supportingPassages("People often eat pancakes or waffles.", mixed).map { it.articleTitle })
    }
}
