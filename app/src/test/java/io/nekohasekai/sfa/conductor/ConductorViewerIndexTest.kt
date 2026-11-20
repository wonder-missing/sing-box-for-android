package io.nekohasekai.sfa.conductor

import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConductorViewerIndexTest {
    private val text =
        """
        # One

        Body foo and `foo`.

        ## Two

        More foo.

        ```
        # not a heading
        foo in code
        ```
        """.trimIndent()

    @Test
    fun headingsKeepTheirTopLevelBlock() {
        val document = Parser.builder().build().parse(text)
        val blocks = ConductorMarkdownIndex.topLevelBlocks(document)
        val headings = ConductorMarkdownIndex.headings(blocks)

        assertEquals(listOf(1, 2), headings.map { it.level })
        assertEquals(listOf("One", "Two"), headings.map { it.title })
        assertTrue(headings[0].blockIndex < headings[1].blockIndex)
    }

    @Test
    fun richSearchFindsOccurrencesInBlocks() {
        val document = Parser.builder().build().parse(text)
        val blocks = ConductorMarkdownIndex.topLevelBlocks(document)
        val matches = ConductorMarkdownIndex.search(blocks, "foo")

        assertEquals(4, matches.size)
        assertEquals(matches.sorted(), matches)
    }

    @Test
    fun rawHeadingsUseSourceLineNumbersAndSkipFences() {
        val headings = ConductorMarkdownIndex.rawHeadings(text)

        assertEquals(listOf(1, 5), headings.map { it.line })
        assertEquals(listOf("One", "Two"), headings.map { it.title })
    }
}
