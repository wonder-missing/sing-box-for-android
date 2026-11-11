# design/

Lyra 维护的设计稿与规格。每个组件一个目录，里面放：

- `SPEC.md`：**实现以它为准**。尺寸、颜色 token、结构、状态、改哪些文件都写成文字，实现方不需要看图。
- `icons/`：SVG 原稿和可直接放进 `res/drawable/` 的 VectorDrawable XML。
- `mockup.png`：像素示意，仅供人看，和 SPEC 冲突时以 SPEC 为准。

## 工程约束（设计前先看）

- Conductor 与大部分新界面用 Jetpack Compose + Material 3。
- 颜色全部从 `MaterialTheme.colorScheme` 取；字体用 `MaterialTheme.typography` 的角色，不直接写 `sp`。
- 尺寸用 `dp`，间距沿用项目标准：`4 / 8 / 12 / 16 / 24dp`；圆角 `8 / 12 / 16dp`；正文 `14–16sp`。
- 触控目标项目标准是 `48dp`。视觉尺寸可以更小，但规范里要把「视觉尺寸」和「触控尺寸」分开写；两者的换算由工程侧处理。
- 设计稿里的 hex 色号、px 数值可以写，但请标注「允许映射到最接近的 Material 3 token」，工程侧会按主题变量落地，不写死色值。
- 设计稿要说明改哪些文件、哪些不改；行为、字符串、ViewModel 不需要改时请明确写出来。
- 深色 / 浅色、RTL、系统大字体、横屏、输入法遮挡如果不在本次范围，也请写一句，避免实现方猜。

## 图标：先查现成库

项目内置 Google Material Icons Extended（Compose 版），分类和搜索在 [`../app/src/main/java/io/nekohasekai/sfa/compose/util/icons/MaterialIconsLibrary.kt`](../app/src/main/java/io/nekohasekai/sfa/compose/util/icons/MaterialIconsLibrary.kt)，profile 图标选择器用的就是它。

设计新模块前先查库里有没有现成 icon。能复用就复用，既省一次绘制，也避免同一功能出现两套风格。确定要自定义时，再给 SVG + VectorDrawable，并写明为什么现成库不够。自定义图标统一 24 viewport、描边 2、圆头圆角、无填充，颜色由 Compose `Icon` 的 tint 决定。

## SPEC 模板

1. **目标与非目标**：改什么、不改什么、哪些行为必须保持。
2. **落点**：组件名、文件路径；新增资源放哪个目录。
3. **结构**：`Column` / `Row` / `Box` 的层级，用文字或简单树状图；写清哪些容器有 padding。
4. **尺寸**：视觉尺寸、触控尺寸、间距、圆角，统一 dp；区分「必须精确」和「可按工程规范微调」。
5. **颜色与字体**：优先 Material 3 token；没有对应 token 时给 hex，并注明允许映射。
6. **状态**：default / pressed / focused / disabled / loading / error / empty，以及深色、浅色。
7. **内容**：字符串 key、最大行数、截断方式、RTL。
8. **可访问性**：`contentDescription`、触控目标、屏幕阅读器顺序、进度语义。
9. **图标**：优先复用 Material 库；自定义时给 SVG + VectorDrawable，注明 viewport、描边。
10. **验收**：可逐条勾选的行为与视觉检查。
11. **已知取舍**：哪些值可以按项目规范调整，哪些必须严格还原。

## 验收检查清单

- 视觉尺寸与触控尺寸分别成立，触控目标不小于 48dp。
- 深浅色两套主题、RTL、系统大字体、横屏、输入法遮挡都有结论。
- 按压态、涟漪、禁用态、加载态、空态、错误态有定义。
- `contentDescription`、阅读顺序、进度语义完整。
- 20dp 左右的小图标在真机上能看清，描边不过细。
- 组件与上方消息流、建议列表、相邻按钮的间距有说明。

## 已实现的映射（供后续保持一致）

- 输入栏 v1.2：底部贴边面板，默认 120dp，展开为可用高度 85%，上两角 16dp、下两角直角；发送后自动收回，系统返回先收起。
- 输入栏文本区 padding 左 16、上 18、右 60、下 60；按钮行高 60，布局 padding 6dp（48dp 热区外扩 6dp 后视觉圆仍距边 12dp）；展开钮和发送钮圆心距右/底 30dp。
- 颜色：面板 `surfaceContainerHigh`；圆钮 `surfaceContainerHighest`；发送可用 `primary` / `onPrimary`；命令与提及图标 `onSurface`；发送不可用 `onSurfaceVariant`；展开/收起图标 `onSurfaceVariant`。
- 圆钮视觉 36dp / 触控 48dp，图标 20dp；发送可用性按 `input.trim()` 是否为空判断。
- 输入栏行为补充（2026-11-09）：默认态从 120dp 起随文本换行自动长高，最多 5 行；删除文本自动回缩到 120dp。展开态仍为 85%。键盘 inset 变化时以及变化结束后 300ms 内，面板高度一律 `snap`；IME 稳定后，展开/收起与文本增减才走 200ms 高度动画。面板高度取整到物理像素，避免亚像素抖动。placeholder 作为输入框的兄弟节点绘制，不放进 `BasicTextField` 的 decoration，避免面板缩放时跟着文本框内部重排而发虚。
- 图标直接使用本目录 `icons/` 里的 VectorDrawable 副本；RTL 下整行镜像，图标几何不镜像。

## 索引

| 目录 | 内容 | 状态 |
|---|---|---|
| `conductor/input-bar/` | Conductor 底部输入框：`/` 命令、`@` 提及、发送三颗圆钮 | v1.2 已实现，真机验收中 |
| `app-icon/` | WonderSing 启动图标 SVG 原稿（A7 定稿） | 已接入，真机验收中 |
