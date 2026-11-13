# WonderSing 待办

只放未完成事项。已完成的事项不再长期留在这里，见 GitHub Releases 和 git history。
新增一项加到「现在待办」；做完就删掉，或在「最近完成」留一行索引。

## 现在待办

> 2026-11-13 的 Conductor 审查见 [`docs/conductor-review-2026-11-13.md`](docs/conductor-review-2026-11-13.md)，综合评估（实验数据 + 审查 + TODO 去重）见 [`docs/conductor-assessment-2026-11-13.md`](docs/conductor-assessment-2026-11-13.md)。下面标 `[审查]` 和 `[评估]` 的项来自这两份文档；TODO 仍是唯一工作清单，文档只放分析和证据。

### 数据面（长期定位：数据搬运 / Taildrop / 通讯）

设计见 [`docs/conductor-work-plan-2026-11-13.md`](docs/conductor-work-plan-2026-11-13.md) 的「数据面」一节。Conductor 的长期定位不只是 APP 控制面，而是个人数据搬运、跨设备文件投递和通讯的 Agent；它长在网络 App 里，天然知道本机网络和 Tailscale peers。当前仍主要是控制面，按下面阶段补。

- [x] **Phase A 文件作用域（wonder.52）**：读整个工作目录（`getExternalFilesDir`，含 `Taildrop/`、crash/OOM/power reports），写只允许 `工作目录/conductor/`。工具 `file_list`、`file_stat`、`file_read`、`file_write`、`file_mkdir`、`file_delete`；canonical path 检查、拒绝 `..`/绝对路径/越界；单文件 2MB、20 个文件、24h TTL；`WorkingDirectoryProvider` docId 越界加固。
- [ ] **Phase B Taildrop（部分完成，wonder.52）**：`taildrop_peers`、`taildrop_send` 已实现；发送只允许工作目录内文件，`ALWAYS` 确认，只允许 tailnet peer；复用 `TaildropSendManager` / `TaildropSendService`，`TaildropSendFile` 已加 `File` 构造；还有 `taildrop_inbox`、`taildrop_receive` 未做。
- [ ] **Phase C 自省与证据（接下来）**：`export_log_tail`、`self_test` 固定套件、`conductor_eval` 单独开关；报告写工作区。加一个脱敏的 `diagnostics_network_history`：最近 N 条 `network_change` / `service_start` / `service_stop` / `vpn_revoked`，让 Agent 自诊断 Tailscale 抖动。目标是 Conductor 自己导出证据、自测、Taildrop 给 Mini 上的 Agent。
- [ ] **工具元数据只用英文（务实版）**：工具名、描述、alias、system prompt 的搜索指引统一英文；prompt 明确「把用户意图翻成英文关键词再 `search_tools`」。清掉现有中文 alias（包括 wonder.57 临时加的 `file_stat`「文件信息」），英文同义词保留。发布时批量生成多语言关键词的流水线记 backlog，不做：模型本身就是翻译层，静态多语言 alias 会带来维护和误匹配成本。加一小段中文 prompt → 英文 query 的评测语料进 `docs/conductor-eval.md`。
- [ ] **文件工具补齐（对齐前沿 Agent 的最小集）**：当前 `file_read` 只能从头读最多 256KB，1MB 的 trace 无法截最后三轮。加 `file_read` 的 `offset`/`limit`（按行）和 `file_tail`、`file_grep`（有界匹配/上下文）、`diagnostics_export(kind=trace, tail=N)`；不做通用 shell / apply_patch。验收：让 Conductor 把 trace 最后三轮摘出来 Taildrop 到 Mini，全程不把 1MB 正文塞进模型。
- [ ] **用户选文件（Agent 发起文件申请）**：新增 `request_files(reason, multiple=true)`，打开系统 `OpenMultipleDocuments` 选择器；工具本身不复制、不改属性，只返回 `{id, name, size, mime}`；URI 只留在内存，不 `takePersistableUriPermission`，会话/进程结束即失效。随后 `file_read_selected(id, max_bytes)` / `taildrop_send_selected(peer, ids)` 按需读取或投递。选择器本身就是用户同意；限制 10 个文件、读取 256KB、投递 8MB；离开页面或取消就结束等待。参考 Android SAF 和 MCP elicitation，不申请 `MANAGE_EXTERNAL_STORAGE`。中等工程，排在文件工具补齐之后。
- [ ] **Phase D 通讯与自动化（以后）**：短消息、通知、定时检查、收件箱监听、A → B 中继；仍然只走 typed 工具 + 确认。

