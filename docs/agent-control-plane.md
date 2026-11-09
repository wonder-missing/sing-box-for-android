# Conductor：个人设备控制面（愿景与落地规划）

状态：Phase 1 已完成并随 wonder.13 起发版；Phase 2a 运行时设计完成，待实现；设计见 [`agent-runtime.md`](agent-runtime.md)。
最后更新：2026-11-10
命名：模块/包名 `conductor`；外层底部导航文案 `Conduct`；Conductor 页面进入沉浸模式，不设页面标题。

## 1. 愿景

把 WonderSing 做成个人联网设备的控制面：一个入口看到所有设备、网络路径和可用能力，用自然语言把任务交给 Agent，由 Agent 规划并调用 typed capability 完成，让手机、Mini、云电脑之间切换和交接像同一台设备一样。

跨设备资源转移（文件、会话、剪贴板）由数据面完成，Agent 只负责规划和确认。

Agent 是最终形态；Phase 1 先做 Conductor 命令面板，把能力集和基础交互夯实，不接 LLM。

## 2. 边界（明确不做）

- 不做通用远程 shell / SSH 登录入口。跨设备执行只走 typed capability，不新增 `ssh` / shell / 任意命令。
- 不做通用远程桌面或通用文件同步器。文件走 Taildrop / rsync / 目标端 HTTP 能力端点，不重造数据面。
- 不让 LLM 进入数据面：App 不自动附加文件内容、订阅链接、日志全文；用户自然语言输入先做 best-effort 敏感内容拦截，命中即本地阻止。模型只看到元数据、用户主动输入和经过 sanitizer 的 tool 结果。
- 不管理别人的设备，只管理主人自己的 Tailscale 设备。
- 第一版不做“万能助手”式的开放式任务。

## 3. 现有基础

- sing-box 网络路径控制：节点、出站组、URL 测试、VPN 服务状态。
- Tailscale 设备身份/连通性，以及现有的 exit node、peer、endpoint、SSH 终端等界面（上游 SFA 能力，保持原样）。
- Remote Control：CommandClient / CommandTarget 的 Status、Groups、Outbounds、Log、ClashMode、Connections。
- Agent POC（wonder.9）：
  - `AgentToolRegistry`：按名称分发的运行时工具注册表，JSON schema 校验、确认策略、结果 sanitizer。
  - 工具：`list_nodes`、`network_snapshot`、`probe_via_node`。
  - `AgentIntentParser`：无模型时的确定性兜底。
  - `AgentViewModel` / `AgentScreen`：手动调用、确认弹窗、结果展示。
  - JVM 单测覆盖注册表、参数校验、确认、sanitizer。
- POC 是验证版，不是正式版。它的 registry / confirmation / sanitizer 可以直接作为 Conductor 的骨架。
- Tools 已回到 fork 前的上游原版（Agent 入口已移除）；POC 的 Agent 能力迁到 Conductor，旧 POC UI/ViewModel 已删除。

## 4. 命名与入口

- 模块/包名：`conductor`（`io.nekohasekai.sfa.conductor`）。
- 外层底部导航文案：`Conduct`（只是入口，不是页面标题）。
- Conductor 页面：沉浸模式，没有页面标题、没有顶部栏、没有底部导航按钮，看起来像打开一个 inner app。消息流 + 输入框就是全部界面；退出走系统返回手势/返回键。
- 底部导航：`Dashboard / Conduct / Tools / Settings`。Conductor 取代现在的 Logs 位置；进入 Conductor 后隐藏外层底部导航。
- Logs：不再占底部导航；移到 Dashboard 的卡片入口，放在 Profiles 下方；点击卡片进入原来的 Logs 页面。
- Tools：回到 fork 前的上游原版，移除 Agent 入口；Conductor 吸收 Agent POC 的能力，不保留两个命令面。
- 不采用的名字：Composer（和 Jetpack Compose / Docker Compose 混淆）、Console（语义被开发者控制台收窄）、Maestro（和移动测试框架撞名）、Cockpit（和主人另一个项目重名）。

## 5. Conductor MVP UI

目标：像手机版 DeepSeek 一样简洁，能让人愿意每天打开、测试、陪它演化。

