# Conductor 消息 Markdown

2026-11-20。消息流不再按语法逐项打补丁：普通文本段走 CommonMark（`compose-markdown` / Markwon），图片、SVG、表格和围栏代码在进入 Markdown 渲染前拆成 Conductor 专用块。

## 支持范围

| 类别 | 支持 |
| --- | --- |
| 块级 | 段落、H1–H3（渲染器也接受更深标题）、无序/有序/嵌套列表、引用、分隔线、围栏代码、缩进代码 |
| 行内 | 粗体、斜体、粗斜体、行内代码（等宽、主题强调色、透明底，走标准长按选择复制）、链接、转义、软换行、硬换行 |
| 表格 | GitHub 风格，支持 `:---` / `:---:` / `---:` 左/中/右对齐；整表横向滚动 |
| 代码 | 围栏代码始终带表头（语言或 `Code`）和复制按钮，复制后按钮短暂变成对勾；等宽、可选中、横向/纵向滚动 |
| 媒体 | Markdown 图片（远端 HTTPS 点按加载、本地 `conductor/resources/`）；SVG 围栏/裸 `<svg>`，点击全屏缩放 |
| 链接 | `http` / `https` 打开外部浏览器；`conductor/resources/` 本地资源打开只读查看器；不执行 `javascript:`、`file:` 或 HTML |

不执行原始 HTML；任务列表、脚注和 HTML 嵌入没有专门渲染。表格单元格内的自定义块（图片/SVG）不拆成独立块。

## 行内代码与本地链接

- 行内代码用主题派生强调色、透明底、等宽字体；复制走标准文本选择（长按选择后点系统 Copy），不额外挂点按手势。消息气泡和正文都保持原生长按选择行为。
- 消息流和 Transcript Viewer 的 Rich 模式共用同一套行内代码 span（透明底、主题强调色、等宽、保留粗体/斜体）。对话转录默认打开 Rich，API trace 默认 Raw。对话转录的 Raw 使用纯文本编辑器（保留行号、搜索、自动换行），不跑 Markdown 语法高亮：Sora 的 Markdown span 在 word wrap 下会把某些换行行叠在一起；Raw 里 `**`/反引号是源文标记，不是渲染结果。API trace 的 Raw 仍保留 Markdown 高亮。
- 正文换行使用 `BREAK_STRATEGY_SIMPLE` 并关闭连字符，长标识符优先整词换行；只有单个标识比一行还宽时才会在词内断行。
- `[说明](conductor/resources/file.md)` 或 `[说明](resources/file.md)` 点按打开只读查看器：显示文件名、路径、内容，可复制；不执行文件内容。只允许 `conductor/resources/` 下的文件。
- 本地资源超过 256 KB 时查看器只显示前 256 KB；二进制文件显示不可预览，不尝试解码。
- 表格单元格里的行内代码和正文一样走长按选择复制。

## 一条消息测完

把下面整段发给 Conductor，要求它只输出一条消息、不调用工具：

```text
请只输出一条 Markdown 测试消息，不要调用工具，不要解释，严格包含下面所有内容：
# 一级标题
## 二级标题
### 三级标题

普通段落：**粗体**、*斜体*、***粗斜体***、`inline code`、[OpenAI](https://example.com)。

- 无序一
  - 嵌套无序
    1. 嵌套有序
- 无序二

1. 有序一
2. 有序二

> 这是一段引用。

---

| 左对齐 | 居中 | 右对齐 |
|:---|:---:|---:|
| alpha | beta | 123 |
| longer-cell | middle | 456 |

```kotlin
fun hello(name: String): String {
    return "hello, $name"
}
```

![remote](https://httpbin.org/image/png)

```svg
<svg xmlns="http://www.w3.org/2000/svg" width="320" height="120" viewBox="0 0 320 120">
  <rect width="320" height="120" rx="12" fill="#12141a"/>
  <text x="20" y="68" font-family="sans-serif" font-size="24" fill="#e6e8ee">SVG works</text>
</svg>
```
```

## 验收清单

- 三级标题字号递减，和正文有明显层级。
- 粗体、斜体、粗斜体、行内代码肉眼可区分；行内代码只有强调色和等宽，没有填充胶囊或描边。
- 点按行内代码不触发复制；长按进入文本选择，用系统 Copy 复制的内容与原 token 完全一致。
- 长代码标识优先整词换行，不横向溢出；只有比一行还宽的标识才会词内断行。
- 链接可点，`http`/`https` 打开外部浏览器。
- 无序/有序列表缩进和嵌套正确。
- 引用有左侧竖线或等价层级。
- 分隔线可见。
- 表格三列分别左/中/右对齐，`---` 分隔行完全不显示。
- 代码块表头始终存在：有语言时显示语言，没有语言时显示 `Code`；复制按钮点击后短暂变成对勾，代码可横向滚动。
- 远端图片点按才加载；SVG 渲染后可点开全屏并缩放。
- 本地资源链接点按弹出只读查看器，标题、路径、内容和复制都可用；不跳到外部 App。
- 原始 HTML 不执行，不出现脚本或 iframe 内容。

本地链接需要先让 Conductor 写一个资源，例如：

```text
先用 file_write 写 conductor/resources/check.md，内容为 "# Check\n\nlocal link works."，
然后只输出一条消息：[打开 check.md](conductor/resources/check.md)。不要调用其他工具。
```
