package io.nekohasekai.sfa.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AgentPlannedCall(
    val tool: String,
    val argsJson: String,
    val rationale: String,
)

/**
 * Tiny deterministic planner used when no cloud model is configured. It only
 * maps a sentence to a registered tool name + JSON arguments; execution still
 * goes through AgentToolRegistry, exactly like a future model hook would.
 */
object AgentIntentParser {
    private val DIRECT =
        Regex(
            """^(list_nodes|network_snapshot|probe_via_node)\b\s*([\s\S]*)$""",
            RegexOption.IGNORE_CASE,
        )
    private val URL = Regex("""https?://[^\s，。；、]+""", RegexOption.IGNORE_CASE)
    private val NODE_PATTERNS =
        listOf(
            Regex("""(?i)(?:节点|node|outbound|出口)\s*[:：=]\s*["“「']?([A-Za-z0-9._@%+\-/]+)(?![A-Za-z0-9._@%+\-/]*://)"""),
            Regex("""(?i)(?:via|用|使用)\s+["“「']?([A-Za-z0-9._@%+\-/]+)(?![A-Za-z0-9._@%+\-/]*://)"""),
            Regex("""(?i)(?:probe|探测)\s+(?:via\s+)?["“「']?([A-Za-z0-9._@%+\-/]+)(?![A-Za-z0-9._@%+\-/]*://)"""),
        )
    private val LIST_WORDS = listOf("节点", "出站", "出口", "nodes", "node list", "outbounds", "outbound list")
    private val SNAPSHOT_WORDS = listOf("网络", "网络环境", "网络状态", "状态", "network", "snapshot", "status")

    fun parse(raw: String): AgentPlannedCall? {
        val text = raw.trim()
        if (text.isEmpty()) {
            return null
        }

        val direct = DIRECT.find(text)
        if (direct != null) {
            val tool = direct.groupValues[1].lowercase()
            val rest = direct.groupValues[2].trim()
            return AgentPlannedCall(
                tool = tool,
                argsJson = rest.ifEmpty { "{}" },
                rationale = "本地规则：输入以工具名开头，直接按名称调用 $tool",
            )
        }

        val url = URL.find(text)?.value?.trimEnd('.', '。', ',', '，', ';', '；', ')', '）')
        if (url != null) {
            val outbound =
                NODE_PATTERNS.firstNotNullOfOrNull { pattern ->
                    pattern
                        .find(text)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.takeIf { it.isNotBlank() }
                }
            if (outbound != null) {
                val args =
                    buildJsonObject {
                        put("outbound", outbound)
                        put("url", url)
                    }
                return AgentPlannedCall(
                    tool = "probe_via_node",
                    argsJson = args.toString(),
                    rationale = "本地规则：检测到 URL 和节点 '$outbound' → probe_via_node",
                )
            }
            return null
        }

        val lower = text.lowercase()
        if (LIST_WORDS.any { lower.contains(it) }) {
            return AgentPlannedCall(
                tool = "list_nodes",
                argsJson = "{}",
                rationale = "本地规则：检测到节点列表意图 → list_nodes",
            )
        }
        if (SNAPSHOT_WORDS.any { lower.contains(it) }) {
            return AgentPlannedCall(
                tool = "network_snapshot",
                argsJson = "{}",
                rationale = "本地规则：检测到网络环境意图 → network_snapshot",
            )
        }
        return null
    }
}
