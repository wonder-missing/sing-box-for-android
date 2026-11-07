# WonderSing / Claude Code 任务：pending.log.tmp 遥测写盘不得带崩 VPN（2026-11-07）

仓库：`wonder-missing/sing-box-for-android`，在 **`dev`** 上改（当前 HEAD 含 Tools Agent POC `a2cf5f1`）。不要打 Release，不要改版本号 / 发版签名钥匙。改完对照 `TODO.md` 勾上本批，仍标未发版。

Soul 负责串联和验收：你改完提交并推到 `dev` 后停下来，由 Soul 对照本文件验收。不要问主人要不要发版。

主人 2026-11-07 ~08:47 CST 已点头：Agent POC 验收后**立即**修这件事，不再问。

---

## 背景（当作事实）

- 现网 `1.15.0-alpha.13-wonder.8`（`cb09a53`）仍有：遥测写盘路径里出现 `pending.log.tmp` 相关失败时，**把 VPN/核心一起崩掉**（主人观察到约 02:44 CST 一带崩）。
- 磁盘队列：`TelemetryLogBuffer` 写 `filesDir/telemetry/pending.log`，经同目录 `pending.log.tmp` 再 `renameTo` / fallback `copyTo`（见 `TelemetryLogBuffer.write`）。`TelemetryEventLog` 也有同类 `.tmp` 写法。
- `TelemetryRuntime.schedulePersist()` 里 `buffer?.persist()` **没有** `runCatching`（约 L675）；其它路径多数有。任何 `IOException` / rename 失败若冒泡到会拖垮服务的协程/线程，就可能带崩 VPN。
- 硬性要求：**遥测异常不得带崩 VPN**。写盘失败应吞掉/记事件、尽量保住内存队列，服务继续跑。
- 这是功能修复，**必须补 JVM 单测**（测纯逻辑，不依赖真机/ANDROID_HOME）。
- 顺查（同一轮可做，别另开大工程）：
  1. Worker `ws-tele.soulfun.fyi` / `wk-7c41e9` 若有 HTTP 500，看 ingest 代码与近期 `/tail`，修明显服务端崩或返回 500 的路径；没权限就写清缺什么。
  2. `client_events_dropped` 出现 `n=8`：查客户端环形缓存丢弃逻辑与上报，避免误丢或至少把丢弃原因写清楚；不要为凑数删事件。

本地参考（只读事实，别把 token 写进仓库）：

- App：`app/src/main/java/io/nekohasekai/sfa/telemetry/TelemetryLogBuffer.kt`、`TelemetryRuntime.kt`、`TelemetryEventLog.kt`
- Worker：`/workspace/CFG/telemetry-ingest/`（若本机有）；入口 `https://ws-tele.soulfun.fyi`

---

## 要做的事

1. **写盘失败隔离**：`TelemetryLogBuffer.persist` / `write`、以及所有调度 persist 的路径（含 `schedulePersist`），任何 IO 异常不得逃出遥测子系统去崩 `BoxService` / 核心。失败可记 `client_events`（如 `telemetry_persist_failed`）或本地计数。
2. **`.tmp` 稳健性**：并发写、目录缺失、`renameTo` 失败、半截 `.tmp` 残留、磁盘满等，都要可恢复；启动 `load()` 时不要被坏文件卡死。
3. **单测**：至少覆盖写失败不抛到调用方、rename 失败走 fallback、坏/半截文件 load 不崩、并发 persist 不互相踩坏主文件。可用临时目录 + 可注入失败的 File/流，或把写盘抽成可测端口。
4. **顺查** Worker 500 与 `client_events_dropped n=8`：有明确修就修并写进结果；只是观测也写清。
5. 更新 `TODO.md` 本批条目为已勾；**不要**改 Agent POC 已勾项，不要动签名/CI/订阅/版本号。

---

## 不要做

- 不要打 Release、不要改 `version.properties`
- 不要改 Tools Agent POC 行为（除非为编译必须的无关冲突）
- 不要把 ingest token、订阅地址写进代码或提交信息
- 不要改 CFG 订阅内容

---

## 完成时

1. 提交说明写清这是 pending.log 写盘隔离 / 防带崩 VPN
2. 推到 `dev`
3. 返回里写：改了哪些文件、怎么跑单测、已知限制、Worker/`client_events_dropped` 顺查结论
4. 停下来等 Soul 验收
