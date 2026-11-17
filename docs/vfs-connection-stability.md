# VFS 连接稳定性

状态：2026-11-17，wonder.94。VFS 行为在 90–92 实现，93/94 补充生命周期遥测、UI 和草稿/telemetry 修复。代码见 `VfsRuntime`、`VfsSharingService`、`VfsConnectionManager`、`VfsSharingPolicy`。可重复的短测/夜间浸泡流程见 [`vfs-soak-test.md`](vfs-soak-test.md)。

## 问题

wonder.89 及以前，VFS provider 的生命周期绑定在 App 前台：

- `AppLifecycleObserver.onStop` 触发 `VfsRuntime` 立即 `disconnect(BACKGROUND)`；
- 回前台再 `reconnectLast()`，先等 1.5 秒，再按 backoff 重连；
- 用户切到聊天/浏览器看 Conductor 会话时，App 不在前台，vfsd 立即看到 provider 离线；
- Portal 的 `docker_run(vfs=true)` 返回 `VFS_UNAVAILABLE`，已经启动的消费者容器读到 EIO/ENOTCONN；
- 用户回 App 后连接重建，providerId 不变但 generation 增加；旧容器不会热恢复。

wonder.88 测试已经复现这条路径：测试期间 provider 多次 connect/disconnect；wonder.86 第一次 `cpk_docker_run` 在 22ms 内失败，重试才成功。用户不可能一边让 App 长期前台、一边查看会话，所以这是产品问题，不是测试环境问题。

## wonder.90 实现

### 连接所有权

`VfsConnectionManager` 和 consent 状态移入进程级 `VfsRuntime`。`ConductorViewModel` 只调用 `VfsRuntime.manager()` 并观察 `VfsRuntime.state`，不再自己创建 manager。因此 Activity 重建、返回键离开 Conductor、配置变化都不会替换或断开已有连接。

### Foreground service

新增 `VfsSharingService`（`specialUse`，子类型 `vfs`）：

- manager 进入 `Connecting`/`Connected`/`Backoff` 时启动；
- 常驻低优先级通知：服务端名、可读 scope、本次累计上传字节数、`Disconnect`；
- 点击通知回到 App；
- 用户在 App 内断开、点击通知 `Disconnect`、Link 撤销/过期、认证类错误导致会话结束时，服务停止；
- `START_NOT_STICKY`，不静默复活；进程被杀后重新打开 App 时按现有流程重连；
- 服务启动失败（例如 Android 12+ 后台启动限制）会写 `vfs_lifecycle` 遥测，并退回下面的 grace。

### 前后台策略

- App 在前台时，`reconnectLast()` 保持原有 1.5 秒等待和 backoff；
- App 切后台时不再立即断开；只要 sharing 服务在线，连接和断线重连继续；
- 没有服务可用的兜底：切后台后 90 秒内回前台就取消，否则断开，避免无通知的长期后台共享；
- 服务被系统停止而连接仍在时，重新启动 90 秒 grace，不允许无通知继续共享；
- 后台只自动重连已信任的 Link，不会在没有 UI 时弹 consent；
- 前台和后台的重连循环单飞，避免两条 loop 重复 connect。

### 观测

新增 `vfs_lifecycle` 客户端事件：`foreground`、`background`、`screen_on`、`screen_off`、`background_grace`、`background_disconnect`、`sharing_start_failed`，字段只有 stage 和 service 状态，不包含 token、路径或文件内容。通知栏下拉没有可靠的 Android 生命周期回调，因此不记录；锁屏/亮屏和前后台切换可记录。

## 不做

- 不做始终开启的后台 provider；没有活动 Link 时服务不启动。
- 不让 VFS 在用户未同意或已撤销信任时自动恢复。
- 不把 Portal/VFS 协议改成常驻长连接新协议。
- 不加 WakeLock；断网/Doze 走现有 backoff。
- 不做进程被杀后的自动重连（P2，见 TODO）。

## 2026-11-17 实测（wonder.90）

第一轮 30 分钟监测在 08:06:27 启动，08:10:06 因连续 3 次不健康提前停止：

