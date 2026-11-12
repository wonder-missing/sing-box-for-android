package io.nekohasekai.sfa.conductor

/**
 * Text produced by the input-bar buttons, plus where the cursor should sit.
 * A null cursor means the text did not change and the existing selection stays.
 */
internal data class ConductorInputEdit(
    val text: String,
    val cursor: Int?,
)

/** One press of the slash button. A visible menu closes; otherwise the slash is inserted. */
internal data class SlashPress(
    val hideMenu: Boolean,
    val edit: ConductorInputEdit?,
)

internal object ConductorInputEdits {
    fun slashPress(
        menuVisible: Boolean,
        text: String,
        maxChars: Int,
    ): SlashPress {
        if (menuVisible) return SlashPress(hideMenu = true, edit = null)
        return SlashPress(hideMenu = false, edit = slash(text, maxChars))
    }

    /** Prepend `/` when the field does not already start with one. Cursor sits after that slash. */
    fun slash(
        text: String,
        maxChars: Int,
    ): ConductorInputEdit {
        if (text.startsWith("/")) {
            return ConductorInputEdit(text.take(maxChars), cursor = null)
        }
        val next = "/$text".take(maxChars)
        return ConductorInputEdit(next, cursor = 1.coerceAtMost(next.length))
    }

    /** Append `@`, with a separating space when the field does not already end in whitespace. */
    fun mention(
        text: String,
        maxChars: Int,
    ): ConductorInputEdit {
        val next =
            when {
                text.isBlank() -> "@"
                text.last().isWhitespace() -> text + "@"
                else -> "$text @"
            }.take(maxChars)
        return ConductorInputEdit(next, next.length)
    }
}