- Conductor 是全屏沉浸页面：没有顶部标题栏，没有底部导航；消息流 + 输入框就是全部界面；退出走系统返回。
- 输入区处理 IME 和系统导航栏 insets；沉浸不等于内容压到刘海或手势区下面。
- 消息流占满剩余空间；底部是输入框。
- 输入框是约两行高的容器：默认文本 1 行，底部一排内部按钮（`/`、`@`、发送）；文本最多 3 行，超出后内部滚动。
- 消息流区分：用户消息、系统/Agent 输出、命令结果、工具调用卡片、确认卡片。系统/Agent 和命令结果直接铺在消息流里，不用卡片，避免卡片 padding 吃掉长消息的宽度；用户消息保持右对齐气泡。
- 命令结果用等宽字体；长结果可展开；工具调用显示命令名、参数摘要、运行中/成功/失败。
- 需要改网络状态的操作在消息流里出 Confirm/Cancel 卡片。
- 新消息自动滚到底；用户手动上滑后不抢滚动，给“回到最新”按钮。
- 空状态直接列出建议命令，例如 `/help`、`/capabilities`、`/tailscale`。

## 6. 命令与能力集

- `/` 开头 = 本地确定性命令；其他文本 = 未来的自然语言 Agent。
- Phase 1 非 `/` 文本只提示“自然语言 Agent 暂未接入，请先用 / 命令”，不接 `AgentIntentParser`。它开销可以忽略（几条正则，发送时跑一次），但会让少数话能懂、多数不能，模糊 `/` 的边界；先留着以后做无模型兜底或调试开关。
- 命令不要依赖记忆：输入 `/` 自动弹命令面板，按前缀过滤，显示命令名、描述、用法、示例；有参数的指令用参数表单/选择器，不要求手打位置参数。
- `/help`、`/commands`、`/capabilities` 全部从同一份能力注册表生成。
- 第一版按交互类型覆盖，不按数量凑：
  - 发现：`/help`、`/commands`、`/capabilities`
  - 只读列表：`/nodes`、`/tailscale`、`/network`
  - 异步长任务：`/telemetry-upload`
  - 参数 + 确认：`/probe`
  - UI：`/clear`（`/logs` 可选）
- 能力注册表：命令和未来 Agent 工具共用一份定义，包含 `name`、`description`、`usage/examples`、参数 schema、权限、`confirmationPolicy`、`allowedUrlParameter`、`execute`；命令/Agent 入口由 `InvocationSource` 区分。字段细节见 [`agent-runtime.md`](agent-runtime.md) 第 6 节。
- 现有 `AgentToolRegistry` 的校验、确认、sanitizer 复用；Conductor 的命令面板是注册表的 UI，不是另一套执行逻辑。

## 7. Logs 卡片

- Dashboard 新增 `CardGroup.Logs`，默认排在 Profiles 下方；是否允许隐藏/排序沿用 Dashboard 现有设置，待定。
- 卡片只显示最新 3 条日志，等宽字体、按级别着色；点击卡片进入原来的 Logs 页面。
- 只在 Dashboard 可见（`RESUMED`）时刷新，每 3 秒一次；离开页面或 App 后台就停。
- 数据源复用现有日志流，不新开第二条 `CommandClient` Log 连接；建议抽共享 `LogRepository`/`LogBuffer`，卡片用 `sample(3s)` 或等价节流。
- 预览状态放在卡片内部或独立小 ViewModel，不要塞进 `DashboardUiState`，避免每 3 秒重组 Profiles/流量卡片。

## 8. 架构原则

### 控制面 vs 数据面

- 控制面：设备清单、在线状态、能力、意图、权限、确认、审计、任务进度。App 负责。
- 数据面：Tailscale 负责连通和身份；文件走 Taildrop / rsync / 目标端 HTTP；模型不碰数据。

### 设备注册表

- 用 Tailscale peer/status 作为设备发现和身份来源，不另造发现协议。
- 每台设备记录：id/名称、在线状态、最后可见、可用能力、当前网络角色（exit node / proxy / 普通）。
- 设备能力必须有 schema、权限等级、是否需要确认、超时。

### Typed capability

- 统一形态：`device + capability + params + permission + confirmation + result`。
- 远端能力通过 Tailscale 上的 typed HTTP/RPC 端点执行；文件用 Taildrop/rsync。
- 设备端只在 Tailscale 没暴露的能力上放一个小 agent/daemon；不暴露 shell。

### Agent 的角色

- 最终是规划器/路由器：把自然语言变成 capability 调用，不直接执行任意命令。
- Phase 1 先不做 LLM；Conductor 只是能力注册表的命令面板，验证“输入 → 解析 → 执行 → 展示”这条链路。
- 工具调用参数由注册表校验；工具结果过 sanitizer 后再进模型/UI。
- 多步任务必须有上限：步数、超时、取消、token/成本预算。

### 模型与密钥

