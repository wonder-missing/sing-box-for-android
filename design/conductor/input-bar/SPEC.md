# Conductor 输入框（ConductorInputBar）· UI 规格

版本：v1.2 · 2026-11-08  
定稿：主人在 v1.1（D 方案 · 矢量圆钮）基础上确认「贴边面板 + 顶部圆角 + 右上角展开」。实现以本文为准，`mockup.png` 只是示意（左：默认；右：展开 85%）；与本文冲突时以本文为准。  
对接约定见 [`design/README.md`](../../README.md)。

## 0. 相对 v1.1 的变化（实现方先看这里）

1. 输入区从「悬浮卡片」改为**底部贴边面板**：左、右、下三边贴屏幕边缘，去掉卡片外距；**只保留左上、右上 16dp 圆角**，下两角为直角。
2. 默认高度固定 **120dp**：上半 60dp 放文本，下半 60dp 放按钮行，按钮圆心在距面板底 30dp 处。
3. 三颗圆钮到屏幕左 / 右 / 下边的视觉距离都是 **12dp**。
4. 新增右上角**展开 / 收起**按钮（ghost 样式），点开后面板高度 = 可用高度 × **85%**。
5. 新增两个图标 `ic_conductor_expand.xml` / `ic_conductor_collapse.xml`，两个字符串 `conductor_expand` / `conductor_collapse`。

v1.1 中仍然有效的：圆钮 36/48、图标 20、Slash–At 间距 12、颜色 token、发送状态逻辑、RTL、可访问性。

## 1. 目标与非目标

**目标**
- 输入区做成从底部升起的面板：三边贴边，顶部圆角保留边界感（主人明确：去掉圆角会像纸片）。
- 按钮到三条屏幕边等距（12dp）。
- 写长指令时可一键展开到 85%，上方仍露出最近一两条消息。

**非目标 / 必须保持**
- 不改 ViewModel，以及 `onSlash` / `onAt` / `onSend` 语义；展开状态是纯 UI 状态。
- 不为系统手势条额外加底部留白（主人确认无所谓）；但 `imePadding()` 必须保留。
- 颜色全部走 `MaterialTheme.colorScheme`，不写死 hex。

## 2. 落点

- 改：`app/src/main/java/io/nekohasekai/sfa/conductor/ConductorScreen.kt`（`ConductorInputBar` 及其外层布局）
- 新增 drawable：把本目录 `icons/ic_conductor_expand.xml`、`icons/ic_conductor_collapse.xml` 复制到 `app/src/main/res/drawable/`（v1.1 的 slash / at / send 三个保持）
- 新增字符串（中英各一）：`conductor_expand`「展开输入框 / Expand input」、`conductor_collapse`「收起输入框 / Collapse input」；若 v1.1 未加 `conductor_running` 一并补上。

## 3. 结构

```
Surface（面板；宽 = 屏宽；形状 RoundedCornerShape(topStart=16, topEnd=16, bottomStart=0, bottomEnd=0)）
└─ Box（高度：默认 120dp / 展开 = 可用高度 × 0.85）
   ├─ BasicTextField（对齐 TopStart；padding 左 16、上 18、右 60、下 60）
   ├─ ExpandToggle（对齐 TopEnd；48×48 触控，圆心距面板顶 30、距右 30）
   └─ Row 按钮行（对齐 BottomStart；高 60；水平 padding 12/12；内容垂直居中）
      ├─ SlashButton ─ 视觉间距 12 ─ AtButton
      ├─ Spacer(weight=1)
      └─ SendButton | Progress
```

面板放在屏幕最底部，外层不再加水平 16 / 垂直 8 的卡片外距；面板外沿就是屏幕外沿。

## 4. 尺寸（dp）

| 项 | 视觉 | 触控 | 说明 |
|---|---|---|---|
| 面板外距 | 0 | — | 左右下贴屏 |
| 面板圆角 | 上 16，下 0 | — | |
| 默认高度 | 120 | — | 固定；输入超过可见区域时文本区内部滚动 |
| 展开高度 | 可用高度 × 0.85 | — | 「可用高度」见第 6 节 |
| 按钮行 | 高 60，贴面板底 | — | 圆钮圆心距面板底 30 |
| 圆钮 | 直径 36 | 48×48 | 同 v1.1 |
| 圆钮到屏幕左/右/下 | **12** | 6 | 圆心距边 30 |
| Slash ↔ At | 12 | 不相交 | 同 v1.1 |
| 文本区 padding | 左 16、上 18、右 60、下 60 | — | 右 60 给展开钮让位；下 60 给按钮行让位 |
| 展开钮 | 图标 20，无底 | 48×48 | 圆心 (右 30, 上 30)，与发送钮同一竖线 |

