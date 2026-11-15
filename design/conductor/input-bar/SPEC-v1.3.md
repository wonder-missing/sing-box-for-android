# Conductor 输入框（ConductorInputBar）· UI 规格

版本：v1.3 · 2026-11-16  
定稿：主人确认三按钮布局（去掉 `@`，`/` 与 `+` 左分组，发送/停止右侧固定，状态回到同一底栏）。  
**实现以本文为准。** 同目录 `three-buttons-preview.png`（及可选的 `three-buttons-preview.svg`）只是布局归档示意，不是图标、字体或颜色 token 的真相来源。文稿与图冲突时以本文为准。  
上一版已实现基线仍见 [`SPEC.md`](SPEC.md)（v1.2）及 [`design/README.md`](../../README.md) 中 2026-11-09 的行为补充。对接约定见 `design/README.md` 的 SPEC 模板（11 节）。

## 0. 相对现行实现的变化（实现方先看这里）

1. **去掉 `@`（提及）**：输入栏不再绘制该按钮，也不保留禁用热区或无障碍节点。不要求删除 `ic_conductor_at` / `conductor_mention` 等既有资源。
2. **底栏改为「左准备、右执行」**：左侧 `/`（命令）然后 `+`（添加图片，现有行为），右侧固定发送 / 停止。两态坐标不得跳动。
3. **状态回到同一底栏**，右对齐在发送/停止之前；不再做独立状态行，面板默认最小高度回到 **120dp** 量级（见第 4 节，不是硬裁切）。
4. **空闲用量**可见格式改为恰好 `17.4k/1M` 这种 `used/limit`（动态真实用量，沿用已有 `formatTokenCount`），**不要**百分号，**不要** `ctx` / 「上下文」前缀。
5. **进行中**在同一固定槽位显示小转圈 + 短英文：`Waiting…` / `Thinking…` / `Running…`；隐藏用量。底栏不展示 provider 长句、工具名或秒数。
6. **本次不新绘图标、不导出新 asset、不引入新的国际化流程。** 界面文案只用英语。

## 1. 目标与非目标

**目标**
- 底栏三枚圆钮分边，中间给短状态，窄屏与大字体下按钮不缩小、不重叠，状态单行省略。
- 保持 v1.2 已落地的贴边面板、展开 85%、键盘 inset、上两角 16dp、发送后收起 / 返回先收起，以及 2026-11-09 起从 120dp 随文本长高（最多 5 行）的行为。

**非目标 / 必须保持**
- 不改 ViewModel 回合状态机，不为底栏编造新的后端状态或状态切换。
- 不改草稿、附件选择/移除/导入中、忙时草稿与附件行为；停止沿用现有 `onStop` / `R.string.stop` / `ic_stop_24` 与现有停止语义（含错误、取消）。
- 空输入发送：沿用现有 `input.trim()` 后为空则不可发送；**有有效附件时仍可发送**（`pendingImage != null`）。导入图片过程中发送仍不可用，与现状一致。
- 不改展开面板、`imePadding()`、消息流与输入栏关系；不得回退成给系统导航条额外垫底（面板默认 120dp **不含** navigation inset）。
- 颜色走 `MaterialTheme.colorScheme`，不把草稿截图像素色写进生产代码。停止钮现有 `errorContainer` / `onErrorContainer` 风格不变。
- 输入/提示字体沿用现有 typography token（`bodyLarge`），**不要**为了临摹草稿里的 14px 提示而缩小字号或改 token。
- 不为这次强制新增详情弹窗；长状态若消息流、工具卡片等现有位置已能看到，保持即可。

## 2. 落点

- 改：`app/src/main/java/io/nekohasekai/sfa/conductor/ConductorScreen.kt`（主要是 `ConductorInputBar`、底栏 `Row`、`TurnStatus` 的可见文案映射）。若可见格式走 string 资源，只改/补英语 `app/src/main/res/values/strings.xml`。
- 用量可见 string 建议改为两个已格式化的量：`%1$s/%2$s`（例 `17.4k/1M`）。Talkback 可用英语描述（例如 Context usage: 17.4k of 1M tokens），不必把百分数当作可见主文案。已有 `values-zh-*` 等 locale 若仍带「上下文」或 `ctx` / `%%`，改为同一英语可见格式以免残留，**不要**借此新增语言包或翻译流程。
- 短状态若使用资源：`Waiting…` / `Thinking…` / `Running…`（含省略号字符 `…`）。可新增英语 key，不必向其他 locale 补译。
- **不改、不新增** `design/conductor/input-bar/icons/`，不往 `res/drawable/` 复制新图标。继续用已有 `ic_conductor_slash`、`ic_conductor_add`、`ic_conductor_send`、`ic_conductor_expand` / `ic_conductor_collapse`、`ic_stop_24`。转圈用现有 `CircularProgressIndicator`。
- 不改：命令/提及/附件/停止的业务语义（提及只是入口从输入栏消失），不改本次范围外的文件。

