# 拨号与上传遥测（2026-11-12）

CFG 要回答的三件事，对应三组字段。URLTest 是第四件事，不进健康度。

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
