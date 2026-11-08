# Conductor 输入框（ConductorInputBar）· UI 规格

定稿：2026-11-08，主人选定「D 方案 · 矢量图标版」。实现以本文为准，`mockup.png` 只是示意。

## 要解决的问题

现在 `/` 和 `@` 是用 `Text` 画的字符，没有底色，看起来像输入框里飘着的文字，不像按钮；字符的居中和字形也随设备字体变化。改为：**三颗同款圆钮 + 矢量图标**。

## 改动范围

- `app/src/main/java/io/nekohasekai/sfa/conductor/ConductorScreen.kt`：`ConductorInputBar`、`InputGlyphButton`
- 新增 drawable（直接复制本目录 `icons/*.xml` 到 `app/src/main/res/drawable/`）：
  - `ic_conductor_slash.xml`（命令）
  - `ic_conductor_at.xml`（提及）
  - `ic_conductor_send.xml`（发送，替换 `Icons.Default.ArrowUpward`）
- 不改字符串资源，沿用 `conductor_commands` / `conductor_mention` / `conductor_send` / `conductor_input_hint`。
- 不改 ViewModel 和 `onSlash` / `onAt` / `onSend` 的行为。

## 布局（单位 dp）

输入卡片 `Surface`：
- 外边距：水平 16，垂直 8（不变）
- 圆角 12，颜色 `colorScheme.surfaceContainerHigh`（不变）

卡片内部改为两套水平内边距：
- 文本输入区 `BasicTextField`：左 16、右 16，上 12
- 按钮行：左 12、右 12，下 12
- 文本区与按钮行之间间距 8
- 实现建议：`Column` 本身不加水平 padding，分别给 `BasicTextField` 和按钮 `Row` 加。

按钮行 `Row`（`verticalAlignment = CenterVertically`）从左到右：
1. 命令钮（slash）
2. 间距 8
3. 提及钮（at）
4. `Spacer(Modifier.weight(1f))`
5. 发送钮

结果：左侧命令钮外沿距卡片左边 12，发送钮外沿距卡片右边 12，两侧对称。

## 圆钮（三颗统一）

- 尺寸 36 × 36，`CircleShape`
- 图标 20 × 20，用 `Icon(painterResource(...))`，居中
- 点击区就是 36 圆本身，`clickable` 加在 `clip(CircleShape)` 之后，涟漪为圆形
- `contentDescription` 用现有字符串

| 钮 | 底色 | 图标 tint |
|---|---|---|
| 命令 `/` | `surfaceContainerHighest` | `onSurface` |
| 提及 `@` | `surfaceContainerHighest` | `onSurface` |
| 发送（输入为空） | `surfaceContainerHighest` | `onSurfaceVariant` |
| 发送（有输入） | `primary` | `onPrimary` |
| 发送（运行中） | 不画圆底，原位置显示 `CircularProgressIndicator` 20dp、strokeWidth 2 | 不变 |

命令钮和提及钮始终可点，不随输入内容禁用。

## 图标规格

三个图标同一套：24 viewport，`strokeWidth 2`，圆头 `round`，圆角接合 `round`，无填充，颜色全靠 tint。路径（SVG 写法，与 XML 等价）：

- slash：`M15 4.5 9 19.5`
- at：内圆 `circle cx=12 cy=12 r=3.5`；外弧 `M15.5 12v1.5a2.5 2.5 0 0 0 5 0V12a8.5 8.5 0 1 0-3.4 6.8`
- send：`M12 19V5M6 11l6-6 6 6`

## 删除

- `InputGlyphButton` 里的 `Text` 字符方案（包括 `FontFamily.Monospace` 那段）整段删掉，改为接收 `@DrawableRes icon: Int`。

## 验收

1. 三颗圆钮直径一致（36），图标视觉居中，不随系统字体变化。
2. 命令钮左外沿到卡片左边 = 发送钮右外沿到卡片右边 = 12。
3. 命令钮和提及钮间距 8。
4. 提示文字左边起点 16（比按钮略往里，属预期）。
5. 空输入时发送钮为灰圆灰箭头；输入后变 primary 底 + onPrimary 箭头；运行中为转圈。
6. 深色和浅色主题都要看一眼，颜色全部来自 `MaterialTheme.colorScheme`，代码里不写死色值。
