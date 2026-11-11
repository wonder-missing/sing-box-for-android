# WonderSing 待办

只放未完成事项。已完成的事项不再长期留在这里，见 GitHub Releases 和 git history。
新增一项加到「现在待办」；做完就删掉，或在「最近完成」留一行索引。

## 现在待办

### P1

- [ ] **节点探测并发/批量**：当前 `probe_via_node` 串行切换全局出站，不能直接并发。需要 `probe_many` 或 libbox per-request outbound binding；URLTest 只能测分组配置 URL，不能测任意 GitHub 地址。先验证 libbox HTTP API，再定方案。`probe_many` 同时给 `search_tools` 一个非核心带参工具做真机验证。

### P2

- [ ] **每条消息的极简 token/cache 用量可视化**：API 响应已有 usage；UI 设计待定。
- [ ] **批量确认的整批预览**：wonder.41 的 `Approve rest of turn` 只显示当前工具参数，后续工具仍由模型逐轮声明；以后可让运行时预检整批、合成一张含全部工具与参数的确认卡。
- [ ] **`tailscale_status` 结构化 + 最后在线时间**：现在是纯文本块，模型能读但不能程序化处理。
- [ ] **`network_snapshot` / `list_nodes` 重叠**：core 字段重复，随 `list_nodes` 摘要一起收。
- [ ] **`search_tools` 相关性与 no-match 信号**：宽泛查询总是返回 top-3 弱相关工具；加分数/置信度或阈值，避免模型误以为有专用能力。
- [ ] **`ToolObservation.ok` 与工具 `output.ok` 的契约**：两层 `ok` 语义重复，模型会误读；去掉 probe 内层 `ok` 或改名 `reachable`，定义单一成败层。
- [ ] **确认与事务语义可见性**：模型看不到确认事件；多步调用没有框架级事务。先记着，等 2B 流式和 Phase 3 再评估。
- [ ] **Stop 1s 未确认的 `TOOL_CANCEL_TIMEOUT` 错误码**：当前 UI 保持禁用并提示「正在停止」，但未把该错误码作为 turn error 输出；等出现无法 1s 取消的工具再补。
- [ ] **对话级 LLM 摘要**：wonder.36 只做旧大 tool observation 的确定性压缩；assistant/user 长文本和跨 turn 的语义摘要还没做。
- [ ] **跨版本会话的版本边界提示（以后考虑）**：旧 tool observation 会被模型当成当前证据；当前决定保留现状，先记着。
- [ ] **probe 链路复测（等出口健康）**：wonder.34 的 6 连失败是出口节点不健康（订阅在 CFG 项目打磨），不是 Conductor 回归；204 分类待出口健康后复测。
- [ ] **连接与规则命中可观测性**：最近 N 条连接 + 命中规则 + 最终出站；需要 CommandClient 支持，Phase 3 方向。
- [ ] **Provider capability 矩阵**：wonder.42 起 Conductor 设置可选 Reasoning effort（默认不传；`low` / `medium` / `high` 写入 `reasoning_effort`）。仍未做：`stream_options.include_usage`、`max_completion_tokens`、Azure 类鉴权，以及 DeepSeek `none`/`max`、xAI `xhigh` 这类不能三家共用的档位。
- [ ] **增量更新（低优先级）**：整包 bsdiff 方案，评估见 [`docs/incremental-update.md`](docs/incremental-update.md)；约 3–4 个工作日，等有整块时间再做。归档感知不做。

## 下个会话接手

- 当前 `dev` 版本号 41，wonder.41 已发布。下一次发版从 42 开始。
- wonder.41 已包含：传输错误边界、单调用 `Approve rest of turn`、`conductor:event` 中断信封、A7 app icon。
- 发版前真机重点：
  - 中断 reasoning 后发第二问，API trace 的下一次请求应是 `system → user([conductor:event turn_interrupted ...] + 正文)`，且没有被打断的第一问。
  - 中断前已有 partial assistant 或 tool 结果时，它们应保留，事件挂在下一问。
  - 确认卡单调用也有 `Approve rest of turn`，点一次后续不再弹卡。
  - xAI/Custom provider 的传输错误提示应是可读文案，不出现 fake-IP。
  - 桌面图标检查 square / round / adaptive / Android 13 themed icon；旧图标缓存可能需要重启启动器。
- 仍未做，按阻塞原因：
  - `probe_many`（P1，需要 libbox 验证；当前 probe 串行切全局出站）。
  - usage 极简可视化（UI 设计未定）。
  - 批量确认整批参数预览（现在只有当前工具参数）。
  - Provider capability（`stream_options`、`max_completion_tokens`、Azure 类鉴权；reasoning effort 的 low/medium/high 从 wonder.42 起可选，`none`/`max`/`xhigh` 仍不做）。
  - 跨版本会话边界提示（之前决定保留现状）。
  - probe 链路复测（等出口健康）；Stop 1s `TOOL_CANCEL_TIMEOUT`（等出现无法取消的工具）。
  - 搜索 no-match、`ToolObservation.ok` 契约、确认/事务可见性、LLM 摘要、增量更新等 backlog。

