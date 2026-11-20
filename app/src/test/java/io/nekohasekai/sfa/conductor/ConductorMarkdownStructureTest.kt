package io.nekohasekai.sfa.conductor

import org.commonmark.node.BlockQuote
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Paragraph
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConductorMarkdownStructureTest {
    private val line =
        "二是 **`Taildrop/` 占 198.6MB，是六个旧 APK**" +
            "（wonder.63/64/68/69/70/71，每个 34.7MB），占了整个挂载的 98.7%，" +
            "对排障没有用处，只会拖慢他们遍历。"

    @Test
    fun boldContainingInlineCodeIsOneStrongNode() {
        val document = Parser.builder().build().parse(line) as Document
        val paragraph = document.firstChild as Paragraph
        val strong = paragraph.firstChild.next as StrongEmphasis

        val code = strong.firstChild as Code
        assertEquals("Taildrop/", code.literal)
        assertEquals(" 占 198.6MB，是六个旧 APK", (code.next as Text).literal)
        assertTrue(strong.next is Text)

        val literals = mutableListOf<String>()
        var node = document.firstChild
        while (node != null) {
            if (node is Text) literals += node.literal
            var child = node.firstChild
            while (child != null) {
                if (child is Text) literals += child.literal
                child = child.next
            }
            node = node.next
        }
        assertTrue(literals.none { it.contains("**") })
    }

    @Test
    fun theSameLineInsideABlockQuoteKeepsTheStrongNode() {
        val document = Parser.builder().build().parse("> $line") as Document
        val quote = document.firstChild as BlockQuote
        val paragraph = quote.firstChild as Paragraph
        val strong = paragraph.firstChild.next as StrongEmphasis

        assertEquals("Taildrop/", (strong.firstChild as Code).literal)
    }
}
