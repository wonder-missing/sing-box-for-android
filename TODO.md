# WonderSing 待办

只放未完成事项。已完成的事项不再长期留在这里，见 GitHub Releases 和 git history。
新增一项加到「现在待办」；做完就删掉，或在「最近完成」留一行索引。

## 现在待办

### P1

- [ ] **Conductor Agent Phase 2b 剩余**：Stop 真取消、流式回答、上下文压缩、probe/telemetry 可取消；设计见 [`docs/agent-runtime.md`](docs/agent-runtime.md)。
- [ ] **节点探测并发/批量**：当前 `probe_via_node` 串行切换全局出站，不能直接并发。需要 `probe_many` 或 libbox per-request outbound binding；URLTest 只能测分组配置 URL，不能测任意 GitHub 地址。先验证 libbox HTTP API，再定方案。`probe_many` 同时给 `search_tools` 一个非核心带参工具做真机验证。

### P2

- [ ] **每条消息的极简 token/cache 用量可视化**：API 响应已有 usage；UI 设计待定。
- [ ] **`tailscale_status` 结构化 + 最后在线时间**：现在是纯文本块，模型能读但不能程序化处理。
- [ ] **`network_snapshot` / `list_nodes` 重叠**：core 字段重复，随 `list_nodes` 摘要一起收。
- [ ] **`search_tools` 相关性与 no-match 信号**：宽泛查询总是返回 top-3 弱相关工具；加分数/置信度或阈值，避免模型误以为有专用能力。
- [ ] **`ToolObservation.ok` 与工具 `output.ok` 的契约**：两层 `ok` 语义重复，模型会误读；去掉 probe 内层 `ok` 或改名 `reachable`，定义单一成败层。
- [ ] **确认与事务语义可见性**：模型看不到确认事件；多步调用没有框架级事务。先记着，等 2B 流式和 Phase 3 再评估。
- [ ] **跨版本会话的版本边界提示（以后考虑）**：旧 tool observation 会被模型当成当前证据；当前决定保留现状，先记着。
- [ ] **probe 链路复测（等出口健康）**：wonder.34 的 6 连失败是出口节点不健康（订阅在 CFG 项目打磨），不是 Conductor 回归；204 分类待出口健康后复测。
- [ ] **连接与规则命中可观测性**：最近 N 条连接 + 命中规则 + 最终出站；需要 CommandClient 支持，Phase 3 方向。
- [ ] **模型供应商抽象**：system prompt 已去 DeepSeek 品牌；client 和 Settings 仍是 DeepSeek 专用，后续抽 base URL/model/key/provider。
- [ ] **增量更新（低优先级）**：整包 bsdiff 方案，评估见 [`docs/incremental-update.md`](docs/incremental-update.md)；约 3–4 个工作日，等有整块时间再做。归档感知不做。

## 最近完成

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