## 3. 结构

```
Surface（宽 = 屏宽；RoundedCornerShape(topStart=16, topEnd=16, bottom=0)；左右下贴屏）
└─ Box（高：默认 ≥ 120dp，随行数/行高增长；展开 = 可用高度 × 0.85）
   ├─ 提示 Text（仅空输入；与输入框兄弟绘制，不进 BasicTextField decoration）
   ├─ BasicTextField（TopStart）
   ├─ ExpandToggle（TopEnd，ghost，48×48）
   └─ Row 底栏（BottomStart，fillMaxWidth，水平 padding 6）
      ├─ SlashButton          // 现有 onSlash
      ├─ AddImageButton       // 现有 onAddImage；导入中用同一 48 槽里的现有小进度，不要另占位导致右钮位移
      ├─ PendingImageChip     // 现有芯片与移除行为；放在左组之后、状态槽之前
      └─ Box 状态槽（Modifier.weight(1f, fill=true)，minWidth = 0）
         │    margin start ≥ 8、end = 10；内容水平 End、垂直居中
         │    空闲：用量 used/limit
         │    进行中：spinner + 短状态；隐藏用量
         └─ SendButton | StopButton   // 同一槽，isRunning 时换停止
```

左组准备输入，右钮执行（发送或停止）。中间状态槽弹性伸缩，控件本身宽度固定。

## 4. 尺寸（dp）

| 项 | 视觉 | 触控 | 说明 |
|---|---|---|---|
| 面板外距 | 0 | — | 左右下贴屏；高度不含系统导航 inset |
| 面板圆角 | 上 16，下 0 | — | 与 v1.2 相同 |
| 默认高度 | **最小 120** | — | 不是硬裁切：随 `bodyLarge` 行高与行数增高，默认最多 5 行，删文回缩到不少于 120。大字体时升高面板，禁止裁字、禁止缩小圆钮 |
| 展开高度 | 可用高度 × 0.85 | — | 「可用高度」= `imePadding()` 之后的父容器高度，与 v1.2 相同 |
| 文本区内边距 | 左 **16**、上 **18**、右避开展开热区、下避开底栏 | — | 见下方「不要照搬草稿 12/14」 |
| 展开钮 | 图标 20，无底 | 48×48 | 圆心距顶 / 右 30，与发送钮同一竖线 |
| 底栏水平 padding | 6 | — | 48 热区外扩 6 后，36 视觉圆距左右边 **12** |
| 底栏高度 / 底边距 | 保持现有 12 视觉边距 | — | 见下方「底栏高度」 |
| 圆钮 | 直径 36 | **48×48** | 图标 20，居中；三钮相同 |
| Slash ↔ Plus 视觉间距 | 12（圆心距 48） | 热区相接不相交 | 与原 Slash–At 间距规则相同 |
| 状态槽 | 弹性 | 非按钮 | 右缘对齐在发送视觉圆左缘之前 **16**（即热区左缘之前 **10**） |

**360dp 宽参考（草稿画板，只说明相对关系，不是写死坐标）：**  
圆心 `/` = 30、`+` = 78、发送/停止 = 330。热区 x：`6–54`、`54–102`、`306–354`，不相交，左右外边距 6。状态右缘 296：距发送热区左缘 10、距发送视觉左缘 312 为 16。  
实现请改成**响应式**：底栏 `padding(horizontal = 6.dp)` + 固定 48 热区，中间 `weight` 槽 `padding(start ≥ 8.dp, end = 10.dp)`，文字 `TextAlign.End`。不要按 360 写死 `30/78/330`。

**底栏高度（必须说明的取舍）：** 草稿为了把独立状态行拿掉，把底栏画成 48 + 4 底距。现行实现是底栏 60，让 36 圆距底边也是 12，与左右 12 齐。**以现行 12dp 底边视觉距为准**（与「两侧贴齐、三边等距」兼容），不要为了临摹草稿把圆钮沉到底边或改成 4dp。48 热区垂直居中在该底栏内。

