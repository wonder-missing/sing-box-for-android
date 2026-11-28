# Share Key VFS v1 战役板

状态：AY 已发布（2026-11-28）：VFS 用户面统一为 Links，VFS Relay 配置简化，Dashboard VFS 默认在事件点阵图上方；CLI 增加 `links` 与 `status --probe`。合同见 [`vfs-share-key.md`](vfs-share-key.md)，协议细节见 [`vfs-share-key-reference.md`](vfs-share-key-reference.md)。合同与参考冲突时以合同为准。

## 目标与验收

一条真实链路：WonderSing（Provider）→ Portal Relay → Mini 上的 `vfsc` CLI → 读取 App VFS。最终发布 arm64 AP，并在真机验证 Share、5 分钟 Pairing Code、list/read、Revoke、自动连接/断开、离线 503。

## 仓库与 ownership

| 工作流 | 仓库 | 分支 | 独占范围 |
|---|---|---|---|
| Portal Relay + CLI | `~/workspace/clawdy-portal` | `main` | `lib/vfs-relay*`、`lib/vfsc-cli.mjs`、`bin/vfsc`、`tests/vfs-relay*`、必要的 `server.mjs`/`hooks.mjs`/安装脚本改动 |
| App Provider | `~/workspace/WonderSing` | `dev` | `app/src/main/java/io/nekohasekai/sfa/vfs/relay/`、Share UI、必要导航/strings/FGS/MCP accessor |
| 裁决与发版 | WonderSing | `dev` | 合同、战役板、集成验收、版本号、tag、Release |

同一仓库同一时间只有一个写代理。Portal 代理完成并报告 commit 后，lead 才做 Portal 侧的集成改动。

## 冻结决策

1. **v1 不做**：`shareKeyVfs` capability、admin socket、Provider key rotate/自删/过期、跨 Portal pending Revoke、master 两阶段轮换、永久 tombstone、多 Portal、tailnet 专用 profile、远程 Consumer。全部按合同 §8 deferred。
2. **Portal listener**：Portal 顶级 Relay 页面管理 `/relay/`；公共 Provider 端点在 `/relay/v1/provider`（WS）与 `/relay/v1/provider-keys`、`/relay/v1/links`（HTTP，自行 Bearer 校验）。另起 `<stateDir>/run/vfs-relay.sock` 只挂 Consumer 的 `/v1/request`、`/v1/response/{consumerId}/{linkId}/{reqId}`、`/v1/health`。旧根 `/v1/provider*` 返回 404。
3. **Provider key**：`POST /relay/v1/provider-keys` 用 Relay token（`relay_...`）+ 32B deviceSecret，首次创建、之后 issue-once。Portal 用单 master AES-256-GCM 加密存 `provider-keys.json` 0600；master 丢失即 503，不做轮换。管理员在 Relay 页面撤销设备。
4. **Link store**：`vfs-relay-links.json` + `vfs-relay-links.initialized`，atomic+fsync；Revoke tombstone 保留 30 天；未登记不 claim；同 owner 幂等，不同 owner 409。
5. **CLI**：Portal 仓库 `lib/vfsc-cli.mjs` + `bin/vfsc`；`$HOME/bin` 安装；默认 stateDir `~/.clawdy-portal`，测试用临时 `PORTAL_STATE_FILE`；Unix socket 无 transport auth；key store 0700/0600 atomic+fsync。
6. **App**：新包 `io.nekohasekai.sfa.vfs.relay`，独立 `VfsRelayRuntime`/`VfsRelayState`；复用现有 source 的 stat/list/read 与 FGS 外壳；绝对路径和分页在新适配层完成，**不改** 旧 `VirtualDirectorySource` 签名和旧 VFS 路由。`SHARE_KEY_VFS` build flag 默认开启，API21 flavor 隐藏。
7. **App Relay Server 配置**：Settings → VFS Relay Servers 保存本地列表（完整 `/relay/v1` URL + Relay token，Keystore 加密）；Relay 自有 `relayDeviceId`，不复用 MCP config。Test connection 只验证 URL/token/device，不创建 Share、不打开 Provider WS。
8. **Scope v1**：`app.data.*`、`app.files`、`app.workspace`、`user.selected`、`all`；`all` 不含 `user.selected`，硬排除物理 `vfs/` 与 `conductor/`。Recommended=app.data.*；Everything=固定 scope；Customize 全选/逐项。
9. **Pairing 与连接**：16 字符 Crockford、5 分钟、单次；New code 同 Share Key；无手动 Connect/Stop。至少一个 active Share 时自动连接；最后一个 Share 撤销后自动关闭；App 重启仅在仍有 active Share 时重连。Revoke=tombstone；数据请求只凭 Share Key，不因 pairState 阻断。
10. **Wire/crypto**：以参考文档附录 A 为 source of truth；Kotlin 与 Node 必须逐字节复现 A.1-A.6；AAD 字段顺序、cursor wire、at-rest AAD 不得自行改。

