# VFS 真机 dry-run 清单（P7）

状态：等 vfsd 生产部署脚本和 `alive` 消费提交，以及 Portal 正式联调卡后执行。本文只是执行清单，不代表已经验收。
设计以 `~/workspace/clawdy-portal/docs/agent-oriented-vfs.md` 为准，Portal 契约见 `~/workspace/clawdy-portal/VFS.md`。
已知缺口：P6e「进程被杀后恢复最后一条 Link」未实现，本轮只记录，不计入通过。
历史基线：wonder.70 全量工作目录案例（Link v1）见 [`vfs-cases/2026-11-15-wonder70-whole-workspace/`](vfs-cases/2026-11-15-wonder70-whole-workspace/)。Link v2 后另建新案例，不直接拿它判 v2。

## 0. 前置

- App：本分支的 arm64 APK，安装到真机；不要再用旧 Tools > Virtual Directory 配置。
- APK 签名：与官方 wonder.62 的 v2/v3 证书一致（subject `O=wonder-missing`/`CN=WonderSing`，SHA-256 `EC:9D:FF:DB:2D:31:FA:4F:3F:70:20:13:47:80:D6:26:34:CF:FF:87:91:3F:C7:17:88:9A:BD:9B:C5:CF:79:37`）；只要手机上是官方 WonderSing，可直接覆盖安装，不需要卸载。
- Portal：P3b 及以上；vfsd：P4 VM runtime、FUSE、watchdog `5fd2c1a` 已完成；生产部署脚本和 `alive` 消费未交；`scripts/e2e-vfs-vm.mjs` 已通过。`docker_run(vfs=true)` 的 `output_dir` 由 Portal `e239f14` 起返回；`alive` 转发由 Portal `e225e32` 起生效。
- MCP Server：直接用手机上已有的 WonderSing MCP Server 和 Token，不要新增、修改或重配。Portal 侧已给现有 WonderSing grant 加上 `vfs_link_request`，Token 没变。如果 App 缓存了工具列表，刷新或重连该 MCP Server，不要改凭证；不要使用新的 `vfs-dry-run-app` grant。
- 记录：App 侧用 `adb logcat` 或 App 里的日志页；Portal 侧用 `~/Library/Logs/clawdy-portal.log`。所有记录先脱敏。

## 1. 用例