**输入区尺寸以本文为准，草稿 14px / 顶距 12 只是示意：** 提示与正文继续用 `MaterialTheme.typography.bodyLarge`（现行如此），不要改成 14sp。顶距继续用现行 **18**，以便第一行与展开钮视觉对齐（v1.2：18 + `bodyLarge` 行高 24，中线约 30）。若改成草稿的 12 又保留 `bodyLarge`，会对齐失真甚至显得挤。右 padding 按「输入内容实际宽度 = 面板宽 − 展开 48 热区及其边距」计算（现行 `end = 60.dp` 可保留）。提示可换行，不得压到展开钮。

**必须精确：** 去掉 `@` 及热区；左 `/`+`+`、右发送/停止不跳动；36/48；水平 padding 6；状态槽 end 距发送热区 10；默认最小 120；上角 16；展开 85%；token 色与现有 typography。  
**可按工程规范微调：** 状态字号取最接近的 typography 角色（见第 5 节）；状态槽 start 间距 ≥ 8；芯片与状态并存时的省略优先级。

## 5. 颜色与字体

全部来自 `MaterialTheme.colorScheme` / `MaterialTheme.typography`，代码不写死 hex 或 `14.sp`。草稿里的 `#27282C` 等只是示意。

| 元素 | token |
|---|---|
| 面板底 | `surfaceContainerHigh` |
| `/`、`+` 圆底 / 图标 | `surfaceContainerHighest` / `onSurface` |
| 发送空态圆底 / 图标 | `surfaceContainerHighest` / `onSurfaceVariant`，不可点 |
| 发送可用 | `primary` / `onPrimary` |
| 停止圆底 / 图标 | 沿用现行 `errorContainer` / `onErrorContainer` + `ic_stop_24`，不改 |
| 展开 / 收起 | `onSurfaceVariant`，无底 |
| 提示 | `bodyLarge` + `onSurfaceVariant`，alpha ≈ 0.7（沿用现状） |
| 输入正文 | `bodyLarge` + `onSurface` |
| 用量 / 短状态 | typography 用现成角色：可沿用底栏已有 `labelSmall`，若大字体下偏弱可用 `labelLarge`；颜色 `onSurfaceVariant`。用量达到现行预警阈值（80%）时仍可用 `error`，但可见文案仍是 `used/limit`，不画百分号 |

深色 / 浅色都只靠 token 验收。

## 6. 状态

| 状态 | 行为 |
|---|---|
| 空闲、无进行中回合 | 状态槽显示用量 `used/limit`（限 `contextLimitTokens > 0`，与现状一致）。发送在 trim 后有正文或已有有效附件时可点 |
| 等待 / 思考 / 执行 | 状态槽改为小转圈 + 对应短文案，**隐藏用量**。发送槽改为停止，位置与空闲发送重合。草稿与附件保持可编辑/可移除，行为与现状一致 |
| 导入图片中 | `+` 槽显示现有小进度，发送不可点；不要把停止/发送挤走 |
| 按压 | Material 默认 ripple；无定制色、无长按菜单 |
| 展开 / 收起 / 键盘 / 返回 / 发送成功自动收起 | 与 v1.2 及 2026-11-09 补充相同；IME inset 变化中及结束后 300ms 内高度 `snap`，稳定后再做 200ms 高度动画；高度取整到物理像素 |
| 错误 / 取消 / 停止 | 沿用现有语义与提示位置，不在底栏新发明错误态样式 |
| 深色 / 浅色 | 只靠 token |

**短状态映射（只影响底栏可见文案，不发明新后端状态）：** 用现有 `turnStatus` / 回合信息归到下面三句之一，不要把 `Waiting for the provider`、`Running {tool}`、`Receiving response` 等长句或工具名放进底栏。建议（可按现有字段微调，但不要新增状态机）：

- 等待 provider、等待确认、准备工具等等待类 → `Waiting…`
- 已有思考/模型产出类（例如 Receiving response，或消息流里已在用的 Thinking）→ `Thinking…`
- 正在跑工具等执行类（例如 `Running …`）→ `Running…`

无法区分时宁可少分、不要编造。秒数（现行 `TurnStatus` 的 ` · 3s`）不要再放在底栏。

## 7. 内容 / RTL

