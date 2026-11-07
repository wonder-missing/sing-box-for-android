# Agent 控制面：愿景与落地规划

状态：规划中。Phase 1 尚未开工，当前 Agent 代码仍是 wonder.9 的 POC。
最后更新：2026-11-08

## 1. 愿景

把 WonderSing 做成个人联网设备的控制面：一个入口看到所有设备、网络路径和可用能力，用自然语言把任务交给 Agent，由 Agent 规划并调用 typed capability 完成，让手机、Mini、云电脑之间切换和交接像同一台设备一样。

跨设备资源转移（文件、会话、剪贴板）由数据面完成，Agent 只负责规划和确认。

Agent 不是通用聊天助手，而是这个控制面的自然语言入口。

## 2. 边界（明确不做）

- 不做通用远程 shell / SSH 登录入口。跨设备执行只走 typed capability，不新增 `ssh` / shell / 任意命令。
- 不做通用远程桌面或通用文件同步器。文件走 Taildrop / rsync / 目标端 HTTP 能力端点，不重造数据面。
- 不让 LLM 进入数据面：文件内容、订阅链接、日志全文不发给模型，模型只看到元数据和经过 sanitizer 的结果。
- 不管理别人的设备，只管理主人自己的 Tailscale 设备。
- 第一版不做“万能助手”式的开放式任务。

## 3. 现有基础

- sing-box 网络路径控制：节点、出站组、URL 测试、VPN 服务状态。
- Tailscale 设备身份/连通性，以及现有的 exit node、peer、endpoint、SSH 终端等界面（上游 SFA 能力，保持原样）。
- Remote Control：CommandClient / CommandTarget 的 Status、Groups、Outbounds、Log、ClashMode、Connections。
- Agent POC（wonder.9）：
  - `AgentToolRegistry`：按名称分发的运行时工具注册表，JSON schema 校验、`needsConfirmation`、结果 sanitizer。
  - 工具：`list_nodes`、`network_snapshot`、`probe_via_node`。
  - `AgentIntentParser`：无模型时的确定性兜底。
  - `AgentViewModel` / `AgentScreen`：手动调用、确认弹窗、结果展示。
  - JVM 单测覆盖注册表、参数校验、确认、sanitizer。
- POC 是验证版，不是正式版。它的 registry / confirmation / sanitizer 可以直接作为正式版的骨架。

## 4. 架构原则

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

- 规划器/路由器：把自然语言变成 capability 调用，不直接执行任意命令。
- 工具调用参数由现有 registry 校验；工具结果过 sanitizer 后再进模型/UI。
- 多步任务必须有上限：步数、超时、取消、token/成本预算。

### 模型与密钥

- 首选 DeepSeek：`https://api.deepseek.com`，OpenAI-compatible，模型 `deepseek-flash`（V4.1-Flash-1017）。支持 tool calls / streaming tool_calls；thinking 可按需关闭。
- 第一版用薄自研运行时，不引入 ADK（Gemini-only，Android 工件 0.1.0）或 Koog（等 Agent 长大再评估）。
- 密钥不进仓库。设备端 BYOK 加密存储或后端代理，二选一。
- 性能：Agent 只在进入 Agent 页面初始化；流式输出；历史和工具输出有上限；不常驻大对象。

### 安全与隐私

- 只管理主人自己的设备；Tailscale ACL/身份作为第一道边界。
- 所有写操作/网络状态变更必须确认；审计记录保留在本地。
- 只把必要元数据发给模型；文件内容、订阅 URL、token、日志全文不发送。
- 设备返回的数据也当不可信输入，防 prompt injection。

## 5. 分阶段规划

### Phase 0：已完成

- Agent POC：3 个本机工具、手动调用、确认、sanitizer、单测。
- 验证了“运行时按名称调用工具”的路径。

### Phase 1：只读控制面 + Agent 问答（下一步）

范围：

- 设备盘点：Tailscale peers/status → 设备列表（在线、最后可见、能力）。
- 能力注册表：typed schema + 权限/确认字段。
- DeepSeek 模型客户端 + 单步 tool calling，把本机 3 个工具和设备盘点接进去。
- 流式聊天 UI；会话历史有上限；密钥存储方案落地。

验收：

- 在真机上问“现在有哪些设备在线？手机当前走哪条线路？”，Agent 能调工具并回答。
- 只读，不改网络状态；无 key 时仍可手动调用工具。
- JVM 测试用假模型服务器覆盖工具调用、参数校验、超时/取消。

不做：跨设备写操作、文件传输、后台常驻。

### Phase 2：网络接力

- 用 exit node / 代理路径把手机流量切到指定设备；改网络状态前确认；失败回滚。
- 验收：目标设备离线、切换失败、回滚都有明确提示。
- 仍不做文件内容传输。

### Phase 3：文件交接

- “把这个文件发到 Mini/云电脑”：Taildrop 或目标端 HTTP 能力端点；进度、取消、失败重试。
- 模型只看到文件名/大小/目标设备，不看内容。
- 验收：大文件不阻塞 UI；目标离线有明确错误；不经过 LLM 数据面。

### Phase 4：会话接力与常驻协调

- URL/剪贴板/命令在另一台设备打开；多步任务编排。
- 如果“手机不在也要接力”，再评估 Mini/云电脑上的常驻协调器；不提前拆服务。

## 6. 第一场景

建议 Phase 1 先做“设备盘点 + 网络状态问答”，因为它只读，能先验证设备注册表和 Agent 工具链，风险最低。若你更在意文件转移，可把 Phase 2/3 对调。

## 7. 未决问题

- 第一场景最终选哪个：设备盘点、网络接力，还是文件交接。
- DeepSeek key：设备端 BYOK 加密存储，还是后端代理。
- 是否需要在 Mini/云电脑上放常驻协调器，还是 App 打开时编排即可。
- 设备端能力端点的最小协议（HTTP/RPC）和部署方式。
- 哪些元数据允许发给模型：设备名、节点名、路径、延迟可以；订阅链接、token、日志全文不可以。

## 8. 验收线

每个功能必须能写成：

> 设备 A，能力 C，参数 P，权限/确认，成功/失败。

写不出来就还不是功能。任何需要新数据面（文件同步、远程桌面、shell）的需求，先回到本文档确认边界。
