# WonderSing 待办

改动先记在这里，不默认立刻打 Release。凑一批或主人点头后再发版。
新增一项就加一条 `- [ ]`；已随某版发出去就勾掉并写上版本号。
未完成项统一放最上面的「现在待办」，已完成批次留作历史。

## 现在待办

- [ ] Conductor Phase 1 剩余：`/probe` 命令 + 参数表单 / 确认卡片
- [ ] CFG Sing-box 订阅里 Tailscale 控制面增加走代理的 detour（只影响连 controlplane，不影响组网）；减轻登录/重连失败。等主人点头再改订阅
- [ ] 美观瑕疵（先不着急）：分组收起时不如原版好看；面板顶满屏宽后，面板上方的分组标题和控件两侧边距也应跟着收窄。需要再调一轮视觉

## 流程（已落地、随仓库生效）

- [x] Release 只编手机能装的 `arm64-v8a`（libbox 只编 arm64，不打其他 ABI / universal / Android 5 legacy）
- [x] Release 跑完自动通知主人（成功给 arm64 安装链接，失败给运行记录）—— 经飞书应用 API 直发（`release-notify.yml`）；Actions secrets：`LARK_NOTIFY_CREDENTIALS`、`LARK_NOTIFY_RECIPIENTS`，不再经 Soul webhook

## 边界（主人铁律）

- [x] 不做、也不再规划 Tailscale SSH / 登机器入口（公钥 sshd 授权；WonderSing 不新增 SSH 便利入口，Agent 也不做 `ssh` / `login_host` / shell）。上游 SFA 自带的 Remote Control 与 SSH 终端是既有能力，与 WonderSing 新增入口无关，保持原样。

## 遥测质量（随 wonder.8 发版）

- [x] 不再丢日志：每次上传把积压分多批（每批 500 行）依次传完，本地缓存上限 4000→12000 行，批次带 `dropped_lines`/`more` 便于发现截断（`e2aade9`）
- [x] 去重：每批带 `batch_id`（内容哈希），成功后立即落盘并记住最后确认的批次；Worker 端同一 `batch_id` 只存一次（旧版 wonder.7 按内容哈希同样去重）（`e2aade9`；Worker 已部署）
- [x] 结构化事件：随原始日志附带聚合事件（启动/停止、Tailscale 状态/登录成功/登录错误如 http 410、自动选择测速成败与延迟、按出站统计的连接错误、DNS EOF、握手重置等），不含订阅地址和登录链接；Worker 对旧版客户端自动从原始日志推导（`e2aade9`）
- [x] 上传失败本身记为本地事件（磁盘环形缓存，最多 300 条 / 48 小时），下次上传成功时随 `client_events` 一起带上，服务器确认后才清除；每次上传都记 ok/failed、走哪条路、耗时（`275a676`；Worker 已部署）
- [x] 上传慢：两台手机每批都固定慢约 16–18 秒（直连 workers.dev 15 秒连接超时后才走 VPN）。改为记住上次成功的路径优先走，备用路径连接超时 6 秒（`275a676`）
- [x] 补充事件：服务启动（省电/电池优化/Doze）、停止原因、报错类型、VPN 权限被收回、网络切换（仅类型）、订阅更新成败（仅主机名）（`275a676`）

## 功能

- [x] 遥测设置：填完地址和 Token 后有明确保存提示，并加一键连通测试 —— 已满足：Save 后显示 `telemetry_saved`（Saved）；Test connection 按钮调用 `TelemetryRuntime.probe` 显示结果，并展示上次测试时间（`TelemetrySettingsScreen.kt`）
- [x] 遥测外层入口和文案保持简洁，少堆说明 —— 已满足：Settings 入口只有标题 + 一行 `telemetry_row_summary`；设置页只有开关、地址、Token、Save/Test/Upload 三个按钮和两行时间，说明默认折叠
- [x] 核对并完善应用内检查更新：已从本仓库 Releases 拉取（`GitHubUpdateChecker.FEED_URL` + `GitHubReleaseFeed`），`downloadAndInstall` 支持一键下载安装。本批只补结果事件，未重做更新 UX