| ID | 操作 | 预期 | 证据 |
|---|---|---|---|
| P7-1 | 安装 App，使用现有 WonderSing MCP Server；必要时刷新或重连工具列表 | 现有凭证不改；`vfs_link_request` 出现在该 Server 的工具列表 | App 设置页、Conductor 的 `/mcp` 或工具列表 |
| P7-2 | 让 Conductor 调 `vfs_link_request`，再调本地 `vfs_connect`；确认框选「仅本次」 | 确认框显示 Server 名、server、scope 路径；批准后状态条出现，Portal 记为一次 provider 连接。Link 只含 `type`/`version`/`server`/`linkId`/`scope`，不含 token、endpoint 或签名 | 确认框截图或文字记录、Link 字段检查、状态条、Portal 日志时间戳 |
| P7-2b | 用原话说「你把咱们这个 APP 的工作目录挂到 Portal 上去」 | Conductor 映射到 `workdir`，不额外请求 `appfiles` 或会话 scope，不反问 scope/参数；确认框用人类标签列出「App 工作目录（全部文件）」 | 工具调用参数、Link 的 scope、确认框文字 |
| P7-2c | 只请求 workdir scope，再连一次 | `/vfs` 根只读列出 App 外部工作目录（`crash_reports`、`cache.db`、`Taildrop` 等），`conductor/` 仍可见；`.runtime` 和 `appfiles/` 不因 workdir 出现 | `/vfs` 根列表、`docker_run` 读文件、scope 字段 |
| P7-2d | 用原话说「把 App 的内部文件挂上去」 | Conductor 映射到 `appfiles`；`/vfs/appfiles` 列出 `logs`、`telemetry`、`conductor/images` 等，`/vfs/appfiles/vfs` 不存在；`.runtime` 和外部 `workdir` 文件不可见 | `/vfs/appfiles` 列表、`vfs/` 排除、scope 字段 |
| P7-2e | 用原话说「把整个 App / 所有文件挂上去」 | Conductor 映射到 `all`；App 展开为 `workdir + appfiles + trace + system_prompt + conversation`；`/vfs` 根同时有外部文件、`appfiles/` 和 `.runtime/`，`conductor/` 由 `workdir` 覆盖 | Link 的 scope、确认框展开项、`/vfs` 根列表 |
| P7-2f | 用原话说「把 Conductor 的文件和对话挂上去」 | Conductor 映射到 `conductor_session`；展开为 `conductor + trace + system_prompt + conversation`；`/vfs` 根只有 `conductor/` 和 `.runtime/`，没有 `crash_reports`、`cache.db`、`appfiles/` | Link 的 scope、确认框展开项、`/vfs` 根列表 |
| P7-3 | 断开后重新走一遍，确认框选「信任 15 天」 | Settings > Security > VFS access 出现该 server 和精确 scope；再次连接不再弹框 | Settings 页面、第二次连接无确认框 |
| P7-4 | 前台连接后按 Home，等 2 秒以上，再回到 App | 后台时状态条消失，Portal 收到 close 4000；回前台约 1.5 秒后自动愈合，状态条恢复，无需重新请求 Link | Portal 日志 close/connect、状态条出现时间 |
| P7-4b | 连接建立后闲置 35 秒（不操作、不切后台），再调 `docker_run(vfs=true)` | 必须仍然成功。Portal 每 30 秒发应用层 ping，App 回 pong，Portal 转发 `alive` 给 vfsd；vfsd 不能把 idle provider 判离线 | Portal 日志 ping/pong/`alive`、vfsd status、`docker_run` 输出 |
| P7-4c | `docker_run(vfs=true)` 执行期间不要切后台 | 切后台会 close 4000；若正读到一半，FUSE 读返回 EIO，调用回 `VFS_UNAVAILABLE`。回前台自动重连后重试即可；P7 只在 App 前台时跑长时间 `docker_run` | Portal close/connect、FUSE 错误、重连后重试结果 |
| P7-5 | 让 Conductor 调 `docker_run(vfs=true, ...)`，容器读 `/vfs/conductor`、`/vfs/appfiles/logs/app.log` 和 `/vfs/.runtime/trace.md`，写 `/work/out.txt` | 容器能读到文件，`/vfs` 只读（写入失败），`/work` 可写；Portal 返回 `output_dir`（`vfs.workDir`）；`/vfs` 下以及 `/vfs/appfiles` 下都看不到 `vfs/trust.json`、`resume.json`、`device-id` | 容器 stdout、sha256、`output_dir` 内容、`ls -la /vfs`、`ls -la /vfs/appfiles` |
| P7-6 | 状态条点「断开」 | 连接关闭，状态条消失；trust 保留；不自动重连 | 状态条、Portal close、再次 `vfs_connect` 不弹框（信任仍在） |
| P7-7 | Settings > Security > VFS access 撤销该 Server | 当前连接立即关闭；trust 消失；下一次 `vfs_link_request` + `vfs_connect` 重新弹确认框 | 撤销前后列表、Portal close 4001、确认框 |
| P7-8 | 用 `adb shell am force-stop <package>` 杀进程，重开 App，确认框选「信任 15 天」 | 记录现状，不验收：P6e 未实现，重启后不会自动恢复最后一条 Link；手动让 Conductor 重新请求并连接应成功 | 重启后状态条、Portal 日志、手动重连结果 |
| P7-9 | 在 App 和 Portal 日志里查密钥 | MCP Token、Bearer、resume token 都不出现；`hk_` 标识可以出现 | `grep -Eic` 计数为 0（不贴匹配行） |
| P7-10 | 可选：确认框选「拒绝」，30 秒内再次 `vfs_connect` | 返回 `VFS_USER_DENIED` 或 `VFS_CONFIRMATION_COOLDOWN`，带剩余时间；倒计时结束后可再次弹框 | 工具结果、状态条/聊天里的倒计时 |
| P7-11 | 可选：第二台设备用同一个 MCP Token 连接 | 预期 `VFS_DEVICE_MISMATCH` 或 HTTP 403；第一台连接不被替换；第二台 App 进终态，不自动重试。没有第二台设备就记录未测 | 第二台错误、第一台状态条、Portal 日志 |
| P7-12 | 可选：Portal 管理页轮换或停用该 knock | 当前 provider WS 断 4401，下一次连接 401；App 清理 Link 和信任，提示重新授权 | Portal 管理页、close 4401、下一次 401、App 状态 |
| P7-13 | 可选：用 `PORTAL_VFS_LINK_IDLE_MS` 很短的 dev Portal 模拟 15 天过期 | 过期后 `vfs_link_request` 重签，旧 Link 清理；生产 Portal 不动 | dev Portal 配置、Link 前后对比、Portal 日志 |

