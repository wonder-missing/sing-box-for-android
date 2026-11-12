package io.nekohasekai.sfa.agent.mcp

/**
 * Encrypts MCP bearer tokens at rest. The API 24+ source set supplies a
 * Keystore-backed implementation; API 21 builds use the plaintext fallback.
 * Tokens are decrypted into memory only for the lifetime of the config.
 */
interface McpTokenCipher {
    val supported: Boolean

    fun encrypt(
        serverId: String,
        token: String,
    ): String

    fun decrypt(
        serverId: String,
        blob: String,
    ): String?
}

object PlaintextMcpTokenCipher : McpTokenCipher {
    override val supported: Boolean = false

    override fun encrypt(
        serverId: String,
        token: String,
    ): String = token

    override fun decrypt(
        serverId: String,
        blob: String,
    ): String = blob
}
