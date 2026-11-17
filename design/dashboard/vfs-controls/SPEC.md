# Dashboard 底部操作栏与展开 VFS 卡片 · UI 规格

版本：v1 · 2026-11-17  
定稿：主人确认 v3 效果图，并批准据此修改。  
**实现以本文为准。** 同目录 `01-disconnected-v3.png`、`02-connected-v3.png`、`03-expanded-v3.png`、`preview-combined-v3.png` 只是布局归档示意，不是图标、字体、颜色 token 或可运行资源的真相来源。不要把这些 PNG 拷进 `res/` 或当作图标资产。不要把预览图外侧的中文标注（「未连接」等）写进应用。文稿与图冲突时以本文为准。  
对接约定见 [`design/README.md`](../../README.md) 的 SPEC 模板（11 节）。

## 0. 已批准的六条（实现方先看这里）

1. **四列等宽。** 左右内容边距 16dp，列间距 8dp，列宽 = `(W − 56) / 4`（360dp 宽时为 76dp）。从左到右：Start/Stop、连接数 + cable 图标、组数 + stacked-layers 图标、VFS（cloud-upload 图标）。
2. **几何。** 视觉高度默认 36dp（大字体时可增长），使用 capsule 圆角（`RoundedCornerShape(percent = 50)`）；最小触控高度 48dp，不得与导航栏重叠。组内文字与图标作为一组在胶囊内居中，间距 6dp，沿用现有 glyph，不要新绘图标。
3. **已连接 VFS 不跳动。** 使用现有状态点；连接前后该列的宽度、位置、高度不得变化。已连接态的背景与前景必须与最左侧 Start/Stop **同一套主题语义色**，跟随 light/dark 动态取 token，不要写死截图色值，不要自定义紫、青绿或其他新色板。未连接保持现有中性样式。
4. **连续底栏。** pill 行与导航必须共用同一条连续底表面：其上沿约在视觉 pill 顶之上 10dp，并向下贯通导航与系统安全区。导航栏本身不改。
5. **展开卡片。** 左右内容边距 16dp；卡片底边距**整条底栏表面的上沿** 12dp（不是距 pill 视觉中心，也不是距 pill 视觉顶 12dp）。不得因此把底栏推走或改变底栏位置。左侧圆形容器沿用现有主题 icon-container 样式，把文件夹图标换成与 VFS pill 相同的 cloud-upload；标题、状态文案、断开按钮、四枚 scope 芯片都不改。
6. **适配与行为。** 长计时、计数变大、宽度 320dp、系统放大字体时：不得重叠、不得缩小触控目标；必要时可增加行高，不要为此单独做滚动。保持现有无障碍语义。界面英语，不新增 locale / 翻译流程。现有计数、计时、启停、VFS 挂载与断开等行为一律不改。

## 1. 目标与非目标

**目标**
- 把 Dashboard 底部四个操作胶囊改成等宽、从左到右为 Start/Stop → 连接数 → 组数 → VFS 的一行，并与导航处于同一条连续底表面。
- 已连接 VFS 改用与 Start/Stop 相同的主题色；展开卡片贴近该底栏上沿，并把左侧文件夹换成同一 cloud-upload。

**非目标 / 必须保持**
- 不改计时、连接数、组数、Start/Stop、VFS 连接 / 断开 / 挂载、卡片展开收起的既有行为与回调。
- 不改导航栏（含手势条与 Tools 等角标），不改 Remote 控制条、Navigation rail / 平板 FAB 布局。
- 不改 ViewModel、VFS 协议与服务、Logs 卡片业务、字符串业务含义；不新增图标资源，不把归档 PNG 当作可运行资产。
- 不新增 UX 功能，不为这次引入新的滚动容器、新色板或新国际化流程。
- 颜色走 `MaterialTheme.colorScheme`，字体走现有 typography 角色（胶囊内沿用已有 `labelLarge`），**不要**按归档图反推字号或 hex。

## 2. 落点

