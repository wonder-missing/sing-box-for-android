package io.nekohasekai.sfa.compat

import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.SpanFactory
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * Lightweight line-based Markdown highlighting for the read-only Conductor
 * viewers. It deliberately does not parse JSON inside fences; headings,
 * fences, inline code, bold and list markers are enough to scan a transcript.
 *
 * MappedSpans requires each line's spans in ascending column order and a span
 * at column 0. Markdown ranges overlap (bold around inline code), so this
 * builds non-overlapping style runs first instead of emitting overlapping
 * spans. Out-of-order/overlapping spans made Sora draw wrapped lines on top
 * of each other in the raw transcript viewer.
 */
class MarkdownLanguage : EmptyLanguage() {
    private val analyzeManager =
        object : SimpleAnalyzeManager<Any>() {
            override fun analyze(
                text: StringBuilder,
                delegate: Delegate<Any>,
            ): Styles {
                val builder = MappedSpans.Builder()
                val lines = text.lineSequence().toList()
                var inFence = false
                lines.forEachIndexed { line, content ->
                    val trimmed = content.trimStart()
                    val base =
                        when {
                            trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                                inFence = !inFence
                                FENCE
                            }

                            inFence -> CODE
                            trimmed.startsWith("#") -> HEADING
                            trimmed.startsWith(">") -> QUOTE
                            else -> NORMAL
                        }

                    val ranges = mutableListOf<StyleRange>()
                    if (
                        base != FENCE &&
                        base != CODE &&
                        (
                            trimmed.startsWith("- ") ||
                                trimmed.startsWith("* ") ||
                                trimmed.startsWith("+ ")
                        )
                    ) {
                        val bullet = content.indexOfFirst { !it.isWhitespace() }
                        if (bullet >= 0) ranges += StyleRange(bullet, bullet + 1, BULLET)
                    }
                    if (base != FENCE && base != CODE) {
                        var index = content.indexOf('`')
                        while (index >= 0) {
                            val end = content.indexOf('`', index + 1)
                            if (end < 0) break
                            ranges += StyleRange(index, end + 1, INLINE_CODE)
                            index = content.indexOf('`', end + 1)
                        }
                        var bold = content.indexOf("**")
                        while (bold >= 0) {
                            val end = content.indexOf("**", bold + 2)
                            if (end < 0) break
                            ranges += StyleRange(bold, end + 2, BOLD)
                            bold = content.indexOf("**", end + 2)
                        }
                    }

                    // Build sorted, non-overlapping style runs. Inline code wins
                    // over bold; bullet markers win over neither.
                    val boundaries = sortedSetOf(0, content.length)
                    ranges.forEach { range ->
                        boundaries += range.start.coerceIn(0, content.length)
                        boundaries += range.end.coerceIn(0, content.length)
                    }
                    var previous = Long.MIN_VALUE
                    boundaries.forEach { column ->
                        val style = styleAt(column, base, ranges)
                        if (style != previous) {
                            builder.add(line, SpanFactory.obtain(column, style))
                            previous = style
                        }
                    }
                }
                if (lines.isNotEmpty()) builder.determine(lines.lastIndex)
                return Styles(builder.build())
            }
        }

    private fun styleAt(
        column: Int,
        base: Long,
        ranges: List<StyleRange>,
    ): Long {
        for (range in ranges) {
            if (column >= range.start && column < range.end) return range.style
        }
        return base
    }

    private data class StyleRange(
        val start: Int,
        val end: Int,
        val style: Long,
    )

    override fun getAnalyzeManager(): AnalyzeManager = analyzeManager

    private companion object {
        val NORMAL = TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)
        val HEADING = TextStyle.makeStyle(EditorColorScheme.ATTRIBUTE_NAME, true)
        val FENCE = TextStyle.makeStyle(EditorColorScheme.COMMENT)
        val CODE = TextStyle.makeStyle(EditorColorScheme.LITERAL)
        val QUOTE = TextStyle.makeStyle(EditorColorScheme.COMMENT)
        val BULLET = TextStyle.makeStyle(EditorColorScheme.OPERATOR)
        val INLINE_CODE = TextStyle.makeStyle(EditorColorScheme.LITERAL)
        val BOLD = TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL, true)
    }
}