**必须精确**：120 默认高、上 16 圆角、三钮距三边 12、展开钮圆心与发送钮圆心在同一 x、85%。  
默认态下提示文字一行即可，单行垂直落在上半区中部（上 padding 18 + bodyLarge 行高 24 → 文字中线约 30，与展开钮圆心齐平）。

## 5. 颜色与字体

| 元素 | token |
|---|---|
| 面板底 | `surfaceContainerHigh` |
| 三钮底 / 图标 / 发送可用态 | 同 v1.1 |
| 展开 / 收起图标 | `onSurfaceVariant`，**无底色**（ghost），比三颗圆钮低一级 |
| 提示文字 / 正文 | 同 v1.1 |

## 6. 状态

| 状态 | 行为 |
|---|---|
| 默认（收起） | 高 120；右上显示 expand 图标 |
| 展开 | 高 = 0.85 × 可用高度；右上换 collapse 图标；按钮行仍贴底；文本区占满中间，可多行输入并内部滚动 |
| 切换 | 点展开钮切换；高度用 `animateDpAsState` 或 `animateContentSize`，约 200ms，标准缓动；图标直接切换，不做旋转动画 |
| 发送成功 | 输入清空后**自动收回默认**（展开是为写长指令，发完回到看消息） |
| 键盘弹出 | 「可用高度」= 状态栏以下、键盘以上的高度（即父容器在 `imePadding()` 之后的高度）；键盘弹出时展开面板跟着缩，按钮始终在键盘上方可见 |
| 返回键 | 展开时按系统返回：先收起，不退出页面（`BackHandler(enabled = expanded)`） |
| 发送 / 运行中 / 有输入 | 同 v1.1 |

展开状态用 `rememberSaveable`，转屏不丢。

## 7. 内容

- 去掉 v1.1 的 `maxLines = 3`：默认态视口只显示约一到两行，超出内部滚动；展开态不限行数。
- RTL：整体镜像（Slash/At 去右侧，发送去左侧，展开钮去左上）；图标几何不镜像。expand/collapse 是对称图形，镜像与否看起来一样，统一不镜像即可。

## 8. 可访问性

- 展开钮 `contentDescription`：收起态读 `conductor_expand`，展开态读 `conductor_collapse`；可加 `stateDescription` 但不强求。
- 触控 48dp；阅读顺序：输入框 → 展开钮 → 命令 → 提及 → 发送。

## 9. 图标

本目录 `icons/` 新增（24 viewport、描边 2、圆头圆角、无填充，tint 着色）：
- expand：`M14 4h6v6M10 20H4v-6M20 4l-6.5 6.5M4 20l6.5-6.5`（右上、左下两个角向外的箭头）
- collapse：`M4 14h6v6M20 10h-6V4M10 14l-6.5 6.5M14 10l6.5-6.5`（同位置，箭头向内）
- 文件：`ic_expand.svg` / `ic_collapse.svg` / `ic_conductor_expand.xml` / `ic_conductor_collapse.xml`

## 10. 验收

1. 面板左右下三边贴屏，上两角 16dp 圆角，下两角直角；无卡片外距。
2. 默认高 120；三颗圆钮视觉边到屏幕左 / 右 / 下均为 12。
3. 展开钮与发送钮圆心在同一竖线，距面板顶 30；无底色，图标 onSurfaceVariant。
4. 点展开：面板升到可用高度 85%，上方仍能看到最近消息；图标变 collapse；再点回 120。
5. 键盘弹出时展开：面板不被键盘盖住，按钮在键盘上方。
6. 发送后自动收回；展开时系统返回先收起。
7. 文本不压到展开钮和按钮行（右 60、下 60 留白生效）。
8. 深浅主题、RTL 各看一眼；v1.1 的 10 条验收仍通过（第 3 条改为相对屏幕边）。

## 11. 已知取舍

- 曾试过去掉顶部圆角，主人否决（像纸片，没有边界感），保留 16。
- 85% 而非 100%：保留上下文，且留出视觉上「这是一块可收起的面板」的提示。
- 不处理手势条：按钮距屏底 12 在手势导航机型上与手势条较近，主人确认可接受。
- v1.1 的 `maxLines = 3` 与卡片外距 16/8、卡片圆角 12 作废。