- 改：`app/src/main/java/io/nekohasekai/sfa/compose/component/ServiceStatusBar.kt`（四列布局、间距、已连接 VFS 的颜色与状态点占位）。
- 改：`app/src/main/java/io/nekohasekai/sfa/compose/component/VfsStatusBar.kt`（左侧 `FolderOpen` 换成与 pill 相同的 `Icons.Outlined.CloudUpload`；容器仍用现有主题圆形样式）。标题、副标题、断开、scope 芯片的结构与文案不改。
- 视需要改：`app/src/main/java/io/nekohasekai/sfa/compose/MainActivity.kt` 里底栏与展开卡片的叠放及间距，使卡片底边距**整条共享底表面的上沿** 12dp，且不推动底栏、不改导航。不要扩大到 Remote 条或 Navigation rail 分支，除非不加会破坏既有行为。
- **不改、不新增** drawable / 自定义 SVG。继续用已有 Material 图标：`Stop`、`Cable`、`Layers`、`CloudUpload`、`LinkOff`。不要再引入 `FolderOpen` 作为该卡片的主导图标。
- 可见文案继续用现有英语 string（如 `vfs_status_chip_compact`、`vfs_status_on` / `off` / `connecting`、`vfs_status_subtitle`、`vfs_status_disconnect`、以及 Conductor / Trace / Prompt / Chat 等已有短标签）。**不要**新增语言包或翻译流程；不要把预览图上的中文标签写进应用。

## 3. 结构

```
共享底表面（全宽；上沿 ≈ 视觉 pill 顶之上 10dp；向下贯通导航与系统安全区）
└─ Column
   ├─ 四列 Row（水平 padding 16，列间距 8，垂直方向保证视觉 pill 与表面关系见第 4 节）
   │    ├─ Start/Stop 胶囊（计时 + Stop 图标；现有启停行为）
   │    ├─ 连接数 + Cable
   │    ├─ 组数 + Layers
   │    └─ VFS + CloudUpload（已连接时文字前有状态点）
   └─ 现有导航栏（本次不改）

展开的 VFS 卡片（仅已连接且展开时；左右 16dp；底边距共享底表面上沿 12dp；不进入底表面、不推动底栏）
└─ 现有 VfsStatusBar 内容
   ├─ 40dp 圆形容器（现有 primaryContainer）+ CloudUpload
   ├─ 标题 serverName、状态副标题、断开按钮
   └─ 四枚（及既有溢出）scope 芯片
```

四列都在时，不要再使用「左右分组 + 中间 `weight` spacer」。某列因**既有可见性规则**暂时不出现时（例如服务未启动时不显示连接数、没有 groups 时不显示组数），不要为凑齐四列而发明空胶囊；剩余列仍用 16dp 边距与 8dp 间距，不要退回旧的左右分组。Start/Stop 与 VFS 的点击行为、计数的入口、VFS 点击展开/收起卡片，一律沿用现状。

## 4. 尺寸（dp）

| 项 | 视觉 | 触控 | 说明 |
|---|---|---|---|
| 左右内容边距 | 16 | — | 胶囊行与展开卡片都是内容边距 16 |
| 列间距 | 8 | — | 三道间隙，合计 24 |
| 列宽 | `(W − 56) / 4` | 列宽内垂直 48 热区 | 360dp 宽时为 76。`56 = 16+16+8+8+8` |
| 胶囊视觉 | 默认高 36，capsule 圆角 | — | 大字体时允许长高，触控不低于 48 |
| 胶囊触控 | — | **最小高 48**，与视觉居中 | 不得与导航重叠，不得缩小热区 |
| 组内文字 ↔ 图标 | 间距 **6** | — | 作为一组在胶囊内居中，不要 `spaceBetween` |
| 已连接状态点 | 沿用现有 6 直径 | — | 预留位置，使连接前后该列宽、位置、高度不变 |
| 共享底表面上沿 | 约在视觉 pill 顶之上 **10** | — | 全宽，向下贯通导航与系统安全区 |
| 展开卡片底边距 | 距**整条底表面的上沿 12** | — | 不是距 pill 视觉顶 12，也不是距 pill 中心。底表面已含约 10dp 上沿，故卡片底到视觉 pill 顶自然约为 22dp，不要再额外加出一段空隙 |
| 展开卡片左右 | 16 | — | 与内容边距对齐 |
| 卡片左侧容器 | 40 圆，沿用现有 | — | 图标沿用现有容器内尺寸，只换 glyph |