- Phase 2 运行时细节（消息模型、tool schema、状态机、确认暂停、预算、BYOK）：[`agent-runtime.md`](agent-runtime.md)。
- Phase 2 首选 DeepSeek：`https://api.deepseek.com`，OpenAI-compatible，模型 `deepseek-v4-flash`（V4.1-Flash-1017，默认值可在 Settings 覆盖）。支持 tool calls / streaming tool_calls；thinking 可按需关闭。
- 第一版用薄自研运行时，不引入 ADK（Gemini-only，Android 工件 0.1.0）或 Koog（等 Agent 长大再评估）。
- 密钥不进仓库。已定设备端 BYOK，Android Keystore 加密存储；设置入口和存储细节见 [`agent-runtime.md`](agent-runtime.md) 第 12 节。
- 性能：Conductor 只在进入页面时初始化；流式输出；历史和工具输出有上限；不常驻大对象。

### 安全与隐私

- 只管理主人自己的设备；Tailscale ACL/身份作为第一道边界。
- 所有写操作/网络状态变更必须确认；审计记录保留在本地。
- 只把必要元数据、用户主动输入和 sanitized tool 结果发给模型；文件内容、订阅 URL、token、日志全文不自动附加，输入命中敏感模式时本地拦截。
- 设备返回的数据也当不可信输入，防 prompt injection。

## 9. 分阶段规划

### Phase 0：已完成

- Agent POC：3 个本机工具、手动调用、确认、sanitizer、单测。
- 验证了“运行时按名称调用工具”的路径。

### Phase 1：Conductor 命令面板 MVP（已完成）

范围：

- 新增 Conductor 页面和底部导航入口（`Conduct`），Logs 移到 Dashboard 卡片。
- Tools 回到 fork 前的上游原版，移除 Agent 入口；Agent 能力迁到 Conductor。
- 能力注册表、`/` 命令面板、自动补全、参数表单、消息流、确认卡片、命令结果展示。
- 第一版命令：`/help`、`/commands`、`/capabilities`、`/nodes`、`/tailscale`、`/network`、`/telemetry-upload`、`/clear`。`/probe` 与确认卡片已随 wonder.18 补齐；参数表单仍未做。
- Logs 卡片：最新 3 条、Dashboard 可见时每 3 秒刷新、点击进入 Logs 页面。
- 不接 LLM；非 `/` 文本提示暂不支持。

验收：

- 真机上能完成：从 `Conduct` 进入沉浸式 Conductor（无标题栏/底部导航）→ 输入 `/` 看到命令面板 → 选中/补全 → 执行 → 看到结果/错误 → 系统返回退出。
- `/tailscale` 能列出节点；`/telemetry-upload` 有异步进度；`/probe` 的确认卡片已完成。
- Logs 卡片只在 Dashboard 可见时刷新，点击进入原 Logs 页面。
- JVM 测试覆盖命令注册表、参数校验、确认、超时/取消、日志预览采样。

不做：LLM、跨设备写操作、文件传输、后台常驻。

### Phase 2：DeepSeek 自然语言 Agent + 设备注册表

- 接 DeepSeek 模型客户端、单步/多步 tool calling、流式输出、会话历史、密钥存储。Agent/BYOK 仅 API 24+，`otherLegacy` 降级。
- 复用 Phase 1 的能力注册表；非 `/` 文本进入 Agent。
- 设备注册表：Tailscale peers/status、在线状态、能力。
- 验收：自然语言能调用只读能力并回答；写操作仍需确认；敏感输入本地拦截；Agent/BYOK 仅在 API 24+ 构建可用。

### Phase 3：网络接力

- 用 exit node / 代理路径把手机流量切到指定设备；改网络状态前确认；失败回滚。
- 验收：目标设备离线、切换失败、回滚都有明确提示。

### Phase 4：文件/会话交接与常驻协调

- 文件走 Taildrop / 目标端 HTTP 能力端点；会话/剪贴板交接；多步任务编排。
- 如果“手机不在也要接力”，再评估 Mini/云电脑上的常驻协调器；不提前拆服务。

## 10. 第一场景

Phase 1 的第一场景就是 Conductor 命令面板本身：用真实可用的命令验证“输入 → 能力 → 结果”的闭环。第一个跨设备场景建议放在 Phase 3 的网络接力；如果主人更在意文件转移，可以把 Phase 3/4 对调。

## 11. 未决问题

- `Logs` 卡片是否允许在 Dashboard 设置里隐藏/排序，还是固定在 Profiles 下方。
- Tools 回到上游原版的具体范围：只移除 Agent 入口，还是同时清理其他 fork 新增项。
- `/probe` 的参数表单（确认卡片已完成）。
- 是否需要在 Mini/云电脑上放常驻协调器，还是 App 打开时编排即可。

## 12. 验收线

每个功能必须能写成：

> 设备 A，能力 C，参数 P，权限/确认，成功/失败。

写不出来就还不是功能。任何需要新数据面（文件同步、远程桌面、shell）的需求，先回到本文档确认边界。
