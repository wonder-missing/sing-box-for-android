package io.nekohasekai.sfa.update

/**
 * Build labels stay two characters after decimal 99.
 *
 * Legacy builds 1..99 keep their decimal spelling. From 100, the label is a
 * two-character base-36 value with a one-time offset:
 *
 *   ordinal = code.toInt(36) - 260
 *   code = (ordinal + 260).toString(36).uppercase()
 *
 * A0 is the first new label (100), followed by A1 (101). The range A0..ZZ
 * covers 100..1035.
 *
 * `WONDER_BUILD` stores the label; `VERSION_CODE` still uses the integer
 * ordinal so Android updates remain monotonic.
 */
object WonderBuild {
    const val FIRST_ENCODED = 100
    const val CODE_OFFSET = 260
    private val LEGACY = Regex("""\d{1,2}""")
    private val CODE = Regex("""[0-9A-Za-z]{2}""")

    fun ordinalOf(code: String): Int? {
        val value = code.trim()
        if (value.isEmpty()) return null
        if (value.all { it.isDigit() }) {
            if (!LEGACY.matches(value)) return null
            return value.toIntOrNull()?.takeIf { it > 0 }
        }
        if (!CODE.matches(value)) return null
        val parsed = value.toIntOrNull(36) ?: return null
        return (parsed - CODE_OFFSET).takeIf { it >= FIRST_ENCODED }
    }

    fun codeOf(ordinal: Int): String? {
        if (ordinal in 1..99) return ordinal.toString()
        if (ordinal < FIRST_ENCODED) return null
        val code = (ordinal + CODE_OFFSET).toString(36).uppercase()
        return code.takeIf { CODE.matches(it) }
    }
}
