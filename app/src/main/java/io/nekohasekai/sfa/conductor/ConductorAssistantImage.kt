package io.nekohasekai.sfa.conductor

internal sealed interface ConductorAssistantSegment {
    data class Text(
        val text: String,
    ) : ConductorAssistantSegment

    data class Image(
        val alt: String,
        val url: String,
    ) : ConductorAssistantSegment
}

private val MARKDOWN_IMAGE = Regex("""!\[([^\]]*)\]\(([^)\s]+)(?:\s+"[^"]*")?\)""")

/**
 * Splits assistant Markdown into plain text and remote image blocks. Images
 * inside inline code are left alone; the model cannot turn a code sample into
 * a live image card by wrapping it in backticks.
 */
internal fun assistantMarkdownSegments(text: String): List<ConductorAssistantSegment> {
    if (text.isEmpty()) return emptyList()
    val segments = mutableListOf<ConductorAssistantSegment>()
    var cursor = 0
    MARKDOWN_IMAGE.findAll(text).forEach { match ->
        if (insideInlineCode(text, match.range.first)) return@forEach
        if (match.range.first > cursor) {
            val before = text.substring(cursor, match.range.first)
            if (before.isNotEmpty()) segments += ConductorAssistantSegment.Text(before)
        }
        segments +=
            ConductorAssistantSegment.Image(
                alt = match.groupValues[1],
                url = match.groupValues[2],
            )
        cursor = match.range.last + 1
    }
    if (cursor == 0) return listOf(ConductorAssistantSegment.Text(text))
    if (cursor < text.length) {
        val after = text.substring(cursor)
        if (after.isNotEmpty()) segments += ConductorAssistantSegment.Text(after)
    }
    return segments
}

private fun insideInlineCode(
    text: String,
    index: Int,
): Boolean {
    var backticks = 0
    var i = 0
    while (i < index) {
        if (text[i] == '`') backticks += 1
        i += 1
    }
    return backticks % 2 == 1
}
