package io.nekohasekai.sfa.conductor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.recyclerview.widget.RecyclerView
import io.noties.markwon.recycler.MarkwonAdapter
import org.commonmark.node.Code
import org.commonmark.node.Heading
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text

internal data class ViewerHeading(
    val level: Int,
    val title: String,
    val blockIndex: Int,
    val line: Int,
)

/** Indexes a parsed Markdown document for the viewer's TOC and Rich search. */
internal object ConductorMarkdownIndex {
    fun topLevelBlocks(document: Node): List<Node> =
        generateSequence(document.firstChild) { it.next }.toList()

    fun headings(blocks: List<Node>): List<ViewerHeading> {
        val out = mutableListOf<ViewerHeading>()
        blocks.forEachIndexed { index, block -> collectHeadings(block, index, out) }
        return out
    }

    fun search(
        blocks: List<Node>,
        query: String,
    ): List<Int> {
        if (query.isBlank()) return emptyList()
        val out = mutableListOf<Int>()
        blocks.forEachIndexed { index, block ->
            val text = blockText(block)
            var start = 0
            while (start < text.length) {
                val hit = text.indexOf(query, start, ignoreCase = true)
                if (hit < 0) break
                out += index
                start = hit + query.length.coerceAtLeast(1)
            }
        }
        return out
    }

    fun rawHeadings(text: String): List<ViewerHeading> {
        val out = mutableListOf<ViewerHeading>()
        var inFence = false
        text.lineSequence().forEachIndexed { index, line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence
                return@forEachIndexed
            }
            if (inFence) return@forEachIndexed
            val match = HEADING.find(trimmed) ?: return@forEachIndexed
            out += ViewerHeading(
                level = match.groupValues[1].length,
                title = match.groupValues[2].trim(),
                blockIndex = -1,
                line = index + 1,
            )
        }
        return out
    }

    private fun collectHeadings(
        node: Node,
        blockIndex: Int,
        out: MutableList<ViewerHeading>,
    ) {
        if (node is Heading) {
            out += ViewerHeading(
                level = node.level,
                title = inlineText(node).trim(),
                blockIndex = blockIndex,
                line = -1,
            )
        }
        var child = node.firstChild
        while (child != null) {
            collectHeadings(child, blockIndex, out)
            child = child.next
        }
    }

    private fun blockText(node: Node): String =
        buildString {
            fun appendNode(current: Node) {
                when (current) {
                    is Text -> append(current.literal)
                    is Code -> append(current.literal)
                    is FencedCodeBlock -> append(current.literal)
                    is IndentedCodeBlock -> append(current.literal)
                    is HtmlInline -> append(current.literal)
                    is HtmlBlock -> append(current.literal)
                    is SoftLineBreak, is HardLineBreak -> append(' ')
                    is Image -> {
                        append(current.title.orEmpty())
                        appendNodeChildren(current)
                    }
                    is Link -> appendNodeChildren(current)
                    else -> appendNodeChildren(current)
                }
            }
            appendNode(node)
        }.replace(Regex("\\s+"), " ")

    private fun StringBuilder.appendNodeChildren(node: Node) {
        var child = node.firstChild
        while (child != null) {
            when (child) {
                is Text -> append(child.literal)
                is Code -> append(child.literal)
                is FencedCodeBlock -> append(child.literal)
                is IndentedCodeBlock -> append(child.literal)
                is HtmlInline -> append(child.literal)
                is HtmlBlock -> append(child.literal)
                is SoftLineBreak, is HardLineBreak -> append(' ')
                else -> appendNodeChildren(child)
            }
            child = child.next
        }
    }

    private fun inlineText(node: Node): String =
        buildString {
            fun appendInline(current: Node) {
                when (current) {
                    is Text -> append(current.literal)
                    is Code -> append(current.literal)
                    is SoftLineBreak, is HardLineBreak -> append(' ')
                    else -> {
                        var child = current.firstChild
                        while (child != null) {
                            appendInline(child)
                            child = child.next
                        }
                    }
                }
            }
            appendInline(node)
        }

    private val HEADING = Regex("^(#{1,6})\\s+(.+?)\\s*#*\\s*$")
}

/** Shared Rich-view handle for TOC jumps, search results and highlights. */
internal class ConductorMarkdownController {
    var recycler: RecyclerView? = null
    var adapter: MarkwonAdapter? = null
    @Volatile
    var blocks: List<Node> = emptyList()
        private set
    var headings by mutableStateOf<List<ViewerHeading>>(emptyList())
        private set
    var ready by mutableStateOf(false)
        private set
    var searchQuery: String = ""
        private set

    fun attachRecycler(value: RecyclerView) {
        recycler = value
    }

    fun attachAdapter(value: MarkwonAdapter) {
        adapter = value
    }

    fun attachDocument(document: Node) {
        blocks = ConductorMarkdownIndex.topLevelBlocks(document)
        headings = ConductorMarkdownIndex.headings(blocks)
        ready = true
        adapter?.notifyDataSetChanged()
    }

    fun search(query: String): List<Int> = ConductorMarkdownIndex.search(blocks, query)

    fun setSearchQuery(query: String) {
        if (searchQuery == query) return
        searchQuery = query
        adapter?.notifyDataSetChanged()
    }

    fun scrollToBlock(index: Int) {
        if (index < 0) return
        recycler?.scrollToPosition(index)
    }
}
