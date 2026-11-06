# WonderSing / Claude Code 任务：遥测一批（2026-11-06）

仓库：`wonder-missing/sing-box-for-android`，在 **`dev`** 上改。不要打 Release，不要改发版签名钥匙，只编/动与本任务相关的代码。改完把本批写进 `TODO.md` 并勾上已落地项（仍标「未发版」）。

主人用的是现网 `1.15.0-alpha.13-wonder.7`。`dev` 上已有未发版的遥测质量改动（多批上传、`batch_id`、`client_events`、直连/VPN 路径记忆、备用路径 6s 连接超时等，见现有 TODO「遥测质量」）。本任务叠在这些改动之上。

Soul 负责串联和验收：你改完推到 `dev`（或开 PR 到 `dev`）后停下来，由 Soul 对照本文件验收。不要问主人要不要发版。

---

## 背景：Soul 刚看过的最近上报（请当作事实，不要再空转猜）

数据：Cloudflare Worker `wk-7c41e9`（现入口 `https://wk-7c41e9.wonderbeyond.workers.dev`），KV `LOGS`，`GET /tail`。窗口约 2026-11-06 13:05–21:41（Asia/Shanghai）。`/tail?limit=200` 拉到 200 条，几乎全是 wonder.7，合计约 7.3 万行原始日志。一台设备。

观察到的：

1. **事件全是服务端从原始日志推导的**（`events_src=server`）。`client_events` 几乎为空（符合 wonder.7；`dev` 上已有客户端事件，未发版）。没有 `crash` / `FATAL` 类遥测事件。
2. 原始日志里和 crash 有关的只有对 `crashlyticsreports-pa.googleapis.com` 的 DNS/路由（Firebase Crashlytics），**不是** 应用内 Tools → Crash Report 那两份本地报告。
3. 上传很密，大量批次 `n=500`（wonder.7 单批上限）。例如 19:08 到 21:41 几乎每 1–3 分钟一批。
4. 结构化事件大致：`urltest_ok` 882、`urltest_fail` 542（多为 `♻️ 自动选择` 的 CF 入口 `deadline`）、`error` 276、`conn_error` 245、`handshake_reset` 38、`ts_netmap` 47、`core_started` 5。没有 `service_stop` 推导（wonder.7 关连接时若阻塞在上报，这条也可能没发出去）。
5. `conn_error` / `outbound=tailscale` / `refused` / `100.95.239.29:443` 出现在 19:48–20:05。那是当天 Mini 临时 Tailscale IPv4（后来主人改回 `100.124.215.62`）。这是当时身份切换的残留，**不是本任务要修的 bug**，验收时若再看到旧 IP 再报即可。
6. `error` 里常见 `outbound/urltest:deadline`、`outbound/urltest:io_timeout`（`dial rmnet_data0 …`，蜂窝网卡直拨 CF 入口超时）、以及 DNS 读超时。说明测速经常在底层蜂窝上死线，和关 VPN 卡住是两条线；本任务只处理主人点名的遥测问题，不要趁机改 urltest/订阅。
7. 有过一条 `app_version=test` 的 `telemetry_upload_failed`：`path=direct`，`ms=6001`，`error=timeout`。与「直连 workers.dev 先超时再改走 VPN」的已知行为一致。

KV / Worker 现状（`/workspace/CFG/telemetry-ingest/index.js`，已部署）：

- 每条记录 `expirationTtl = 7 * 86400`（7 天），含去重键 `~dup:<batch_id>`。**已经会定期清，不会无限涨。** 不需要再做 cron 删除，除非你验收时用 wrangler 看 key 数量高得离谱再建议调 TTL。
- 单请求上限 1 MiB；记录含 `lines` + `events` + 可选 `client_events`。
- Worker 目前只有 `workers.dev`（`wrangler.toml` 里 `workers_dev = true`，无自定义域）。大陆访问 `workers.dev` 本身就不稳；CFG 的代理/订阅已经改走 `*.soulfun.fyi` 就是这个原因。

关闭连接卡住的代码路径（请改这里，不要另绕）：

- `BoxService.stopService()`：先把 `status` 设成 `Stopping`，在 `Dispatchers.IO` 里关掉 VPN/`closeService()`，然后 **`await TelemetryRuntime.detachAndFlush()`**，成功后才把状态打成 `Stopped`。
- `detachAndFlush()`：最多 `TelemetryPayload.FINAL_FLUSH_TIMEOUT_MS`（12s）里 `uploadAvailable("stop", finalFlush=true)`，一次最多 `MAX_STOP_BATCHES`（6）批。内部 HTTP 是阻塞的 `HttpURLConnection` / 手写 socket，**`withTimeoutOrNull` 取消不了正在 connect 的调用**。VPN 已经拆掉后，优先走的 `direct` 打 `*.workers.dev` 在大陆很容易把整个 Stopping 卡住十秒以上（连接超时常见 6–15 秒一跳，两跳更久）。
- `onDestroy()` 走的是 `detachFromDestroy()`（后台 daemon 线程），关连接 UI 等的不是这条。

应用内 Crash Report（未纳入遥测）：

- `CrashReportManager`：本地 `crash_reports/` 目录，含 `metadata.json`、`go.log`、`jvm.log`，**可能还有 `configuration.json`（订阅/配置，视为敏感，禁止原文上报）**。
- UI：`CrashReportListScreen` / `CrashReportDetailScreen`。主人说已经积了两份。现网上报事件里没有对应内容。