### P1

- [ ] **probe 真正并发（阻塞）**：libbox 没有 per-request outbound binding，只能临时全局切换。`probe_many` 已按顺序批量实现；真正并发需要 daemon/新 API。实验里五次 probe 串行 6 到 15 秒。
- [ ] **[审查] Conductor 生命周期（剩余）**：旋转和返回页面已不再取消（wonder.53 activity 作用域）；提交时已先持久化 user。进程死亡仍会丢 turn 中途的 tool 卡片和未完成 assistant。
- [ ] **[审查] `TOOL_CANCEL_TIMEOUT` 精确 1 秒**：工具级超时、Tailscale 取消已落地；仍没有在 Stop 后 1 秒内把未确认取消转成 turn error。

### P2

- [ ] **服务重启缺口（2026-11-13 12:39）**：CFG 遥测显示 09:11 和 12:39 两次 `service_start`，中间没有 `service_stop`；如果不是手动重启，下轮用 Conductor 的 file tools 读工作目录里的 crash/OOM reports，或对比当时的 `tailscale_status`。排障入口见 [`docs/agent-runtime.md`](docs/agent-runtime.md) 的「服务状态排障」。
- [ ] **会话编解码移出主线程**：`ConductorSessionStore` 的 JSON encode/decode 和 `AppLog` 同步写文件仍在调用线程；wonder.51 在 submit 时又加了一次 `persistSession()`，长会话可能造成主线程卡顿。先让 `ui_jank` 取证，再把 encode/save 放 IO、load 放后台。
- [ ] **旧会话 UI 与导出的敏感原文**：wonder.51 只清洗 transcript（模型 egress）；会话 items 和对话导出仍保留旧的 `tailscale_status` 原文，分享导出前需清对话或手动处理。
- [ ] **[评估] trace 长 body 截断**：usage/finish/错误码/请求 ID 已结构化，内存有总量上限，摘要在会话里持久化；单条 body 超过 200k 字符仍会截断，跨重启只有摘要没有完整 body。
- [ ] **批量确认：预览与分级**：整批预检并合成一张含全部工具参数的卡；`Approve rest of turn` 只覆盖同工具或只读工具，`DATA_EGRESS` / MCP 写工具逐次确认。
- [ ] **确认与事务语义可见性**：模型看不到确认事件；多步调用没有框架级事务。先记着，等 2B 流式和 Phase 3 再评估。
- [ ] **[评估] 网络诊断能力**：`dns_lookup(domain, via=direct|outbound)`、`route_decision(domain)`、脱敏 `core_logs`、`node_detail`；先做前两个。实验里模型只能从 probe 结果推测 DNS 故障。
- [ ] **[评估] `probe_via_node` 的 HEAD 歧义**：加 `http_fetch_probe(outbound, url, max_bytes)`，把 HEAD 被拒和节点坏分开；`speed_test` 后续。
- [ ] **对话级 LLM 摘要**：wonder.36 只做旧大 tool observation 的确定性压缩；assistant/user 长文本和跨 turn 的语义摘要还没做。
- [ ] **跨版本旧 tool schema 归一（剩余）**：wonder.51 已加版本 Notice、`app_updated` 事件和导出 header；旧 tool observation 里的 `ok` 等字段仍按旧语义留在 transcript，没有逐条改写。
- [ ] **[评估] 设备上下文版本历史（低优先级）**：模型建议在 device context 加 `appVersionHistory` 或 `updateCount`；当前只有 `appVersion` + `app_updated` 事件，答不出精确更新次数。跨版本复用不常发生，先记着。
- [ ] **probe 链路复测（等出口健康）**：wonder.34 的 6 连失败是出口节点不健康（订阅在 CFG 项目打磨），不是 Conductor 回归；204 分类待出口健康后复测。
- [ ] **连接与规则命中可观测性**：最近 N 条连接 + 命中规则 + 最终出站；需要 CommandClient 支持，Phase 3 方向。
- [ ] **Provider capability 矩阵**：wonder.42 起 Settings 和 `/reasoning` 共用 Reasoning effort（默认不传；`low` / `medium` / `high` 写入 `reasoning_effort`）。仍未做：`stream_options.include_usage`、`max_completion_tokens`、Azure 类鉴权，以及 DeepSeek `none`/`max`、xAI `xhigh` 这类不能三家共用的档位。
- [ ] **增量更新（低优先级）**：整包 bsdiff 方案，评估见 [`docs/incremental-update.md`](docs/incremental-update.md)；约 3–4 个工作日，等有整块时间再做。归档感知不做。
- [ ] **[审查] trace release 驻留**：`DATA_EGRESS` 和 AppLog 审计已做；trace 内存有总量上限，摘要跨重启保留；`AgentApiTrace` 仍在 release 默认驻留完整请求/响应，直到进程结束。
- [ ] **[审查] 路由 A/B 真机执行**：语料见 [`docs/conductor-eval.md`](docs/conductor-eval.md)；native/routing 对比还没跑。
- [ ] **Conductor 最后一条用户消息长按只有「全选」**（[#7](https://github.com/wonder-missing/sing-box-for-android/issues/7)）：wonder.47/48 已用 `ComposeFoundationFlags` 关闭 Compose 1.11 的新 context menu 和 smart selection；待真机确认 Copy 回来，确认后按上游修复再移除开关。

## 下个会话接手

- 下一步做数据面 Phase B 的剩余部分（`taildrop_inbox`、`taildrop_receive`）和 Phase C（`export_log_tail`、`self_test` 固定套件、证据闭环）。设计见 [`docs/conductor-work-plan-2026-11-13.md`](docs/conductor-work-plan-2026-11-13.md) 的「数据面」一节。
- Conductor 综合评估（2026-11-13）：先读 [`docs/conductor-assessment-2026-11-13.md`](docs/conductor-assessment-2026-11-13.md)。wonder.49/50/51 已落地 P1/P2/P3 大部分（P3 已全部完成），剩余项和阻塞证据见该文档；审查证据见 [`docs/conductor-review-2026-11-13.md`](docs/conductor-review-2026-11-13.md)。
- 当前已发布 wonder.57（tag `v1.15.0-alpha.13-wonder.57`，commit `6e4008f`；56 为 `a473fca`，55 为 `8db3dc7`，54 为 `7a6bd1b`）。下一次从 58 开始，先别打 tag。发布 tag 用 annotated tag。能说清的话在注解里写 `Agent-Time` 和 `Agent-Loops`，说不清就留空，通知会省略。不要为了补这两行去移动已有 tag。
- 手机上看近期版本（49 到 54）：
  - 长按最后一条用户消息，菜单应有「复制」和「全选」；失败后错误卡应有「重试」，点后只保留一份用户消息。
  - 说「把某组前 5 个节点都测一遍」应触发 `probe_many`：一次确认，返回结果表；不要再弹 5 张确认卡。
  - 说「把出口切到 direct」应出确认卡；执行后用 `list_nodes` 读回。失败时应回滚旧选择。
  - MCP 工具应全部显示 asks first；确认卡里的 URL 可读，token/query 打码。修改服务器工具定义后，刷新应提示重新启用，而不是静默替换。
  - 每轮结束应有 usage 摘要（in/out/cache）；流式回答上滑后不应被强制拉到底，点「回到最新」回到末尾。
  - 运行中点 `/clear` 应停止当前 turn 并清空。
  - Tailscale 工具输出不应带账号邮箱和 peer IP；离线设备应有 last-seen。wonder.51 起返回 `endpoints/self/peers` JSON，不再是 `result` 文本。
  - 升级后打开旧会话：末尾应有一条版本 Notice；对话导出 header 应写当前 app version 和 session started on。之前没有 pending failure 时，下一条消息的 event 行应带 `app_updated`。
  - 在旧会话里问「我刚才那条失败的消息说了什么」：模型应能复述，不应再说「没收到内容」。
  - 导出 API trace：应出现 `Request history (persisted)`，包含重启前的请求摘要（时间、版本、状态、耗时、usage）。
  - 问「你能把日志传到 Mini 吗」：模型应先 `search_tools` 找 MCP 能力，不应直接断言没有。
  - 失败卡出现后，下一条消息的请求 event 行应带 `message="..."`（DNS/超时等详情），模型能据此解释失败原因。
  - 问「把 API trace 和 transcript 发到 Mini」：应先 `diagnostics_export` 写 `conductor/`，再 `taildrop_send`；确认卡显示 peer 和文件；Mini 的 Taildrop 收件箱出现文件。
  - 让 Conductor `file_list` 工作目录：应看到 `Taildrop/`、报告目录和 `conductor/`；`file_write` 只能写 `conductor/`，写外面应被拒。
  - `file_read` trace 或日志：token 应打码为 `***`，邮箱/Tailscale IP 也应打码。
  - `taildrop_send` 给离线 peer 应明确失败，不应卡住。
  - 发消息后立刻划屏幕边缘回主界面，再进 Conductor：turn 应继续，发送按钮应仍是停止；不应悄悄取消。
  - 如果某条消息确实没跑成，下一轮问「你看到我上一条没发出去的消息了吗」：模型应能说出内容。
  - 让模型「列出所有工具」或空 query 搜索：应返回 catalog，不应报 `search_tools requires a non-blank 'query'`。
- 给 Portal 的 `readOnlyHint` 建议先别整理。
- wonder.45 在 wonder.44 的 MCP 客户端上加了 `/mcp`。拨号遥测仍在这一版里（`9c3ef15`，说明见 [`docs/telemetry-dial.md`](docs/telemetry-dial.md)）。CFG ingest `e9053fa` 已部署。MCP 说明见 [`docs/mcp.md`](docs/mcp.md)。
- 手机上看 wonder.46：
  - 设置根页 App 行：标题下方直接是裸版本号，没有「应用版本」字样。右侧是刷新图标，不是「更新」按钮。点图标仍会检查更新；已有新版本时下面多一行提示。
  - 设置 → 应用里不再有 Node list layout。分组表仍可用标题栏的单列/双列按钮切换。
  - Conductor 点一次 `/` 弹出命令菜单，再点一次 `/` 收起，文字不变。第三次再弹出。输入框里继续打字后，菜单按内容重新出现。
  - 离开再进入 Conductor，消息流不再追加 “Service started. Waiting for the core…”。旧会话里已经有的那一行还在，清对话才会消失。
  - 设置 → Conductor → MCP 服务器。填名称、https URL、可选 Bearer token，点「添加并连接」。成功时这一行显示工具数量。对话里点 `/`，选 `mcp`，应列出服务器短名、必填参数，以及「asks first」或「runs immediately」。`/mcp knocks` 只留下名字里带 knocks 的。`/mcp use orb_status` 发送后，输入框变成 `Use the MCP tool orb_status from <服务器名>. `，光标在末尾，这一步不发给模型。系统提示里不应出现 MCP 服务器名或工具名。明文 `http://` 可能被系统拒绝。（wonder.49 起所有 MCP 工具都要确认，`readOnlyHint` 不再免确认。）
  - Conductor 点 `/`，选 `reasoning`，再选 `high`。对话里应出现已设为 high 的提示。下一条普通消息的请求带 `reasoning_effort=high`。`/reasoning` 无参数只显示当前值。
  - 输入框已有文字时点 `/`，斜杠在最前，光标紧跟在斜杠后面。点 `@`，`@` 在末尾，光标在 `@` 后面。
- 仍未做，按阻塞原因：
  - probe 真正并发：libbox 没有 per-request outbound binding；`probe_many` 目前顺序执行。
  - `dns_lookup` / `route_decision` / `http_fetch_probe`：libbox 没有 DNS/route API，GET 也没有状态码和 body 上限。
  - `TOOL_CANCEL_TIMEOUT` 精确 1 秒；返回页面仍会取消进行中的 turn；turn 中途 tool 卡片不落盘。
  - 批量确认整批预览；trace 单条长 body 截断、跨重启无完整 body、release 驻留；会话编解码仍在主线程。
  - 路由 A/B 真机执行；旧 tool schema 归一；旧会话 UI/导出的敏感原文。
  - Provider capability、probe 链路复测、连接可观测、LLM 摘要、增量更新。

## 最近完成

- wonder.57（2026-11-13）：补 `file_stat` 的中文 alias「文件信息」，让「文件」查询能召回全部文件工具；加回归测试。tag `v1.15.0-alpha.13-wonder.57`，commit `6e4008f`。
- wonder.56（2026-11-13）：工具搜索召回修正。分类名查询返回整个分类（network/files/mcp 等）；去掉 alias 精确命中的提前返回，中文泛化 alias 不再遮住其他工具；system prompt 声明每轮搜索次数上限；golden hash 更新。来源是 Conductor 自测报告 `tool-search-report-20261113.md`。tag `v1.15.0-alpha.13-wonder.56`，commit `a473fca`。
- wonder.55（2026-11-13）：性能与抖动遥测。`network_change` 只记 default uplink；新增 `ui_jank`、`tailscale_status` 耗时/结果、`service_heartbeat`、`conductor_tool` 耗时；主线程事件走后台写线程。tag `v1.15.0-alpha.13-wonder.55`，commit `8db3dc7`。
- wonder.54（2026-11-13）：恢复会话时，所有非 `/` 的 UI user 都算 agent 消息，不再要求后面必须跟着 agent 产物；最后一条没跑成的 user 也会补回 transcript。修「消息留在流里、下一轮 Agent 不知道」。tag `v1.15.0-alpha.13-wonder.54`，commit `7a6bd1b`。
- wonder.53（2026-11-13）：Conductor ViewModel 改为 activity 作用域，离开页面不再取消进行中的 turn；`search_tools` 空 query 返回 catalog（不含 schema），修掉提示词契约与实现不一致；数据面设计合并进工作规划。tag `v1.15.0-alpha.13-wonder.53`，commit `abd0958`。
- wonder.52（2026-11-13）：数据面第一版。读整个工作目录、写 `conductor/`；`file_list/stat/read/write/mkdir/delete`；`diagnostics_export(kind=conversation|trace)`；`taildrop_peers`、`taildrop_send`（只允许工作目录内文件，`ALWAYS` 确认，复用现有 Taildrop service）；参数 schema 支持 integer/boolean/string array；`WorkingDirectoryProvider` docId 越界加固。tag `v1.15.0-alpha.13-wonder.52`，commit `6ebbacb`。
- wonder.51（2026-11-13）：跨版本会话修复。提交时先持久化 user；恢复时把 UI 有、transcript 缺的 agent user 按锚点补回；清洗历史 tool 里的邮箱和 Tailscale peer IP；session 记录 appVersion，版本变化加 Notice/`app_updated` 事件，导出写版本 header；trace 摘要跨重启持久化；`turn_failed` 事件带 `message="..."`；`tailscale_status` 改结构化 JSON；MCP 发现提示 + `search_tools` categories；模型取消改代际；prompt 加观察/推断规则。tag `v1.15.0-alpha.13-wonder.51`，commit `204bfa6`。
- wonder.50（2026-11-13）：P3 清理。trace 加 1M 字符总量上限（旧条目先缩到 20k 再丢弃）、错误码和 `X-Request-Id`；传输错误拆成 `network_dns` / `network_connect` / `network_tls` / `network_handshake` / `network_timeout`；system prompt 加 SHA-256 字节锁；删除无入口的 `AgentIntentParser`。tag `v1.15.0-alpha.13-wonder.50`，commit `072be92`。
- wonder.49（2026-11-13）：Conductor P1/P2/P3 批次。工具级超时、Tailscale 取消、`probe_many` 顺序批量、`select_outbound` 确认/回滚；MCP 全部 AGENT_ONLY、`DATA_EGRESS` 强制、定义 fingerprint、404 不重试、token Keystore、确认 URL、刷新并行；token 预算、会话 512KB 上限、search weak、probe `reachable`、trace usage/耗时、Retry-After、`/clear`、AppLog 审计；UI Retry/usage/滚动/回到最新。libbox 无 per-request outbound、DNS/route、GET 状态码，probe 真正并发和诊断工具阻塞。评估见 [`docs/conductor-assessment-2026-11-13.md`](docs/conductor-assessment-2026-11-13.md)。
- 2026-11-13：Conductor 综合评估（实验导出 + 实现评审 + TODO 去重），见 [`docs/conductor-assessment-2026-11-13.md`](docs/conductor-assessment-2026-11-13.md)。已修复项在评估里划掉；P1 顺序已重排。
- wonder.48（2026-11-13）：失败 turn 不再把本轮 user 从模型 transcript 回滚，UI 和模型上下文一致；空失败 turn 显示 Retry，重发最后一条用户消息，不重跑已有工具结果。system prompt 明确连续两条 user 是独立请求。
- wonder.47（2026-11-13）：Conductor 审查落地：SSE 缺完成标记或空回答被当成功、`AgentContext.trim` 在 final assistant 边界切断 tool 批次、会话持久化 `takeLast` 切断 tool 批次；新增对应单测。修复 [#7](https://github.com/wonder-missing/sing-box-for-android/issues/7)：关闭 Compose 1.11 的新 context menu 和 smart selection，恢复用户消息长按菜单的「复制」。审查文档见 [`docs/conductor-review-2026-11-13.md`](docs/conductor-review-2026-11-13.md)。
- wonder.46（2026-11-12）：A11 启动图；导出文件名带本地时间；系统提示不再列 MCP；`/mcp use` 把一句优先提示放进输入框。设置根页 App 行改为刷新图标，版本号紧贴标题下方。删掉应用设置里的 Node list layout（分组表的单列/双列按钮还在）。Conductor 的 `/` 按钮第二次按下收起菜单。进入 Conductor 不再追加 “Service started. Waiting for the core…”
- wonder.45（2026-11-12）：`/mcp` 列出已打开的 MCP 服务器和工具。按完整工具名搜索只返回那一个；按服务器名搜索一次带出该服务器的 schema
- wonder.44（2026-11-12）：Conductor MCP 客户端。设置里添加并打开 Streamable HTTP 服务器，Agent 用 `search_tools` 发现、`route_call` 调用。只读工具直接执行，其他工具先确认。拨号遥测同一版才能在手机上看到
- wonder.43（2026-11-12）：`/reasoning` 与设置页共用推理强度；设置根页 App 行的版本标题和版本号分行；输入栏 `/`、`@` 插入后光标跟在新文字后面
- wonder.42（2026-11-12）：设置页 App 行版本号与「更新」；Conductor Reasoning effort（low/medium/high，默认不传）；飞书通知带单测条数和短 changelog（超过 320 字或 8 行用卡片）；Conductor 未发送草稿跨页面保留；启动图标垂直居中
- wonder.41（2026-11-11）：A7 app icon（adaptive / legacy / round / monochrome / playstore）；传输错误边界；单调用 `Approve rest of turn`；`conductor:event` 中断信封与空 turn 回滚
- wonder.40（2026-11-11）：中断/失败 turn 收尾：Stop 保留已流出的 assistant 正文，没有正文时在 transcript 末尾补合成 assistant 结束消息，避免下一次请求出现两个连续 user；对话与 API trace 共用文件导出，新增 Save conversation to file
- wonder.39（2026-11-11）：多 Provider：DeepSeek / xAI / Custom（OpenAI-compatible）预设、按 provider 独立 model/key/base URL、turn 级 provider 快照、OpenAI cached_tokens 与 reasoning 字段兼容
- wonder.38（2026-11-11）：Thinking 生命周期修复（结算所有 streaming 项，正文开始即停 spinner）、两行自动上滚 Reasoning ticker + Show/Hide、批量确认 Run all、API trace 保存为文件
- wonder.37（2026-11-11）：真机反馈修复：2MB 粘贴崩溃（输入上限/恢复截断）、空 API trace 复制提示、Stop 后 `Stopped.` 与去重、已完成工具结果保留、Reasoning 流式 `Thinking` 块、压缩阈值 8k
- wonder.36（2026-11-10）：Phase 2b 全部收尾：OkHttp SSE 流式回答、Stop 真取消、probe/telemetry 可取消、旧大 tool observation 的 turn 边界压缩、请求前缀/SSE/Stop/压缩单测
- wonder.35（2026-11-10）：`list_nodes` 默认只回 group 摘要（tag/selected/itemCount/selectedDelayMs、groupCount/outboundCount），传 `group=<tag>` 才回该组节点明细；去掉 flat `outbounds` 与 `groups[].items` 的重复
- wonder.34（2026-11-10）：`probe_via_node` 按 HTTP 状态判定可达（204/3xx/4xx/5xx 不再误报 `tool_failed`）、probe 回滚边界写进描述、请求前缀不变量单测、prompt 允许知识迁移（知道名字和参数就直接调）
- wonder.33（2026-11-10）：2B 工具目录与路由 + 缓存修正：静态 `route_call` / `search_tools`、目录 roster、分发前 unwrap、滞后裁剪、`toolChoice = NONE`、Settings 路由开关、API trace cache usage
- wonder.32（2026-11-10）：IME 收起后清焦点、system prompt 去 DeepSeek 品牌、probe HEAD 说明、2A 文档/TODO 与实现对齐
- wonder.31（2026-11-10）：横三点对话菜单、Markdown 对话复制、Copy API trace（headers 脱敏）、用户消息自由选择、8 次/轮与 12 轮的宽松 guard、Telemetry 冗余说明删除
- wonder.30（2026-11-10）：系统提示改为稳定前缀并持久化 transcript，提高缓存命中；对话复制改到最后消息下方三点菜单；Settings Conductor 入口瘦身、设置按钮改为纵向满宽；服务状态条为内容预留底部空间；工具步数耗尽后仍给出最终回答
- wonder.29（2026-11-10）：Conductor 会话跨页面/进程持久化、顶部一键复制整段对话、模型输出上限 1024→4096、服务启动文案与工具摘要优化
- wonder.28（2026-11-10）：Conductor 2A 打磨：用户消息长按复制、轻量 Markdown 渲染、工具卡片折叠与美化、Tailscale 友好错误、服务连接文案、基础设备上下文（语言/时区/版本）
- wonder.27（2026-11-10）：Conductor Phase 2a：DeepSeek BYOK、自然语言单步 tool calling、确认卡片、敏感输入拦截、tool 卡片、可配置模型 ID
- wonder.26（2026-11-09）：Conductor 展开键盘到顶时的颤动；IME 变化期间及之后 300ms 高度 snap、高度取整到像素、placeholder 移出 `BasicTextField` decoration
- wonder.25（2026-11-09）：Conductor 输入栏默认态随文本长高到 5 行、回删变矮；收键盘时面板高度 snap，避免下塌回弹
- wonder.24（2026-11-09）：定位到问题实际在 Dashboard 的 Groups 底部面板标题行；只改标题行右 4dp、分组 chevron 右 16dp，撤回自绘顶栏与列表 padding 实验
- wonder.23（2026-11-09）：Groups 顶栏改为自绘、外观保持一致；顶栏尾部 icon 与分组 chevron 共用 `TrailingActionSlot`
- wonder.19（2026-11-09）：Conductor 键盘遮挡、分组 chevron 与顶栏对齐、订阅时间改用 Update/Published 图标
- wonder.18（2026-11-09）：Conductor 输入栏 v1.2、app 日志与日志页解耦、`/probe`

CFG 订阅、Tailscale 控制面这类跨项目问题归 CFG 仓库跟踪，不放在这里。
- wonder.17（2026-11-08）：快捷方式后台启停、Tailscale 直达、订阅时间单行
- wonder.16（2026-11-08）：APK 收缩/英文过滤、504 错误详情、桌面快捷方式首版
