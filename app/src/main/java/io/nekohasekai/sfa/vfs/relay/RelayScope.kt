package io.nekohasekai.sfa.vfs.relay

/** Concrete Share scopes. `all` is expanded once, at Share creation. */
enum class RelayScope(
    val wire: String,
    val root: String,
    val warning: RelayWarning,
    val available: Boolean = true,
) {
    APP_DATA_ACTIVITY("app.data.activity", "/appdata/activity", RelayWarning.AMBER),
    APP_DATA_GROUPS("app.data.groups", "/appdata/groups", RelayWarning.AMBER, available = false),
    APP_DATA_LOGS("app.data.logs", "/appdata/logs", RelayWarning.AMBER),
    APP_DATA_TELEMETRY("app.data.telemetry", "/appdata/telemetry", RelayWarning.AMBER),
    APP_DATA_DIAGNOSTICS("app.data.diagnostics", "/appdata/diagnostics", RelayWarning.AMBER),
    APP_FILES("app.files", "/appfiles", RelayWarning.RED),
    APP_WORKSPACE("app.workspace", "/", RelayWarning.RED),
    USER_SELECTED("user.selected", "/selected", RelayWarning.PICKER),
    ;

    companion object {
        private val byWire = RelayScope.entries.associateBy { it.wire }

        val fixed: List<RelayScope> = RelayScope.entries.filter { it != USER_SELECTED && it.available }

        fun fromWire(value: String): RelayScope? = byWire[value]

        fun parseAll(values: Iterable<String>): Set<RelayScope> =
            values.mapNotNull(::fromWire).toSet()

        fun recommended(): Set<RelayScope> =
            setOf(
                APP_DATA_ACTIVITY,
                APP_DATA_LOGS,
                APP_DATA_TELEMETRY,
                APP_DATA_DIAGNOSTICS,
            )

        fun everything(): Set<RelayScope> = fixed.toSet()
    }
}

enum class RelayWarning {
    AMBER,
    RED,
    PICKER,
}

object RelayRoots {
    /** Sorted, de-duplicated roots for the pair response. `app.workspace` is `/`. */
    fun of(scopes: Set<RelayScope>): List<String> =
        scopes
            .map { it.root }
            .distinct()
            .sortedWith { a, b -> compareUtf8(a, b) }

    fun scopesSorted(scopes: Set<RelayScope>): List<String> =
        scopes.map { it.wire }.distinct().sortedWith { a, b -> compareUtf8(a, b) }

    fun compareUtf8(
        a: String,
        b: String,
    ): Int {
        val left = a.toByteArray(Charsets.UTF_8)
        val right = b.toByteArray(Charsets.UTF_8)
        val common = minOf(left.size, right.size)
        for (index in 0 until common) {
            val diff = (left[index].toInt() and 0xff) - (right[index].toInt() and 0xff)
            if (diff != 0) return diff
        }
        return left.size - right.size
    }
}
