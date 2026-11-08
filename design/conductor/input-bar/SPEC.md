# Conductor 输入框（ConductorInputBar）· UI 规格

版本：v1.1 · 2026-11-08  
定稿方向：主人选定「D 方案 · 矢量图标版」。实现以本文为准，`mockup.png` 只是示意；与本文冲突时以本文为准。  
对接约定见 [`docs/design-handoff.md`](../../../docs/design-handoff.md)。

## 1. 目标与非目标

**目标**
- 把 `/`、`@` 从「看起来像飘着的文字」改成与发送钮同款的圆钮 + 矢量图标。
- 视觉尺寸 36dp、触控尺寸 48dp，符合项目触控标准。
- 左右外边距对称。

**非目标 / 必须保持**
- 不改字符串资源（沿用 `conductor_commands` / `conductor_mention` / `conductor_send` / `conductor_input_hint`）。
- 不改 ViewModel，以及 `onSlash` / `onAt` / `onSend` 的行为语义。
- 不改输入框圆角、卡片外距、主题色写死；颜色全部走 `MaterialTheme.colorScheme`。
- 不调整输入栏与上方消息流 / 命令建议列表的间距（沿用现有结构）。

## 2. 落点

- 改：`app/src/main/java/io/nekohasekai/sfa/conductor/ConductorScreen.kt`（`ConductorInputBar`、`InputGlyphButton`）
- 新增 drawable：把本目录 `icons/ic_conductor_*.xml` 复制到 `app/src/main/res/drawable/`
  - `ic_conductor_slash.xml`
  - `ic_conductor_at.xml`
  - `ic_conductor_send.xml`（替换 `Icons.Default.ArrowUpward`）
- 不改：`values*/strings.xml`、ViewModel、命令逻辑

## 3. 结构

```
Surface (卡片)
└─ Column（无水平 padding；垂直：上 12 / 下 12，子项间距见下）
   ├─ BasicTextField（水平 padding 16 / 16）
   └─ Row（按钮行；水平 padding 12 / 12；与上方间距 8）
      ├─ SlashButton
      ├─ 视觉间距 12
      ├─ AtButton
      ├─ Spacer(weight=1)
      └─ SendButton | Progress
```

## 4. 尺寸（dp）

| 项 | 视觉 | 触控 | 说明 |
|---|---|---|---|
| 卡片外距 | 水平 16、垂直 8 | — | 不变 |
| 卡片圆角 | 12 | — | 不变 |
| 文本区内边距 | 左 16、右 16、上 12 | — | 提示文字起点 16，比按钮略往里，属预期 |
| 按钮行内边距 | 左 12、右 12、下 12 | — | 视觉上左右对称 |
| 文本区 ↔ 按钮行 | 间距 8 | — | |
| 圆钮 | 直径 36 | **48×48** | 视觉圆 36；外扩透明触控到 48，涟漪可铺满触控区 |
| 图标 | 20×20 | — | 居中于 36 圆 |
| Slash ↔ At 视觉间距 | **12**（圆心距 48） | — | 保证两颗 48 触控区不相交；v1.0 写的 8 作废 |
| 发送钮 | 同圆钮 | 同 48 | |

**必须精确**：圆钮视觉 36、触控 48、图标 20、左右视觉外边距 12、Slash–At 视觉间距 12。  
**可微调**：若 Material 默认 ripple 裁剪与 48 触控有冲突，优先保证触控 48 与视觉圆 36 分离，不要为了涟漪缩小触控区。

触控实现建议：外层 `Box(Modifier.size(48.dp), contentAlignment = Center)` + `clickable` / `IconButton`；内层 `Box(Modifier.size(36.dp).clip(CircleShape).background(...))`。不要把点击绑在 36 圆上。

左右 12 视觉边距时，48 触控会各向卡片外多出 6dp；由卡片水平内边距吸收即可，不要再加大卡片外距。

## 5. 颜色与字体

全部来自 `MaterialTheme.colorScheme`，代码不写死 hex。