## 遥测下一批（2026-11-06，随 wonder.8 发版）

- [x] 关闭连接 / Stopping 不再等待遥测上报：先落盘，再由 daemon 线程异步冲刷（`detachAndFlushAsync`；不变更 `Stopped` 的时序）
- [x] 设置页展示最近一次成功上报时间（`last_upload_ok_at`）和上次连通测试时间，并提供「立即上报」按钮（与连通测试分开）
- [x] 把应用内 Crash Report 摘要纳入上报：`crash_report` 事件带 metadata 摘要 + go/jvm 日志头（每份最多 2500 字符，每次最多 3 份）；不读取/上报 `configuration.json`；服务器接受后才记住报告 id
- [x] App 侧上报入口改用 `ws-tele.soulfun.fyi`，已保存的旧 `wk-7c41e9.wonderbeyond.workers.dev` 读取时自动规范化；`wrangler.toml` 已加自定义域；Soul 已部署绑定（workers.dev 兜底保留）
- [x] 核对 ingest KV 仍是 7 天 TTL（`index.js` 中 `TTL = 7 * 86400`，未改动）；远程约 210 条记录 + 152 个去重键

## wonder.10 批次（2026-11-07，已随 wonder.10 发版）

- [x] 应用内检查更新结果事件（github flavor 的 UpdateChecker）：`src/other/.../Vendor.kt` 在每次检查后记 `update_check`（source / ok / has_update / version_code / error，无 token）
- [x] `vpn_session_id`：每次 `BoxService` 成功 attach 生成 12 位会话 id；`service_start` / `service_stop` / 节点切换 / 上传事件自动携带，下一轮服务换新 id
- [x] `default_network_id`：`network_change` 与 `service_start` 记录默认上行 `wifi:<hash>` / `cellular:<hash>`（仅 transport + Network hash，无 SSID / 运营商 / IP）
- [x] `outbound_selected`：复用 CommandClient Groups（与 Agent 同一条命令路径），首次快照和 selected 变化时记 `group` / `from` / `to` / `reason` / `root` / 可选 `ms`；reason = `start` | `urltest` | `user` | `unknown`
- [x] 异常隔离：选中/网络事件全部 runCatching，写盘失败不得影响 VPN
- [x] JVM 单测：会话 id 生命周期、选中变化去重、reason 映射、scrub 不含订阅 URL 路径/查询
- [x] 检查更新不再依赖 GitHub REST API（issue #3）：改用 `releases.atom` + `releases/expanded_assets` 两个普通页面，避开未认证 API 每 IP 的 403 限流；资产名/大小/ABI 从资产页解析，解析层有 JVM 单测。随之移除失效的 GitHub Token 与 Update Track 设置（fork 的 tag 发布都不标记 prerelease，track 已无作用）
- [x] 组内节点列表支持单列布局（issue #2）：Groups 顶栏、仪表盘组面板标题栏都有单列/双列切换按钮，App 设置里也有「节点列表布局」开关；偏好持久化并跨界面同步，单列时节点名占满整行（`GroupLayout` 有 JVM 单测）

## wonder.11 批次（2026-11-07）

- [x] Groups 面板改为全宽：去掉列表外层 16dp 横向 padding，节点距屏幕边缘只剩面板内一层 16dp；仪表盘组面板标题同步对齐

## wonder.12 批次（2026-11-07）

- [x] 发布链路压缩：Release 的 libbox 复用 Test 在 `dev` 上写的缓存（tag 可读默认分支缓存），省掉每次发版的 Go 构建；APK job 去掉未使用的 sing-box checkout / Go / NDK 安装，直接安装下载的 AAR。实测 wonder.12：libbox job 293s → 7s，整条 Release 13m18s → 8m03s；剩余大头是 APK 的 `assembleOtherRelease`（436s，Kotlin/KSP/R8）