**必须精确：** 四列公式与顺序；36 / 48；组内间距 6；已连接不跳动；已连接 VFS 与 Start/Stop 同 token；卡片底边距底表面上沿 12；导航不改；不改既有行为。  
**可按工程规范微调：** 共享表面上沿「约 10dp」与热区 48 的垂直分配（热区可伸入表面上沿，但不可伸进导航）；窄宽或大字体时行高增长方式，只要不重叠、不缩小热区、不另做滚动。

**窄宽、长计时、大计数、系统大字体：** 以 320dp 宽和放大字体为验收条件。文字不得裁切到不可读、胶囊不得互相重叠、触控不得小于 48dp。计时出现 `h:mm:ss` 等更长文本时，优先在列内单行省略或升高该行，**不要**缩小热区，**不要**为这一行单独发明滚动。

**实现补充（工程侧）**：360dp 四列等宽时，胶囊横向 padding 用 8dp、图标用 16dp，保住 `3:42` 和 `VFS` 文字；320dp 或大字体下 VFS 文字放不下时隐藏文字，只留状态点 + cloud-upload，`contentDescription` 继续报 `VFS On/Off`。Starting/Stopping 在 76dp 列宽内放不下完整文案，改为 spinner + Stop 图标，语义仍用 `status_starting` / `status_stopping`。

## 5. 颜色与字体

全部来自 `MaterialTheme.colorScheme` / `MaterialTheme.typography`。归档图里的periwinkle / 中性灰只是深色主题示意，禁止写进生产代码。

| 元素 | token |
|---|---|
| 共享底表面 | 与现有底栏 / 导航同一连续表面；今日底栏为 `surfaceContainer`，不要另造一条更深的页面底露在 pill 背后 |
| Start/Stop 胶囊 | 沿用现有 `primaryContainer` / `onPrimaryContainer` |
| 连接数、组数胶囊 | 沿用现有 `secondaryContainer` / `onSecondaryContainer` |
| VFS 未连接 | 沿用现有中性：`surfaceContainerHighest` / `onSurfaceVariant` |
| VFS 已连接 | **必须与 Start/Stop 相同**：`primaryContainer` / `onPrimaryContainer`。不要再用 `tertiaryContainer` / `onTertiaryContainer` 或任何自定义紫、青绿 |
| 已连接状态点 | 保留现有 6dp 点作为非颜色指示；颜色跟该胶囊的 on-color，不要另用 `tertiary` 或自定义色来「表示已连接」 |
| 胶囊内文字 | 沿用现有 `labelLarge` + Medium，不要按图改成 14sp 或其他写死字号 |
| 展开卡片面板、标题、副标题、断开、芯片 | 保持现有 token 与样式，不要整卡改成绿色或其他新色 |
| 卡片左侧圆容器 | 沿用现有 `primaryContainer`；cloud-upload 使用该容器的配对 on-color（`onPrimaryContainer`），不要自定义绿色或 `primary` 以外的新色 |

深色 / 浅色都只靠 token 验收。

## 6. 状态

| 状态 | 行为 |
|---|---|
| VFS 未连接 | 中性胶囊，无状态点，cloud-upload + 现有未连接文案；不改现有是否可点的规则 |
| VFS 已连接、卡片收起 | 与 Start/Stop 同色；文字前有状态点；列宽/位置/高度与未连接时相同 |
| VFS 已连接、卡片展开 | 同上，并显示现有卡片；卡片底边距共享底表面上沿 12dp |
| VFS 连接中 | 沿用现有 spinner 与 `vfs_status_connecting` 语义，不要改协议或超时 |
| Start/Stop、计数 | 沿用现有启动/停止/计时/跳转；服务未启动等既有可见性规则保持 |
| 按压 | Material 默认 ripple，无定制色 |
| 深色 / 浅色 | 只靠 token |
| 横屏、输入法 | 不在本次单独立项；不得借此改导航或发明新的底栏行为 |

