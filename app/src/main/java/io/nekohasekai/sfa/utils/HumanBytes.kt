package io.nekohasekai.sfa.utils

import java.util.Locale

internal fun humanBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L)
    return when {
        value < 1024L -> "$value B"
        value < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", value / 1024.0)
        value < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.1f GB", value / (1024.0 * 1024.0 * 1024.0))
    }
}