设置页：`TelemetrySettingsScreen` 在 `dev` 上已有保存提示和「连通测试」（`TelemetryRuntime.probe`）。主人这次要的是 **最近一次成功上报时间** 和 **立即触发真正上报（把积压发出去）**，不要和 probe 混成同一个按钮。

---

## 要做的事

### 1. 关闭连接不再被上报堵住（优先）

- `stopService()` / `stopAndAlert()` **不要再等待** `detachAndFlush()` 完成才离开 `Stopping`。先持久化本地缓存，再异步上报（可复用/靠拢 `detachFromDestroy()` 的思路），VPN 关闭与 UI 回到 Stopped 不得被 HTTP 拖住。
- 异步上报失败就记 `telemetry_upload_failed`（`dev` 上已有），下次成功或用户点「立即上报」再发；不要丢盘上的 pending 日志。
- 不要为了「停得快」在主线程做网络。不要把 `runBlocking` 放到会卡住 UI/Stopping 的路径上。

### 2. 设置页：上次成功时间 + 立即上报

- 展示最近一次 **服务器接受成功** 的本地时间（Asia/Shanghai 对用户设备本地时区即可），没有成功过就显示尚未成功。probe 成功也可以更新「上次连通」，但要和「上次日志上报成功」分开，或文案写清楚。主人要的是上报，请至少记录 `last_upload_ok_at`。
- 提供「立即上报」按钮：立刻尝试把当前积压（日志 + `client_events` + 下面的 crash 摘要）发出去；进行中禁用按钮并给简短结果（成功 / 失败原因）。不阻塞其它 UI。
- 文案保持简洁，少堆说明（现有 TODO 已有这条原则）。

### 3. 把应用内 Crash Report 纳入遥测

- 上传里带上尚未报送的本地 Crash Report **摘要**，例如：报告 id、时间、metadata 里的 exceptionName/exceptionReason/source/appVersion/coreVersion，以及 go/jvm 日志的截断文本（建议每份日志最多几千字符，去掉明显的订阅 URL；沿用现有 `TelemetryEvents.scrub`）。
- **禁止**上传 `configuration.json` 原文或完整订阅/token。
- 用事件类型如 `crash_report`（或等价名字）放进 `events` / `client_events`，服务端 `cleanEvents` 已能原样收下 type 字符串。同一份报告只成功报送一次（本地记住已报送 id）。
- Worker 若只需原样存 `events` 就不必大改；不要把整份 zip 塞进 KV。

### 4. 上报 URL 改用 `*.soulfun.fyi`（有必要，请做）

原因：现入口是 `wk-7c41e9.wonderbeyond.workers.dev`。大陆直连 workers.dev 差，关 VPN 后只剩 direct 路径，既导致超时卡住，也让「立即上报」在未连 VPN 时容易失败。同账号下 CFG 已用 `cdn-ixbw.soulfun.fyi` / `cfg-5k6z.soulfun.fyi`。

建议主机名：`ws-tele.soulfun.fyi` → 同一个 Worker `wk-7c41e9`，路径仍是 `/ingest`、`/tail`。Token 不变。

- 在 `/workspace/CFG/telemetry-ingest/wrangler.toml`（或你在本机对应的 CFG 副本）加上自定义域/routes，**不要**关掉 `workers.dev` 兜底。
- 若你这边没有 Cloudflare 写权限或 DNS 加不出记录，在结果里写明缺哪一步，交给 Soul 去绑域名，不要编造已经绑好。
- App 侧：设置页 hint 不要再只写 `https://example.workers.dev`。若用户已保存的 endpoint host 就是旧的 `wk-7c41e9.wonderbeyond.workers.dev`，读取时规范到新域名（只改这一台已知 worker，别误伤用户自定义的其它 URL）。
- 不要把 ingest token 写进仓库、文档或提交信息。现网 token 在云电脑 `/workspace/CFG/private/sfa-telemetry.env`，只给运行/验收用。

### 5. 服务端清理（结论：已有 7 天 TTL，核对即可）

- 读一下现网 `expirationTtl`，确认仍是 7 天。不要删除这个 TTL。
- 可用 wrangler/KV list 看一下当前 key 数量级，写在结果里。若没有爆炸（按今天的量，7 天大概数千条记录 + 同等数量去重键），不必再做清理任务。
- 不要把原始日志或 token 贴进 PR 描述。

### 6. TODO.md

按仓库习惯追加本批条目；已在 `dev` 落地的打勾并注明未发版。不打 Release。

---

## 验收标准（给 Soul）

1. 点击关闭连接：Stopping 很快结束，不再明显等待上报；断网或 workers.dev 不通时也不应卡十秒以上。
2. 设置页能看到上次成功上报时间；点「立即上报」能在未关 UI 的情况下把积压发到 ingest（可用现网 token 对 `/tail` 看到新记录，含你制造的测试事件）。
3. 人为放一份本地 crash_reports（或用已有测试夹具）后上报，`/tail` 记录里能看到 crash 摘要事件，且没有 configuration/订阅原文。
4. 若自定义域已绑好：未开 VPN 时 direct 打 `https://ws-tele.soulfun.fyi/ingest` 应能成功（或明显好于 workers.dev）。旧 workers.dev URL 仍可用作兜底。
5. KV 仍带 7 天 TTL；结果里有 key 数量级说明。
6. 无 Release、无 token 泄漏、无订阅 URL 进仓库。

做完后用几段话写清：改了哪些文件、域名是否已经绑上、你怎么自测的、Soul 验收还缺什么。
