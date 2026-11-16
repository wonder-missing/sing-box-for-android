package io.nekohasekai.sfa.agent

/**
 * Classic backtick fence, widened when the content itself contains backticks.
 * Generated transcripts and traces should use the Markdown default instead
 * of a non-standard tilde fence.
 */
internal fun markdownFence(
    content: String,
    language: String = "",
): String {
    var longestRun = 0
    var run = 0
    for (ch in content) {
        if (ch == '`') {
            run++
            if (run > longestRun) longestRun = run
        } else {
            run = 0
        }
    }
    val fence = "`".repeat(maxOf(3, longestRun + 1))
    return buildString {
        append(fence).append(language).append('\n')
        append(content)
        if (!content.endsWith('\n')) append('\n')
        append(fence)
    }
}
