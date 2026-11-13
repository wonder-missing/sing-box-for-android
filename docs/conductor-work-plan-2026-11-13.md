# Conductor 执行与数据面计划（2026-11-13）

工作清单仍以 [`TODO.md`](../TODO.md) 为准；本页记执行顺序、数据面设计和验收。数据面设计已并入本页，不再单独维护 `conductor-data-plane.md`。

## 状态（2026-11-13 晚）

- 批次 1：完成。旋转不再取消 turn；工具级超时、Tailscale 取消、MCP 确认/加密/pinning、token 预算、会话字节上限、`probe_many` 顺序批量、`select_outbound` 已落地。进程死亡仍会丢 turn 中途的 tool 卡片，精确 1 秒的 `TOOL_CANCEL_TIMEOUT` 仍未完成。
- 批次 2：大部分完成。`search_tools` 弱匹配、`reachable`、trace usage、Tailscale 脱敏、MCP 刷新并行、同工具自动批准、UI 滚动/回到最新已落地。`dns_lookup` / `route_decision` 和 `http_fetch_probe` 被 libbox API 阻塞；整批确认预览和真机 A/B 未做。
- 批次 3：完成。`/capabilities` MCP params、未知工具上限、`truncateObservation` 预算、`Retry-After`、`/clear`、`network_snapshot` 字段合并在 wonder.49；event reason 细分、prompt golden 字节锁、`AgentIntentParser` 清理在 wonder.50。
- 批次 4：完成。Test CI 绿，tag `v1.15.0-alpha.13-wonder.49`（commit `7048e2f`），Release 绿，`SFA-1.15.0-alpha.13-wonder.49-arm64-v8a.apk` 已上传，飞书通知已发送。
- 批次 5：完成（wonder.50，commit `072be92`）。补齐批次 3 的 event reason 细分、prompt 字节锁、`AgentIntentParser` 清理；另加 trace 内存上限和错误码/请求 ID。tag `v1.15.0-alpha.13-wonder.50`，Release 绿，飞书通知已发送。
- 批次 6：完成（wonder.51，commit `204bfa6`）。跨版本会话修复：提交即持久化 user、恢复对账、历史 tool 脱敏、MCP 发现、失败详情事件、版本边界、trace 摘要持久化、Tailscale JSON、取消代际、观察/推断 prompt 规则。
- 批次 7：完成（wonder.52，commit `6ebbacb`）。数据面第一版：文件作用域、`diagnostics_export`、`taildrop_send`、结构化参数、provider 加固。
- 批次 8：完成（wonder.53，commit `abd0958`）。Conductor ViewModel 改为 activity 作用域，离开页面不再取消 turn；`search_tools` 空 query 现在返回 catalog（不含 schema）；数据面设计并入本页。
- 批次 9：完成（wonder.54，commit `7a6bd1b`）。恢复会话时，所有非 `/` 的 UI user 都补进 transcript，不再要求后面必须跟着 agent 产物；最后一条没跑成的 user 也会补回。修「消息留在流里、下一轮 Agent 不知道」。

## 数据面（自 wonder.52 起）

长期定位：Conductor 不只是 APP 控制面，而是个人数据搬运、跨设备文件投递和通讯的 Agent。它长在网络 App 里，天然知道本机网络、Tailscale peers 和出口状态。

术语：

- **工作目录**：`getExternalFilesDir(null)`，Settings > Core > Working Directory > Browse 看到的根。Agent 的读范围。
- **Conductor 工作区**：`工作目录/conductor/`。Agent 唯一的 raw write 区域。
- **WorkingDirectoryProvider**：把工作目录暴露给系统文件选择器的 SAF DocumentsProvider。它不是工作区，只是查看入口。
- **Taildrop**：Tailscale 的身份绑定文件传输通道，不是目录；收件落在 `工作目录/Taildrop/`。

权限：

| 范围 | 读 | 写 | 说明 |
|---|---|---|---|
| 工作目录（含 `Taildrop/`、crash/OOM/power reports） | 允许 | 禁止 | 只读；发送前确认 |
| `工作目录/conductor/` | 允许 | 允许 | 唯一 raw write 区域；2MB/文件、20 个文件、24h TTL |
| 内部 `filesDir` / `shared_prefs` / keystore | 禁止 raw | 禁止 raw | 需要时走 typed reader（trace、conversation、log tail），先脱敏 |
| Tailscale peers | 读状态 | 无 | 只给名字、online、lastSeen；不暴露 IP、账号邮箱 |
| Taildrop | 列表/接收 | 发送 | 发送 `ALWAYS` 确认；只允许 tailnet peer |

