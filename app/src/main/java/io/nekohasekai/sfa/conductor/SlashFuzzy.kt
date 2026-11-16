package io.nekohasekai.sfa.conductor

import java.util.Locale

/**
 * Small deterministic fuzzy matcher for slash suggestions. It keeps exact and
 * prefix matches ahead of subsequence matches, then prefers shorter names.
 */
internal object SlashFuzzy {
    fun score(
        query: String,
        candidate: String,
    ): Int? {
        val q = query.trim().lowercase(Locale.US)
        if (q.isEmpty()) return 0
        val value = candidate.lowercase(Locale.US)
        if (q == value) return 0
        if (value.startsWith(q)) return 10 + value.length - q.length

        val words = value.split('-', '_', ' ').filter { it.isNotEmpty() }
        if (words.any { it.startsWith(q) }) {
            return 20 + value.length - q.length
        }

        var index = 0
        var last = -1
        var gaps = 0
        for (position in value.indices) {
            if (index < q.length && value[position] == q[index]) {
                if (last >= 0) gaps += position - last - 1
                last = position
                index++
            }
        }
        if (index != q.length) return null
        return 100 + gaps * 3 + value.length - q.length
    }

    fun <T> rank(
        query: String,
        items: List<T>,
        name: (T) -> String,
    ): List<T> {
        if (query.isBlank()) return items
        return items
            .mapNotNull { item ->
                score(query, name(item))?.let { item to it }
            }.sortedWith(
                compareBy<Pair<T, Int>>(
                    { it.second },
                    { name(it.first).length },
                    { name(it.first) },
                ),
            ).map { it.first }
    }
}