- 可见界面只用英语。草稿图外侧「回复结束」「等待响应」是演示标签，**不是**应用文案。
- 提示沿用 `conductor_input_hint`：`Ask anything, or type / for commands`。可按实际宽度换行，单行放得下就不必折。
- 用量可见：`{formatTokenCount(used)}/{formatTokenCount(limit)}`，例 `17.4k/1M`。`formatTokenCount` 已存在，沿用。
- 短状态：`Waiting…`、`Thinking…`、`Running…`（U+2026）。单行，`maxLines = 1`，`overflow = Ellipsis`，`Modifier.weight` 槽 `minWidth = 0`，禁止撑破或缩小两侧按钮。
- RTL：整行镜像（左组到视觉右侧，发送到视觉左侧，展开到左上，状态仍在发送内侧并靠发送对齐）。**图标几何不镜像。**

## 8. 可访问性

- 去掉提及按钮后，不再暴露 `conductor_mention` 节点。
- 现有描述继续用：`conductor_commands`、`conductor_image_add`、`conductor_send`、`stop`、`conductor_expand` / `conductor_collapse`。导入中沿用现有进度语义。
- 用量：可见短格式 + 英语 `contentDescription`（可读出 used/limit）。
- 进行中：转圈与短状态要有英语进度/状态语义（`Waiting…` / `Thinking…` / `Running…`），不要把长句读给用户当底栏标签。
- 触控目标：圆钮与展开均为 48dp；状态文本本身不是新的 48 按钮。
- 阅读/焦点顺序：输入框 → 展开/收起 → 命令 → 添加图片（→ 若有附件芯片）→ 状态（若有）→ 发送或停止。

## 9. 图标

本次**无新图标**。全部复用已有 drawable / 指示器，不要在 `design/.../icons` 或 `res/drawable` 再导出一份。

| 用途 | 现有资源 |
|---|---|
| 命令 `/` | `ic_conductor_slash` |
| 添加图片 `+` | `ic_conductor_add` |
| 发送 | `ic_conductor_send` |
| 停止 | `ic_stop_24` |
| 展开 / 收起 | `ic_conductor_expand` / `ic_conductor_collapse` |
| 等待转圈 | 现有 `CircularProgressIndicator`（现行约 10dp / stroke 1.5，可略调以与文字对齐，不必新 asset） |

自定义图标若被复用，仍是 24 viewport、描边 2、圆头圆角、无填充、20dp 绘制、tint 着色——这是既有风格，不是新设计。`icons/ic_at*` 仅作为 v1.1/v1.2 归档保留。

## 10. 验收

1. 输入栏没有 `@`，也没有空白热区或 TalkBack「Mention」节点。
2. 空闲：左 `/`、`+`，右灰色或可点发送（规则见上），状态槽右对齐 `17.4k/1M` 这类真实用量；无 `ctx`、无「上下文」、无可见百分号。有/无附件都成立，按钮位置不变。
3. 等待中：同一槽位变为转圈 + `Waiting…`，用量隐藏；右侧为停止且不位移。有/无附件都成立。思考/执行时分别变为 `Thinking…` / `Running…`，按钮仍不跳。
4. 空输入且无附件：发送禁用；仅有有效附件或非空正文：发送可用。忙时停止语义、错误/取消与现状一致；草稿和附件不丢失、不无故清空。
5. 320dp 与 360dp 宽、字体缩放 1.3 与 2.0：按钮 36/48 不缩小、热区不相交；状态单行省略；面板从 120 起长高而不裁切输入或状态；展开仍为可用高度 85%，键盘弹出时按钮在键盘上方，无 navigation inset 回退。
6. 提示与正文不与展开钮重叠；左右视觉圆距边 12。
7. TalkBack 为英语标签，阅读顺序正确；深浅色各看一眼；无新 drawable、无新 locale 目录。
8. 生产代码无草稿 hex；停止钮颜色仍是现有 error token。

## 11. 已知取舍

- **文稿规范、图片归档：** 实现只跟本文。PNG/SVG 用来对照「左分组、右执行、状态回底栏」的空间关系，其中 14px 字、顶距 12、底栏 48+4、以及截图 hex 都不是生产标准。
- **120dp 是默认下限** 不是视口铁盖：大字体和多行应长高（已有最多 5 行），避免为了「看起来像草稿」而裁切。
- 底栏保持距底 12 的视觉边距，不采用草稿的 4dp 底距。
- 短状态是底栏的显示映射，不是新的业务状态机；长细节留在现有消息流/工具卡片。
- 不处理新的 i18n；本面英语。
- v1.2 的 `@` 三钮描述、以及「运行中发送位只出进度、不可停止」等已被后续实现取代的句子，以本文和现行停止/附件行为为准。
