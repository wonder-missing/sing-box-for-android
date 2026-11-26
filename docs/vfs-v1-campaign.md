# Share Key VFS v1 战役板

状态：实现中。合同见 [`vfs-share-key.md`](vfs-share-key.md)，协议细节见 [`vfs-share-key-reference.md`](vfs-share-key-reference.md)。合同与参考冲突时以合同为准。

## 目标与验收

一条真实链路：WonderSing（Provider）→ Portal Relay → Mini 上的 `wonder-vfs` CLI → 读取 App VFS。最终发布 arm64 AP，并在真机验证 Connect、Share、5 分钟 Pairing Code、list/read、Stop/Revoke、离线 503。

## 仓库与 ownership

| 工作流 | 仓库 | 分支 | 独占范围 |
|---|---|---|---|
| Portal Relay + CLI | `~/workspace/clawdy-portal` | `main` | `lib/vfs-relay*`、`lib/wonder-vfs-cli.mjs`、`bin/wonder-vfs`、`tests/vfs-relay*`、必要的 `server.mjs`/`hooks.mjs`/安装脚本改动 |
| App Provider | `~/workspace/WonderSing` | `dev` | `app/src/main/java/io/nekohasekai/sfa/vfs/relay/`、Share UI、必要导航/strings/FGS/MCP accessor |
| 裁决与发版 | WonderSing | `dev` | 合同、战役板、集成验收、版本号、tag、Release |

同一仓库同一时间只有一个写代理。Portal 代理完成并报告 commit 后，lead 才做 Portal 侧的集成改动。

## 冻结决策

1. **v1 不做**：`shareKeyVfs` capability、admin socket、Provider key rotate/自删/过期、跨 Portal pending Revoke、master 两阶段轮换、永久 tombstone、多 Portal、tailnet 专用 profile、远程 Consumer。全部按合同 §8 deferred。
2. **Portal listener**：现有 `PORTAL_LISTEN` 的所有 TCP 地址都按 public 处理，新增 `/v1/provider`（WS）与 `/v1/provider-keys`、`/v1/links`（HTTP，自行 Bearer 校验）。另起 `<stateDir>/run/vfs-relay.sock` 只挂 `/v1/request`、`/v1/response/{consumerId}/{linkId}/{reqId}`、`/v1/health`。不新增 profile 环境变量。
3. **Provider key**：`POST /v1/provider-keys` 用 hook secret（必须 `handler=mcp`、enabled、未过期，不受 `maxCalls` 影响）+ 32B deviceSecret，首次创建、之后 issue-once。Portal 用单 master AES-256-GCM 加密存 `provider-keys.json` 0600；master 丢失即 503，不做轮换。管理员删除走现有 Portal 管理员会话 `DELETE /v1/provider-keys/{deviceId}?hook=`；不新增 CLI/admin socket。
4. **Link store**：`vfs-relay-links.json` + `vfs-relay-links.initialized`，atomic+fsync；Revoke tombstone 保留 30 天；未登记不 claim；同 owner 幂等，不同 owner 409。
5. **CLI**：Portal 仓库 `lib/wonder-vfs-cli.mjs` + `bin/wonder-vfs`；`$HOME/bin` 安装；默认 stateDir `~/.clawdy-portal`，测试用临时 `PORTAL_STATE_FILE`；Unix socket 无 transport auth；key store 0700/0600 atomic+fsync。
6. **App**：新包 `io.nekohasekai.sfa.vfs.relay`，独立 `VfsRelayRuntime`/`VfsRelayState`；复用现有 source 的 stat/list/read 与 FGS 外壳；绝对路径和分页在新适配层完成，**不改** 旧 `VirtualDirectorySource` 签名和旧 VFS 路由。`SHARE_KEY_VFS` build flag 默认开启，API21 flavor 隐藏。
7. **App Portal 选择**：沿用现有 `McpServerConfig.serverBase` + `token`（`whsec_`）；`canonicalBase=origin(serverBase)`；v1 不加 `vfsBase/authKind`，OAuth-only server 直接报不支持。Provider key 失败时错误可行动。
8. **Scope v1**：`app.data.*`、`app.files`、`app.workspace`、`conductor.*`、`user.selected`、`all`；`all` 不含 `user.selected`，硬排除 `vfs/`；`app.workspace` 排除 `conductor/`、`.runtime/`、保留 root。Recommended=app.data.*；Everything=all；Customize 全选。
9. **Pairing**：16 字符 Crockford、5 分钟、单次；New code 同 Share Key；Stop=paused；自然过期=expired；Revoke=tombstone；数据请求只凭 Share Key，不因 pairState 阻断。
10. **Wire/crypto**：以参考文档附录 A 为 source of truth；Kotlin 与 Node 必须逐字节复现 A.1-A.6；AAD 字段顺序、cursor wire、at-rest AAD 不得自行改。

## 实现顺序

1. Portal：provider key + link store + WS/register + Unix consumer + CLI pair/list/read；假 Provider 测试。
2. App：crypto/store/pairing + Relay client + source 适配 + 最小 Share UI；JVM 测试。
3. 联调：本地 Portal + CLI + App debug；再真机 + Mini；最后 Release CI。
4. 发版：`WONDER_BUILD` 下一标签（当前 AO，下一 AP），五步收尾。

## 证据

| 项 | 证据 |
|---|---|
| 合同/参考拆分 | commit `b47788f`（origin 推送待网络恢复） |
| Portal 侦察 | server.mjs:1268-1277 listener；handleRequest:560；handleUpgrade:1195；hooks.mjs:70-173；install-launchd.sh:25-28 |
| App 侦察 | VirtualDirectoryProtocol.kt:78-212；VfsSharingService.kt:36-268；SettingsScreen.kt:313；Navigation.kt:642；McpRuntime.kt:101-112 |
| Portal 实现 | 待补 |
| App 实现 | 待补 |
| 真机 E2E | 待补 |
| Release | 待补 |

## 风险

- Portal 生产部署会影响现有服务；先在临时 stateDir/端口联调，部署前单独确认。
- App 新路径与旧 `/vfs/provider`、MCP VFS 并存；旧路径不改，新入口只加 Settings 行。
- cursor/1 MiB 页与绝对路径适配是 App 侧最大实现风险；先写 JVM 测试再写 UI。
- Provider key 自动 provision 依赖 MCP token 读取；不得绕过 `McpRuntime` 另开 store 实例。
