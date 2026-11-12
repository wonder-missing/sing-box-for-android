# Conductor 综合评估（2026-11-13）

状态：基于 2026-11-12/13 的实验导出、实现评审（[`conductor-review-2026-11-13.md`](conductor-review-2026-11-13.md)）和 [`TODO.md`](../TODO.md) 的合并评估。本文做去重、状态和优先级；可执行工作仍以 TODO 为准。

实验导出（未入库）：

- `conductor-api-trace-20261113-003417.md.txt`
- `conductor-conversation-20261113-003410.md.txt`

## 1. 实验数据

12 次模型请求，时间从 2026-11-12 23:59 到 2026-11-13 00:33。

| 请求 | 时间 | 结果 | messages | 工具调用累计 | prompt tokens | cache hit |
|---|---|---|---|---|---|---|
| #1 | 23:59:22 | 200 | 2 | 0 | 1,353 | 128 (9.5%) |
| #2 | 23:59:24 | 200 | 5 | 2 | 2,150 | 1,408 (65.5%) |
| #3 | 23:59:26 | 200 | 7 | 3 | 3,828 | 2,304 (60.2%) |
| #4 | 00:00:00 | 200 | 11 | 6 | 4,544 | 4,224 (93.0%) |
| #5 | 00:00:19 | 200 | 14 | 8 | 5,193 | 4,864 (93.7%) |
| #6 | 00:00:24 | 200 | 16 | 9 | 6,371 | 5,760 (90.4%) |
| #7 | 00:06:26 | 200 | 18 | 9 | 未记录 | 未记录 |
| #8 | 00:07:55 | 200 | 20 | 9 | 6,815 | 5,888 (86.4%) |
| #9 | 00:08:03 | 200 | 23 | 11 | 未记录 | 未记录 |
| #10 | 00:14:51 | timeout | 25 | 11 | 未记录 | 未记录 |
| #11 | 00:15:28 | DNS 解析失败 | 25 | 11 | 未记录 | 未记录 |
| #12 | 00:33:18 | 200 | 25 | 11 | 未记录 | 未记录 |

观察：

- system prompt 和 `tools` 在 12 次请求里保持一致，prompt cache 命中率从首请求的 9.5% 升到 90% 以上。缓存纪律有效。
- messages 从 2 条增长到 25 条，没有触发 `trim` 或 `compaction`。本次实验没有覆盖高水位上下文。
- 11 次工具调用：`network_snapshot`、`list_nodes` 两次、`probe_via_node` 五次、`search_tools` 两次、`tailscale_status`。`route_call` 参数都合法，没有 unknown tool 或参数错误。
- 五次 probe：direct 204/206ms；一个节点 EOF/5853ms；一个节点 15s 超时；两个节点 DNS `no such host`/约 6s。
- #10 超时，#11 重试时 DNS 失败，#12 在 18 分钟后成功。失败原因是本地网络/VPN DNS，不是模型或消息序列化。
- 失败的用户消息「如果我想让你获得编写临时脚本并运行的能力，你觉得应该怎么实现？」在 #10、#11 的请求里，不在 #12 的请求里。wonder.48 已改为保留该消息；#12 在新版本会包含它。
- #7、#9、#12 的响应 body 在 API trace 里被 200,000 字符截断，因此 usage 和 finish_reason 没有记录。流本身由客户端完整解析，不是模型错误。
- `tailscale_status` 的输出包含账号登录名（邮箱）和所有 peer 的 IP，随后被发给模型并进入对话导出。见 P2。
- 模型在 #7 自己提出 `dns_lookup`、`route_decision`、`core_logs`、`node_detail`、`probe_matrix`、`http_fetch_probe`、`speed_test` 等能力。这些是实验暴露的能力缺口，不是当前实现的 bug。

## 2. 合并后的优先级

状态说明：`已修` 表示代码已改并过 CI；`待真机` 表示需要设备确认；`待办` 表示尚未实现。

### P1：数据丢失、安全边界、核心可靠性

