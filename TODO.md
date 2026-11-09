# WonderSing 待办

只放未完成事项。已完成的事项不再长期留在这里，见 GitHub Releases 和 git history。
新增一项加到「现在待办」；做完就删掉，或在「最近完成」留一行索引。

## 现在待办

- [ ] **Conductor 键盘遮挡**（2026-11-09 反馈）：软键盘弹出后，输入面板底部被压住，圆钮下部被裁掉一点。落点：`MainActivity` 的 Scaffold 内容区目前先 `consumeWindowInsets(paddingValues)`，Conductor 自己的 `imePadding()` 可能拿不到 IME inset；需要在 Scaffold 内容区给 Conductor 外层先加 `imePadding()`，或等价地保留未被消费的 IME inset。
- [ ] **分组卡片展开/收起按钮与顶栏最后一个按钮未对齐**（2026-11-09 反馈）：面板顶满屏宽后，Groups 顶栏最后一个按钮和每个分组卡片右侧的 chevron 不在同一竖线上。落点：`GroupsCard.kt` 的 `GroupHeader` 右侧内边距从 8dp 调到 12dp，让 24dp chevron 的中心距右边 24dp，与顶栏 48dp IconButton 的中心对齐。
- [ ] **远程订阅时间图标换成 profile 图标库里的两个 icon**（2026-11-09 反馈）：上次成功拉取时间用 `Icons.Filled.Update`（profile 图标库的 “Update”）；源端 `Last-Modified` 用 `Icons.Filled.PublishedWithChanges`（“Published”）。落点：`ProfilesCard.kt` 的 `ProfileInfoRow`。
- [ ] **增量更新（低优先级）**：整包 bsdiff 方案，评估见 [`docs/incremental-update.md`](docs/incremental-update.md)；约 3–4 个工作日，等有整块时间再做。归档感知不做。
- [ ] **Tailscale 登录/协调流量走代理**：Tailscale 的登录和协调服务器连接（control plane）在某些网络下会失败/重连；让它走现有代理节点，设备之间的实际流量（data plane）不变。需要改 CFG 的 sing-box 订阅，属于订阅配置改动，等主人确认再做。

## 最近完成

- wonder.18（2026-11-09）：Conductor 输入栏 v1.2、app 日志与日志页解耦、`/probe`
- wonder.17（2026-11-08）：快捷方式后台启停、Tailscale 直达、订阅时间单行
- wonder.16（2026-11-08）：APK 收缩/英文过滤、504 错误详情、桌面快捷方式首版