| 元素 | token |
|---|---|
| 卡片底 | `surfaceContainerHigh` |
| 三钮默认底（含发送空态） | `surfaceContainerHighest` |
| 命令 / 提及图标 | `onSurface` |
| 发送空态图标 | `onSurfaceVariant` |
| 发送可用底 / 图标 | `primary` / `onPrimary` |
| 提示文字 | `onSurfaceVariant`，alpha ≈ 0.7（沿用现状） |
| 输入正文 | `bodyLarge` + `onSurface` |

## 6. 状态

| 状态 | 行为 |
|---|---|
| 默认 | 命令、提及：最高容器底 + onSurface 图标；发送：最高容器底 + onSurfaceVariant 箭头，**不可点** |
| 有输入 | 「有输入」= `input.trim().isNotEmpty()`（全空格算空）→ 发送变 primary / onPrimary，可点 |
| 按压 | Material 默认 ripple；无定制颜色 |
| 运行中 `isRunning` | 发送位置不画圆底，显示 `CircularProgressIndicator` 20dp、strokeWidth 2；命令与提及仍可点 |
| 深色 / 浅色 | 只靠 token，两套主题都验收 |

无单独 focused / error 态要求。长按无特殊行为（不弹菜单）。

## 7. 内容

- 字符串 key 不变。
- `BasicTextField` 仍 `maxLines = 3`；超长滚动，不截断成省略号。
- RTL：整行 `Row` 自然镜像（发送到视觉左侧）；**三个图标本身不镜像**（slash / at / send 路径保持 LTR 几何）。实现：`Icon` 上 `Modifier.autoMirrored` 关掉或使用不自动镜像的 drawable。

## 8. 可访问性

- `contentDescription`：命令 / 提及 / 发送用现有字符串。
- 运行中：进度指示器设 `contentDescription` 为「正在执行」（若无现成 string，可新增 `conductor_running`，中英各一；不写死在 Composable 里）。
- 触控目标：三钮均为 48dp。
- 阅读顺序：命令 → 提及 → 输入框 → 发送/进度（沿用语义树即可，不必强行自定义 traversal）。

## 9. 图标

本目录 `icons/`：
- SVG 原稿：`ic_slash.svg` / `ic_at.svg` / `ic_send.svg`
- VectorDrawable：`ic_conductor_slash.xml` / `ic_conductor_at.xml` / `ic_conductor_send.xml`

统一：viewport 24、描边 2、圆头 round、接合 round、无填充；颜色由 tint 决定。路径：

- slash：`M15 4.5 9 19.5`
- at：内圆 `cx=12 cy=12 r=3.5`；外弧 `M15.5 12v1.5a2.5 2.5 0 0 0 5 0V12a8.5 8.5 0 1 0-3.4 6.8`
- send：`M12 19V5M6 11l6-6 6 6`

真机验收：20dp 下 slash 不要细到看不见；at 不要糊成一团。若偏细，只允许把 strokeWidth 微调到 2.25，不要改路径。

## 10. 验收

1. 三颗钮视觉直径一致 36，图标居中，不随系统字体漂移。
2. 触控热区均为约 48×48，Slash 与 At 热区不相交。
3. 命令钮左外沿到卡片左边 = 发送钮右外沿到卡片右边 = 12（视觉）。
4. Slash–At 视觉间距 12。
5. 提示文字左边起点 16（比按钮略往里）。
6. trim 后为空 → 发送灰且不可点；有非空白 → primary 可点。
7. 运行中：转圈 + 读「正在执行」；命令 / 提及仍可点。
8. 深色、浅色各看一眼；无硬编码色值。
9. RTL：行镜像，图标几何不镜像。
10. 按压为默认 ripple；无长按菜单。

## 11. 已知取舍

- v1.0 写「点击区就是 36 圆本身」作废；以视觉 36 / 触控 48 为准。
- v1.0 Slash–At 间距 8 作废；改为 12，否则 48 触控重叠。
- 输入栏与上方列表间距不在本次范围。
- 大字体 / 横屏 / 输入法遮挡沿用现有 `maxLines=3` 与 `imePadding()`；本次不新增验收项，但实现不得删掉现有 imePadding。