## 7. 内容

- 应用内文案保持英语。预览拼图上的「未连接 / 已连接 / 已连接 · 展开 VFS 卡片」只是归档标签，不进应用。
- VFS 胶囊可见文字继续用现有 string（默认宽度下已有 compact 标签则沿用），不要为临摹归档图新写一套文案。
- 卡片标题为运行中的 `serverName`（归档图里的 `Clawdy Portal Knocks` 只是当时快照），副标题沿用 `vfs_status_subtitle`，断开与芯片沿用现有 key。
- 单行省略沿用现状（`maxLines = 1` + Ellipsis）。RTL：整行跟随系统镜像，图标几何不镜像，与 `design/README.md` 既有约定一致。
- 不新增 locale 目录或翻译流程。

## 8. 可访问性

- 保持现有 `contentDescription` 与语义：启停、连接数、组数、`vfs_status_on` / `off` / `connecting`、`vfs_status_disconnect`。已连接不能只靠颜色区分，必须保留状态点，并沿用上述语义。
- 触控目标最小 48dp，不得缩小，不得与导航重叠。
- 屏幕阅读器顺序：从左到右四列，再到达导航；展开卡片在底栏之前，断开按钮保持独立节点。
- 系统大字体见第 4 节：升高行高，不裁字、不缩小热区。

## 9. 图标

- 全部复用现有 Material 图标，不新绘、不导出新 asset。
- 胶囊：Start/Stop 用现有 `Stop`（及现有计时文字）；连接数 `Cable`；组数 `Layers`；VFS `CloudUpload`。
- 展开卡片左侧由 `FolderOpen` 改为同一 `CloudUpload`；断开仍为 `LinkOff`。
- 图标颜色由所在容器的 content / on-color tint 决定。

## 10. 验收

- [ ] 360dp 宽时四列各 76dp，间距 8，左右 16，顺序为 Start/Stop、连接数、组数、VFS。
- [ ] 视觉 36、触控 ≥ 48，不与导航重叠；组内文字与图标居中，间距 6。
- [ ] 已连接 VFS 与 Start/Stop 在深浅色下都使用同一套 `primaryContainer` / `onPrimaryContainer`，无自定义紫或青绿；未连接仍为中性。
- [ ] 状态点在已连接时出现，连接前后该列宽、位置、高度不变。
- [ ] pill 行与导航是同一条连续底表面，上沿约在视觉 pill 顶之上 10dp，pill 背后不再露出更深的页面底。
- [ ] 展开卡片左右 16dp，底边距整条底表面上沿 12dp，不推动底栏；导航视觉与行为不变。
- [ ] 卡片左侧是现有主题圆容器 + cloud-upload；标题、状态、断开、芯片未改色也未改文案结构。
- [ ] 320dp 宽、长计时、大计数、系统大字体下不重叠、不缩小热区、不为该行单做滚动。
- [ ] 计时、计数、启停、VFS 挂载与断开行为与改前一致；无新图标、无新 locale、归档 PNG 未进入 `res/`。
- [ ] 无障碍仍能区分未连接 / 已连接 / 连接中，不只靠颜色。

## 11. 已知取舍

- 「约 10dp」的底表面上沿是与现有 48dp 热区一起分配的视觉关系，允许为对齐热区做 1–2dp 级微调，但不能改成明显更大的空隙，也不能让 pill 浮在更深的页面底上。
- 卡片底边距的参照是**整条共享底表面的上沿**，不是 pill 视觉顶。若误把 12dp 加在 pill 顶上，会变成约 22dp，显得过远——这是明确否定的算法。
- 列宽公式按四列都在时计算。部分列按既有规则隐藏时，不要发明占位胶囊，也不要恢复中间 spacer。
- 归档图中的 3:42、计数 8/10、卡片标题只是对照快照，不是要写死的运行值。
- Navigation rail、远程控制条、Logs 卡片内容不在本次范围。
