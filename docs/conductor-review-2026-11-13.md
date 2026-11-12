# Conductor Agent 运行时审查（2026-11-13）

状态：只读审查完成。2026-11-13 已修复 3 项确定问题，见 §5。可执行项记在 [`TODO.md`](../TODO.md) 的 `[审查]` 标记下；本文只保留分析和证据，不另开任务清单。

审查范围：`app/src/main/java/io/nekohasekai/sfa/agent/`、`app/src/main/java/io/nekohasekai/sfa/conductor/`、相关 JVM 单测，以及 `docs/agent-runtime.md`、`docs/mcp.md`、`docs/agent-control-plane.md`。

综合评估、实验数据、去重和优先级见 [`conductor-assessment-2026-11-13.md`](conductor-assessment-2026-11-13.md)。

方法：静态对照代码、测试和设计文档。Mini 上没有 JDK，按项目约定编译和单测在 CI；写这份文档时没有跑 CI。

## 1. 结论

Conductor 的 Agent 骨架在我看过的移动端自研实现里属于上游。结构化 tool call、静态 system prompt 加追加式 transcript、`route_call` / `search_tools` 的渐进披露、确认策略放在模型外、transcript 配对失败即关闭，这几条和 2025-2026 主流做法一致。它现在的问题不在“能不能跑”，而在三个闭环还没收严：上下文预算、MCP 信任边界、流式/超时/生命周期的失败收尾。

判断：可以放心让它读网络状态、做 probe；把第三方 MCP 写工具交给它自动跑，还不够。

## 2. 与主流机制的对照

| 维度 | Conductor 现在 | 主流 / 前沿做法 | 判断 |
|---|---|---|---|
| 工具调用 | 结构化 tool call；routing 模式只发 `route_call` / `search_tools` | Anthropic Tool Search Tool、OpenAI function calling、Claude Agent SDK 的按需加载 | 方向对。核心工具仍被 `route_call` 包住，缺 hybrid 和真机 A/B |
| 上下文 | 稳定前缀、追加 transcript、48/36 滞后裁剪、旧 observation 压缩 | 2026 共识：前缀稳定、旧 observation 掩码或摘要、按需检索 | 缓存纪律很好；没有 token 预算，也没有语义摘要 |
| 循环与预算 | 12 轮工具、8 调用/轮、字符预算、Stop 真取消 | Agents SDK / LangGraph 的步数、超时、检查点 | 有界，但缺工具级超时和步骤级验证 |
| 确认 | 注册表策略、暂停点、按 turn 批准 | HITL / least agency：按影响分级、模型外强制 | 基本正确；`readOnlyHint` 和 blanket approval 是弱点 |
| MCP | Streamable HTTP、session、不跟随重定向、token | MCP 规范加 OWASP MCP 安全清单 | 基础对；注解信任、定义 pinning、取消语义缺 |
| 可观测与评估 | 内存 API trace、单测 | tracing、场景评测、可靠性指标 | 只有 trace，没有持久审计和场景评测 |
| 移动生命周期 | ViewModel 加 Compose | 服务端框架没有直接对照 | 旋转/离开会杀 turn，是移动端特有问题 |

## 3. 高优先级发现

### 3.1 MCP 的 `readOnlyHint` 被当成执行许可

`McpProtocol.parseTool` 把服务器返回的 `annotations.readOnlyHint == true` 映射成 `McpRemoteTool.readOnly`，`mcpAgentTools` 再把它映射成 `ConfirmationPolicy.NEVER`。`AgentPermission` 只出现在 `/capabilities` 展示里，`AgentToolRegistry` 的确认判断只看 `confirmationPolicy`，没有检查 `DATA_EGRESS`。

后果：一个恶意或已被污染的 MCP 服务器可以把写工具标成只读，也可以在工具描述里注入指令，让模型把设备数据塞进参数发出去。即便工具真是只读的，`fetch(url=...)` 这类工具本身也能通过 URL 外带数据。

OWASP 的 MCP 安全清单明确说注解只是 hint，不能作为安全决策，并且应该对完整工具定义做 hash/pinning，`readOnlyHint` 翻转要当成实质变更。建议：MCP 工具默认全部 `AGENT_ONLY`；只读自动执行要按服务器显式开启；对工具定义做 hash 并在变更时提示；确认卡显示服务器名、工具名和实际参数；在注册表构造时强制 `DATA_EGRESS` 至少 `AGENT_ONLY`。