原则：文件字节默认不进模型；工具返回 manifest（路径、大小、mtime、sha256、脱敏摘要）；`file_read` 默认打码 token/邮箱/Tailscale IP；发送是唯一出网边界，只允许工作目录内文件 + tailnet peer + 用户确认。

已实现（wonder.52）：

- `file_list`、`file_stat`、`file_read`、`file_write`、`file_mkdir`、`file_delete`；读根工作目录，写根 `conductor/`；canonical path 检查、绝对路径/`..`/越界拒绝。
- `diagnostics_export(kind=conversation|trace)`：写 `conductor/`，默认时间戳文件名，返回 path/size/sha256，内容先脱敏。
- `taildrop_peers`、`taildrop_send`：只允许工作目录内文件，`ALWAYS` 确认，等待完成，取消/超时/审计；复用 `TaildropSendManager` / `TaildropSendService`。
- 工具参数支持 integer、boolean、string array；`WorkingDirectoryProvider` docId 越界加固。
- 工具描述保持能力导向，不写具体 peer/应用/一次性场景；新工具不是 core，roster 只加分类名，schema 走 `search_tools`。

待做：

- `taildrop_inbox`、`taildrop_receive`（接收方向）。
- `export_log_tail`、`self_test` 固定套件、`conductor_eval`。
- 角色 prompt 从 network control plane 扩成 data courier + control plane（等接收方向和自测闭环齐了再改，避免承诺没有的能力）。
- 通讯与自动化：短消息、通知、定时检查、收件箱监听、A → B 中继；仍然只走 typed 工具 + 确认。

## 批次

### 批次 1：P1 核心可靠性

- [ ] 生命周期：移除 `onDispose { disconnect() }`，旋转不取消 turn；离开页面由 `onCleared` 收尾。
- [ ] 工具超时与取消：`AgentConfig.toolTimeoutMs`，注册表 `withTimeout`，`tailscale_status` 接 `ToolCancellation`，超时回 observation。
- [ ] MCP 信任：所有 MCP 工具默认 `AGENT_ONLY`，`DATA_EGRESS` 强制至少 `AGENT_ONLY`，`tools/call` 404 不自动重试。
- [ ] 上下文：`maxInputTokens` 接入估算与裁剪；会话持久化总字节上限。
- [ ] probe 批量：`probe_many`（先顺序执行，一次工具调用返回表）；如果 libbox 支持 per-request outbound，再并行。

### 批次 2：P2 诊断、隐私、可观测性

- [ ] 网络诊断：`dns_lookup` / `route_decision` 视 libbox API 可用性实现；不可用则记录阻塞证据。
- [ ] `http_fetch_probe`：GET 小范围内容，解决 HEAD 歧义；`speed_test` 视 API 决定。
- [ ] `select_outbound`：确认、快照、失败回滚。
- [ ] `tailscale_status`：去掉登录名/邮箱，peer IP 可选，结构化输出。
- [ ] `search_tools`：弱匹配标记/阈值；`ToolObservation.ok` 改成 `reachable`。
- [ ] API trace：结构化 usage/finish/耗时/开始时间，长流不再依赖 200k body。
- [ ] Stop 竞态与流式重试 UI 一致。
- [ ] MCP 刷新并行/短超时；确认卡保留 URL host/path。
- [ ] 权限与审计：`AgentPermission` 强制策略；结构化审计摘要。
- [ ] 批量确认：整批预览 + 同工具自动批准。
- [ ] UI：stick-to-bottom、回到最新、稳定 key。
- [ ] A/B：15 到 20 条中文 prompt 语料和指标；真机执行留待设备。

### 批次 3：P3 小修

- [ ] `/capabilities` MCP params。
- [ ] 未知工具列表上限。
- [ ] `truncateObservation` 预算。
- [ ] `Retry-After`。
- [ ] `/clear` 文档/行为。
- [ ] prompt golden。
- [ ] event reason 细分；trace 开始时间/错误码。
- [ ] `network_snapshot` / `list_nodes` 字段合并。

### 批次 4：验证与发布

- [ ] Test CI 绿。
- [ ] `WONDER_BUILD=49`，tag `v1.15.0-alpha.13-wonder.49`。
- [ ] Release workflow 绿，arm64 APK 存在，飞书通知已发。
- [ ] TODO / assessment 更新，未完成项写明阻塞原因和证据。

## 验收

每个代码改动至少满足：

- JVM 单测覆盖核心分支，或明确说明只做编译验证的理由。
- 不破坏 transcript 配对、sanitizer、确认边界。
- 需要真机确认的 UI/网络项写进 TODO 的「手机上看 wonder.49」。