- 08:06:27–08:08:33 五次健康：provider online、mounted、readOnly，FUSE 读取正常。
- `lastSeen` 最后更新约 08:08:34；Portal 在 08:09:42 记录 provider `code=1006`（不是 `4000 background`）。
- 同一秒，手机上的 Portal terminal WebSocket 也断开；vfsd 在 08:09:35 有一条 FUSE teardown 的 `ENOENT ... write`。
- App 仍在运行：`app.log` 08:10:42 起继续记录，telemetry 在 08:10:47 仍成功上传；08:09:24 的 `vfs_lifecycle` 显示 `foreground` + `service=true`。没有 service stop，也没有 app crash。
- 之后 App 没有自动重连；08:11:30 是新请求的 `all` Link 替换旧 Link，不是旧 Link 自动恢复。

判断：前台服务本身撑住了约 8 分钟，失败点更像手机到 Portal 的路径被黑洞式切断（同时 terminal 也掉），而 App 的 WebSocket 配置是 `readTimeout=0`、`pingInterval=0`，没有本地死链检测，所以没有及时触发 `onFailure`/重连。不是 4000 background 断开。

wonder.91 修复：

- provider WebSocket 加 OkHttp 协议层 `pingInterval=20s`。它只做本地死链检测；Portal 的应用层 ping/pong 仍是 `lastSeen` 的权威。
- 重连预算耗尽时不再停在 `Backoff`，改为 `VFS_CONNECT_FAILED` Error，前台服务随之停止，避免留下永远“连接中”的通知。
- 监测脚本只在有 online provider 时切换目标，避免把旧的离线 provider 当成新目标。

第二轮监测（同一 wonder.90，改进方法）在 08:20:04–08:50:04 跑了 56 个样本，**56/56 健康**：目标 provider 始终 online/mounted/readOnly，FUSE 读 140 字节测试文件稳定，6 次真实消费者容器读取全部成功，没有 provider 或 generation 切换。期间 App 多次回到前台（CFG 可见 `vfs_lifecycle foreground`），说明前台服务在这段窗口里保住了同一 Link。这轮是 90 的窗口；91 的死链检测到 93/94 才随版本安装，之后 93 真机短时后台/锁屏观察中 provider 一直 online。

wonder.92 修复：全量 Link 的 `missingScopes` 不再错误列出 `conductor`（`workdir` 已覆盖）；VFS 活跃时记录 `background`/`screen_on`/`screen_off` 遥测。通知栏下拉没有可靠回调，仍不记录。

wonder.93/94 实测补充：93 的新会话在 12:54–12:58 记录了 `background`、`screen_off`、`screen_on`、`foreground` 事件，期间 provider 一直没有断开；5 分钟浸泡 10/10 健康、2/2 消费者容器读取成功、0 outage/0 重连。94 不改变 VFS 行为。另一个已确认的限制是 VFS 大文件批量读取：当前蜂窝 + Tailscale 路径下 1 MB 约 25–28 秒，`stat` 只要 0.2 秒；先换 Wi-Fi 复测，见 TODO。

## 待真机验收

没有模拟器，以下场景需要真机确认：

| 场景 | 预期 |
|---|---|
| 切到聊天 1 分钟 | provider 仍 online，`docker_run(vfs=true)` 成功 |
| 切到聊天 5–10 分钟 | provider 仍 online，通知仍在 |
| 锁屏 10 分钟 | 按系统网络策略保持或 backoff，恢复后重连；不能无通知共享 |
| 通知 `Disconnect` | 连接断开、通知消失、最后一次 Link 被遗忘 |
| App 内断开 | 服务停止，通知消失 |
| Link 撤销/过期 | 立即断开、服务停止 |
| 网络切换/断网 | backoff，不刷 generation 风暴；恢复后重连 |
| 返回 App | 状态 pill 仍为 On，不需要新的 `vfs_connect` |
| 服务启动失败 | 90 秒 grace 后断开；`vfs_lifecycle` 有 `sharing_start_failed` |
| 进程被杀 | 重新打开 App 后需重新发起连接；不静默恢复 |

## P2 候选

- 进程死亡后恢复最后一条 Link（resume token 仍在，但需要持久化 linkId/scope 和用户策略）。
- 按 Portal refs/consumer 生命周期自动停止。
- 可选设置：后台共享最长时长、仅本次/15 天信任与后台权限分开。
- 通知常驻、CPU、网络、Doze、电量回归。
