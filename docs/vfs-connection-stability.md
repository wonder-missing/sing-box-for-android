# VFS 连接稳定性规划

状态：2026-11-17，wonder.89 后规划。当前实现见 `VfsRuntime`、`VfsConnectionManager`、`VfsConnectionState`。

## 问题

现在的 VFS provider 生命周期绑定在 App 前台：

- `AppLifecycleObserver.onStop` 触发 `VfsRuntime` 立即 `disconnect(BACKGROUND)`；
- 回前台再 `reconnectLast()`，先等 1.5 秒，再按 backoff 重连；
- 用户切到聊天/浏览器看 Conductor 会话时，App 不在前台，vfsd 立即看到 provider 离线；
- Portal 的 `docker_run(vfs=true)` 返回 `VFS_UNAVAILABLE`，已经启动的消费者容器读到 EIO/ENOTCONN；
- 用户回 App 后连接重建，providerId 不变但 generation 增加；旧容器不会热恢复。

wonder.88 测试已经复现这条路径：测试期间 provider 多次 connect/disconnect；wonder.86 第一次 `cpk_docker_run` 在 22ms 内失败，重试才成功。用户不可能一边让 App 长期前台、一边查看会话，所以这是产品问题，不是测试环境问题。

## 目标与边界

目标：

- 用户切到聊天/浏览器后，VFS sharing 在明确、有限的时间内继续可用；
- 用户能看到共享正在进行，能随时断开；
- App 进程被杀、网络切换、Link 过期/撤销后有确定行为；
- 不静默长期共享，不新增后台常驻服务而没有通知。

不做：

- 不做始终开启的后台 provider；
- 不让 VFS 在用户未同意或已撤销信任时自动恢复；
- 不把 Portal/VFS 协议改成常驻长连接新协议。

## 方案比较

### A. 延后断开（短平快）

`onStop` 后先等 60–120 秒再 `disconnect`；期间回前台就取消计时。

- 优点：改动小，无新权限/通知，能覆盖“切到聊天看几分钟”。
- 缺点：没有 foreground service，Android 仍可能在后台挂起或杀进程；长时间不可靠；用户看不到后台共享状态。
- 适合做第一步的体感验证，不能作为最终方案。

### B. Foreground service（推荐）

新增 `VfsSharingService`，只在有活动 VFS Link 时启动前台服务：

- 通知：服务端名、scope、当前上传字节数、Disconnect；
- 点击通知回到 Conductor/VFS 状态；
- 用户断开、Link 撤销/过期、信任撤销时停止服务；
- Activity 的 `onStop` 不再直接断开 provider，服务持有连接；
- 进程被杀后服务按 `START_STICKY`/显式 restart 恢复，若 resume token 和信任仍有效则重连；否则发一条明确通知并停止。

Android 注意事项：

- API 34+ 需要声明 foreground service type；按实际用途选 `dataSync` 或 `connectedDevice`，并核对 targetSdk 限制；
- `POST_NOTIFICATIONS` 权限与通知渠道；
- 只能从用户可见的操作启动（VFS consent 后启动），不能从后台无提示启动；
- 不加 WakeLock；断网/Doze 用 backoff，不无限重试。

安全：

- 通知必须可见，不能静默共享；
- 断开/撤销信任必须立即停止服务；
- 通知不显示 token/文件内容，只显示服务端和 scope；
- 可选：无消费者 refs 且无人操作 N 分钟后自动停止（需要 Portal/vfsd refs 或本地 idle 计时）。

### C. 只在“消费端任务”期间保活

Portal 启动容器时通过 Link/refs 告知 App 需要保持在线；容器结束后释放。当前协议里 App 不直接知道 refs。

- 优点：最省电，最符合按需共享。
- 缺点：需要 Portal/协议改动，且用户在容器运行期间仍可能需要看聊天；不能作为第一版唯一方案。
- 可放到 B 之后评估。

## 分阶段

### P0：延迟断开 + 观测

- `VfsRuntime` 把 background 立即 disconnect 改成可取消的 grace timer（建议 90 秒，常量可调）。
- 前台恢复取消 timer；grace 到期仍未回前台才断开。
- 增加 `vfs_lifecycle` 遥测/日志：`connect`、`background_grace_start`、`background_disconnect`、`foreground_cancel`、`reconnect_attempt/success/fail`，带 generation 和耗时，不带 token/内容。
- 真机：切到聊天 1/2/5 分钟，再 `docker_run(vfs=true)` 读一个小文件。

### P1：Foreground service

- 把 `VfsConnectionManager` 的所有权从 `ConductorViewModel` 提升到进程级 `VfsSharingService`（或由服务 attach）；UI 只观察状态。
- 服务启动/停止、通知、Disconnect action、Link 生命周期绑定。
- Activity 生命周期不再决定 provider 连接；服务在线时状态 pill 显示 On。
- 进程死亡/服务重启路径与 resume token 处理。
- 真机：home、锁屏、切聊天 5/10 分钟、网络切换、服务被系统杀、Link 撤销/过期。

### P2：按需保活与自动停止

- 评估 Portal refs/consumer 生命周期通知；无活动 consumer 且用户不在前台时自动停止。
- 可选设置：后台共享最长时长、仅本次/15 天信任与后台权限分开。
- 性能/电量回归：通知常驻、CPU、网络、Doze。

## 验收矩阵

| 场景 | 预期 |
|---|---|
| 切到聊天 1 分钟 | provider 仍 online，docker_run 成功 |
| 切到聊天 5 分钟 | provider 仍 online（P1）；P0 允许按 grace 断开但必须有明确状态 |
| 锁屏 10 分钟 | 按设置/策略，不能静默无限共享 |
| 进程被杀 | 服务重启并重连，或通知用户需重新打开 |
| Link 撤销/过期 | 立即断开、通知、清 resume/trust 按现有规则 |
| 网络切换/断网 | backoff，不刷 generation 风暴；恢复后重连 |
| 用户在 App 内断开 | 服务停止，通知消失 |
| 容器运行中 | 读取持续可用；不因 App 切后台 EIO |

## 待拍板

- 后台共享是否需要用户额外开关，还是 VFS consent 即包含有限后台时间？
- 默认 grace/最长后台时间（建议 P0 90 秒，P1 前台服务开启后按 Link 有效期/用户断开）。
- Foreground service type 与通知文案。
- 是否把 refs 生命周期纳入 P2，还是先只做服务保活。