### 3.2 旋转屏幕或返回会取消正在跑的 turn，并丢掉这一轮消息

`ConductorScreen` 在 `onDispose` 里调用 `viewModel.disconnect()`。`ConductorViewModel.disconnect()` 先判断 `isRunning`，运行中就不 `persistSession()`，随后 `runtime.cancel()` 和 `agentJob?.cancel()`。Activity 没有声明 `configChanges`，所以旋转会重建 Activity，Compose composition 被销毁，`onDispose` 必然执行。

实际后果：turn 跑到一半旋转屏幕，turn 被取消；这一轮的用户消息、已经流出的正文、已经完成的工具结果都不会落盘；下次进入 Conductor 恢复的是上一轮会话。按返回键离开也一样。这和文档里“离开 Conductor、回到主界面或进程重启后恢复”的表述不一致。

建议：不要在 composition dispose 里取消 Agent；把取消放到 `onCleared` 或显式返回路径，或者用 `activity.isChangingConfigurations` 区分旋转；取消前先保存当前 transcript 和 items。

### 3.3 流式空流或缺少 `finish_reason` 被当成成功

`OpenAiCompatibleModelClient.collectStream` 在 SSE 读到 EOF 时无条件发出 `Completed(finish ?: UNKNOWN, usage)`。`AgentRuntime.runTurn` 只要没有 tool call 就返回 `TurnOutcome.Completed`，不检查内容是否为空，也不检查 `finish_reason`。非流式路径的 `parseResponse` 反而会抛 `INVALID_MODEL_RESPONSE`，两条路行为不一致。

实际后果：VPN 或代理中断 SSE、服务器提前关连接、返回 200 但内容不是 SSE，都会得到一个“已完成”的空回答或半截回答；transcript 会把它当成合法 assistant 消息，下一轮继续带着它。

2026-11-13 已修复，见 §5。

### 3.4 工具没有超时，turn deadline 只在模型轮之间检查

`AgentToolRegistry` 只有 `withContext(Dispatchers.IO)`，没有 `withTimeout`；`AgentRuntime.runTurn` 只在每轮循环开头检查 turn 总超时。MCP 读超时 30 秒，`tailscale_status` 最多 5 秒，8 个工具调用串行时一轮就可能远超预算。`TOOL_CANCEL_TIMEOUT` 和文档里的 `tool_timeout` 都没有任何产生路径。

`TODO.md` 里写“等出现无法 1 秒取消的工具再补”，但 `tailscale_status` 已经是这样的工具：它在 Stop 后最多还要等 5 秒。建议：每个工具加显式超时和取消注册；turn deadline 在工具前后都检查；`tailscale_status` 把 `ToolCancellation` 传进去；超时转成 observation，而不是让 UI 一直处于 `isRunning`。

### 3.5 上下文只有字符/条数预算，没有 token 预算

`AgentConfig.maxInputTokens = 64_000` 没有被任何代码使用。真正生效的是消息条数（48/36）、单条 observation 24k 字符、单轮 64k 字符、单 turn 128k 字符。长 assistant 正文、长 user 消息、很多条 7k 字符的 observation 都不在预算内，可能把一次请求推到 provider 的上下文上限，最后只得到一条 `invalid_request`。

2026 年“Less Context, Better Agents”的结论是：全量历史 71% 完成率，裁剪到最近 5 组工具结果 79%，裁剪加摘要 91.6%。Conductor 现在做的是旧 tool observation 的掩码，方向对，但只做了一半。建议：加一个保守 token 估算（中文按字、英文按 4 字符/token），在 token 高水位时触发压缩；至少对 old assistant/user 轮做摘要或丢弃；把 `maxInputTokens` 真正接进请求前检查。

### 3.6 会话持久化可能写入不合法的 transcript，恢复时整段丢弃

`ConductorSessionStore.save` 原来直接 `transcript.takeLast(MAX_TRANSCRIPT)`。如果第 120 条边界正好切在 assistant tool call 和对应 tool 结果之间，保存的 JSON 本身可解析，但 `restoreSession` 里 `validateTranscriptPairing` 会失败，随后 `transcript = emptyList()`。UI items 仍然恢复，于是界面显示旧对话，模型历史却是空的。

