# Conductor P1/P2/P3 执行计划（2026-11-13）

预算：4 小时。工作清单仍以 [`TODO.md`](../TODO.md) 为准；本页只记执行顺序和验收。

## 状态（2026-11-13 晚）

- 批次 1：完成。旋转不再取消 turn；工具级超时、Tailscale 取消、MCP 确认/加密/pinning、token 预算、会话字节上限、`probe_many` 顺序批量、`select_outbound` 已落地。返回或进程死亡仍会取消中途 turn，精确 1 秒的 `TOOL_CANCEL_TIMEOUT` 仍未完成。
- 批次 2：大部分完成。`search_tools` 弱匹配、`reachable`、trace usage、Tailscale 脱敏、MCP 刷新并行、同工具自动批准、UI 滚动/回到最新已落地。`dns_lookup` / `route_decision` 和 `http_fetch_probe` 被 libbox API 阻塞；整批确认预览和真机 A/B 未做。
- 批次 3：完成。`/capabilities` MCP params、未知工具上限、`truncateObservation` 预算、`Retry-After`、`/clear`、`network_snapshot` 字段合并在 wonder.49；event reason 细分、prompt golden 字节锁、`AgentIntentParser` 清理在 wonder.50。
- 批次 4：完成。Test CI 绿，tag `v1.15.0-alpha.13-wonder.49`（commit `7048e2f`），Release 绿，`SFA-1.15.0-alpha.13-wonder.49-arm64-v8a.apk` 已上传，飞书通知已发送。

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
