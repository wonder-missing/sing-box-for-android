# WonderSing 待办

改动先记在这里，不默认立刻打 Release。凑一批或主人点头后再发版。
新增一项就加一条 `- [ ]`；已随某版发出去就勾掉并写上版本号。

## 流程（已落地、随仓库生效）

- [x] Release 只编手机能装的 `arm64-v8a`（libbox 只编 arm64，不打其他 ABI / universal / Android 5 legacy）
- [x] Release 跑完自动通知主人（成功给 arm64 安装链接，失败给运行记录）

## 遥测质量（随 wonder.8 发版）

- [x] 不再丢日志：每次上传把积压分多批（每批 500 行）依次传完，本地缓存上限 4000→12000 行，批次带 `dropped_lines`/`more` 便于发现截断（`e2aade9`）
- [x] 去重：每批带 `batch_id`（内容哈希），成功后立即落盘并记住最后确认的批次；Worker 端同一 `batch_id` 只存一次（旧版 wonder.7 按内容哈希同样去重）（`e2aade9`；Worker 已部署）
- [x] 结构化事件：随原始日志附带聚合事件（启动/停止、Tailscale 状态/登录成功/登录错误如 http 410、自动选择测速成败与延迟、按出站统计的连接错误、DNS EOF、握手重置等），不含订阅地址和登录链接；Worker 对旧版客户端自动从原始日志推导（`e2aade9`）
- [x] 上传失败本身记为本地事件（磁盘环形缓存，最多 300 条 / 48 小时），下次上传成功时随 `client_events` 一起带上，服务器确认后才清除；每次上传都记 ok/failed、走哪条路、耗时（`275a676`；Worker 已部署）
- [x] 上传慢：两台手机每批都固定慢约 16–18 秒（直连 workers.dev 15 秒连接超时后才走 VPN）。改为记住上次成功的路径优先走，备用路径连接超时 6 秒（`275a676`）
- [x] 补充事件：服务启动（省电/电池优化/Doze）、停止原因、报错类型、VPN 权限被收回、网络切换（仅类型）、订阅更新成败（仅主机名）（`275a676`）
- [ ] 应用内检查更新结果事件（github flavor 的 UpdateChecker，暂未接）

## 功能

- [ ] 遥测设置：填完地址和 Token 后有明确保存提示，并加一键连通测试
- [ ] 遥测外层入口和文案保持简洁，少堆说明
- [ ] 核对并完善应用内检查更新：从本仓库 Releases 拉取 APK，支持一键下载安装（部分逻辑已随近几版带上，不够再补）
- [ ] CFG Sing-box 订阅里 Tailscale 控制面增加走代理的 detour（只影响连 controlplane，不影响组网）；减轻登录/重连失败。等主人点头再改订阅

## 遥测下一批（2026-11-06，随 wonder.8 发版）

详细说明见 [docs/cc-task-telemetry-2026-11-06.md](docs/cc-task-telemetry-2026-11-06.md)。

- [x] 关闭连接 / Stopping 不再等待遥测上报：先落盘，再由 daemon 线程异步冲刷（`detachAndFlushAsync`；不变更 `Stopped` 的时序）
- [x] 设置页展示最近一次成功上报时间（`last_upload_ok_at`）和上次连通测试时间，并提供「立即上报」按钮（与连通测试分开）
- [x] 把应用内 Crash Report 摘要纳入上报：`crash_report` 事件带 metadata 摘要 + go/jvm 日志头（每份最多 2500 字符，每次最多 3 份）；不读取/上报 `configuration.json`；服务器接受后才记住报告 id
- [x] App 侧上报入口改用 `ws-tele.soulfun.fyi`，已保存的旧 `wk-7c41e9.wonderbeyond.workers.dev` 读取时自动规范化；`wrangler.toml` 已加自定义域；Soul 已部署绑定（workers.dev 兜底保留）
- [x] 核对 ingest KV 仍是 7 天 TTL（`index.js` 中 `TTL = 7 * 86400`，未改动）；远程约 210 条记录 + 152 个去重键
