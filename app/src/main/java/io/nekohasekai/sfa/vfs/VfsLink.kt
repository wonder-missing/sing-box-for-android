package io.nekohasekai.sfa.vfs

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** The fixed scope vocabulary the app can export. */
enum class VfsScope(
    val wire: String,
    val path: String,
) {
    CONDUCTOR("conductor", "conductor/**"),
    TRACE("trace", ".runtime/trace.md"),
    SYSTEM_PROMPT("system_prompt", ".runtime/system_prompt.md"),
    CONVERSATION("conversation", ".runtime/conversation.md"),
    ;

    companion object {
        val DEFAULT_SCOPE: Set<VfsScope> = setOf(CONDUCTOR, TRACE, SYSTEM_PROMPT)

        fun fromWire(value: String): VfsScope? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * A routing handle issued by the MCP server. It carries no secret and no
 * endpoint; the app dials its configured server after matching [issuer].
 */
data class VfsLink(
    val issuer: String,
    val linkId: String,
    val scope: Set<VfsScope>,
) {
    companion object {
        const val TYPE = "vfs-link"
        const val VERSION = 1
        const val MAX_LINK_ID_CHARS = 256
    }
}

/** A vfs_connect failure that maps directly to the app's public error table. */
class VfsConnectException(
    val code: String,
    message: String,
    val retryAfterMs: Long? = null,
    val cooldownUntil: Long? = null,
) : Exception(message)

object VfsLinkParser {
    fun parse(value: JsonObject): VfsLink {
        val type = value.stringField("type") ?: invalid("Link is missing type.")
        if (type != VfsLink.TYPE) invalid("Link type is not ${VfsLink.TYPE}.")
        val version = value.intField("version") ?: invalid("Link is missing version.")
        if (version != VfsLink.VERSION) invalid("Link version $version is not supported.")

        val issuer =
            value.stringField("issuer")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { VfsIssuer.normalize(it) }
                ?: invalid("Link issuer is not a valid base URL.")

        val linkId =
            value.stringField("linkId")
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= VfsLink.MAX_LINK_ID_CHARS }
                ?: invalid("Link id is invalid.")

        val scopeArray = value["scope"] as? JsonArray ?: invalid("Link scope is missing.")
        val requested = LinkedHashSet<String>()
        for (item in scopeArray) {
            val primitive = item as? JsonPrimitive
            if (primitive == null || !primitive.isString) invalid("Link scope contains a non-string.")
            requested.add(primitive.content)
        }
        if (requested.isEmpty()) invalid("Link scope is empty.")

        val unsupported = requested.filter { VfsScope.fromWire(it) == null }
        if (unsupported.isNotEmpty()) {
            throw VfsConnectException(
                "VFS_SCOPE_UNSUPPORTED",
                "Link requests an unsupported scope: ${unsupported.joinToString()}.",
            )
        }

        return VfsLink(
            issuer = issuer,
            linkId = linkId,
            scope = requested.mapNotNull { VfsScope.fromWire(it) }.toSet(),
        )
    }

    private fun JsonObject.stringField(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.intField(name: String): Int? =
        (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun invalid(message: String): Nothing =
        throw VfsConnectException("VFS_LINK_INVALID", message)
}
