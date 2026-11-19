# 拨号与上传遥测（2026-11-12）

CFG 要回答的三件事，对应三组字段。URLTest 是第四件事，单列策略观测，不混进拨号健康度。

1. 这一批有没有送到。批次带上当时的上行网络，以及日志的墙上时钟范围。
2. 手机有没有拨到代理入口。内核每次入口拨号记一条 `dial_attempt`，成功和失败都记。
3. 入口起来之后，用户流量有没有下行。连接流按分钟汇总，不按条上传。

不做：网络变化时自动再跑 URLTest、SSID / 家里标记、`http_status`、拨号重试序号、把 12000 行队列加大、把 `conn_error` 改名、给 ingest 域名加路由规则。

## 批次

`ts` 仍是上传时间。另外有 `upload_ts`（同一个值）、`transport`、`network_id`、`metered`、`validated`、`event_ts_min`、`event_ts_max`、`upload_lag_ms`。

`upload_lag_ms = upload_ts - event_ts_min`，表示这批里最旧一条带时间戳的日志有多陈。日志行只有内核开机秒数（`INFO[123]`），墙上时钟在收到时算，只放内存。进程死后从磁盘恢复的行没有时间戳，不参与 `event_ts_*`，这时批次里没有 `upload_lag_ms`。沉默或缺口不是健康。

`network_id` 形如 `wifi:ab12`，是传输类型加 Network 对象的哈希，重连会变。没有 SSID。

这组字段要 Worker 抄进记录才进 Observability。`client_events` 本来就会过去。

## dial_attempt

一条表示一次到代理入口的拨号（TCP / TLS / WS，Shadowsocks 的 UDP 入口也算）。字段：`dial_ts`、`outbound`、`network`（tcp/udp）、`entry`（拨通后的 `RemoteAddr`，失败时用配置的 server）、`result`（ok/fail）、`error_class`、`duration_ms`、`cause`。

Socks 的 UDP associate，以及 UoT，包装连接的 `RemoteAddr` 是用户目的地。这两条只记配置里的 server，不把访问地址写进 `entry`。

`cause=urltest` 是 URLTest 打进来的，健康度要排除。`cause=traffic` 是用户流量带起来的新物理连接。复用已有的 mux / 会话不再记。

直连、block、dns、分组出站不记。走 detour 的外层不记，由真正拨入口的那一层记。

没盖住的协议：hysteria、hysteria2、tuic、naive、tor，以及 snell 的 TCP。它们的握手在外部模块里，包住「每条用户流」的 API 会把用户流当成拨号。

手机侧每 2 秒调 `Libbox.readDialAttempts()` 把内核环（最多 256，满了丢最旧）抄进 `client_events`。抄的时候盖上当时的 `transport` / `network_id` / `metered` / `validated`，和拨号时刻最多差一个轮询间隔。`error_class` 跟现有 `conn_error` 同一套名字，另加 `dns`。

## conn_minute

连接流（1 秒一拍）按关闭时刻所在的分钟汇总成一条 `client_events`，类型 `conn_minute`。一行是 outbound、粗目的（端口或嗅探协议，不上传域名）、有没有下行、条数、上下行字节、平均时长、平均 `first_byte_ms`。`first_byte_ms` 是 NEW 到第一次下行增量，精度就是这 1 秒。

`error_class` 只在关闭前后 2 秒内有同一 outbound 的 `open connection to …` ERROR 时填上，否则空着。对不上就不编。DNS 出站不进汇总。

`entry` 如果有，是配置文件里的 `server:server_port`，不是实际拨到的 IP。实际地址只在 `dial_attempt`。

未关闭的连接留到关闭才进分钟。服务停止时把当前分钟和仍开着的连接一并交出。

## 内核补丁

补丁是 `core/patches/dial-attempt.patch`，打在不可变的 `v1.15.0-alpha.13` 上，不改 `CORE_VERSION`。libbox 缓存键从 `v1` 改成 `v2` 加补丁哈希，Test 和 Release 用同一条。缓存未命中时 CI 先 `go test ./common/dialreport/`，再编 aar。

## 性能与抖动信号（2026-11-13 起）

这一组只回答「是不是 App 侧卡了/服务死了/状态查询慢」，不上传页面名、参数、域名或正文。

- `ui_jank`：一分钟一窗，只在前台 Activity 存活时统计 Choreographer frame gap。字段 `frames`、`jank32`（≥32ms）、`severe200`（≥200ms）、`max_ms`。只有出现 200ms 以上卡顿或至少 5 个 jank 帧才发；写事件走后台线程，避免监控本身造成卡顿。
- `tailscale_status`：每次 `tailscale_status` 查询一条，字段 `source`（agent/command/taildrop）、`ms`、`result`（ok/timeout/cancelled/error）、`error_class`（service_down/timeout/other）。用来区分 Tailscale 状态查询慢和服务没起来，不含设备名/IP。
- `service_heartbeat`：服务附着后每 15 分钟一条，字段 `uptime_ms`、`app`，带 `vpn_session_id`。相邻两次 start 之间没有 stop 但有 heartbeat 缺口时，说明服务被静默杀掉。
- `conductor_tool`：Conductor 工具调用一条，字段 `tool`（MCP 工具统一记 `mcp`）、`ms`、`ok`、`code`。不含参数和结果正文。

`network_change` 的口径同时收紧：只在当前 default uplink（`currentUplink`，排除 VPN）变化时记一条，不再把 Wi-Fi 和蜂窝并存的各自 capabilities 变化都记成事件。`network_lost` 只在没有可用物理 uplink 时记。

## URLTest 策略观测（2026-11-19 起）

目标不是给节点打分，而是量自动策略的反应：坏节点被选中后多久切走、切走后多久切回、切换是否落到当时最快的可用节点。数据全部来自已有的 Groups 快照和日志，不额外发探测请求。

- `outbound_selected` 增加 `available`、`total`、`fastest`、`fastest_ms`（仅 URLTest 组）。`from` / `to` / `reason` / `ms` 不变。
- `urltest_health`：选中的 URLTest 节点在快照里从可用变不可用（`state=down`）或反过来（`state=up`）时记一条；字段 `group`、`outbound`、`state`、`delay_ms`（up 时）、`available`、`total`、`root`。只在状态变化时记，不按快照刷。
- `urltest_manual`：用户点分组或节点的测速按钮时记一条，字段 `group` 或 `outbound`。用来把人工动作和核心自动测速分开；`outbound_selected.reason` 仍是 `urltest`。
- 日志派生的 `urltest_ok` / `urltest_fail` 增加 `ts0`、`ts1`（墙上时钟毫秒，来自内存里的日志时间戳）。`t0`、`t1` 仍是内核开机秒数；磁盘恢复的旧行没有墙上时间，不输出这两个字段。

性能边界：普通 Groups 快照只读选中项的 `urlTestDelay`；只有选中项切换或健康状态翻转、需要写事件时，才为该组再读一遍所有节点算 `available` / `fastest`。`urltest_health` 只对选中节点做差分，节点数不增加事件量；Groups 回调在 `uploadReady=false` 时直接返回；事件走后台队列，不占 CommandClient 回调线程。没有新增定时器、网络请求或每快照落盘。

已知边界：`urlTestDelay=0` 可能表示失败，也可能表示尚未测过；当前只在选中节点从正延迟变 0 时当作 down。`urltest_health` 的时间是收到 Groups 快照的时间，不是内核完成健康检查的精确时刻；需要精确检测耗时时仍以 `urltest_ok/fail` 的 `ts0/ts1` 为准。
