# WonderSing 待办

改动先记在这里，不默认立刻打 Release。凑一批或主人点头后再发版。
新增一项就加一条 `- [ ]`；已随某版发出去就勾掉并写上版本号。

## 流程（已落地、随仓库生效）

- [x] Release 只编手机能装的 `arm64-v8a`（libbox 只编 arm64，不打其他 ABI / universal / Android 5 legacy）
- [x] Release 跑完自动通知主人（成功给 arm64 安装链接，失败给运行记录）

## 功能

- [ ] 遥测设置：填完地址和 Token 后有明确保存提示，并加一键连通测试
- [ ] 遥测外层入口和文案保持简洁，少堆说明
- [ ] 核对并完善应用内检查更新：从本仓库 Releases 拉取 APK，支持一键下载安装（部分逻辑已随近几版带上，不够再补）
- [ ] CFG Sing-box 订阅里 Tailscale 控制面增加走代理的 detour（只影响连 controlplane，不影响组网）；减轻登录/重连失败。等主人点头再改订阅
