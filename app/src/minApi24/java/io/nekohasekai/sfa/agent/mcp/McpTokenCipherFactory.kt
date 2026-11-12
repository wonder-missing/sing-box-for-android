package io.nekohasekai.sfa.agent.mcp

object McpTokenCipherFactory {
    fun create(): McpTokenCipher = KeystoreMcpTokenCipher()
}
