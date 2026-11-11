# WonderSing 启动图标（A7 定稿，2026-11-11）

- 原稿：`wondersing-icon.svg`，viewBox `0 0 1024 1024`，整张画布对应 108dp adaptive icon，中心 66dp 圆为安全区。
- 构成：沿用原 sing-box 等轴立方体（三面 `#42555F` 顶 / `#232F37` 左 / `#34444F` 右，背景 `#ECEFF1`），在面上「印」一个 S，按所在面分别用胶带色：顶 `#E1E8ED`、右 `#CCD6DD`、左 `#99AAB5`。描边 72，平切头。
- S 走势：起笔在顶面右上，向左绕上弯，中段斜穿左面到右面，下弯收在右面；左面上的末端钩子刻意去掉（右面上那一小截尾巴保留到棱线）。
- 起笔平切头两个角收 16 的圆角，由 SVG 里的 `cap-round` 遮罩实现，圆角与弯曲的笔画边缘相切。
- 落地方式交给工程侧：替换 `ic_launcher_foreground` / 背景色 / playstore 图时，几何与色值以 SVG 为准，不要改 S 的路径、粗细和起止点。
- 棱线（磨边）画在 S 之上：裸露处沿用原色 `#66808D` / `#5A737F` / `#445963`；被漆覆盖处由 `paint` 遮罩改成漆面色调，外轮廓两条 `#B4C2CA`，正面竖棱 `#DCE4E9`。

## 工程落地（2026-11-11）

- Android VectorDrawable 不支持这份 SVG 里的 `mask` / `<use>` 组合，`ic_launcher_foreground` 和 `ic_launcher_monochrome` 由原 SVG 直接栅格化成密度资源；S 路径、描边、起止点和磨边几何不改。
- adaptive icon：背景仍是 `#ECEFF1`；foreground 为透明底 PNG，密度 108/162/216/324/432 px。
- monochrome：由同一几何派生，白色立方体剪影 + S/棱线镂空，透明底，供 Android 13+ themed icon tint。
- legacy：`mipmap-*/ic_launcher.png` 和 `ic_launcher_round.png` 由同一 SVG 生成；`app/src/main/ic_launcher-playstore.png` 为 512px。
- 资源名保持 `ic_launcher_foreground` / `ic_launcher_monochrome`，位置改到 `mipmap-*`；adaptive XML 和 `view_app_list_item` 的引用同步更新。