## 实现顺序

1. Portal：provider key + link store + WS/register + Unix consumer + CLI pair/list/read；假 Provider 测试。
2. App：crypto/store/pairing + Relay client + source 适配 + 最小 Share UI；JVM 测试。
3. 联调：本地 Portal + CLI + App debug；再真机 + Mini；最后 Release CI。
4. 发版：`WONDER_BUILD` 下一标签（当前 AV），五步收尾。

## 证据

| 项 | 证据 |
|---|---|
| 合同/参考拆分 | commit `b47788f`（origin 推送待网络恢复） |
| Portal 侦察 | server.mjs:1268-1277 listener；handleRequest:560；handleUpgrade:1195；hooks.mjs:70-173；install-launchd.sh:25-28 |
| App 侦察 | VirtualDirectoryProtocol.kt:78-212；VfsSharingService.kt:36-268；SettingsScreen.kt:313；Navigation.kt:642；McpRuntime.kt:101-112 |
| Portal 实现 | `clawdy-portal` `4b096d4`；`pnpm check` 208/208；部署 `~/.clawdy-portal` 成功，socket 0600，`/v1/health` ok |
| App 实现 | WonderSing `dev`；VFS 入口在首页 Dashboard 卡片，Relay 配置在 Settings → VFS Relay；829 JVM tests / 0 failures；Links UI、卡片操作与 Pairing 弹卡已本地渲染确认 |
| CLI | `vfsc`（Portal commit `4b096d4`）；安装到 `$HOME/bin/vfsc`；`links`、`status --probe`、命令前后选项可用；Portal 208 用例绿 |
| 真机 E2E | 待主人后续引导：AY 安装、Link/pair/list/read/Revoke/自动连接/离线/重启/Doze |
| Release | AY 已发布：`v1.15.0-alpha.13-wonder.AY`；APK 35,347,962 B，sha256 `b8b300ecd735a848d7ef9813cab7cf7afc38f8730517349f96280a51b3b3cae5`；Links UI、VFS Relay 入口、Dashboard 顺序、CLI `links`/`status --probe`。 |

## 风险

- Portal 生产部署会影响现有服务；先在临时 stateDir/端口联调，部署前单独确认。
- VFS 功能入口只在首页 Dashboard 卡片，Relay 配置只在 Settings；旧 VFS access 页面与状态条已删除，新 VFS 不读取旧 VFS 的 Share/连接状态。
- cursor/1 MiB 页与绝对路径适配是 App 侧最大实现风险；先写 JVM 测试再写 UI。
- Relay Server token 只保存在本地 Keystore；Test connection 和自动连接都只访问配置的完整 `/relay/v1` base。

## v1 已知限制

- `app.data.groups` 目前固定 `{"available":false,"groups":[]}`：Conductor group state 在 ViewModel 内，没有全局源；不在 v1 修旧架构。
- 真实 Keystore 只有编译 + fake cipher JVM 测试；等真机安装后验证。
- Egress 的 `VpnService.protect`/underlying DNS 只有注入测试；VPN on/off、per-app include、规则切换需真机 E2E。
- 重复 JSON 键由 kotlinx 宽松解析；不作为 v1 阻塞。
- 每次创建 Share 或恢复服务会 issue-once 取当前 Provider key；Portal 5/min 限速下快速重试可能 429。
- 跨 Portal pending Revoke、永久 tombstone、Provider key rotate/自删、admin socket 均为 post-v1。

- AR：隐藏旧 VFS access；首次进入不自动选 Portal；文案改为 paired client / a Portal，并说明 Portal 只转发加密数据。

- AS：Portal Relay 顶级页面与 Relay token；App Share files 改为本地 Relay Server 列表（Add/Edit/Delete），不再读 MCP；旧 MCP-derived config 不加载。
