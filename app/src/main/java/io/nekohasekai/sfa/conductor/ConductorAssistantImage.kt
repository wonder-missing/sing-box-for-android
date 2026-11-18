package io.nekohasekai.sfa.conductor

internal sealed interface ConductorAssistantSegment {
    data class Text(
        val text: String,
    ) : ConductorAssistantSegment

    data class Image(
        val alt: String,
        val url: String,
    ) : ConductorAssistantSegment

    data class Svg(
        val source: String,
    ) : ConductorAssistantSegment
}

private val MARKDOWN_IMAGE = Regex("""!\[([^\]]*)\]\(([^)\s]+)(?:\s+"[^"]*")?\)""")
private val SVG_FENCE = Regex("""(?is)```[ \t]*svg[ \t]*\r?\n(.*?)\r?\n```""")
private val SVG_TILDE_FENCE = Regex("""(?is)~~~[ \t]*svg[ \t]*\r?\n(.*?)\r?\n~~~""")
private val RAW_SVG = Regex("""(?is)<svg\b.*?</svg>""")

private data class MarkdownMatch(
    val start: Int,
    val end: Int,
    val segment: ConductorAssistantSegment,
)

/**
 * Splits assistant Markdown into plain text, raster image and SVG blocks.
 * Matches inside inline code are left alone; fenced SVG is preferred over a
 * raw `<svg>` match inside it.
 */
internal fun assistantMarkdownSegments(text: String): List<ConductorAssistantSegment> {
    if (text.isEmpty()) return emptyList()
    val candidates = mutableListOf<MarkdownMatch>()
    val svgRanges = mutableListOf<IntRange>()

    fun addSvgFences(regex: Regex) {
        regex.findAll(text).forEach { match ->
            if (insideInlineCode(text, match.range.first)) return@forEach
            val source = match.groupValues[1]
            if (source.isBlank()) return@forEach
            candidates +=
                MarkdownMatch(
                    start = match.range.first,
                    end = match.range.last + 1,
                    segment = ConductorAssistantSegment.Svg(source),
                )
            svgRanges += match.range
        }
    }

    addSvgFences(SVG_FENCE)
    addSvgFences(SVG_TILDE_FENCE)
    RAW_SVG.findAll(text).forEach { match ->
        if (insideInlineCode(text, match.range.first)) return@forEach
        if (svgRanges.any { range -> match.range.first <= range.last && range.first <= match.range.last }) {
            return@forEach
        }
        candidates +=
            MarkdownMatch(
                start = match.range.first,
                end = match.range.last + 1,
                segment = ConductorAssistantSegment.Svg(match.value),
            )
        svgRanges += match.range
    }
    MARKDOWN_IMAGE.findAll(text).forEach { match ->
        if (insideInlineCode(text, match.range.first)) return@forEach
        if (svgRanges.any { range -> match.range.first <= range.last && range.first <= match.range.last }) {
            return@forEach
        }
        candidates +=
            MarkdownMatch(
                start = match.range.first,
                end = match.range.last + 1,
                segment =
                    ConductorAssistantSegment.Image(
                        alt = match.groupValues[1],
                        url = match.groupValues[2],
                    ),
            )
    }

    if (candidates.isEmpty()) return listOf(ConductorAssistantSegment.Text(text))
    candidates.sortBy { it.start }
    val segments = mutableListOf<ConductorAssistantSegment>()
    var cursor = 0
    candidates.forEach { match ->
        if (match.start < cursor) return@forEach
        if (match.start > cursor) {
            val before = text.substring(cursor, match.start)
            if (before.isNotEmpty()) segments += ConductorAssistantSegment.Text(before)
        }
        segments += match.segment
        cursor = match.end
    }
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