| ID | 问题 | 来源 | 证据 | 状态 / 下一步 |
|---|---|---|---|---|
| P1-1 | 旋转屏幕或离开页面会取消进行中的 turn，并丢掉这一轮消息 | 实现评审 §3.2 | `ConductorScreen` 的 `onDispose` 调用 `disconnect()`；运行中不 `persistSession()`；Activity 没有 `configChanges` | 部分已修（wonder.49）：旋转不再取消；返回或进程死亡仍会取消进行中的 turn。 |
| P1-2 | 工具没有通用超时；`tailscale_status` 不能 1s 取消；`TOOL_CANCEL_TIMEOUT` 未产生 | 实现评审 §3.4；实验 probe 15s/6s | `AgentToolRegistry` 只有 `withContext(IO)`；deadline 只在模型轮之间检查 | 部分已修（wonder.49）：工具级 `withTimeout`、Tailscale 取消、`tool_timeout` 已落地；精确 1 秒的 `TOOL_CANCEL_TIMEOUT` 仍未完成。 |
| P1-3 | MCP 把 `readOnlyHint` 当执行许可；`DATA_EGRESS` 只是标签 | 实现评审 §3.1 | MCP 只读工具映射成 `NEVER`；注册表不按 `AgentPermission` 决策 | 已修（wonder.49）：MCP 全部 AGENT_ONLY；`DATA_EGRESS` 强制；完整定义 hash/pinning；404 不重试；token Keystore 加密；确认卡保留 URL。 |
| P1-4 | 上下文没有 token 预算；`maxInputTokens` 未使用；会话持久化没有总字节上限 | 实现评审 §3.5、§3.6 | 只有 48/36 消息数和字符预算；实验 25 条未触发高水位 | 已修（wonder.49）：token 估算与裁剪、会话字节上限；语义摘要和 IO 编解码仍未做。 |
| P1-5 | `probe_via_node` 串行切换全局出站，不能批量并发 | TODO P1；实验五次 probe | 每次 probe 6 到 15 秒，五次串行；每次临时切换再还原 | 已修（wonder.49）：`probe_many` 顺序批量已实现；libbox 没有 per-request outbound binding，真正并发阻塞。 |

### P2：能力缺口、隐私、可观测性、体验

| ID | 问题 | 来源 | 证据 | 状态 / 下一步 |
|---|---|---|---|---|
| P2-1 | 缺少确定性网络诊断：`dns_lookup`、`route_decision`、脱敏 `core_logs`、`node_detail` | 实验 #7 模型建议；probe DNS 失败 | 模型只能从 probe 结果推测「隧道内 DNS 坏了」 | 阻塞：libbox 没有 DNS 查询或 route match API，需要 daemon/CommandServer 扩展。 |
| P2-2 | `probe_via_node` 只做 HEAD，EOF 有歧义；缺少真 GET/速度测试 | 实验 probe；实现评审 | 一个节点 EOF/5853ms，模型无法区分 HEAD 拒绝和节点坏 | 阻塞：libbox GET 没有状态码 API，且会完整读取 body，无法限制 max_bytes；暂不实现。 |
| P2-3 | `search_tools` 宽泛查询返回弱相关结果，没有 no-match 信号 | TODO P2；实验 #6 | 查询「切换选择策略组...」返回三个只读网络工具，模型多花约 3.5k 字符 | 已修（wonder.49）：搜索结果增加 `weak` 标记和提示。 |
| P2-4 | `ToolObservation.ok` 与工具 `output.ok` 语义重复 | TODO P2；实现评审 | 两层 `ok` 容易误读 | 已修（wonder.49）：probe 内层改为 `reachable`。 |
| P2-5 | `tailscale_status` 把账号登录名（邮箱）和所有 peer IP 发给模型；输出未结构化 | 实验 #8；TODO P2 | 对话导出包含账号名和 peer IP；模型只需要设备名/在线状态 | 部分已修（wonder.49）：Agent 视图去掉登录名和 IP，offline peer 加 last-seen；未改成 JSON。 |
| P2-6 | usage/cache 不可见；API trace 在 200k 字符处截断，长流丢失 usage/finish | 实验 #7/#9/#12；TODO P2 | 长响应 body 到 200,000 字符被截断；只有 7/12 请求有 usage | 已修（wonder.49）：trace 结构化 usage/finish/耗时/开始时间，UI 增加 usage 摘要；长响应 body 仍会截断。 |
| P2-7 | Stop 取消标志在 turn 开头被重置，存在竞态；流式重试会重复 UI 文本 | 实现评审 §4 | `cancelRequested = false`、`cancelled = false`；重试只清 `partialContent` | 部分已修（wonder.49）：runtime 取消标志不再在 turn 开头重置；流式重试发 StreamReset。模型客户端 cancel 竞态仍在。 |
| P2-8 | MCP `tools/call` 404 自动重试可能重复写；token 明文；确认卡 URL 被打码；刷新阻塞首条消息 | 实现评审 §4 | `McpSession.request` 对任何方法重试；token 在 JSON；无 `allowedUrlParameter`；刷新串行 | 已修（wonder.49）：404 不重试、token 走 Keystore、确认 URL、刷新并行；刷新进度 UI 仍无。 |
| P2-9 | 权限与审计没有闭环；`AgentApiTrace` 在 release 默认驻留完整请求/响应 | 实现评审 §4 | `AgentPermission` 不参与决策；trace 只在内存，无结构化审计 | 部分已修（wonder.49）：`DATA_EGRESS` 强制、AppLog 审计；trace release 驻留和内存上限仍未做。 |
| P2-10 | `Approve rest of turn` 的爆炸半径；批量确认没有整批预览 | 实现评审 §4；TODO P2 | 一次批准覆盖后续所有工具，包括 MCP 写工具 | 部分已修（wonder.49）：自动批准只覆盖同工具；整批预览未做。 |
| P2-11 | 路由模式没有真机 A/B；默认已开 | 实现评审 §7 | `conductorToolRouting` 默认 `true`；仓库没有评测 | 语料已建（`docs/conductor-eval.md`），真机执行待做。 |
| P2-12 | 消息列表自动滚动/稳定 key 未按设计实现 | 实现评审 §6 | 只按 `items.size` 滚到底；没有「回到最新」；`key = index` | 已修（wonder.49）：stick-to-bottom、回到最新、稳定 key；key 仍是 index+type，不是持久 id。 |
| P2-13 | 会话持久化体积和主线程编解码 | 实现评审 §3.6 | 120 条 transcript + 200 条 item，单条 tool output 最多 24k；SharedPreferences 同步读 | 部分已修（wonder.49）：总字节上限；编解码仍在主线程。 |
| P2-14 | 缺少 `select_outbound` 等受控写能力 | 实验 #7 模型建议 | 模型能诊断但不能处置；probe 只临时切换 | 已修（wonder.49）：`select_outbound` 确认、失败回滚；未做选择后自动读回校验。 |
| P2-15 | 其他已记录 backlog | TODO P2 | LLM 摘要、跨版本会话边界、probe 复测、连接可观测、Provider capability | 保持现有优先级，不重复展开 |

