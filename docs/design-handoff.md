# 设计稿与工程对接

Lyra 的设计稿在 [`design/`](../design/README.md)。这份文档记录本项目的 UI 技术栈、token 映射和交接约定，供下一轮设计减少来回。

## 技术栈

- Conductor 与大部分新界面用 Jetpack Compose + Material 3。
- 颜色全部从 `MaterialTheme.colorScheme` 取；字体用 `MaterialTheme.typography` 的角色，不直接写 `sp`。
- 尺寸用 `dp`，间距沿用项目标准：`4 / 8 / 12 / 16 / 24dp`；圆角 `8 / 12 / 16dp`；正文 `14–16sp`。
- 触控目标项目标准是 `48dp`。设计稿如果给更小的视觉尺寸，最好同时说明触控区怎么处理。
- 图标给 SVG 原稿 + 可直接放进 `res/drawable/` 的 VectorDrawable XML；颜色留黑，由 Compose `Icon` 的 tint 决定。
- 设计稿里的 hex 色号、px 数值可以写，但请标注「允许映射到最接近的 Material 3 token」，工程侧会按主题变量落地，不写死色值。

## 这次 Conductor 输入框规范的评估

做得好的地方：

- 明确「实现以 SPEC 为准，mockup 只是示意」，避免截图和文字冲突。
- 明确改了哪些文件、哪些不改（字符串、ViewModel、行为都不动）。
- 结构、尺寸、颜色、状态、验收条件齐全，能逐条检查。
- 颜色直接用 Material 3 token（`surfaceContainerHigh`、`surfaceContainerHighest`、`primary`、`onPrimary`），不是 hex。
- 图标同时给 SVG 和 VectorDrawable，viewport、描边、圆头圆角都写清楚。
- 验收条件包含「深浅色都看一眼」「代码里不写死色值」，方向正确。

这次遇到的摩擦，下次可以提前写清：

1. **视觉尺寸与触控尺寸分开写**：v1.0 写「点击区就是 36 圆本身」，但项目触控标准是 48dp。Lyra 在 v1.1 已改成「视觉 36 / 触控 48」，并明确 Slash–At 视觉间距改为 12dp。实现时按钮行布局 padding 从 12dp 调到 6dp，让 48dp 热区外扩 6dp 后，36dp 视觉圆仍距卡片边 12dp。这个换算值得保留在以后的规范里。
2. **空白输入算不算空**：规范写「输入为空」，实现沿用 `isNotBlank()`，全空格视为空。下次可直接写「trim 后为空」。
3. **运行中状态的可访问性没定义**：发送钮变成转圈后，屏幕阅读器读什么没有说；实现目前只保留进度指示器。
4. **没有覆盖 RTL、系统大字体、横屏、输入法遮挡**：实现沿用现有 `maxLines = 3` 和 `imePadding()`，但规范没给这些场景的验收标准。
5. **没有按压态、涟漪颜色、长按行为**：实现用 Material 默认 ripple；如果设计有特殊要求，需要单独写。
6. **图标只给路径，没有 20dp 下的视觉检查**：描边在 20dp 下是否还清晰、视觉重量是否和文字协调，需要真机看一眼。
7. **没有说明与现有组件的层级关系**：比如输入栏和上方命令建议列表、消息流的间距是否要一起调；这次按现有结构处理，没有冲突。

## 下一份 SPEC 的模板

1. **目标与非目标**：改什么、不改什么、哪些行为必须保持。
2. **落点**：组件名、文件路径；新增资源放哪个目录。
3. **结构**：`Column` / `Row` / `Box` 的层级，用文字或简单树状图。
4. **尺寸**：视觉尺寸、触控尺寸、间距、圆角，统一 dp；区分「必须精确」和「可按工程规范微调」。
5. **颜色与字体**：优先 Material 3 token；没有对应 token 时给 hex，并注明允许映射。
6. **状态**：default / pressed / focused / disabled / loading / error / empty，以及深色、浅色。
7. **内容**：字符串 key、最大行数、截断方式、RTL。
8. **可访问性**：`contentDescription`、触控目标、屏幕阅读器顺序、进度语义。
9. **图标**：SVG + VectorDrawable，注明 viewport、描边、圆头圆角。
10. **验收**：可逐条勾选的行为与视觉检查。
11. **已知取舍**：哪些值可以按项目规范调整，哪些必须严格还原。

## 本次实现的映射

- 卡片 `surfaceContainerHigh`；圆钮 `surfaceContainerHighest`；发送可用 `primary` / `onPrimary`；命令与提及图标 `onSurface`；发送不可用 `onSurfaceVariant`。全部来自 `MaterialTheme.colorScheme`。
- 36dp 视觉圆钮、48dp 触控、20dp 图标、文本区 16/16/12dp、按钮行布局 6/6/12dp（48dp 热区外扩 6dp 后视觉仍是 12dp）、Slash–At 视觉间距 12dp（两颗 48dp 热区相邻）、圆角 12dp。
- 发送可用性用 `input.isNotBlank()`，全空格算空。
- 运行中新增 `conductor_running`（中英各一），作为进度指示器的 `contentDescription`。
- 字符串沿用 `conductor_commands` / `conductor_mention` / `conductor_send` / `conductor_input_hint`。
- 图标直接使用 `design/conductor/input-bar/icons/` 里的 VectorDrawable 副本；XML 没有 `autoMirrored`，RTL 下整行镜像但图标几何不镜像。
