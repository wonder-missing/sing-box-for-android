# WonderSing / Claude Code 任务：Tools Agent POC（2026-11-07）

仓库：`wonder-missing/sing-box-for-android`，在 **`dev`** 上改。不要打 Release，不要改版本号 / 发版签名钥匙，只动与本任务相关的代码。改完对照 `TODO.md`「Tools Agent POC（未发版）」勾上已落地项（仍标未发版）。

Soul 负责串联和验收：你改完提交并推到 `dev` 后停下来，由 Soul 对照本文件验收。不要问主人要不要发版。

主人刚点头要做一个 **POC**，并把应用定位成「个人的网络平面 + 资料枢纽」。本 POC 只做网络平面这一侧的运行时工具调用，不做人资料枢纽存储。

主人约定：这次是功能开发，**必须补单元测试**，不能只停在“没真机所以不测”。

---

## 背景

- Tools 页：`app/src/main/java/io/nekohasekai/sfa/compose/screen/tools/ToolsScreen.kt`
- 路由：`compose/navigation/Navigation.kt`（已有 `tools/network_quality`、`tools/outbound_picker/{selectedOutbound}` 等）
- 节点列表：`CommandClient` 的 `updateOutbounds`；UI 侧可参考 `OutboundPickerScreen.kt`、`GroupsCard` / dashboard ViewModel
- 测速/探测可复用 Tools 里已有的 Network Quality 等相关实现，不要另写一套隧道
- 仓库里目前几乎没有 `app/src/test`；请补上 JVM 单测（JUnit），测纯 Kotlin 逻辑，不要依赖真机/ANDROID_HOME
- 已有 Tailscale SSH 终端等能力，**本 POC 禁止接入“登到某台机器执行命令”**。订阅节点多数只是出口，登不上去。

---

## 要做的事

### 1. 运行时工具宿主（核心）

做一个可在运行时按名称动态调用的工具层，例如 `io.nekohasekai.sfa.agent`：

- 工具注册表：每个工具有稳定 `name`、简短描述、JSON 参数 schema、权限标签
- `invoke(name, argsJson)`：校验参数后执行，返回结构化结果（成功/失败原因，不要把订阅 URL、token、configuration.json 原文带出去）
- 本轮注册至少三个工具：
  1. `list_nodes`：列出当前能感知到的出站组与节点（tag、类型、是否选中、延迟若已有就带上）。服务没在跑时给出清楚说明，不要崩。
  2. `network_snapshot`：当前设备网络环境——网络类型（wifi/cellular/其他，有就写）、VPN/核心是否在跑、当前选中出站、必要时电池优化/省电这类已有信号（有现成的就用，没有不要编）
  3. `probe_via_node`：参数至少 `outbound`（或 node tag）+ `url`；经指定节点当出口探测该 URL，返回状态码/耗时/错误。复用现有 outbound 选择与 HTTP 探测能力。
- 不要实现 `ssh` / `login_host` / 任意 shell。注册表里也别留这类入口。
- 若后续有“切换默认出站”这类改路径动作：可以做成工具，但必须带 `needs_confirmation`，POC 的对话层在真正执行前要先展示确认，不能悄悄改。做不做切换由你判断工作量；列节点 + 环境 + 探测是必须项。

### 2. Tools 入口与对话页

- 在 Tools 列表加一项 Agent（中文文案简洁，如「Agent」或「网络 Agent」），进入 `tools/agent`
- 一个能看到调用过程的简单页：用户可输入一句话，或在无模型时用按钮/芯片手动调用上述三个工具
- 对话记录里要能看出：决定调用了哪个工具、参数是什么、返回了什么（证明是运行时动态调用，不是写死按钮流程）
- 无云端模型密钥是正常情况：不要把任何 API key 写进仓库、文档或提交信息。可选挂钩（设置里一个 endpoint/model，未配置则走手动调用）即可。不要为此去申请或编造密钥。
- 不要做复杂的资料枢纽 UI。

### 3. 单元测试（必须）

至少覆盖：

- 未知工具名 / 缺参数 / 类型不对时失败且不执行副作用
- `list_nodes`、`network_snapshot` 在假数据源上的结果形状
- `probe_via_node` 会把指定 outbound + url 交给探测端口（mock），默认拒绝明显非 http(s) 的目标
- 权限边界：试图调用未注册的登机器类工具会失败
- 结果净化：输出里不含订阅 URL 或 token 字段

测试放在 `app/src/test/java/...`，用 JUnit 测纯逻辑。若现有 Gradle 还没有 testImplementation，最小限度补上（JUnit），不要借机升级整套构建。

---

## 不要做

- 不要打 Release、不要改 `version.properties`
- 不要改遥测上报、签名、CI 发版流程
- 不要改 CFG 订阅
- 不要把 ingest token、DeepSeek key、订阅地址写进代码或文档
- 不要实现自动登 Mini / oci / 任意 SSH

---

## 完成时

1. 提交说明写清楚这是 Tools Agent POC
2. 推到 `dev`
3. 在本任务说明或返回里写：改了哪些文件、怎么跑单测、手动怎么点、已知限制
4. 停下来等 Soul 验收

---

## 补记（主人 2026-11-07 ~01:45 CST，必须遵守）

主人指出：原版 SFA 的 Remote Control 说明内部能力已经接口化暴露，POC **必须充分复用这套接口，不要另起查询/控制通道**。

事实核对（Soul 已读代码）：

- Remote Control 并不是另一套 HTTP API。`RemoteControlManager` + `CommandTarget` 只是把 `CommandClient` 从本地 `Libbox.newStandaloneCommandClient()` 切到 `Libbox.newStandaloneRemoteCommandClient(...)`。
- 同一套命令在本地核心和远程被控实例上都能用：`Status` / `Groups` / `Outbounds` / `Log` / `ClashMode` / `Connections`（见 `CommandClient.ConnectionType` 与 `Handler`）。
- `list_nodes`、核心是否在跑、当前出站组、探测若已有走 CommandClient/standaloneClient 的实现，都应该走这条，这样 Agent 在本机和进入 Remote Control 时自然对同一套能力生效。
- 有些东西不在这套接口上（应用内 Crash/OOM 文件、本机 wifi/cellular 等系统网络类型）。`network_snapshot` 里属于设备本地的部分请标明是本地感知；不要假装 Remote Control 也能看到。也不要为了 Agent 去扩展 Remote Control 协议。

若你读到本补记时已经写了平行的数据源，请改回复用 `CommandClient` / `CommandTarget`。