### P3：小修与清理

- `AgentContext.truncateObservation` 在 output 和 message 都超限时可能接近 2 倍预算。
- `AgentToolRegistry` 未知工具错误会拼全部工具名，MCP 工具多时爆 token。
- `/capabilities` 对 MCP 工具显示 `params: none`。
- `/clear` 在 `isRunning` 时提交不了，和文档不一致。
- 重试退避是线性 0.5/1/1.5 秒，不读 `Retry-After`。
- `AgentPromptStabilityTest` 不是跨版本 golden 字节锁。
- `AgentIntentParser` 是 POC 遗留，主代码没有入口。
- turn 事件的 reason 只有错误大类，DNS、连接失败、5xx 都会落到 `model_unavailable`；可以细分。
- API trace 的时间是完成时间，不是开始时间；错误只记字符串，没有错误码/请求 ID。
- `network_snapshot` 和 `list_nodes` 的 core 字段重复。

### 已修复

- ~~SSE 缺 `finish_reason`/`[DONE]` 或空回答被当成成功~~（wonder.47，含单测）。
- ~~transcript `trim` 在 final assistant 边界切断 tool 批次~~（wonder.47，含单测）。
- ~~会话持久化 `takeLast` 切断 tool 批次~~（wonder.47，含单测）。
- ~~长按最后一条用户消息只有「全选」~~（wonder.47/48，待真机确认 [#7](https://github.com/wonder-missing/sing-box-for-android/issues/7)）。
- ~~失败 turn 把本轮 user 从模型 transcript 回滚，下一轮模型看不到失败消息~~（wonder.48，含单测）。
- ~~失败且没有 assistant/tool 输出时没有 Retry~~（wonder.48）。
- ~~连续两条 user 没有说明~~（wonder.48，system prompt 已说明）。

### 环境 / 非 App

- #10 超时、#11 DNS 解析失败：本地网络/VPN DNS 问题，不是 Conductor。direct probe 能通，节点内 DNS 失败；模型 API 也走同一条网络。先把 DNS/VPN 修好，再用 manual Retry。
- 实验里的 tailnet 账号名和 peer IP 是用户自己的数据；本文不复述具体值。

## 3. 结论

- 架构骨架是稳的：结构化 tool call、静态前缀、按需工具目录、模型外确认、transcript 配对、sanitizer 都在实验里正常工作；prompt cache 从首请求 9.5% 升到 90% 以上，说明缓存纪律有效。
- 实验暴露的两处体验问题已经修掉：失败消息不再从模型上下文消失；失败 turn 有 Retry；UI 和模型看到的历史一致。
- 当前最该做的是 P1：生命周期数据丢失、工具超时/取消、MCP 信任边界、上下文预算、probe 批量。它们分别对应「别丢用户的 turn」「别让 Stop 卡住」「别让第三方工具越权」「别让上下文爆」「别让 66 个节点串行 15 秒」。
- 实验最大的能力缺口不是再加只读工具，而是诊断和处置：`dns_lookup`、`route_decision`、`http_fetch_probe`、`probe_matrix`、受控 `select_outbound`。这些排在 P2，先做诊断，再做写。
- 本次实验没有覆盖 MCP 实际调用、旋转、长上下文、Stop 中途取消；这些 P1 仍然需要真机或专门测试。
