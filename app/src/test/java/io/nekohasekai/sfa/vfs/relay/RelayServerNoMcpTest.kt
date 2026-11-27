package io.nekohasekai.sfa.vfs.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the Share files path from drifting back to MCP/Conductor coupling.
 * Reads the checked-in sources so it catches UI/runtime references too.
 */
class RelayServerNoMcpTest {
    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("source not found: $relative")
        return file.readText(Charsets.UTF_8)
    }

    @Test
    fun relayRuntimeProvisionerAndUiDoNotReferenceMcp() {
        val forbidden =
            listOf(
                "McpRuntime",
                "McpServerConfig",
                "McpRuntimes",
                "whsec_",
                "hookId",
                "portalServerId",
                "portal",
                "mcp",
                "conductor",
                "agent",
                "hook",
            )
        for (path in listOf(
            "src/main/java/io/nekohasekai/sfa/vfs/relay/VfsRelayRuntime.kt",
            "src/main/java/io/nekohasekai/sfa/vfs/relay/VfsProviderKeyProvisioner.kt",
            "src/main/java/io/nekohasekai/sfa/vfs/relay/RelayStore.kt",
        )) {
            val text = source(path).lowercase()
            for (token in forbidden) {
                assertFalse("$path still references $token", text.contains(token.lowercase()))
            }
        }
    }

    @Test
    fun shareFilesStringsDescribeRelayServerWithoutLegacyNames() {
        val required =
            listOf(
                "settings_share_files_title",
                "settings_share_files_help",
                "share_files_add_server",
                "share_files_add_server_title",
                "share_files_server_name",
                "share_files_server_url",
                "share_files_server_token",
                "share_files_no_server",
                "share_files_server_has_shares",
                "share_files_server_delete_title",
                "share_files_server_delete_help",
            )
        for (path in listOf("src/main/res/values/strings.xml", "src/main/res/values-zh-rCN/strings.xml")) {
            val text = source(path)
            for (name in required) {
                assertTrue("$path missing $name", text.contains("name=\"$name\""))
            }
            val values =
                Regex("<string name=\"([^\"]+)\">(.*?)</string>")
                    .findAll(text)
                    .filter {
                        it.groupValues[1].startsWith("share_files") ||
                            it.groupValues[1].startsWith("settings_share_files")
                    }
                    .joinToString("\n") { it.groupValues[2].lowercase() }
            for (forbidden in listOf("agent", "mcp", "conductor", "portal", "hook", "whsec")) {
                assertFalse("$path still has '$forbidden' in Share files copy", values.contains(forbidden))
            }
        }
        assertTrue(
            source("src/main/res/values/strings.xml")
                .contains("<string name=\"share_files_portal\">Relay Server</string>"),
        )
    }
}
