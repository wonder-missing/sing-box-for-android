# design/

Lyra 维护的设计稿与规格。每个组件一个目录，里面放：

- `SPEC.md`：**实现以它为准**。尺寸、颜色 token、结构、状态、改哪些文件都写成文字，实现方不需要看图。
- `icons/`：SVG 原稿和可直接放进 `res/drawable/` 的 VectorDrawable XML。
- `mockup.png`：像素示意，仅供人看，和 SPEC 冲突时以 SPEC 为准。

工程侧的 token 映射、触控标准和交接约定见 [`../docs/design-handoff.md`](../docs/design-handoff.md)。

## 索引

| 目录 | 内容 | 状态 |
|---|---|---|
| `conductor/input-bar/` | Conductor 底部输入框：`/` 命令、`@` 提及、发送三颗圆钮 | 已实现，待随 wonder.16 真机验收 |
