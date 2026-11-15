package io.nekohasekai.sfa.compat

import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * Lightweight line-based Markdown highlighting for the read-only Conductor
 * viewers. It deliberately does not parse JSON inside fences; headings,
 * fences, inline code, bold and list markers are enough to scan a transcript.
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
                    when {
                        trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                            builder.addIfNeeded(line, 0, FENCE)
                            inFence = !inFence
                        }

                        inFence -> builder.addIfNeeded(line, 0, CODE)

                        trimmed.startsWith("#") -> builder.addIfNeeded(line, 0, HEADING)

                        trimmed.startsWith(">") -> builder.addIfNeeded(line, 0, QUOTE)

                        trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
                            builder.addIfNeeded(line, 0, NORMAL)
                            builder.addIfNeeded(line, content.indexOfFirst { !it.isWhitespace() }, BULLET)
                        }

                        else -> builder.addIfNeeded(line, 0, NORMAL)
                    }
                    if (!inFence) {
                        var index = content.indexOf('`')
                        while (index >= 0) {
                            val end = content.indexOf('`', index + 1)
                            if (end < 0) break
                            builder.addIfNeeded(line, index, INLINE_CODE)
                            builder.addIfNeeded(line, end + 1, NORMAL)
                            index = content.indexOf('`', end + 1)
                        }
                        var bold = content.indexOf("**")
                        while (bold >= 0) {
                            val end = content.indexOf("**", bold + 2)
                            if (end < 0) break
                            builder.addIfNeeded(line, bold, BOLD)
                            builder.addIfNeeded(line, end + 2, NORMAL)
                            bold = content.indexOf("**", end + 2)
                        }
                    }
                }
                builder.determine(lines.size - 1)
                return Styles(builder.build())
            }
        }

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
