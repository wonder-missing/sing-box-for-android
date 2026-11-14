# VFS 真机 dry-run 清单（P7）

状态：等 vfsd watchdog 提交和 Portal 正式联调卡后执行。本文只是执行清单，不代表已经验收。
设计以 `~/notes/agent-oriented-vfs.md` 为准，Portal 契约见 `~/workspace/clawdy-portal/VFS.md`。
已知缺口：P6e「进程被杀后恢复最后一条 Link」未实现，本轮只记录，不计入通过。

## 0. 前置

- App：本分支的 arm64 APK，安装到真机；不要再用旧 Tools > Virtual Directory 配置。
- Portal：P3b 及以上；vfsd：P4 VM runtime、FUSE、watchdog 均已提交；`scripts/e2e-vfs-vm.mjs` 已通过。`docker_run(vfs=true)` 的 `output_dir` 由 Portal `e239f14` 起返回。
- MCP Server：用 `portal-knock` 或管理页生成 `handler=mcp`、启用 `vfs_link_request` 的 grant；把 `call.mcp` 里的 URL 和 Token 填进 App 的 Settings > Conductor > MCP servers，并打开该 Server。
  - URL 形如 `https://<host>/knocks/mcp/hk_...`。
  - Token 是 Bearer secret，不写进任何记录。
- 记录：App 侧用 `adb logcat` 或 App 里的日志页；Portal 侧用 `~/Library/Logs/clawdy-portal.log`。所有记录先脱敏。

## 1. 用例

| ID | 操作 | 预期 | 证据 |
|---|---|---|---|
| P7-1 | 安装 App，配置并启用标准 `call.mcp` 入口 | MCP Server 显示已连接，`vfs_link_request` 出现在工具列表 | App 设置页、Conductor 的 `/mcp` 或工具列表 |
| P7-2 | 让 Conductor 调 `vfs_link_request`，再调本地 `vfs_connect`；确认框选「仅本次」 | 确认框显示 Server 名、issuer、scope 路径；批准后状态条出现，Portal 记为一次 provider 连接。Link 只含 `type`/`version`/`issuer`/`linkId`/`scope`，不含 token、endpoint 或签名 | 确认框截图或文字记录、Link 字段检查、状态条、Portal 日志时间戳 |
| P7-3 | 断开后重新走一遍，确认框选「信任 15 天」 | Settings > Security > VFS access 出现该 issuer 和精确 scope；再次连接不再弹框 | Settings 页面、第二次连接无确认框 |
| P7-4 | 前台连接后按 Home，等 2 秒以上，再回到 App | 后台时状态条消失，Portal 收到 close 4000；回前台约 1.5 秒后自动愈合，状态条恢复，无需重新请求 Link | Portal 日志 close/connect、状态条出现时间 |
| P7-5 | 让 Conductor 调 `docker_run(vfs=true, ...)`，容器读 `/vfs/conductor` 和 `/vfs/.runtime/trace.md`，写 `/work/out.txt` | 容器能读到文件，`/vfs` 只读（写入失败），`/work` 可写；Portal 返回 `output_dir`（`vfs.workDir`）；`/vfs` 下看不到 `vfs/trust.json`、`resume.json`、`device-id` | 容器 stdout、sha256、`output_dir` 内容、`ls -la /vfs` |
| P7-6 | 状态条点「断开」 | 连接关闭，状态条消失；trust 保留；不自动重连 | 状态条、Portal close、再次 `vfs_connect` 不弹框（信任仍在） |
| P7-7 | Settings > Security > VFS access 撤销该 Server | 当前连接立即关闭；trust 消失；下一次 `vfs_link_request` + `vfs_connect` 重新弹确认框 | 撤销前后列表、Portal close 4001、确认框 |
| P7-8 | 用 `adb shell am force-stop <package>` 杀进程，重开 App，确认框选「信任 15 天」 | 记录现状，不验收：P6e 未实现，重启后不会自动恢复最后一条 Link；手动让 Conductor 重新请求并连接应成功 | 重启后状态条、Portal 日志、手动重连结果 |
| P7-9 | 在 App 和 Portal 日志里查密钥 | MCP Token、Bearer、resume token 都不出现；`hk_` 标识可以出现 | `grep -Eic` 计数为 0（不贴匹配行） |
| P7-10 | 可选：确认框选「拒绝」，30 秒内再次 `vfs_connect` | 返回 `VFS_USER_DENIED` 或 `VFS_CONFIRMATION_COOLDOWN`，带剩余时间；倒计时结束后可再次弹框 | 工具结果、状态条/聊天里的倒计时 |
| P7-11 | 可选：第二台设备用同一个 MCP Token 连接 | 预期 `VFS_DEVICE_MISMATCH` 或 HTTP 403；第一台连接不被替换；第二台 App 进终态，不自动重试。没有第二台设备就记录未测 | 第二台错误、第一台状态条、Portal 日志 |
| P7-12 | 可选：Portal 管理页轮换或停用该 knock | 当前 provider WS 断 4401，下一次连接 401；App 清理 Link 和信任，提示重新授权 | Portal 管理页、close 4401、下一次 401、App 状态 |
| P7-13 | 可选：用 `PORTAL_VFS_LINK_IDLE_MS` 很短的 dev Portal 模拟 15 天过期 | 过期后 `vfs_link_request` 重签，旧 Link 清理；生产 Portal 不动 | dev Portal 配置、Link 前后对比、Portal 日志 |

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

- P7-1 到 P7-7、P7-9 全部通过；P7-8 只记录，不算失败；P7-10 到 P7-13 可选，未测就写未测，不阻塞本轮。
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