2026-11-15 v2 实测：P7-4b 通过（01:33:16 provider connect，01:33:35 Link reuse，01:33:47 / 01:35:18 两次 docker 读，间隔 91 秒；provider 01:36:41 才退后台，`.71` 无错误）。P7-5 未验：两个 `/work` 目录为空，没有 `out.txt`；`/vfs` 只读和 `/work` 写由 vfsd e2e 覆盖，本轮不要求补跑。

2026-11-16 起 App 把 VFS 可见范围分成两层。根有 6 个：`conductor`、`trace`、`system_prompt`、`conversation`、`workdir`、`appfiles`；预设有 2 个：`all`（workdir + appfiles + 三个 runtime scope，conductor 由 workdir 覆盖）和 `conductor_session`（conductor + 三个 runtime scope）。`workdir` 在 `/vfs` 根只读暴露外部工作目录，`appfiles` 在 `/vfs/appfiles` 暴露内部 `filesDir` 并排除 `vfs/`，`.runtime` 仍由三个独立 scope 控制。预设只在 App 解析 Link 时展开成根，信任记录和确认框看到的都是展开后的根；Portal 把 scope 当不透明字符串集合，只做类型、长度和去重校验，不需要改 VFS 协议或 Portal 代码。

P7 导出摩擦（2026-11-14 真机）：`diagnostics_export(kind=trace, name=api-trace.json)` 写入的是 Markdown 文本，先改用 `.md` 名称。App 侧待做：按 kind 自动纠正扩展名，或名称与内容不符时提示。

2026-11-16 真机 VFS 故障（wonder.84，SM-F741U）：`vfs_connect` 四次 `VFS_CONNECT_FAILED ... handshake in time`，`docker_run(vfs=true)` 返回 `VFS_UNAVAILABLE`。App 侧没有 scope 报错；vfsd-vm 日志在 19:09 连续出现 `fuse init timed out`，且容器内残留 `p_5612...`、`p_f20...`、`p_0951...` 三个旧 provider 的 FUSE mount。重启 `vfsd-vm` 后 stale mounts 清空。App 的 `VFS_CONNECT_FAILED` 是握手超时的表象，不是 `all` / `appfiles` scope 导致；vfsd 的 `detach` 会保留 mount 供复用，本次还不能证明它是唯一原因，具体触发点需要 vfs 项目继续取证。

P7-5 的参考命令：

```sh
ls -la /vfs
cat /vfs/conductor/<file>
sha256sum /vfs/conductor/<file> /vfs/.runtime/trace.md
echo ok > /work/out.txt
touch /vfs/should-fail
```

`touch /vfs/should-fail` 必须失败；`/work/out.txt` 必须存在。`ls -la /vfs` 不得出现 `vfs/`、`trust.json`、`resume.json`、`device-id`。

## 2. 日志检查

```sh
grep -Eic 'Bearer |Authorization:|resumeToken|X-WonderSing-Resume' ~/Library/Logs/clawdy-portal.log
adb logcat -d | grep -Eic 'Bearer |Authorization:|resumeToken|X-WonderSing-Resume'
```

两处计数都应为 0。不要把匹配到的行贴到聊天或 issue。

## 3. 通过标准

- P7-1 到 P7-7、P7-4b、P7-9 全部通过；P7-8 只记录，不算失败；P7-10 到 P7-13 可选，未测就写未测，不阻塞本轮。
- 容器只看到 `/vfs` 和 `/work`；`/vfs` 只读；App 私有 `vfs/` 文件不可见。
- 失败项必须带：时间、App/Portal/vfsd commit、命令、预期、实际、脱敏后的日志片段。
- 任何密钥出现在日志、截图或记录里，本轮直接判失败，先清理再继续。

## 4. 记录模板

```text
日期：
App commit / 版本：
Portal commit：
vfsd commit：
手机型号 / Android：
用例：
结果（pass / fail / recorded）：
证据：
备注：
```
