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

    data class Code(
        val language: String,
        val code: String,
    ) : ConductorAssistantSegment

    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
    ) : ConductorAssistantSegment
}

private val MARKDOWN_IMAGE = Regex("""!\[([^\]]*)\]\(([^)\s]+)(?:\s+"[^"]*")?\)""")
private val SVG_FENCE = Regex("""(?is)```[ \t]*svg[ \t]*\r?\n(.*?)\r?\n```""")
private val SVG_TILDE_FENCE = Regex("""(?is)~~~[ \t]*svg[ \t]*\r?\n(.*?)\r?\n~~~""")
private val CODE_FENCE = Regex("""(?is)```[ \t]*([\w+#.-]*)[ \t]*\r?\n(.*?)\r?\n```""")
private val CODE_TILDE_FENCE = Regex("""(?is)~~~[ \t]*([\w+#.-]*)[ \t]*\r?\n(.*?)\r?\n~~~""")
private val RAW_SVG = Regex("""(?is)<svg\b.*?</svg>""")
private val TABLE_SEPARATOR = Regex("""^:?-{3,}:?$""")

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
    val occupied = mutableListOf<IntRange>()

    fun overlaps(range: IntRange): Boolean =
        occupied.any { range.first <= it.last && it.first <= range.last }

    fun addSvgFences(regex: Regex) {
        regex.findAll(text).forEach { match ->
            if (insideInlineCode(text, match.range.first) || overlaps(match.range)) return@forEach
            val source = match.groupValues[1]
            if (source.isBlank()) return@forEach
            candidates +=
                MarkdownMatch(
                    start = match.range.first,
                    end = match.range.last + 1,
                    segment = ConductorAssistantSegment.Svg(source),
                )
            occupied += match.range
        }
    }

    fun addCodeFences(regex: Regex) {
        regex.findAll(text).forEach { match ->
            if (insideInlineCode(text, match.range.first) || overlaps(match.range)) return@forEach
            val code = match.groupValues[2]
            if (code.isBlank()) return@forEach
            candidates +=
                MarkdownMatch(
                    start = match.range.first,
                    end = match.range.last + 1,
                    segment =
                        ConductorAssistantSegment.Code(
                            language = match.groupValues[1].trim().take(24),
                            code = code,
                        ),
                )
            occupied += match.range
        }
    }

    addSvgFences(SVG_FENCE)
    addSvgFences(SVG_TILDE_FENCE)
    addCodeFences(CODE_FENCE)
    addCodeFences(CODE_TILDE_FENCE)
    addTables(text, candidates, occupied) { range -> overlaps(range) }
    RAW_SVG.findAll(text).forEach { match ->
        if (insideInlineCode(text, match.range.first) || overlaps(match.range)) return@forEach
        candidates +=
            MarkdownMatch(
                start = match.range.first,
                end = match.range.last + 1,
                segment = ConductorAssistantSegment.Svg(match.value),
            )
        occupied += match.range
    }
    MARKDOWN_IMAGE.findAll(text).forEach { match ->
        if (insideInlineCode(text, match.range.first) || overlaps(match.range)) return@forEach
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

private data class AssistantLine(
    val text: String,
    val start: Int,
    val end: Int,
)

private fun addTables(
    text: String,
    candidates: MutableList<MarkdownMatch>,
    occupied: MutableList<IntRange>,
    overlaps: (IntRange) -> Boolean,
) {
    val lines = assistantLines(text)
    var index = 0
    while (index + 1 < lines.size) {
        val header = lines[index]
        val separator = lines[index + 1]
        if (overlaps(header.start..header.end) || overlaps(separator.start..separator.end)) {
            index += 1
            continue
        }
        if (!header.text.contains('|') || !isTableSeparator(separator.text)) {
            index += 1
            continue
        }
        val rows = mutableListOf(header.text)
        var next = index + 2
        while (next < lines.size) {
            val line = lines[next]
            if (!line.text.contains('|') || overlaps(line.start..line.end)) break
            rows += line.text
            next += 1
        }
        val cells = rows.map(::splitTableRow)
        val columns = cells.maxOfOrNull { it.size } ?: 0
        if (columns == 0) {
            index += 1
            continue
        }
        val padded = cells.map { row -> List(columns) { row.getOrElse(it) { "" } } }
        val range = header.start until lines[next - 1].end
        candidates +=
            MarkdownMatch(
                start = range.first,
                end = range.last + 1,
                segment =
                    ConductorAssistantSegment.Table(
                        header = padded.first(),
                        rows = padded.drop(1),
                    ),
            )
        occupied += range
        index = next
    }
}

private fun assistantLines(text: String): List<AssistantLine> {
    val lines = mutableListOf<AssistantLine>()
    var start = 0
    for (line in text.split('\n')) {
        val end = start + line.length
        lines += AssistantLine(line, start, end)
        start = end + 1
    }
    return lines
}

private fun isTableSeparator(line: String): Boolean {
    if (!line.contains('|')) return false
    val cells = splitTableRow(line)
    return cells.isNotEmpty() && cells.all { TABLE_SEPARATOR.matches(it) }
}

private fun splitTableRow(line: String): List<String> {
    var value = line.trim()
    if (value.startsWith("|")) value = value.substring(1)
    if (value.endsWith("|")) value = value.dropLast(1)
    return value
        .replace("\\|", "\u0000")
        .split('|')
        .map { it.replace('\u0000', '|').trim() }
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
