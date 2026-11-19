# Conductor 消息 Markdown

2026-11-20。消息流不再按语法逐项打补丁：普通文本段走 CommonMark（`compose-markdown` / Markwon），图片、SVG、表格和围栏代码在进入 Markdown 渲染前拆成 Conductor 专用块。

## 支持范围

| 类别 | 支持 |
| --- | --- |
| 块级 | 段落、H1–H3（渲染器也接受更深标题）、无序/有序/嵌套列表、引用、分隔线、围栏代码、缩进代码 |
| 行内 | 粗体、斜体、粗斜体、行内代码（等宽、主题强调色、透明底）、链接、转义、软换行、硬换行 |
| 表格 | GitHub 风格，支持 `:---` / `:---:` / `---:` 左/中/右对齐；整表横向滚动 |
| 代码 | 围栏代码带语言表头、复制按钮，等宽、可选中、横向/纵向滚动 |
| 媒体 | Markdown 图片（远端 HTTPS 点按加载、本地 `conductor/resources/`）；SVG 围栏/裸 `<svg>`，点击全屏缩放 |
| 链接 | 只打开 `http` / `https`；不执行 `javascript:`、`file:` 或 HTML |

不执行原始 HTML；任务列表、脚注和 HTML 嵌入没有专门渲染。表格单元格内的自定义块（图片/SVG）不拆成独立块。

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
- 链接可点，只有 `http`/`https` 打开。
- 无序/有序列表缩进和嵌套正确。
- 引用有左侧竖线或等价层级。
- 分隔线可见。
- 表格三列分别左/中/右对齐，`---` 分隔行完全不显示。
- 代码块语言标签是低对比小字，和代码正文区分；代码可横向滚动，右上复制按钮可用。
- 远端图片点按才加载；SVG 渲染后可点开全屏并缩放。
- 原始 HTML 不执行，不出现脚本或 iframe 内容。