## 整理（2026-11-08，已落地，随下次发版生效）

- [x] README 补 WonderSing fork 说明、Releases/arm64 链接、AGENTS/signing 指针
- [x] 版本单源：`version.properties` 只保留 `CORE_VERSION`/`WONDER_BUILD`/`GO_VERSION`，`VERSION_CODE`/`VERSION_NAME` 由 Gradle 派生；新增 `WonderVersionTest` 防 base 漂移
- [x] AGENTS 发版流程去掉硬编码 core 版本；open items 收敛到本文件
- [x] TODO 修掉失效链接和 stale 符号；未完成项提到顶部「现在待办」
- [x] `.claude/settings.json` 的 tag 权限从固定 core 版本改为 `v*`

## wonder.13 批次（2026-11-08，随 wonder.13 发版）

- [x] Conductor 命令面板 MVP：沉浸式全屏（无标题栏/底部导航）、`/` 自动补全、消息流、命令注册表；首批 `/help`、`/commands`、`/capabilities`、`/clear`、`/nodes`、`/network`、`/tailscale`、`/telemetry-upload`
- [x] 底部导航 Logs → Conduct；Logs 移到 Dashboard 卡片（最新 3 条，Dashboard 可见时每 3 秒刷新，点击进入原 Logs 页）
- [x] Tools 回到 fork 前原版，移除 Agent 入口；旧 POC 的 Agent UI/ViewModel 删除，registry/sanitizer 复用
- [x] Conductor 命令注册表 JVM 单测

## Tools Agent POC（已随 wonder.9 发版）

定位：个人网络平面 + 资料枢纽的第一块运行时能力。已随 wonder.9 发版（未单独打 Release）。

正式版的愿景、边界和分期见 [`docs/agent-control-plane.md`](docs/agent-control-plane.md)；POC 是验证版，不是最终形态。Phase 1 会把 Tools 恢复到 fork 前原版（无 Agent 入口），POC 能力迁到 Conductor。

- [x] Tools 增加 Agent 入口与对话页，能在运行时按名称动态调用工具（工具注册表 + JSON 参数），而不是写死流程
- [x] 复用原版 Remote Control 那套已暴露接口（`CommandClient` / `CommandTarget`：Status、Groups、Outbounds、Log、ClashMode、Connections），不要另起查询通道；本机系统网络类型等不在该接口上的能力需标明为本地感知
- [x] 工具：列出当前配置里的节点/出站组、感知当前设备网络环境（网络类型、VPN/服务状态、当前选中出站）
- [x] 工具：按需指定某条节点当出口探测 URL（复用已有 outbound / CommandClient / 测速侧能力，不要另起拨号栈）
- [x] 按主人铁律不包含登机器：WonderSing 不做、也不再规划 Tailscale SSH / 登机器入口；改网络路径若做到了，执行前要有确认点
- [x] 无模型密钥时仍可手动点选/调用上述工具（证明运行时调用本身能工作）；有可选的 tool-calling 挂钩即可，不要把密钥写进仓库
- [x] 为注册表、参数校验、权限边界（禁止登机器）和探测请求构造补 JVM 单元测试

## pending.log.tmp 写盘隔离（已随 wonder.9 发版）

主人 2026-11-07：wonder.8 上遥测写 `pending.log.tmp` 失败会带崩 VPN；修完前不要再发版指望它已好。

- [x] 遥测写盘 / `.tmp` rename 失败不得冒泡崩 VPN；`schedulePersist` 等路径全部隔离异常
- [x] `TelemetryLogBuffer`（及同类 EventLog）对坏文件、半截 tmp、rename 失败可恢复
- [x] JVM 单测覆盖写失败隔离与 load 韧性
- [x] 顺查 Worker HTTP 500 与 `client_events_dropped`（有修就修）
  - Worker 源码 `~/workspace/CFG/telemetry-ingest` 已只读检查，未见明确的代码级 500 路径；服务端未改、未部署。`client_events_dropped` 事件补充 `capped` / `expired` 丢弃原因。