## 最近完成

- wonder.41（2026-11-11）：A7 app icon（adaptive / legacy / round / monochrome / playstore）；传输错误边界；单调用 `Approve rest of turn`；`conductor:event` 中断信封与空 turn 回滚
- wonder.40（2026-11-11）：中断/失败 turn 收尾：Stop 保留已流出的 assistant 正文，没有正文时在 transcript 末尾补合成 assistant 结束消息，避免下一次请求出现两个连续 user；对话与 API trace 共用文件导出，新增 Save conversation to file
- wonder.39（2026-11-11）：多 Provider：DeepSeek / xAI / Custom（OpenAI-compatible）预设、按 provider 独立 model/key/base URL、turn 级 provider 快照、OpenAI cached_tokens 与 reasoning 字段兼容
- wonder.38（2026-11-11）：Thinking 生命周期修复（结算所有 streaming 项，正文开始即停 spinner）、两行自动上滚 Reasoning ticker + Show/Hide、批量确认 Run all、API trace 保存为文件
- wonder.37（2026-11-11）：真机反馈修复：2MB 粘贴崩溃（输入上限/恢复截断）、空 API trace 复制提示、Stop 后 `Stopped.` 与去重、已完成工具结果保留、Reasoning 流式 `Thinking` 块、压缩阈值 8k
- wonder.36（2026-11-10）：Phase 2b 全部收尾：OkHttp SSE 流式回答、Stop 真取消、probe/telemetry 可取消、旧大 tool observation 的 turn 边界压缩、请求前缀/SSE/Stop/压缩单测
- wonder.35（2026-11-10）：`list_nodes` 默认只回 group 摘要（tag/selected/itemCount/selectedDelayMs、groupCount/outboundCount），传 `group=<tag>` 才回该组节点明细；去掉 flat `outbounds` 与 `groups[].items` 的重复
- wonder.34（2026-11-10）：`probe_via_node` 按 HTTP 状态判定可达（204/3xx/4xx/5xx 不再误报 `tool_failed`）、probe 回滚边界写进描述、请求前缀不变量单测、prompt 允许知识迁移（知道名字和参数就直接调）
- wonder.33（2026-11-10）：2B 工具目录与路由 + 缓存修正：静态 `route_call` / `search_tools`、目录 roster、分发前 unwrap、滞后裁剪、`toolChoice = NONE`、Settings 路由开关、API trace cache usage
- wonder.32（2026-11-10）：IME 收起后清焦点、system prompt 去 DeepSeek 品牌、probe HEAD 说明、2A 文档/TODO 与实现对齐
- wonder.31（2026-11-10）：横三点对话菜单、Markdown 对话复制、Copy API trace（headers 脱敏）、用户消息自由选择、8 次/轮与 12 轮的宽松 guard、Telemetry 冗余说明删除
- wonder.30（2026-11-10）：系统提示改为稳定前缀并持久化 transcript，提高缓存命中；对话复制改到最后消息下方三点菜单；Settings Conductor 入口瘦身、设置按钮改为纵向满宽；服务状态条为内容预留底部空间；工具步数耗尽后仍给出最终回答
- wonder.29（2026-11-10）：Conductor 会话跨页面/进程持久化、顶部一键复制整段对话、模型输出上限 1024→4096、服务启动文案与工具摘要优化
- wonder.28（2026-11-10）：Conductor 2A 打磨：用户消息长按复制、轻量 Markdown 渲染、工具卡片折叠与美化、Tailscale 友好错误、服务连接文案、基础设备上下文（语言/时区/版本）
- wonder.27（2026-11-10）：Conductor Phase 2a：DeepSeek BYOK、自然语言单步 tool calling、确认卡片、敏感输入拦截、tool 卡片、可配置模型 ID
- wonder.26（2026-11-09）：Conductor 展开键盘到顶时的颤动；IME 变化期间及之后 300ms 高度 snap、高度取整到像素、placeholder 移出 `BasicTextField` decoration
- wonder.25（2026-11-09）：Conductor 输入栏默认态随文本长高到 5 行、回删变矮；收键盘时面板高度 snap，避免下塌回弹
- wonder.24（2026-11-09）：定位到问题实际在 Dashboard 的 Groups 底部面板标题行；只改标题行右 4dp、分组 chevron 右 16dp，撤回自绘顶栏与列表 padding 实验
- wonder.23（2026-11-09）：Groups 顶栏改为自绘、外观保持一致；顶栏尾部 icon 与分组 chevron 共用 `TrailingActionSlot`
- wonder.19（2026-11-09）：Conductor 键盘遮挡、分组 chevron 与顶栏对齐、订阅时间改用 Update/Published 图标
- wonder.18（2026-11-09）：Conductor 输入栏 v1.2、app 日志与日志页解耦、`/probe`

CFG 订阅、Tailscale 控制面这类跨项目问题归 CFG 仓库跟踪，不放在这里。
- wonder.17（2026-11-08）：快捷方式后台启停、Tailscale 直达、订阅时间单行
- wonder.16（2026-11-08）：APK 收缩/英文过滤、504 错误详情、桌面快捷方式首版