同一处还有体积问题：120 条 transcript 加 200 条 UI item，每条 tool output 最多 24k 字符，极端情况下 JSON 可达数 MB；`SharedPreferences` 的读取在 ViewModel 初始化时同步发生，编码在每次 `persistSession` 时也在主线程。

2026-11-13 已修复配对截断，见 §5。体积上限和 IO 线程仍待做。

## 4. 中优先级发现

- **Stop 竞态**：`AgentRuntime.runTurn` 开头重置 `cancelRequested = false`，`OpenAiCompatibleModelClient.stream` 开头重置 `cancelled = false`。Stop 如果发生在 `submitAgent` 检查 `stopRequested` 之后、`runTurn` 重置之前，会被吞掉。建议用 `agentJob` 的取消或传入 generation token，不要重置别人持有的取消标志。
- **流式重试会重复 UI 文本**：`streamModelWithRetry` 只清 `partialContent`，之前已经发到 UI 的 `AssistantDelta` / `ReasoningDelta` 不会撤回；重试后的 delta 会追加到同一个 streaming item，界面上出现重复。如果重试失败，`appendPartialAssistant` 只保留最后一次尝试的 partial，transcript 和 UI 不一致。建议重试前发一个 reset 事件，或只在首字前重试。
- **MCP `tools/call` 在 404 时自动重试**：`McpSession.request` 对任何方法都重试一次。若服务器已经执行了写操作但 session 过期返回 404，客户端会重新 initialize 再发一次，可能重复副作用。建议只对 `initialize` / `tools/list` 自动重试；`tools/call` 返回可恢复错误，或按工具只读性决定。
- **MCP token 明文存储**：API key 走 Android Keystore，MCP Bearer token 却直接写在 `conductor/mcp-servers.json`。应用私有目录加备份排除已经是底线，但和 API key 的处理不一致。建议复用 Keystore 加密，或在文档里明确接受这个风险。
- **MCP 确认卡把 URL 全部打码**：`AgentSanitizer.sanitizeArgsJson` 对 MCP 工具没有 `allowedUrlParameter`，`url` 会变成 `[redacted-url]`。用户点确认时看不到即将发送的地址，审批退化成橡皮图章。建议确认卡对 URL 只屏蔽 userinfo 和敏感 query，保留 host/path；日志仍然全打码。
- **MCP 刷新阻塞首条消息**：`ConductorViewModel.submitAgent` 在 `runTurn` 前调用 `refreshMcpTools()`，`McpRuntime.refreshIfStale()` 串行连接每个过期服务器，连接超时 15 秒、读超时 30 秒，最多 8 个服务器；期间 UI 只有 `isRunning`，没有进度。建议并行刷新加短超时，或用上一份缓存先开 turn，后台刷新。
- **权限与审计没有闭环**：`AgentPermission` 只是元数据，注册表不按它做决策；文档说审计复用 `AppLog`，实际只有内存 `AgentApiTrace`，且 release 里默认驻留完整请求/响应，和文档“debug build 生效”的约定不一致。建议至少记录 turn id、工具名、权限、确认结果、耗时、token 用量到结构化日志；trace 加开关或改成 debug-only。
- **`Approve rest of turn` 的爆炸半径**：它一次批准本 turn 后续所有工具，包括模型后来才声明的 MCP 写工具。HITL 的 2026 共识是按影响分级，不可逆或外部动作不该被 blanket approval 覆盖。建议自动批准只覆盖同一工具或只读工具；`DATA_EGRESS` / MCP 写工具仍然逐次确认。

## 5. 2026-11-13 已修复

