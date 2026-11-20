# Conductor 消息流验收清单

2026-11-20。按 `wonder.A6` 的改动整理；每条都可以在真机上直接对照。

## 思考块

准备：让 Conductor 输出一段两段以上、足够长的 `reasoning`，或使用已有长思考记录。

- [ ] 收起态是恰好两行：没有标题、没有箭头或按钮；整张卡可点。
- [ ] 流式输出时小窗内部滚动，始终看到最新内容，外部高度不变。
- [ ] 结束后停在最后两行，不改写为摘要。
- [ ] 点卡片在原位置展开，不打开新页面或对话框。
- [ ] 展开态顶部有 `Thinking` / `思考` 小字和右侧收起 chevron；控制行高度与收起态两行窗口一致（默认 36dp），下面有内缩分隔线；正文占满宽度、可选中。
- [ ] 点展开态顶部控制行收起，回到两行小窗；控制行按下时没有整行高亮。
- [ ] 展开/收起有约 180ms 的高度动画；流式输出中操作时是即时变化。
- [ ] 在列表中段展开或收起，列表不会自动滚到最新消息，卡顶保持原位；下方内容随动画平移。
- [ ] 流式输出中收起，小窗恢复内部滚动；已结束时停留最后两行。
- [ ] 系统大字号下两行窗口仍按两行高度放大，不裁切文字。

## 行内代码

- [ ] 正文里的行内代码是等宽、主题强调色、透明底，没有填充胶囊或描边。
- [ ] 与正文相比字号接近，不把行高撑高；换深浅色或动态取色后，颜色跟着主题走。
- [ ] 点按行内代码后粘贴内容与原 token 完全一致；Android 13+ 使用系统剪贴板提示，不再弹会遮挡消息的 Toast；旧版本才显示短 Toast。
- [ ] 长按行内代码仍能进入文本选择。
- [ ] 较长的标识符优先整词移到下一行；只有比一整行还宽时才会词内断行，不横向溢出。
- [ ] 表格单元格里的行内代码可长按选择复制（该处没有点按复制）。
- [ ] Transcript Viewer 打开时默认 Rich（对话转录）；Rich 与消息流使用同一套行内代码样式，嵌套粗体+行内代码（例如 ``**`Taildrop/` ...**``）在 Rich 下不应出现字面 `**`。
- [ ] 切到 Raw 时不应出现行间叠字；对话转录的 Raw 是纯文本源文（保留行号/搜索/自动换行），`**`/反引号会按源文显示，但没有 Markdown 语法高亮。

## 本地资源链接

准备：让 Conductor 写一个资源，再只输出一条链接消息：

```text
先用 file_write 写 conductor/resources/check.md，内容为 "# Check\n\nlocal link works."，
然后只输出一条消息：[打开 check.md](conductor/resources/check.md)。不要调用其他工具。
```

- [ ] 点按链接弹出只读查看器，不跳到外部 App、不执行内容。
- [ ] 查看器显示文件名、完整路径、内容；内容可选中。
- [ ] 点复制后粘贴内容与文件一致。
- [ ] 关闭返回消息流，消息内容和滚动位置不变。
- [ ] 指向 `conductor/resources/` 以外或 `file://` 的链接不会打开查看器。
- [ ] 二进制资源显示不可预览；超过 256 KB 的资源显示截断提示。

## file_grep

准备：在工作区写一个含 `alpha`、`beta` 两行的文本文件。

- [ ] `file_grep(query="alpha|beta")` 不开 `regex`：零匹配，输出含 `hint`，提示 `regex=true`。
- [ ] 同一 query 开 `regex=true`：返回两行匹配。
- [ ] 匹配到超长行时，默认每行最多 400 字符；被截行带 `lineTruncated=true`，整体 `truncated=true`。
- [ ] 传 `max_line_chars` 可改每行上限，超过 2000 按 2000 处理。

## ask_user

- [ ] 正常回答：输出 `answered=true`，`selected` 是所选标签。
- [ ] 用户划掉问题卡片：输出 `cancelled=true`、`reason=dismissed`。
- [ ] 问题等待期间点 Stop：输出 `cancelled=true`、`reason=stopped`。
- [ ] 10 分钟不回答：输出 `cancelled=true`、`reason=timeout`。
- [ ] 前一题未结束时再问：输出 `cancelled=true`、`reason=busy`。

## Transcript / API trace viewer

- [ ] Rich 与 Raw 滑动时右侧出现垂直滚动条；松手后约 1.8 秒淡出，能看到当前阅读位置。
- [ ] 点放大镜打开搜索：Rich 高亮所有命中并显示 `当前/总数`，上下按钮跳块；Raw 用编辑器搜索高亮，计数和上下跳转正常；切换 Rich/Raw 后搜索仍生效。
- [ ] 点目录图标，列出 Markdown 标题；Rich 跳到对应 block，Raw 跳到对应行；没有标题时显示空状态。
- [ ] API trace 的 `### Response body` 是标准 `chat.completion` JSON：流式 `chat.completion.chunk` 已合并，`content`、`reasoning_content`、`tool_calls`、`finish_reason`、`usage` 都在，不显示 `data:` 行或 chunk 列表。
- [ ] API trace 的请求体仍是原始请求 JSON，未被误改。

## Release notes

- [ ] 让 Conductor 调 `release_notes`：返回的 `notes` 不能只有 `wonder.A9`，应包含 A9 正文和 `Changes since v1.15.0-alpha.13-wonder.A8`。
- [ ] GitHub Release 正文与 APK 内置 `assets/release-notes-1.15.0-alpha.13-wonder.A9.txt` 一致。
- [ ] 只有 `wonder.<label>` 一行、没有正文的 tag 注解应被 Release workflow 拒绝。

## 回归抽查

- [ ] Markdown 标题、列表、引用、粗斜体、链接仍正常；链接只打开 `http` / `https`。
- [ ] 表格左/中/右对齐正确，`---` 分隔行不显示，整表可横向滚动。
- [ ] 围栏代码块语言标签是低对比小字，复制按钮可用。
- [ ] 远端图片点按才加载；SVG 可点开全屏并缩放。
- [ ] 每条回复的用量页脚可展开，点击不高亮整行。
- [ ] 工具行右侧只有 chevron，成功/失败/运行中标记完整，失败有简短说明。

## 已知边界

- 本地链接只支持 `conductor/resources/`；工作区其他路径不开放给消息链接。
- 思考块收起时不做“下方正文完全钉住”的滚动补偿；用卡顶锚定 + 高度动画代替。
- 表格单元格里的行内代码没有点按复制，仍走长按选择。
- 真机视觉效果以本清单为准；本地 JVM 测试只覆盖编译、路径归一化、grep 截断和 ask_user 输出。