- **SSE 缺完成标记**：`OpenAiCompatibleModelClient.collectStream` 现在区分 `[DONE]` 和 EOF；既没有 `finish_reason` 也没有 `[DONE]` 时抛 `INVALID_MODEL_RESPONSE`，标记 retryable。补了 `OpenAiCompatibleModelClientTest.rejectsSseThatEndsWithoutCompletion`。
- **空回答**：`AgentRuntime.runTurn` 在没有 tool call 且 content 为空时返回 `INVALID_MODEL_RESPONSE`，不再写入空 assistant。补了 `AgentRuntimeTest.emptyStreamingAnswerFailsClosed`。
- **transcript 截断切断 tool 批次**：新增 `trimTranscriptToLast`，从最近的 user 消息开始切片。`AgentContext.trim` 和 `ConductorSessionStore.save` 都改用它。补了 `AgentContextTest.trimStartsAtATurnBoundaryWhenTheCutFallsOnAFinalAssistant` 和 `ConductorSessionStoreTest.savedTranscriptNeverStartsInsideAToolBatch`。

## 6. 小问题与文档漂移

- `AgentContext.truncateObservation` 在 output 和 message 都超限时，会各截到 `budget`，实际序列化结果可能接近 2 倍预算。
- `AgentToolRegistry` 的未知工具错误会把全部工具名拼进 message。routing 模式下 `route_call` 的未知目标已经用 top-3 建议接管，但模型直接拼错真实工具名时仍会走这里；MCP 工具多了会爆 token。
- `/capabilities` 对 MCP 工具显示 `params: none`，因为只读 `definition.parameters`，没读 `parametersSchema`。
- `/clear` 在 `isRunning` 时提交不了，因为 `submit` 开头就拦了所有输入，但文档写它能取消活动 turn。
- 重试退避是线性的 0.5/1/1.5 秒，不读 `Retry-After`，文档写的是指数退避并尊重 `Retry-After`。
- 消息列表没有“用户上滑后不抢滚动 / 回到最新”的实现；代码只在 `items.size` 变化时 `animateScrollToItem`，流式正文增长不会跟随，也没有回到底部的按钮。
- `LazyColumn` 用 index 作 key，`takeLast(MAX_ITEMS)` 丢头元素后，展开状态可能错位到别的 item。
- `AgentIntentParser` 只在测试里使用，主代码没有入口，是 POC 遗留。
- `AgentPromptStabilityTest` 只断言两次生成结果相等，不是跨版本的 golden 字节锁，改提示词不会被它拦住。

## 7. 建议顺序

1. **生命周期**：`onDispose` 不再取消 Agent；旋转和返回都保留当前 turn；取消前先持久化。
2. **超时与取消**：工具级 `withTimeout`、`tailscale_status` 注册取消、`TOOL_CANCEL_TIMEOUT` 真正产生并上报。
3. **MCP 信任边界**：注解只当 hint、定义 hash/pinning、`tools/call` 不盲重试 404、token 加密、确认卡显示参数。
4. **上下文预算**：token 估算加高水位压缩；会话文件总字节上限，编解码移到 IO。
5. **评测闭环**：用 15 到 20 条中文真实 prompt 做 native / routing 两种模式的 A/B，记录首次参数合法率、工具选择准确率、延迟、prompt tokens、cache hit；再决定 routing 是否默认开。文档里这件事一直写着“待做”，但默认已经是 `true`。
6. **UI 手感**：stick-to-bottom 加“回到最新”，流式增长也跟随；列表用稳定 id 作 key。

## 8. 参考

- [Anthropic: Introducing advanced tool use](https://www.anthropic.com/engineering/advanced-tool-use)
- [Claude Agent SDK: Tool search](https://code.claude.com/docs/en/agent-sdk/tool-search)
- [Less Context, Better Agents (arXiv 2606.10209)](https://browse-export.arxiv.org/abs/2606.10209)
- [Beyond pass@1: A Reliability Science Framework for Long-Horizon LLM Agents (arXiv 2603.29231)](https://arxiv.org/html/2603.29231v1)
- [Locating Hidden Failures Makes Long-Horizon Agents More Reliable (arXiv 2609.17930)](https://export.arxiv.org/pdf/2609.17930)
- [OWASP MCP Security Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/MCP_Security_Cheat_Sheet.html)
- [MCP Server Capability Declaration Audit](https://safeguard.sh/resources/blog/mcp-server-capability-declaration-audit)
- [Human in the Loop and Least Agency Checklist](https://brandefense.io/knowledge-hub/agentic-security/human-in-the-loop-and-least-agency-checklist/)
- [Context Engineering for AI Agents: Complete 2026 Guide](https://sourcegraph.com/blog/context-engineering)
