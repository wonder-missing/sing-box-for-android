# Share Key VFS 参考设计（非实现门槛）

> 本文件保存历史协议细节、边缘状态、评审历史与 golden vectors，供实现和排障查阅；**不作为 v1 实现验收门槛**。日常设计与实现只读 [v1 合同](vfs-share-key.md)；两者冲突时以合同为准。**AS 起 Portal 把 Relay 作为顶级服务，Provider key 用 Relay token 认证，不再使用 hook secret/MCP；本文中 hook/MCP/capability 认证段落属于历史参考，已被合同取代。** 本文件的 master 轮换、admin socket、跨 Portal pending Revoke、永久 tombstone、Provider key 自删/rotate 等属于 post-v1 参考。

状态：参考文档（基于 v16，2026-11-26）；保留 P0 vectors + 控制帧/inner/cursor golden；未实现。
相关旧路径：`~/workspace/clawdy-portal/VFS.md`、`~/workspace/clawdy-portal/docs/agent-oriented-vfs.md`、`~/workspace/clawdy-portal/docs/jobs.md`、[`vfs-dry-run.md`](vfs-dry-run.md)、[`vfs-connection-stability.md`](vfs-connection-stability.md)、[`mcp.md`](mcp.md)。本设计通过后按 §3.12 改写或标退役。

## 0. v16 修复摘要

| 评审项 | v16 处理 |
|---|---|
| B1 cursor 是裸 HMAC，Provider 无法恢复 lastKey；list 排序未定义 | cursor 改为可逆 wire `0x01||u32be(len(lastKey))||lastKey||tag16`；entries 按 `entry.path` UTF-8 bytewise 升序，Share 内 path 唯一；tag 绑定 linkId/path/limit/scopeVersion；lastKey 被删/改名或绑定不符 → `cursor_invalid`；补 Kcursor/cursor golden。 |
| B2 同一 deviceId 多 hook 时 pair/unregister 只校验 deviceId | `register`/`pair`/`unregister` 及该 WS 的数据帧统一校验 auth `(hookId,deviceId)` == link owner；同 deviceId 不同 hook 不能互相 pair/unregister/tombstone。 |
| B3 §4.2 pending revocation 模型缺 Portal 归属 | §4.2 与 §3.3 对齐：补 `portalServerId/canonicalBase/hookId/deviceId/linkId/reason/providerKeyId?/pendingUntil` 与 Share `revoking`；定义 401/403 `DEVICE_REVOKED`/410 清理；切换 Portal 仍只向 owning Portal 重试。 |
| B4 master loss 用 4414，但 4414 在 App 契约里是永久停止 | master loss 改 close 4413，App 用 deviceSecret issue-once 恢复；4414 只保留给 device/hook 永久删除；补端到端测试。 |
| B5 Relay pairState 内存/持久表述互相矛盾，重启后无状态分支未定义 | pairState 明确为 **App 持久、Relay 内存**；Relay 重启后无 pairState：WS 未注册 → 503 `PROVIDER_OFFLINE`，WS 已注册 → 503 `PAIR_WARMING`；paused 仅在 Relay 仍有该状态时直接回答。 |
| High | Provider paused 兜底 `share_paused`；Share `registrationState/registeredAt`；base 变化先重加密 at-rest；自然过期用 `expired` 不发 paused；pair 只要求 link store 登记；CLI/portal-knock 默认 stateDir；provider-key store marker；link cap 含 offline；CLI 默认命令统一；inner→CLI 映射；pair 帧必需字段；负向向量；at-rest AAD golden；tailnet/Serve 不可区分；admin socket 判定；PAIR_WARMING 超时；tombstone 永久；pairEpoch wire 初值；waiting/Disconnect 语义。 |

## 1. 背景与目标

现有 VFS 由 Conductor 通过 MCP `vfs_link_request` / `vfs_connect` 建立 Link，Portal 把 provider WebSocket 交给 vfsd，容器通过 `/vfs` 挂载读取。它已经能工作，但概念和运维都重：MCP、vfsd、FUSE、Docker、Link、consent 五层；Portal 能接触明文；App 升级/进程死亡后 Link 不自动恢复；大文件在蜂窝 + Tailscale 上实测 25–28 秒/MB。Conductor 是试验品，App 的核心是规则化网络出口选择；VFS 应该退回成简单、可独立演进的观测数据出口。

目标：

- Claude Code Agent 在 Mini 上直接读取 App 的 VFS，不用手动导出、不用容器挂载。
- **数据面协议不向 Portal/Relay 提供明文**，只给路由和大小等元数据；v1 视 Portal 主机/同用户为可信（见 §2.2），不宣称主机被攻破仍读不到。
- 用户只做一次 Connect，之后每个 Agent 建一个 Share、粘一次 5 分钟 Pairing Code。
- Consumer 侧只提供 CLI；不引入 MCP server。
- 保留所有已有 VFS scope 的数据能力，但重新命名、分组、给出人类可读的警告。
- 不降低当前后台可用性：FGS 活着时后台可读；进程死/Doze 后 503。

非目标：

- 不做 Consumer 端 MCP。
- 不做容器 `/vfs` / `job_run(vfs=true)` / `vfs_export` 的替代挂载；本设计通过后这条路径退役。
- 不做 always-on provider、不做 FCM 唤醒、不做 24/7 保证。
- 不做 PFS、账号/多租户、跨账号分享、反向直连 HTTPS。
- 不兼容旧 Conductor VFS 工具、Link 格式、trust/resume 存储。

## 2. 概念设计

### 2.1 角色与术语

| 术语 | 含义 | UI 用词 |
|---|---|---|
| App / Provider | 数据在手机上；主动连 Portal/Relay；验证 Share Key；按 Share 的 scope 读 VFS。 | App |
| Agent / Consumer | 远端 Claude Code；用 `vfsc` CLI 读。 | Agent |
| Portal / Relay | 盲路由；鉴权 transport、按 Link ID 转发密文、限速/审计元数据。 | Portal |
| Share | 一次授权：label + scope 集合 + 一个 Link；一个 Share 对应一个逻辑 Agent；密码学上只有一把 Share Key，持有者等价。 | Share |
| Pairing Code | 5 分钟、一次性、16 字符 Crockford base32。 | Pairing Code |
| Share Key | 长期 AEAD 密钥；CLI 保存，用户不直接管理。 | “the key the Agent saves” |
| Scope | Share 允许读的路径组。 | What the Agent can read |
| New code | 同一 Share 的新 Pairing Code；不换 Share Key。 | New code |
| Revoke | 永久删除 Share/Link/Share Key。 | Revoke this Share |
| Stop sharing | 暂停所有 Agent 的 transport；Share/Key 保留。 | Stop sharing |

用户需要理解的 **五个主概念**：App、Agent、Portal、Share、Pairing Code。Share Key 由 CLI 保存，用户只需知道“Agent 会保存一把 key”，不需要手动管理。Provider/Consumer、Link ID、deviceId、generation、reqId、AEAD/HKDF、202+poll 不出现在主 UI。

### 2.2 信任边界

- **Transport auth**：App 默认复用现有 Portal 连接，自动换/发一个 Relay 专用 Provider key；本机 Agent 走 Unix socket，免 transport key；远端 Consumer v1 不开放。Transport key 只防滥用，不授予数据。
- **Data auth**：Share Key。App 和 CLI 各持有；Portal/Relay 永远看不到。
- **Authorization**：App 按 Share 的 scope 逐请求检查；Portal 不解释 scope。
- **Consent**：创建 Share、生成 Pairing Code 就是授权；每个敏感 scope 有自己的红字/黄字警告；`all` 的警告是并集。

必须对用户说清楚：

- **Portal 看得到**：哪个 Share、什么时候、多少字节、**完整 Provider key 与 deviceSecret**（Provider key 由 master 可逆存储；deviceSecret 只在 issue/rotate 请求中出现，at-rest 只存 SHA-256，不可逆；审计日志只记前缀/身份；Consumer key v1 后置）、Link ID、pairId、reqId、op、ts；看不到文件内容、路径、scope、label、inner error。
- **Agent 看得到**：你分享的文件；Agent 可能把它们发给它的模型提供方。
- **Pairing Code**：一次性、5 分钟、在聊天里可见；看到它的人在这 5 分钟内可能抢先配对；`pairId` 是公开路由，不是 secret。
- **Share Key**：由 Agent 的 CLI 保存；Portal 读不到；Agent 宿主机被信任（同一用户可读 CLI 的 key store）；不要宣称模型绝对拿不到 key。
- **Relay 不可读内容（协议保证，主机可信前提）**：密文和 AEAD tag 保证 Relay 的**data payload**不能读/伪造/篡改；Portal 仍能看到路由元数据，可以丢包、延迟。**明文控制帧（pair-error/link-error/ack/error）不防伪造**：恶意/被攻破 Relay 可注入或延迟，CLI/App 只把它们当提示，不作为数据真实性证据；`message` 必须是固定短语，不含路径/scope/文件名。但 v1 Portal 与 CLI 同机、同 macOS 用户（`launchctl gui/$uid`），同一用户或被攻破的 Portal 进程可以读 CLI key store（`~/Library/Application Support/vfsc/keys.json`）和 Unix socket，从而解密 Share。**v1 把 Portal 代码与同机用户视为可信**；要抵抗 Portal 进程被攻破，需独立 OS 用户/VM 或 Keychain ACL/独立用户，见 §2.5。
- **必须对用户说清楚（补充）**：UI/文档不能宣称“Portal 宿主机被攻破也读不到”；准确说法是“正常运行的 Relay 只转发密文；Portal 主机/同用户进程可信”。
- **可用性**：读取只在 WonderSing 的 sharing 通知/FGS 活着时可用；进程死/Doze 时 Relay 在 WS close/写失败时立即 fail pending 为 503，半开连接最长约 90s 心跳超时后 503；重开 App 再试。不承诺 24/7。

### 2.3 用户模型（一页话术）

> WonderSing 连上你的 Portal 后，创建一个 Share：起个名字，选 Agent 可以读的文件。复制 App 给的一行配对命令（5 分钟有效）到 Agent 聊天。Agent 的 CLI 用它换一把自己保存的 key，然后 list/read 这些文件。Portal 正常运行时只转发加密数据和路由元数据，看不到内容；Portal 主机与同用户进程按 §2.2 视为可信。撤销 Share 就断掉访问。

### 2.4 用户流程

一次性设置：

1. 安装 `vfsc` CLI（Portal `pnpm service-install` 装到 `$HOME/bin`；Claude Code skill/CLAUDE.md 告诉 Agent 命令）。
2. Settings → VFS Relay → 选择 Relay Server（Advanced 可手填完整 `/relay/v1` URL + Relay token）→ Test connection。

每个 Agent：

3. New Link → 名字（可选）→ Recommended / Everything / Customize → Create。
4. Copy pairing line（5:00 倒计时）；App 复制一行无换行文本 `Use "$HOME/bin/vfsc" to access my shared files: Link ID=<linkId>, Pair Code=<code>.`；CLI 从 code+linkId 派生 pairId，并支持 `--code-stdin`/`VFSC_CODE`。code 会进入聊天/模型上下文，见 §2.5。
5. 粘到 Agent 聊天；Agent 运行命令，返回它能看到的 roots。
6. Agent 运行 `"$HOME/bin/vfsc" list/read ...`；不再需要 code。撤销 Share → Revoke。

### 2.5 已接受的取舍

- Pairing Code 会进入聊天/模型上下文；一次性、5 分钟、兑换后失效；长期 Share Key 不打印、不进聊天。用户已拒绝 out-of-band CLI/QR。argv 还可能进 shell history/`ps`/tool transcript；App 提示、CLI 支持 stdin/env，不宣称绝对不泄露。
- `all` 可以包含敏感 App 文件，但必须有红字警告；物理 `vfs/` 与 `conductor/` 永久排除；`app.files`、`app.workspace` 各自也有红字警告，不只 `all`。
- 可用性保持现状：FGS 活着时后台可读；进程死/Doze 后 503；不做 always-on。
- 无 PFS；静态 Share Key + 短 TTL/Revoke；以后可加 X25519 ephemeral。
- Relay 元数据（Link ID、pairId、reqId、时间、大小、key 前缀）可见；不记录内容/路径/scope/label/inner error。
- v1 Portal 与 CLI 同机同 macOS 用户；Portal 代码/同用户可信，Portal 进程被攻破可读 CLI key store 并解密；不宣称主机隔离；需要强隔离时 Portal 独立用户/VM 或 Keychain ACL。
- Share 功能要求 API 24+；API 21 `otherLegacy` 隐藏新旧 VFS（含 `appfiles`；其 MCP token 是明文）。
- 容器 `/vfs` 退役；Conductor 的手机侧 VFS 工具移除；CLI 是替代。
- 保留 `user.selected` 和细粒度 `app.data.*`；实现可分阶段，设计保留。
- Share 默认不过期；`lastUsedAt` 记录；以后可选 TTL。Stop sharing 只是暂停 transport，Share/Key 保留；Revoke 才永久删除。New code 不 eject 已配对 Agent。
- Reinstall 会清 `noBackupFilesDir`、Keystore key 和 deviceSecret：旧 Share 不能在新装里使用/撤销；用同一 knock 执行 `portal-knock provider-keys list --hook <hookId>`，再 `portal-knock provider-keys delete --hook <hookId> --device <id>` 撤销；新装生成新 deviceId 并新建 Share/重新配 CLI。旧装未 Revoke 前旧 Share 仍可能可用，不能声称自动失效。

### 2.6 备选方案

- **Consumer MCP**：对 Claude Code 多一层 server 配置、进程、握手；CLI 足够。以后非 shell Agent 再加薄 wrapper。
- **Direct HTTPS over tailnet**：手机入站、NAT、Doze 不可靠；App 已经会出站。Relay 更合适。
- **Push/snapshot**：不 live，且把数据放到 Relay，违反盲路由目标；拒绝。
- **旧 vfsd/容器**：能工作但复杂，含 MCP/FUSE/Docker/Link/consent 和 stale mount；退役。
- **Relay 侧 owner/consumer-provider 绑定**：换手机要改绑定；拒绝。Share 自己就是授权。
- **Out-of-band CLI/QR pairing**：用户已拒绝；用一次性 Pairing Code。

## 3. 方案设计

### 3.1 总体架构

```
App (Provider) --WSS /v1/provider--> Portal/Relay
                                        ^
Agent CLI (Consumer) --Unix socket--> Portal/Relay
```

- App 只出站；Portal 按 Link ID 路由不透明 envelope；不需要 vfsd/FUSE。
- Agent CLI 把 VFS 请求加密；App 解密、验 scope、读 VFS、加密响应；Portal 只转发。
- 一个 Share 一个 Link ID；App 只维护一条 Provider WebSocket，多 Link ID 用 register/unregister 复用，上限 8。
- Relay 持久存 `vfs-relay-links.json`（linkId -> hookId/deviceId/createdAt/revokedAt），内存存 `linkId -> connection`、pair 路由、bounded response cache；不存 secret/scope/label/path。
- v1 公网 listener 开 Provider WS + `/v1/provider-keys`/`/v1/links` 登记（豁免门户登录）；Consumer `/v1/request` 只在 Unix socket；远端 Consumer 后置。

### 3.2 App / Portal 配置与 Provider key

- **Relay 选择**：Settings → VFS Relay Servers 保存本地列表（完整 `/relay/v1` URL + Relay token，Keystore 加密）；用户选一个作为当前 Relay，App 用 Relay token + deviceSecret 领 Provider key，不再读取 MCP server/hook secret。Advanced 可手填 URL/token；Test connection 只验证 URL/token/device，不创建 Link。
- **Provider key 发放（device-bound）**：App 首次生成 32B `deviceSecret`（按 `(hookId,deviceId)` 存 `noBackupFilesDir/vfs/device-secrets/<hookId>/<deviceId>`；首次 issue 前暂存 `pending/<hash(canonicalBase|deviceId)>`，拿到 hookId 后原子迁移，`VfsShareCipher` 加密）；`POST /v1/provider-keys`（hook secret + `shareKeyVfs`）body `{deviceId,name,deviceSecret}`；`deviceSecret` 是 32B base64url 无 padding，`providerKey` 为 `wsk_` + 32B base64url，`providerKeyId` 为 16B base64url，响应含 `hookId`。首次创建 `(hookId,deviceId)` 记录并保存 `SHA-256(deviceSecret)`、返回 key；同 device 再调必须带同一 secret（constant-time 比较），且仅在当前 key active 未过期时返回当前 key（issue-once）；secret 不匹配 → `403 DEVICE_AUTH`；deviceRevokedAt 非空 → `403 DEVICE_REVOKED`；keyRevokedAt 非空 → 发新 key；过期 → `403 ROTATE_REQUIRED`（用 rotate）。hook secret 单独不能取回已有 device 的 key；`deviceSecret` 不进日志/审计/URL。
- **管理面**：`GET /v1/provider-keys` 用该 device 的 Provider key 只返回自己；admin 用 `GET /v1/provider-keys?hook=<hookId>` 列该 hook 全部；删除任意 device 只走 Portal 管理员（登录会话或本机 `portal-knock provider-keys delete --hook <hookId> --device <deviceId>`），hook secret 单独 → `403 ADMIN_REQUIRED`。`DELETE /v1/provider-keys/{deviceId}?hook=<hookId>` **以 `(hookId,deviceId)` 为单位**：admin 删除 → 标记 `deviceRevokedAt`、关闭 WS（4414）、tombstone 该 `(hook,device)` 的 Link，之后该组合 issue/rotate `DEVICE_REVOKED`；该 device 的 Provider key 自删只标记 `keyRevokedAt`、不 tombstone Link（links 变 offline），之后带 deviceSecret 的 issue-once 可发一把新 key 恢复。不得跨 hook 删除/tombstone；capability 移除只是 suspend（见下），不是 delete。
- **Rotate**：`POST /v1/provider-keys/rotate` body `{deviceId,deviceSecret}`（或该 device 的 Provider key）撤销旧 key、返回新 key；关闭旧 WS；**不 tombstone Link**；App 用新 key 重连并重注册同一 linkId，Share Key/Agent 不需要重配对。响应丢失时重发 issue-once 取当前 key。
- **丢失设备**：admin `portal-knock provider-keys delete --hook <hookId> --device <id>` 永久撤销该 `(hook,device)`；丢失设备即使持有 hook secret 也不能再取该 device 的 key。它可用新 deviceId 重新领 key，但拿不到旧 Share store/Share Key；要彻底断掉该 App，使用 hook delete 或 admin delete 该 device；disable/移除 capability 只是 suspend。新手机用新 deviceId + 新 Share。
- **Hook/device 模型**：`(hookId,deviceId)` 在同一 hook 内唯一；**同一 `deviceId` 可同时注册在多个 hook/Portal 下**（每个 `(hookId,deviceId)` 独立 Provider key/deviceSecret），不再有跨 hook 的 `DEVICE_OWNED`。`deviceId` 是 per-install UUID；**存储主键是 `(hookId,deviceId)`**（`relayAccount`），每条记录带 `canonicalBase`/`portalServerId`；同一 base 的两个 hook 是两条独立 record，但同一时刻只有一条 Provider WS（切换 hook 即切换当前 Portal）。`deviceSecret`/Provider key 按 `(hookId,deviceId)` 存；Share 绑定 `(hookId,deviceId,canonicalBase)`。deviceId 由 Provider key 认证绑定；`register` 自报不同 deviceId 被拒；Provider WS 上的 `register`/`pair`/`unregister` 及该 WS 的数据帧统一要求 auth `(hookId,deviceId)` == link 记录 owner；Link 仍全局绑定 `(linkId -> hookId,deviceId)`。
- **Store 容量**：App 显示当前未 revoke link 占用（含 offline）；配额 8/64；revoked tombstone 不设硬上限、不会被正常使用撑死；admin prune 只删已 deviceRevoked 记录。
- **切换 Portal**：切换只影响当前 `portalServerId`；旧 Portal 的 Share/Provider key/deviceSecret 保留，切回旧 Portal 可继续用；新 Portal 用同一 deviceId 但新 hook 领独立 key、建新 Share。CLI 本地 socket 只连当前 Portal，旧 Portal 的 Share 在切换期间不可读（404/503），不是被 Revoke。
- **Link 所有权**：`linkId` 绑定认证得到的 `hookId+deviceId`；`providerKeyId` 只做审计。同 hook+device 的新 provider key（rotate 后）可重注册旧 Link；不同 hook/device → `LINK_OWNED`。
- **Link 归属登记（必须先于暴露 linkId）**：Share 创建时 App 先 `POST /v1/links {linkId}`（Provider key auth）；Portal 持久化 `linkId -> hookId,deviceId,createdAt,revokedAt?` 到 `<stateDir>/vfs-relay-links.json` 0600/atomic/独立 lock，再允许显示 Pairing Code。同 linkId 同 owner 且未 revoke 幂等；revoked → `410 REVOKED`；不同 owner → `409 LINK_OWNED`；未登记或 store 丢失 → `LINK_UNKNOWN`，**不自动 claim**。per-`(hookId,deviceId)` **未 revoke** link ≤8、per-hook ≤64（**含 offline**；Revoke 后 tombstone 不占名额，立即释放配额）；active WS 并发另按 §3.6 Limits；**revoked tombstone 不设硬上限、永久保留防复活**，admin prune 只删 deviceRevoked 记录；从未 register 的 orphan 30 天后可清；超出配额报 `429 LINK_LIMIT`；Relay 重启后 ownership/tombstone 仍在。
- **device-id 丢失**：Share store/deviceId 丢失后旧 Link 不可恢复；新建 Share/重配。deviceSecret 丢失但 deviceId 仍在时，不能 rotate/issue；admin delete 旧 device，App 生成新 deviceId + 新 Share。
- **Hook 能力**：`hooks.json` 增加 durable `capabilities:["shareKeyVfs"]`（缺省 = 空数组，不隐含授权）；`POST /v1/provider-keys`、`POST /rotate` 和 Provider WS 都检查 `enabled && shareKeyVfs && !expired`；`GET`/`DELETE` 允许 admin 或该 device 的 Provider key 做清理（capability 撤销后仍可 delete）；`maxCalls` 只对 MCP tool calls 计数，不阻塞 Relay transport。hook **disable/expire/移除 capability = suspend**：关闭 WS 4401，keys/links 保留；重新 enable/grant 后 App 用 deviceSecret issue-once 恢复并重连。hook **delete = 永久**：revoke keys、tombstone 该 hook 全部 links、close 4414。Provider WS 连接时和每 5 分钟重新验证；**hooks 状态变更通过 admin socket 立即 close WS 并 tombstone；5 分钟只作兜底；数据转发前也校验 hook 状态**。**`shareKeyVfs` 只允许 `handler=mcp`；inbox 的 maxCalls/disable 不得影响 transport。`sweepHooks` 对带 provider key/link 的 hook 只 suspend、不删除；所有删除入口（CLI/admin UI/API）统一走 revoke keys + tombstone links + close 4414。**
- **能力发放/迁移**：`portal-knock`、admin API（`/knocks/api`）、`public/knocks.html` 增加 `capabilities` create/update/list（`portal-knock capabilities list|grant|revoke`）；现有带 `vfs_link_request` 的 hook 在 P2 显示 one-click grant，不静默迁移；App 选到无 capability 的 server 时只用 `POST /v1/provider-keys` 探测，403 `CAPABILITY_REQUIRED` 就显示 `portal-knock capabilities grant <hookId> shareKeyVfs` 指引，不缓存错误。OAuth-only server v1 不支持 Share Key VFS；不引入 App OAuth client/refresh/scope。
- **Portal 识别/绑定**：**存储主键是 `(hookId,deviceId)`，`canonicalBase` 是记录字段**；同一 base 的双配置若解析出同一 hookId → 复用同 deviceSecret/Provider key；若不同 hookId → 两条独立 record（同一时刻只连一个）。base 变化时先用已有 `(hookId,deviceId)` 的 deviceSecret 调新 base 的 issue：Portal 仍有该 hook/device → 成功；随后用旧 AAD 解密、新 AAD 原子重加密 deviceSecret/Provider key 并更新 canonicalBase（失败则保留旧记录与旧 base）；`403 DEVICE_AUTH` → 必须 admin delete 或新 Share。App 只把 token 发到选中 server 的 canonical base，不自动推断。
- **状态目录**：`<stateDir> = dirname(PORTAL_STATE_FILE)`；部署默认 `~/.clawdy-portal`，`service-install` 在 plist/env 显式写 `PORTAL_STATE_FILE=~/.clawdy-portal/state.json`。源码直接跑沿用仓库根 `state.json` 仅作开发默认，必须在仓库外显式设置 `PORTAL_STATE_FILE` 才能部署；该开发默认不受“stateDir 不得在仓库内”约束，但不得用于发布。所有 store/socket 跟随 `PORTAL_STATE_FILE`；`vfsc`/`portal-knock` 未设 env 时默认 `~/.clawdy-portal/state.json`，可用同一 env 覆盖；测试/E2E 用临时目录；`provider-keys.json`/`provider-keys.initialized`/master/`vfs-relay-links*`/`run/` 加入 `.gitignore`。
- **Master key**：`<stateDir>/vfs-provider-key.master` 0600、`{v:1,current:<32B b64url>,previous?:<32B b64url>}`；Provider key 加密存 `<stateDir>/provider-keys.json` 0600、`{v:1,masterVersion,keys:[{...,keyVersion}]}`、atomic；值格式 `0x01||iv(12)||ct||tag(16)`，JSON 字段用标准 base64url 无 padding，AES-256-GCM **Portal master 侧 AAD** `v1|hookId|deviceId|providerKeyId`。**master 轮换两阶段**：先写 master `current=新, previous=旧`（atomic），再用新 master 全量重加密 keys（atomic），最后清 `previous`；任一步崩溃，旧/新 keys 都能用 current/previous 解开。读坏 → 视为 master-loss：所有旧 key 标记 revoked、close 4413（可恢复，不是 4414）；App 用 deviceSecret 调 issue-once，用现存 `deviceSecretHash` 发新 key，不返回旧 key、不自动清空文件；`service-install` 不得覆盖/删除。`provider-keys.json` 缺失/损坏与 master 丢失分开：`<stateDir>/provider-keys.initialized` marker 存在但文件丢失 → 503 `RELAY_STATE_UNAVAILABLE`，禁止自动 claim；首次初始化先写 store 再写 marker；合法 store 存在而 marker 缺失时 adopt 并补 marker；store 与 marker 都不存在 → 全新初始化；admin 重建/删除 device；`portal-knock provider-keys rotate-master` 执行；丢失 master 的恢复步骤写进 runbook（删除并重发全部 device key；provider key 丢失可 issue-once 恢复，Share Key 不变；deviceSecret/deviceId 丢失才需重配）。
- **Store 锁**：`provider-keys.json`/`vfs-relay-links.json`/master/marker 都 atomic+fsync（文件与目录）；`provider-keys.json` 用独立 `provider-keys.lock`（不是 `hooks.json` 的 `withStore`）；**全局锁序 hooks → provider-key → link，任何路径不得反向**；hook disable/delete 先写 hooks 状态，再撤销 keys/links；崩溃后 5 分钟 revalidation 补撤销，不留下可用 key。`rotate-master` 持同一 `provider-keys.lock`，与 issue/rotate/delete 串行，顺序为读 master→写 master→重加密 keys→清 previous。
- 更新 `AUTH.md`/`HOOKS.md`：`/v1/provider-keys` 的 auth（hook secret + `shareKeyVfs` + deviceSecret；GET/DELETE admin/self）、device 绑定、Origin、限速、审计；不记录 secret/deviceSecret；OAuth token 仍只用于 MCP，不用于 provider-key。

### 3.3 Share 与 Pairing Code

- Share 创建时生成 128-bit `linkId` + 32B `secret` + 16B `shareId`；先持久化 `pending` Share（含 linkId/secret、`registrationState=pending`），再 `POST /v1/links {linkId}` 登记归属；POST ack 后持久化 `registrationState=registered` 与 `registeredAt`；瞬时失败保留 pending 重试，永久 4xx 标 `registrationState=failed`（UI 显示错误、不自动重试）；启动时 `registrationState=pending` 且无 `registeredAt` 的重试 POST，已 POST 但落盘失败则 best-effort unregister revoked/orphan 供 admin prune；一个 Agent 一个 Share；多个 Agent 建多个 Share；上限 8，超出提示先 Revoke。
- Share 记录：`shareId,label,linkId,secretCiphertext,scopes,portalServerId,hookId,canonicalBase,deviceId,generation,registrationState(pending|registered|failed),registeredAt?,pairEpoch,pairId?,pairState(waiting|active|consumed|paused|expired),pairExpiresAt?,revokingAt?,createdAt,pairedAt,lastUsedAt,expiresAt(null),revokedAt`。`deviceId` 是现有 `VfsDeviceId` 的 per-install UUID（不再有独立 `instanceId`）。`scopes` 存**创建时展开的 concrete scope 集合**；`all` 只在 Create 时展开一次，以后新增 scope 不自动进入旧 Share。存 `noBackupFilesDir/vfs/`，不进 telemetry/export；`lastUsedAt` 最多每 5 分钟写一次。**公开 `pairId`/`pairState`/`pairExpiresAt` 持久化；code 仍 memory-only。** `waiting` 是 Share 已建、尚未生成第一个 code 的初始 UI 状态，不发给 Relay；New code 后变 `active`。
- `deviceSecret` 32B 首次生成，按 `(hookId,deviceId)` 存 `noBackupFilesDir/vfs/device-secrets/<hookId>/<deviceId>`；首次 issue 前暂存 `pending/<portalServerId>/<hash(canonicalBase|deviceId)>`，pending AAD 用 `v1|<canonicalBase>|<deviceId>|deviceSecret`（**不含 hookId**）；拿到 hookId 后重新加密并原子迁移到 `device-secrets/<hookId>/<deviceId>`（AAD 含 canonicalBase/hookId/deviceId）；provider key issue/rotate 必带；不进日志/export。不同 Portal 不复用 secret。
- **创建/New code gating**：只有 Provider WS Connected 时才能建 Share/New code；`POST /v1/links`、`register`、`pair active` 都拿到 ack 后才显示可复制命令；Stop/离线时按钮禁用并提示先 Connect。避免生成 Relay 不认识的命令。
- Label：可选，最长 64，只允许可打印字符、去控制字符/换行；CLI 输出转义；重复 label 允许；`--link` 用 `linkId` 或唯一 label，重复 label 报 `LINK_AMBIGUOUS`。
- **Pairing Code**：16 字符 Crockford base32（无 I/L/O/U），输入规范化大写/去空格连字符，并按 Crockford 别名 `O→0`、`I/L→1`、`U→V` 归一；code/linkId/secret/deviceSecret 用 `SecureRandom`；pairId 由 `HKDF-SHA256(codeASCII, salt=linkId_raw, info="wsvfs/pair/id", 16)` 派生；5 分钟；单次；一个 Share 同时只有一个 active code。**不设 5-attempt 上限**；用 80-bit + 5 分钟 + per-Link rate limit；无效 AEAD tag 只限速，不烧 code。
- **PairingCode（memory-only）**：`{shareId,pairId(16B public),code(ByteArray ASCII 16),createdAt,expiresAt,consumedAt}`；只在 App 内存；code 用 `ByteArray`/`CharArray`，**best-effort** 不落盘/不进日志/不进通知；Kotlin/JSON 边界不可避免的短生命周期 `String`（UI 输入、命令拼接）不持久化、不打印；消费/过期/进程死 `fill(0)`。不持久化 `codeCiphertext`/`codeHash`。Provider 按 linkId 找 Share 的 active pairId/code。
- **pairState 持久化**：New code 成功生成后持久化 `pairId/pairState=active/pairExpiresAt`；配对成功 → `consumed`（保留 pairId）；Stop → `paused`；code 自然过期 → `expired`（**不发** `pair paused`；Relay 未重启时按 `pairExpiresAt` 返回 `code_expired`，重连时补发 `expired`）；进程死亡后 code 丢失：**以持久 `pairState` 为准**；`consumed` 保持 `consumed`（用当前 pairId，不把 active New code 误标 consumed）；`active` 但 code 丢失 → `expired`（需 New code）；`pairedAt` 不用于推断当前状态。进程死亡会丢 memory-only pair response cache；`consumed` 只恢复状态，不承诺 exact tuple 可取，响应已丢失时 CLI 需 New code。Relay 重启/App 重连据此补发 `consumed`/`paused`/`active`/`expired`；Relay 未重启时也会按 `pairExpiresAt` 自然判定 `code_expired`。不再依赖 memory-only PairingCode 的 pairId。
- **pairId/Relay pair 状态**：16B 公开派生（code+linkId，不可逆）；pair envelope/AAD/cache key 都带；New code 生成新 `pairId+code`，旧 pairId 立即失效。App 向 Relay 发 Provider WS 控制帧 `pair {v:1,controlId,linkId,pairId,ttlSeconds,state,pairEpoch}`（只含公开 pairId/剩余 TTL/单调 epoch）；每个 active/paused/consumed/expired 状态变化都 `pairEpoch+1` 并随 Share 持久化；Relay 同 epoch+同 state 幂等 ack，只应用更大 epoch，更低 epoch 回 `STALE_PAIR_EPOCH`，防止迟到重试把 New code/Stop 回退。Relay 存 `activePairId+pairExpiresAt+pairState+pairEpoch`，purge 其他 pairId 的 pair response cache；旧/过期 pairId 请求得到明文 `pair-error`（§3.4.1），不是 404 Link；`consumed` 后同 pairId 的**新 tuple** 直接回 `code_consumed`，完全相同的 `(consumerId,pairId,reqId,clientNonce)` 仍返回已缓存响应直到 TTL。无需 secret/purge 帧，也不会 eject 已配对 Agent。
- App 复制一行无换行文本：`Use "$HOME/bin/vfsc" to access my shared files: Link ID=<linkId>, Pair Code=<code>.`；CLI 从同一 code+linkId 派生 pairId；`--code-stdin`/`VFSC_CODE` 是可选的 argv/shell history 规避方式。linkId/pairId 是公开路由，code 是 secret；复制文本只含 linkId 与 code。
- 无 QR；Pairing Code 不进通知/状态栏；页面不设置 `FLAG_SECURE`，用户可主动截图；clipboard 自动清；shell history 由 skill/CLAUDE.md 提示不要保留。
- 生命周期：**New code**（同 Share Key；新 pairId/code；旧 code/pairId 失效；不 eject 已配对 Agent；旧 pair cache 不可达）和 **Revoke**（先持久化 pending revocation，再 best-effort `unregister {reason:"revoked"}`；只有 owner 能建持久 tombstone；owner + link 不存在 ack 不建 tombstone；非 owner `DEVICE_MISMATCH`；再删除 Share/linkId/secret；cache purge；revoke 期间保留 `revoking` 归属直到 ack）。没有 rotate；换 key 就 Revoke + 新 Share。删除本地 Share 后对迟到请求回明文 `link-error share_revoked`。**每个 Link 的 pair/read/Revoke 在 App 内串行；Revoke 先置本地 revoked、取消/拒绝新 pair，pair 在发送前重查 revoked；已开始的读可完成，之后回 link-error。**
- Cap 8：按 `(hookId,deviceId)`/当前 Portal 分区，第 9 个提示先撤销；切换 Portal 不占另一 Portal 的名额。
- **Share Key 唯一性语义**：一个 Share 只有一把 Share Key；所有持有它的 CLI 等价。New code 是给**同一逻辑 Agent**恢复；**第二个 CLI 必须用 New code**（同一 code 只能兑换一次），New code 不 eject 第一个；两个 CLI 最终共享同一 Share Key；没有 per-Agent 撤销。要切断全部持有者只能 Revoke（或 Stop+Revoke）。UI 在 New code 处写明。
- Share 默认不过期；`expiresAt` 预留。
- **Pending revocation**：Revoke 不论 WS 是否连接，先持久化 `{linkId,reason,portalServerId,canonicalBase,hookId,deviceId,providerKeyId?,pendingUntil}`，并写 `revokingAt`（逻辑状态 `revoking`，不写入 pairState；清 secretCiphertext、停止服务），但**保留归属信息直到 tombstone ack**；下次 Connect/启动/网络恢复时只向 owning Portal 重试（必要时为该 Portal 临时建立一次 WS，先处理 4412/4413/issue-once）；收到 ack/410 后才删除 Share/linkId/secret 与 pending；收到 401/403 `DEVICE_REVOKED` 或 410 视为终态，清 pending、删除本地 Share 与 secret、不再重试；503/超时保留 pending。`unregister` purge pair/response cache。若 `POST /v1/links` 成功但 Share 落盘失败，立即 best-effort `unregister revoked`/记录 orphan 供 admin prune。**pending 未送达前 Relay 不会墓碑，CLI 只得到 503/`LINK_UNKNOWN`；App 重连补发 unregister 后才 410**；CLI 不应在 503 时 `forget`。
- **Stop sharing**：遍历所有已注册 Link，逐个标记 Share stopped、拒绝新 pair 兑换，并持久化 per-link `pendingPairState=paused`；逐个向 Relay 发 `pair {v:1,controlId,linkId,state:"paused",pairEpoch}` 并等 ack（2s）；Relay 不可达的保留 pending，下次 Connect 先补发；然后 zeroize 所有 code、关闭 Provider WS、清 pair cache。Share/Share Key 保留；Connect 恢复，不重配对。不等同 Revoke。Relay 不可达期间 CLI 得到 503 `PROVIDER_OFFLINE`，不是 `LINK_REVOKED`/`LINK_UNKNOWN`。

### 3.4 Wire、AAD 与 Inner Payload

#### 3.4.1 Wire

Data request/response：

```json
{"v":1,"op":"vfs","consumerId":"...","linkId":"...","reqId":"...","clientNonce":"...","ts":0,"iv":"...","ct":"..."}
```

Pair request/response：

```json
{"v":1,"op":"pair","consumerId":"...","linkId":"...","pairId":"...","reqId":"...","clientNonce":"...","ts":0,"iv":"...","ct":"..."}
```

- `consumerId`：CLI 每进程生成的 16B base64url 路由标识；只用于 Relay/Provider 关联响应，**不进 AAD、不被认证，只是路由标签**；CLI 不把它当身份；POST/GET/pair-error/link-error/Provider response 都带。`(consumerId,linkId,reqId)` 在 Relay 保留期间（≥120s，Relay 重启前）唯一；同三元组不同 `clientNonce`/`ct` → `409 REQID_CONFLICT`。CLI 规范要求 reqId 永不复用；Relay 重启/淘汰后不承诺继续判冲突。
- `linkId`/`pairId`/`reqId`：16B base64url（无 padding）；`clientNonce`：32B base64url；`iv`：12B base64url；`ct`：base64url(`ciphertext || 16B GCM tag`)；`v`：JSON int 1；`ts`：JSON int Unix 毫秒。
- Pair request plaintext（UTF-8 JSON，字段顺序如下）：`{"pairId":"<b64url>","clientNonce":"<b64url>"}`；inner 的 `pairId/clientNonce` 必须等于 outer，否则 `pair_unknown`/`PROTOCOL_ERROR`；outer `ts` 必须在 ±5 分钟内。
- Pair response plaintext：`{"shareId":"<b64url>","shareSecret":"<b64url>","scopes":["..."],"roots":["..."],"label":"..."}`；`roots` 按 §3.7 的 scope→root 映射去重排序。
- **Pair error（明文控制帧，不加密、不含 data）**：`{"v":1,"op":"pair-error","consumerId":"...","linkId":"...","pairId":"...","reqId":"...","code":"pair_unknown"|"code_invalid"|"code_expired"|"code_consumed"|"share_paused"|"stale_ts"|"rate_limited","message":"...","retryAfterMs":0,"serverTs":0,"skewMs":0}`。Provider 在 code 未知/过期/已用/限速/时钟偏差时返回；Relay 在 pairId 非 active/已过期时返回 `pair_unknown`/`code_expired`；`state=paused` 返回 `share_paused`，`state=expired` 返回 `code_expired`（HTTP 200，同步，不需要 Provider WS）。`message` 固定短语，不含 code/secret/路径。Provider 返回的 pair-error 按 `(consumerId,linkId,reqId)` 缓存 120s，GET 可取；Relay 本地直接 200 的同步返回不缓存。CLI 映射：`pair_unknown`/`code_invalid`→`CODE_INVALID`，`code_expired`→`CODE_EXPIRED`，`code_consumed`→`CODE_CONSUMED`，`share_paused`→`LINK_PAUSED`，`stale_ts`→`STALE_TS`，`rate_limited`→`RATE_LIMITED`。
- **Link error（明文控制帧）**：`{"v":1,"op":"link-error","consumerId":"...","linkId":"...","reqId":"...","code":"link_unknown"|"share_revoked"|"not_paired"|"auth_failed","message":"..."}`。App 在无法解密/找不到 Share/Link 已不在本地时返回；`message` 固定短语，不含路径/scope/文件名；按 `(consumerId,linkId,reqId)` 缓存 120s，GET 可取。CLI 映射：`link_unknown`/`share_revoked`→`LINK_UNKNOWN`（**未验证提示，保留 key、不 `forget`/不重建 Share**）、`not_paired`→`NOT_PAIRED`、`auth_failed`→`AUTH`（不删 key）。只有 HTTP 410 触发终止性 `LINK_REVOKED`；明文 `link-error` 与 inner error 都不触发 `forget`/删 key。
- Data inner request（UTF-8 JSON）：`{"v":1,"type":"stat"|"list"|"read","path":"...","offset":0,"length":0,"cursor":"...","limit":0,"etag":"..."}`；path 以 `/` 开头（root 是 `/`）、UTF-8、≤4 KiB、无 `.`/`..`/`//`、尾斜杠规范化；这是相对旧 VFS 相对路径的 breaking change，CLI/App 都只用绝对路径；违规 → inner `invalid_path`；无内层 id，外层 `reqId` 关联。
- Data inner response：`{"v":1,"type":"entry"|"entries"|"data"|"error","entry":{...},"entries":[...],"nextCursor":null|"...","data":"<base64>","etag":"...","offset":0,"length":0,"nextOffset":0,"eof":true,"error":{"code":"...","message":"..."}}`。
- `entry`：`{path,name,isDirectory,size,mtimeMs,mimeType?,etag?}`；`list` 的 `entries` 同 schema；`read` 的 `data` 带 `offset/length/nextOffset/eof`，`get` 分块使用；分块带 `etag`，变化 → `bad_request` 并重读；cursor 的 lastKey 已删除/改名 → `cursor_invalid`。
- Inner error codes：`not_found`、`invalid_path`、`bad_request`、`not_supported`、`provider_error`、`range_not_satisfiable`、`nonce_reused`、`stale_ts`、`in_flight`、`too_large`、`permission_lost`、`cursor_invalid`、`timeout`；`stale_ts` 可带 `serverTs/skewMs`。全部加密。CLI 映射：`not_found`→`PATH_NOT_FOUND`、`permission_lost`→`PERMISSION_LOST`、`too_large`→`TOO_LARGE`、`cursor_invalid`→`CURSOR_INVALID`、`range_not_satisfiable`→`BAD_RANGE`、`provider_error`→`PROVIDER_ERROR`、`timeout`→`PROVIDER_TIMEOUT`、`invalid_path`/`bad_request`/`not_supported`→`PROTOCOL_ERROR`；`nonce_reused`/`stale_ts`/`in_flight` 映射同名错误码。
- `data` 字段是**标准 base64（可带 `=` padding）**，不是 base64url；文本 preview 由 CLI 解码；二进制用 `get --out`。
- **list 预算**：Provider 按 1 MiB **inner JSON UTF-8 字节（outer base64/AEAD 前）**预算截断 `entries` 并返回 `nextCursor`；单 entry 名/元数据超过 64 KiB（同口径）→ `too_large`；`limit` 默认 1k、最大 10k；`entries` 按 `entry.path` 的 UTF-8 bytes 升序（bytewise）排序；Share 内 path 唯一（reserved roots/denylist 保证），无需 tie-breaker。cursor wire = `base64url(0x01 || u32be(len(lastKey)) || lastKey || tag16)`；`tag16 = HMAC-SHA256(Kcursor, u32be(len(linkId))||linkId || u32be(len(path))||path || u32be(4)||u32be(limit) || u32be(len(scopeVersion))||scopeVersion || u32be(len(lastKey))||lastKey)[0:16]`；`Kcursor = HKDF-SHA256(K, salt=空, info="wsvfs/cursor")`；`scopeVersion="1"`（Share scope 集不可变），`lastKey` = 上一页最后一个 `entry.path` 的 UTF-8 bytes；`limit` 缺省按 1000 规范化后再算 tag。Provider 解码 cursor 后重算 tag，绑定 linkId/path/limit/scopeVersion；任一不符、Share 不存在或 `lastKey` 已删除/改名 → `cursor_invalid`。cursor 无服务端状态、无 TTL；只要 Share 存在且 lastKey 仍存在即可继续。跨 Share/改 path/limit/篡改 cursor 必须 `cursor_invalid`。

#### 3.4.2 AAD 编码

AAD 由下列字段按顺序拼接；每个字段编码为 `u32be(len(fieldValue)) || fieldValue`。**下表/下面列出的 `v`、`ts` 编码已经包含这个长度前缀，不要再加一层**：

| op/direction | 字段顺序 |
|---|---|
| pair-req | `v, op, linkId, pairId, reqId, clientNonce, ts, iv, direction` |
| pair-resp | `v, op, linkId, pairId, reqId, clientNonce, requestTs, requestIv, ts, iv, direction` |
| request | `v, op, linkId, reqId, clientNonce, ts, iv, direction` |
| response | `v, op, linkId, reqId, clientNonce, requestTs, requestIv, ts, iv, direction` |

字段编码：

- `v`：**完整字段** = `u32be(1) || 0x01`（长度 1、值 1；不要再包一层）。
- `op`：UTF-8 bytes；`direction` ∈ `pair-req`、`pair-resp`、`request`、`response`，也按通用长度前缀编码。
- `linkId`/`pairId`/`reqId`：raw decoded 16B。
- `clientNonce`：raw decoded 32B。
- `ts`/`requestTs`：**完整字段** = `u32be(8) || u64be(Unix 毫秒)`（长度 8、值 8B；不要再包一层）。
- `iv`/`requestIv`：raw decoded 12B。
- Response AAD 的 `requestTs`/`requestIv` 来自 request；`ts`/`iv` 来自 response；response wire 含 `clientNonce,ts,iv`，Consumer 用自己保存的 request 值验证。
- 所有 base64url **无 padding 且必须 canonical**（拒绝非零 padding bits/变体）；JSON 重复键、非整数 `v/ts` 一律拒绝；cache key 用解码后的 raw bytes。Kotlin/Node 都按 RFC 4648 §5 无 padding。
- **负例**：把 `v`/`ts` 当值再加一层前缀会得到不同 AAD/tag：双前缀 pairReqAAD 的 tag 是 `482c7c6e1d51fdda69a1308fb9c4c6b5`（不是 `24fb26f8901a93d82d627d4a61e59947`）；非 canonical base64url（non-zero padding bits/变体）必须在解码前拒绝；P0 测试必须拒绝。

#### 3.4.3 Pairing

- `pairId = base64url(HKDF-SHA256(normalizedCodeASCII, salt=linkId_raw, info="wsvfs/pair/id", 16))`；公开路由，App/CLI 各自派生，不进入复制文本。向量：linkId `101112131415161718191a1b1c1d1e1f`、code `ABCDEFGHJKMNPQRS` → `n2NxoA743x71oXkKa1BkWg`。
- `Kpair = HKDF-SHA256(normalizedCodeASCII, salt=linkId_raw, info="wsvfs/pair/req")`；只用归一化后的 16 字符 ASCII。
- `KpairResp = HKDF-SHA256(codeASCII, salt=clientNonce_raw, info="wsvfs/pair/resp")`；KDF 不绑定 linkId，但 AAD 绑定 linkId/pairId/reqId，跨 Link 响应会被拒绝。
- Pair request `ct = AES-256-GCM(Kpair, pairPlaintext, aad_pair_req)`；`iv` 随机 12B。
- Pair response `ct = AES-256-GCM(KpairResp, pairRespPlaintext, aad_pair_resp)`；`iv` 随机 12B。
- Provider 顺序（响应丢失可重试）：`pairState=="paused"` → 直接 `pair-error share_paused`（即使 pairId 不匹配；Stop 后 Relay 通常已直接回答，这里是兜底）；`pairState=="expired"` → `pair-error code_expired`；否则先校验 `pairId == activePairId` 且 `pairState ∈ {active,consumed}`；不匹配 → `pair_unknown`（旧 cache 不可达）。`active` 时查 exact tuple 缓存，命中返回；否则 verify AEAD → persist Share Key（idempotent）→ build encrypted response → cache `(linkId,pairId,reqId,clientNonce)` **≤ code expiry 5 分钟（从 code 创建时算，不因响应生成晚而延长）** → **persist pairedAt/pairState=consumed** → consume code + zeroize → send。`consumed` 时只允许 exact tuple 缓存，miss → `code_consumed`。先缓存后消费；cache 是响应密文，不是 code。
- 响应丢失时 CLI 用同一 `(pairId,reqId,clientNonce,iv,ts,ct)` 重试（同一 CLI 进程内），必须原样重发 `iv/ts/ct`，改变则 `PROTOCOL_ERROR`；缓存过期/CLI 进程重启/Provider 进程死亡（memory-only cache 丢失）则 New code。New code 换新 pairId，旧缓存不可达；Revoke/Stop 清 cache；Share Key 在 Share 创建时已持久化，New code 返回同一把 Share Key。
- 无效 AEAD tag：按 Link 限速（5 pair/min），不消费 code，返回 `pair-error code_invalid`（不回显原因）；有效兑换才消费。code 过期/用完即废；App 到期把 pairState 置 `expired`、清 code/cache 但保留 pairId，不发 paused；到期时/重连时发 `expired` 控制帧（带 pairId）；New code 换新 pairId。已消费 code 再试 → `pair-error code_consumed`；pairId 仍 active 但 code 过期 → `code_expired`；New code 换 pairId/Provider 找不到该 pairId → `pair_unknown`；`|now-ts|>5min` → `stale_ts`。
- 配对请求只接受 `pairId == activePairId && now < expiresAt && state=="active"`；成功后 App 记 `pairedAt`，发 `pair {v:1,controlId,linkId,pairId,state:"consumed",pairEpoch}`（best-effort，必须带 pairId）；Relay 保留 exact tuple 缓存、拒绝同 pairId 的**新 tuple**（`code_consumed`）直到 TTL，同 tuple 重试仍返回缓存；不 eject 已有 Agent。
- 客户端：`pair` 命令持久化 Share Key（0600/atomic/fsync）后才报告成功；按 Share 存 key；重启不需要 code；pair 前拒绝任何 read。pair response AEAD/字段验证失败 → 不写 key、报 `PROTOCOL_ERROR`，不重试同一响应（可重试同 tuple 请求）。
- **P0 vectors**：附录 A 的 exact hex 是 source of truth；Kotlin/Node 必须逐字节复现，否则不冻结 wire contract。

#### 3.4.4 Relay 状态与语义错误

- Relay 只看 outer envelope；不能解析 inner path/read/list/error。Relay 状态（body 均带 `{code,message,retryAfterMs?}`）：
  - `400 BAD_ENVELOPE`、`401 AUTH`、`404 LINK_UNKNOWN`（持久 link store 无此 link）、`404 RESPONSE_UNKNOWN`（该 consumer 无此响应）、`409 IN_FLIGHT`（同 tuple 处理中）、`409 REQID_CONFLICT`（同 `(consumerId,linkId,reqId)` 不同 nonce/ct）、`410 REVOKED`（持久 tombstone；App 的 Share 过期也先走 unregister revoked → tombstone）、`413 ENVELOPE_TOO_LARGE`、`429 RATE_LIMITED`（带 `retryAfterMs`）、`503 PROVIDER_OFFLINE`/`PAIR_WARMING`/`PROVIDER_KEY_REVOKED`/`RELAY_STATE_UNAVAILABLE`、`504 PROVIDER_TIMEOUT`。**`LINK_OWNED` 只出现在 `POST /v1/links`（409）和 `register` error frame（`LINK_OWNED`，不 close），不是数据请求状态。**
- **pair 状态**：Relay 为每个 Link 存**内存** `activePairId/pairExpiresAt/pairState/pairEpoch`（App 侧持久，Relay 重启后由 App 重连补发）。**判定优先级**：① Link tombstone → 410；② link store 无记录 → 404 `LINK_UNKNOWN`；③ exact tuple cache 命中 → 返回缓存（即使 Provider 离线；consumed 也命中）；④ 内存 pairState 判定：paused → `share_paused`，expired 且 pairId 匹配 → `code_expired`，active 已过期 → `code_expired`，consumed 的新 tuple → `code_consumed`，pairId 不符 → `pair_unknown`；这些在 Relay 仍有该状态时可在 WS 关闭时单独回答；⑤ 无 pairState 且 Provider WS 未 register → 503 `PROVIDER_OFFLINE`；⑥ 无 pairState 且 WS 已 register → 503 `PAIR_WARMING`（retryAfterMs，绝不 `pair_unknown`；最多 10s，超时按 offline 并关闭该 WS）；⑦ 状态 active 且有效但 Provider WS 未 register → 503 `PROVIDER_OFFLINE`；⑧ 转发给 Provider。pair-error 均为 HTTP 200，不是 404/410。Relay 本地拒绝时 `POST /v1/request` 直接返回 200 + pair-error（不 202）；转发后 Provider 的 pair-error 经 202/poll 回给 Consumer（并缓存 120s）。
- **outer/link 错误**：Relay 校验失败（坏 JSON/base64/长度/`ct<16`）→ `400 BAD_ENVELOPE`；Link 无持久记录 → `404 LINK_UNKNOWN`；tombstone → 410；Provider 无法解密/本地无 Share → 明文 `link-error`（§3.4.1），Relay 按 `(consumerId,linkId,reqId)` 转给 Consumer；可加密时仍用 inner error，不可加密时才用 outer/link-error。
- `nonce_reused`/`stale_ts`/`in_flight`（inner）/`too_large`（inner read/list）/path 限制由 App 在密文响应里返回；Relay 不产生这些语义码。
- 1 MiB read、4 KiB path、10k list、1 MiB list 页由 App 执行；Relay 只执行 8 MiB envelope/frame。
- `GET /v1/response/{consumerId}/{linkId}/{reqId}` 必须匹配 Relay 的 pending/cache key；不再绑定 Unix connection（HTTP 池可换连接）。long-poll 默认 60s，超时 204；响应缓存 120s；客户端重试同一 `(consumerId,linkId,reqId)`。
- 客户端 404/503 不删 key；410 才提示重新配对；pair-error 按 code 映射，不删 key。

### 3.5 数据加密

- Share Key 32B；`K = HKDF-SHA256(secret, salt=linkId_raw, info="wsvfs/v1")`。
- 每请求 `clientNonce` 32B raw；`Kreq = HKDF(K, salt=clientNonce_raw, info="request")`，`Kresp = HKDF(K, salt=clientNonce_raw, info="response")`。
- Data 请求/响应：AES-256-GCM；request `iv` **随机 12B**；response `iv` 随机 12B；AAD 按 §3.4.2；response AAD 绑定 request `clientNonce/reqId/requestTs/requestIv`。
- **Nonce 规则**：data `clientNonce` 每 Share 唯一；Provider per-Share seen-`clientNonce` cache 10 分钟（bounded 20k）；重复 `nonce_reused`（inner 409）；`|now-ts| > 5 分钟` 硬拒绝 `stale_ts`（inner 409）；replay cache 10 分钟 ≥ skew。随机 12B IV 使即使 cache 过期/进程重启后 nonce 被复用，GCM IV 碰撞概率也可忽略；仍需唯一 `clientNonce`。
- **Replay/cache tuple（固定）**：Provider outer 去重与响应缓存用 `(linkId,reqId,clientNonce)`；Relay pending/cache 用 `(consumerId,linkId,reqId)`，nonce 作为值并校验一致；nonce 去重用 `(linkId,clientNonce)`；`ts/iv/ct` 必须与首次请求一致，任何字段变化按新请求处理但同 nonce 拒绝。Provider 处理前插入 `(linkId,reqId,clientNonce)`；完全相同 tuple 返回缓存（已完成）或 `in_flight`（处理中）；失败读可用同一 tuple 重试。**Provider 按 `(linkId,reqId,clientNonce)` cache miss 即当新请求处理；`REQID_CONFLICT` 只由 Relay 在其 120s pending/cache 保留期内判定，Provider 不维护单独 reqId 集。**
- Consumer pending `reqId`；`SecureRandom`，不用 `kotlin.random.Random`。
- AES-256-GCM only（ChaCha 需 API 28+）；**默认 minSdk 24**；`otherLegacy` minSdk 21 隐藏 Share 功能（代码放 `src/minApi24` 或 guard）。HKDF 用 HMAC-SHA256（RFC 5869）或 Tink；Kotlin/Node 交叉 vectors。
- 无 PFS：静态 secret + 历史密文被录，key 泄露可解历史；短 TTL/Revoke 降低暴露；以后可加 X25519 ephemeral，不宣称 PFS。
- VFS 错误/状态也在密文里；Portal 看不到路径或错误详情。**数据请求只凭 Share Key 解密 + scope 授权；pairState/pairedAt 只用于 pairing 展示/路由，不作数据门禁。**
- **Pairing code 零化（best-effort）**：code 用 `ByteArray`/`CharArray`；消费/过期/进程死 `fill(0)`；不落盘、不进日志/通知/telemetry/export；clipboard 自动清；不可避免的短生命周期 `String`（UI 输入、命令拼接）不打印、不持久化。不宣称 JVM 内绝对无副本。

### 3.6 Relay

- **Provider key 认证**：`POST /v1/provider-keys` 用 `Authorization: Bearer <hook secret>` + body `{deviceId,name,deviceSecret}`（hook secret 不放 URL/query）；已有记录且 **active 未过期**才 issue-once 返回当前 key；已过期 → `403 ROTATE_REQUIRED`，绝不返回过期 key；`POST /rotate` 接受 deviceSecret 或该 device 的有效 Provider key，返回新 key。`GET`/`DELETE` 只接受该 device 的 Provider key 或管理员会话，hook secret 单独 → `403 ADMIN_REQUIRED`。**OAuth access token 不用于 provider-key**（App 无 OAuth client/refresh；OAuth-only server v1 不支持）。hook gate `enabled && shareKeyVfs && !expired`；`maxCalls` 只计 MCP tool calls，**不复用 `mcpAuthBySecret`/`live()`**，不阻断 transport。Provider WS 的 `Authorization: Bearer <Provider key>` 用 `SHA-256(providerKey)` 在 `providerKeyHash` 索引常量时间查找，解析出 `providerKeyId,hookId,deviceId`，不是 hook token。issue/rotate/delete 在独立 provider-key store 锁内串行；issue/rotate 限速 5/min per hook，审计 `hook/device/action/result`，不记 key/deviceSecret。
- **Hook 生命周期**：Provider WS 连接时和每 5 分钟重新校验 hook；**hooks.json ENOENT（账本文件消失）按 fail closed 关闭该 hook WS 4401、keys/links 暂挂（suspend，不 tombstone）；EACCES/CORRUPT/临时 I/O 才 503 保留**；账本可读且确认 `disabled/expired/移除 shareKeyVfs` → suspend/close 4401；`hook delete` → 永久 4414 + tombstone。Provider key 本身有 `expiresAt?`（默认 null）；过期 close 4412；外部 rotate 旧 key 被取代 close 4413；hook `expiresAt` 到期的行为与 disable 相同。**hook secret rotate 不撤销已有 Provider key；既有 WS 继续，rotate 接口接受当前 Provider key；issue 需要新 hook secret，App UI 提示更新 MCP token。**
- **Link 登记**：`POST /v1/links {linkId}`（Provider key auth）同样检查 `enabled && shareKeyVfs && !expired`（suspend 时只允许 DELETE 清理，不允许新建）；写入持久 link store；同 owner 幂等、不同 owner `409 LINK_OWNED`；`register` 只接受已登记 link，未登记 `LINK_UNKNOWN`，不自动 claim。
- **Provider WS**：`GET /v1/provider` + `Authorization: Bearer <Provider key>`；带 `Origin` 时必须与 Host 同源（原生无 Origin 放行，和旧 `/vfs/provider` 相同）；auth 得到 `(hookId,deviceId)`。**升级失败 HTTP 契约**：401 `AUTH`（key 无效/被撤）、403 `DEVICE_REVOKED`（永久）、403 `HOOK_SUSPENDED`（disable/expire/capability 移除）、403 `ROTATE_REQUIRED`（key 过期）、403 `CAPABILITY_REQUIRED`、503 `RELAY_STATE_UNAVAILABLE`；body `{code,message,retryAfterMs?}`，与 close code 映射一致。Advanced 手工 key 无 deviceSecret，4412/4413 只能由 admin rotate/reissue。帧：
  - `pair {v:1,controlId,linkId,pairId,ttlSeconds,state,pairEpoch}`：公开 pair 状态；`state` ∈ `active|paused|consumed|expired`；`active` **必须**带 `pairId`+`ttlSeconds`（1..300）；`paused` 不带 `pairId`/`ttlSeconds`；**`consumed`/`expired` 必须带 `pairId`**（consumed 保留 exact tuple 缓存；expired 保留同 pairId 的 code_expired 判定）；缺字段/类型错 → `INVALID_CONTROL`；`pairEpoch` 本地初值 0（不上线），每次状态变化 +1，wire 只发 1..2^53-1，Relay 只接受更大 epoch；Relay 更新 `activePairId/pairExpiresAt/pairState/pairEpoch`，purge 其他 pairId 的 pair cache。Relay 以收到 `active` 帧的本地时间 + `ttlSeconds`（1..300）计算 `pairExpiresAt`，避免手机与 Portal 时钟偏差；New code 重新计时。
  - `register {v:1,controlId,linkId,deviceId,generation}`：绑定/刷新 Link。
  - `unregister {v:1,controlId,linkId,reason}`：`reason` wire 只允许 `revoked|shutdown`（`replaced` 仅 Relay 内部）。**只接受 link 当前 connection/generation 的帧；旧 generation 的 unregister/pair 回 `STALE_GENERATION`，不得 tombstone。真值表**：owner + link 存在未 tombstone → `revoked` 写持久 tombstone、`shutdown` 置 offline、`replaced` 仅当前连接；owner + 已 tombstone → ack（幂等）；owner + link 不存在 → ack、不建 tombstone；非 owner → `DEVICE_MISMATCH`，不建 tombstone；未认证 → 401/403。
  - `request`/`response` envelope（data/pair，outer 含 `consumerId`；Provider 原样 echo）；`pair-error {v:1,consumerId,linkId,pairId,reqId,code,message,retryAfterMs?}`（明文，按 `(consumerId,linkId,reqId)` 路由给等待的 Consumer，不进 cache）；`link-error {v:1,consumerId,linkId,reqId,code:"link_unknown"|"share_revoked"|"not_paired"|"auth_failed",message}`（明文，App 无法解密/找不到 Share 时返回，Relay 同样按 `(consumerId,linkId,reqId)` 路由）；`ping {v:1,op:"ping",id}` / `pong {v:1,op:"pong",id}`（应用层 JSON，不用 WebSocket 协议级 ping 做 liveness）。
- **控制 ack/错误契约**：Relay 对每个控制帧回：
  - `{"v":1,"op":"ack","control":"pair"|"register"|"unregister","controlId":"...","linkId":"...","ok":true,"generation":0}`（`register` ack 的 `generation` 是 Relay 接受的 generation；其他 control 可省略）
  - `{"v":1,"op":"error","control":"...","controlId":"...","linkId":"...","code":"INVALID_CONTROL"|"LINK_UNKNOWN"|"LINK_OWNED"|"DEVICE_MISMATCH"|"STALE_GENERATION"|"STALE_PAIR_EPOCH"|"REVOKED"|"RATE_LIMITED"|"INTERNAL","message":"...","retryable":false,"retryAfterMs":0,"currentGeneration":0,"currentPairEpoch":0}`
  `controlId` 是 16B base64url；未知字段忽略（向前兼容），非法 enum/长度 → `INVALID_CONTROL`；`generation` 是 1..2^53-1 的 JSON number；`pairEpoch` 同。
  Provider 收到 ack 前不认为状态生效；5s 未 ack 重发同 `controlId`（幂等）；Relay 按 `(connection,controlId)` 去重，同 `controlId` 只执行一次并回同一 ack/error；连续 2 次失败/WS 关闭则重连并重新 register（generation 已递增）。`LINK_OWNED`/`REVOKED`/`DEVICE_MISMATCH` 是永久错误（`retryable:false`），App 标记 Share error；`STALE_GENERATION`/`RATE_LIMITED` 带 `retryable:true`+`retryAfterMs?`，`STALE_GENERATION` 读 `currentGeneration` 并 +1 重试；`STALE_PAIR_EPOCH` 丢弃旧重试，用最新状态。
- **Registration 规则**：
  - `register` 只接受已登记且 owner 匹配的 linkId；未登记 → `LINK_UNKNOWN`，App 可用同一 linkId 重新 `POST /v1/links`（owner auth；revoked 仍 410）后再 register；不静默 claim；不同 owner → `LINK_OWNED`（error frame `LINK_OWNED`，不 close）。
  - `register.deviceId` 必须等于 Provider key auth 的 `deviceId`，否则 `DEVICE_MISMATCH`（per-link error frame，不 close）；link 首次注册绑定认证得到的 `hookId+deviceId`，不接受帧自报身份。`providerKeyId` 只审计。同 hook+device 的新 Provider key（rotate 后）可重注册同一 linkId。
  - `pair`/`unregister` 及该 WS 的数据帧也要求 auth `(hookId,deviceId)` == link 记录 owner；任一不匹配 → `DEVICE_MISMATCH`；同 deviceId 不同 hook 不能互相 pair/unregister/tombstone。
  - 不同 hook/device 注册同 linkId → `LINK_OWNED`（error frame `LINK_OWNED`，不 close）。
  - 同 linkId+deviceId+高 generation → 替换旧连接，旧连接 close 4404；低 generation → `STALE_GENERATION`。
  - 同 generation：同一连接重试 → ack；无活连接（如 rotate 关闭旧 WS）→ 绑定新连接并 ack；不同活连接 → `STALE_GENERATION`（不双活）。
  - tombstoned linkId → `REVOKED`（error frame），数据请求 410。
- **deviceId/generation**：`deviceId` = 现有 `VfsDeviceId`（per-install UUID，`noBackupFilesDir`）；App 重启不变，reinstall 新 UUID；不再有独立 `instanceId`。`generation` = 每 Share 持久化整数，初值 0，每次 register 前 +1 并持久化，首次 wire 1；wire 用 JSON number 且限制 `1..2^53-1`（Relay 超出回 `INVALID_CONTROL`，`currentGeneration` 同规则）；溢出/回滚由 App 新建 Share。
- **Consumer**：v1 只在 Unix socket 开 `POST /v1/request`（无 transport auth）；公网 listener **不挂** `/v1/request`，远端 Consumer 后置。Unix socket `<stateDir>/run/vfs-relay.sock`，目录 0700、socket 0600；启动检查 `sun_path ≤100`，过长报错并提示短 `PORTAL_STATE_FILE`；500 req/min per consumer connection，防本地 flood。Relay 先校验 outer（v/op/长度/base64/`ct>=16`/ts int），坏 → `400 BAD_ENVELOPE`；正常 202 `{reqId}`；`GET /v1/response/{consumerId}/{linkId}/{reqId}` 60s long-poll，超时 204；公网 listener 无 `/v1/request`。
- **Tombstone/link record（持久）**：`<stateDir>/vfs-relay-links.json` 0600/atomic 存 `{v:1,initializedAt,links:[{linkId,hookId,deviceId,createdAt,revokedAt?}]}`；**marker 由 vfs-relay 模块独占**：首次初始化先 atomic+fsync 写 link store，再写 `<stateDir>/vfs-relay-links.initialized`（0600）表示 store 已完整落盘；**只要合法 link store 存在就 adopt 并补 marker，绝不因 marker 缺失覆盖已有 store**；store 缺失且 marker 存在 → `503 RELAY_STATE_UNAVAILABLE`（丢失，不自动重建）；store 与 marker 都不存在 → 全新初始化。不得把 marker 放进 `auth.saveState()` 的 `state.json`（该 schema 会重写固定字段）。`connection/activePairId/pairExpiresAt/pairState/pairEpoch/providerKeyState(active|revoked|expired)` 是内存；providerKeyState 由认证/复验更新。Revoke 只接受 owner 的 `unregister revoked`；持久 tombstone **永久保留、不自动过期；只由 admin `portal-knock links prune` 清理，且只删该 `(hook,device)` 已 `deviceRevokedAt` 的记录，单 link revoked 但 device 仍 active 的不删（防复活）**；请求 410。WS 关闭置 `connection=null`、数据 503；offline record 不自动删除，Relay 重启后 ownership/tombstone 仍在。store 损坏/丢失 → `503 RELAY_STATE_UNAVAILABLE`，不自动 claim/重建，需 admin 恢复。客户端 404/503 保留 key；410 才重新配对。
- **unregister reasons**：`revoked`（owner + 存在 → 持久 tombstone 410；owner + 不存在 → ack 不建 tombstone；非 owner → `DEVICE_MISMATCH`）、`shutdown`（owner + 存在 → offline 503；owner + 不存在 → ack；非 owner → `DEVICE_MISMATCH`）、`replaced`（Relay 在 generation 替换时内部标记，App 不主动发；旧连接 close 4404）；`shutdown` 只在用户显式 Disconnect/FGS 优雅停止时发；Stop 只发 pair paused。**没有 `rotate` reason**；rotate 由 Relay 关闭旧 WS、App 用新 key 重注册，不 tombstone。`New code` 不 unregister（pairId 换新）。
- **Pair 状态**：link record 的 `activePairId/pairExpiresAt/pairState/pairEpoch` 由 `pair` 控制帧维护；Relay 同 `pairEpoch`+同 `pairId`/`state` 幂等 ack；更低 epoch 回 `STALE_PAIR_EPOCH` 且不改状态；更高 epoch 应用；同 epoch 不同 state/pairId → `INVALID_CONTROL`（App 必须递增 epoch）；在 `pairExpiresAt` 后拒绝 pair（pair-error `code_expired`），`state="paused"` 返回 pair-error `share_paused`（HTTP 200；CLI 提示 Connect；已配对 Agent 直接读，未配对/旧 code 需 New code），`state="expired"` 返回 `code_expired`（Relay 置 pairState=expired、activePairId=该帧 pairId、清 pairExpiresAt，供同 pairId 请求判定），`state="consumed"` 只允许 exact `(consumerId,pairId,reqId,clientNonce)` 返回缓存、新 tuple 回 `code_consumed`；`unregister revoked`/tombstone 清空。pair 请求只要求 Link 已在持久 store 登记；未登记 → 404 `LINK_UNKNOWN`。Provider WS 未注册 → 503 `PROVIDER_OFFLINE`；已注册但 pair 状态未收到 → 503 `PAIR_WARMING`（≤10s，超时按 offline）。paused 只有 Relay 内存仍有该 Link 的 pairState 时直接回答；Relay 重启后无状态按上句 503。
- **Response cache**：Provider data/error 缓存 `(linkId,reqId,clientNonce)` 120s；Relay data/pair-error/link-error `(consumerId,linkId,reqId)` 120s（data 值含 clientNonce）；pair 成功响应 `(consumerId,linkId,pairId,reqId,clientNonce)` ≤ code expiry 5min；New code/`pair` 控制帧 purge 其他 pairId，`consumed` 保留 exact tuple 至 TTL；同 `(consumerId,linkId,reqId)` 不同 clientNonce/ct/op → `409 REQID_CONFLICT`；pair/data 不共用同一三元组。
- **Limits**：8 MiB frame/body；1 MiB read；1 MiB list 页/10k entries/4 KiB path；per-link concurrency 8；per-consumer pending 8；per-`(hookId,deviceId)` 未 revoke link 8、per-hook 64（**含 offline**；revoked tombstone 不计且不设硬上限）；120 req/min per Link + 500 req/min per consumer connection 作 anti-flood（Link 120 是有效上限）、64 MiB/min、5 pair/min；seen-nonce 20k/10min；per-hook Provider key/device ≤32、并发 WS ≤16、per-WS control ≤60/min、全局 WS ≤256；response cache 120s、总字节上限 64 MiB，超限淘汰最旧；Provider 处理超时 90s → `504 PROVIDER_TIMEOUT`；revoked tombstone 永久保留（admin prune 只删 deviceRevoked 记录）、offline 未 revoke record 不自动删除（orphan 30d/admin prune）；应用层 `ping` 30s、`pong` 10s，90s 无 pong close 1001（reason `heartbeat`）。
- **Close codes**：**4401 auth suspended（hook disabled/expired/capability removed）→ 停连，regrant 后 issue-once 恢复**；**4414 permanent revoked/device/hook deleted → 永久停止，新 deviceId/Share**；4403 只用于握手阶段无法确定身份的 device mismatch；per-link DEVICE_MISMATCH 用 error frame，不 close；4404 replaced；4408 stale generation（仅旧路径/兼容，新路径用 error frame）；**4412 provider key expired → rotate**；**4413 provider key rotated externally / master loss → issue-once 取当前 active key**；4429 rate；1013 overload；1001 going away。**4409 保留给旧路径 `REBIND_REQUIRED`，新路径不用**；App 新旧映射分开：新路径用独立 `RelayCloseCodes`，不复用旧 `VfsCloseCodes.isTerminal`（旧 4401 是 terminal，新 4401 是 suspend）。App 对 4401 停连等待 regrant、对 4414/4403 永久停止；4412 调 rotate；4413 调 issue-once（deviceSecret）；4404/4412/4413 重连；4429 退避。
- **audit**：event/linkId prefix/pairId prefix/key prefix/bytes/duration/result；不记 ciphertext、路径、scope、label、inner error。
- **部署**：同一个 Portal 进程 + Unix socket；Provider WS 挂现有 Portal origin/tailnet；不新开 Funnel 端口；launchd 随 Portal；启动时先尝试连接 stale socket，活则失败，死则 unlink + lock；provider key 文件见 §3.2。listener profile **显式配置**：`PORTAL_PUBLIC_LISTEN`/`PORTAL_TAILNET_LISTEN`/`PORTAL_UNIX_SOCKET`，不从地址推断；LAN/未知地址必须显式归 public 或 tailnet，env 优先级：新 `PORTAL_*_LISTEN` > legacy `PORTAL_LISTEN`（全部按 public 兼容，去重端口）；未标注 profile 的 listener 默认按 `public` 兼容（保留现有 Portal 路由，但不挂 Consumer），启动日志提示迁移；`portal-knock config migrate-listen` 可生成新 env；不得静默不监听。
  - `public`/`tailnet`：现有路由 + `/v1/provider`（WS）、`POST /v1/provider-keys`、`POST /v1/provider-keys/rotate`、`GET/DELETE /v1/provider-keys`（Provider key 或 admin 登录）、`POST /v1/links`；这些路径**显式豁免门户登录**，但 handler 必须自校验 hook secret/Provider key/deviceSecret/admin session；GET/DELETE 的 admin 分支必须校验 `portal_sid` 或 `PORTAL_BASIC_*` + Origin 同源（复用现有 `originOk()`；v1 不新增 CSRF token）；DELETE 用非简单方法；不挂 `/v1/request`、`/v1/response`。
  - `unix`：只挂 `/v1/request`、`/v1/response/{consumerId}/{linkId}/{reqId}`、`/v1/health`，不挂 admin/login/knocks。
  - 本机 admin 控制 socket `<stateDir>/run/portal-admin.sock` 0600：挂 `/v1/admin/provider-keys/delete`、`/v1/admin/links/prune`、`/v1/admin/hooks/disable|delete`、`/v1/admin/capabilities/revoke`、`/v1/admin/provider-keys/issue|list`，供 `portal-knock` 在 server 运行时读写同一 store 并立即 close/tombstone；5 分钟 revalidation 只作兜底。
- **旧路径**：`/vfs/provider`/vfsd/P5 禁用；新 `/v1/provider` 与旧路由在 P4 前共存。

### 3.7 Scope

| Scope | 物理来源 | VFS 路径 | 生成方式 | 警告 |
|---|---|---|---|---|
| `app.data.activity` | `filesDir/activity/events.jsonl` | `appdata/activity/events.jsonl` | 现有文件虚拟映射 | amber |
| `app.data.groups` | 内存 group state；core 停止时 `available:false` | `appdata/groups/current.json` | on-read snapshot | amber |
| `app.data.logs` | `filesDir/logs/app.log` + AppLog | `appdata/logs/app.log` | 新 source 生成 redacted view | amber |
| `app.data.telemetry` | `filesDir/telemetry/` | `appdata/telemetry/...` | curated view | amber |
| `app.data.diagnostics` | counts/sizes/version/profile rev | `appdata/diagnostics/index.json` | on-read metadata only | amber |
| `app.files` | `filesDir/**` except `vfs/` 与物理 `conductor/` | `appfiles/**` | raw | red |
| `app.workspace` | external files dir；denylist `conductor/`、`.runtime/`、reserved roots | `**` | raw | red |
| `user.selected` | SAF content URIs | `selected/**` | virtual, pfd | picker |
| `all` | 所有固定 scope 的并集 | union | — | red union |

- **`all` = 所有固定 scope，创建时展开**：`app.data.activity`、`app.data.logs`、`app.data.telemetry`、`app.data.diagnostics`、`app.files`、`app.workspace`；`app.data.groups`（Proxy groups）v1 不可选，**排除 `user.selected`，硬排除物理 `vfs/` 与 `conductor/`**。Share 存展开后的 concrete list；以后新增 scope 不自动进入旧 Share。`Everything = all`。**`all` 含 `app.files` 的 raw logs/telemetry，会绕过 `app.data.logs` 的 redacted view；警告必须写明。**
- **scope→root 映射（pair response `roots`）**：`app.data.activity`→`/appdata/activity`；`app.data.groups`→`/appdata/groups`；`app.data.logs`→`/appdata/logs`；`app.data.telemetry`→`/appdata/telemetry`；`app.data.diagnostics`→`/appdata/diagnostics`；`app.files`→`/appfiles`；`app.workspace`→`/`（CLI 显示 `/ (excludes appfiles/appdata/selected)`）；`user.selected`→`/selected`。pair response 的 `roots` 是 concrete scopes 的映射去重后按 UTF-8 bytewise 排序；`scopes` 同排序；CLI 直接打印，不硬编码。例：`scopes=["app.data.activity","app.files"]` → `roots=["/appdata/activity","/appfiles"]`。**`list("/")` 的规则**：虚拟 root 优先、按 Share scope 过滤、与 `roots` 一致；`app.workspace` 的 `/` 只在对应 scope 授权时列出 `appfiles` 等独立 root；重复项去重。
- **`app.workspace` 是 denylist 且标红**：external files dir 减去物理 `conductor/`、`.runtime/` 和所有保留虚拟 root；已知内容有 crash reports（可能含 configuration.json 副本）、core cache/state、Taildrop；警告列出。虚拟 root `/` 必须同时覆盖 stat/list/resolve/canonical，物理同名 `conductor/` 不能覆盖虚拟 entry；补 symlink/同名目录测试。
- **`app.files` 只暴露 `filesDir`**，不暴露 `noBackupFilesDir`；硬排除物理 `vfs/` 与 `conductor/`（canonical 检查）。红字警告列出 `filesDir` 内高风险项：`configs/*.json`、`logs/`、`telemetry/`、`activity/`；`configuration.json` 只在 crash 处理时临时出现，警告标注“可能临时出现”。`settings.db`/`profiles.db` 在 `databases/` 不在 `filesDir`，从警告移除；`datastore/` 不存在；Tailscale state 不在 `filesDir`，不硬编码。
- **`app.data.logs` redaction**：新 source 只输出经过 redactor 的 tail。Redactor 规则：移除 `Authorization`/`Bearer`/token/password/secret/订阅 URL/`http(s)://` 原文；UUID/节点地址按模式脱敏；保留错误类/事件名/时间。测试用已知 secret 字符串断言不出现在输出。
- **`app.data.diagnostics` schema**：`{"generated_at","app_version","device_prefix","profile_rev","event_count","activity_bytes","telemetry_bytes","log_bytes","share_count"}`；不读 crash report/config 内容。
- **`app.data.telemetry` schema**：只列 `filesDir/telemetry/` 现有文件（raw bytes），不合成、不扫描 `noBackupFilesDir`；文件名/大小/mtime 可见，内容按文件读；空目录返回空列表。amber 仅表示比 `app.files` 范围窄，**不代表已脱敏**。
- **`app.data.logs` 测试 corpus**：注入 `Authorization: Bearer secret`、订阅 URL、UUID、节点地址、token/password；断言输出只保留时间/级别/事件名/错误类。redactor 是 best-effort，amber 不代表已脱敏；UI 明写“可能仍含敏感信息”。
- **`app.data.groups`**：UI 名 Proxy groups；v1 从可创建 scope 中隐藏，旧 Share 仍可解析；core/group state 不可用时 `{"generated_at","available":false,"groups":[]}`。
- **`user.selected`**：SAF `ACTION_OPEN_DOCUMENT` 多选，persistable read；存 URI+metadata，不复制文件；虚拟名 `<id>-<sanitized-name>`，冲突用 id 去重；v1 只支持文件，目录 → `not_supported`；删除 → `not_found`；权限丢失 → `permission_lost`；`pfd` + `Os.pread`，不支持 seek 的 provider 退化 stream-skip；read-only；不在 `all`；测试 permission loss/provider death/seek failure。
- **保留虚拟 root / denylist**：`appfiles`、`appdata`、`selected` 是虚拟 root；物理 `conductor/`、`.runtime/`、`vfs/` 是安全 denylist，在 external raw `**` 与 app.files 中一律排除；canonical 检查 + symlink 测试。
- **预设精确列表**：
  - **Recommended**：`app.data.activity`、`app.data.logs`、`app.data.telemetry`、`app.data.diagnostics`；不含 `app.data.groups`（Proxy groups，v1 不可选）、`app.workspace`、`app.files`、`user.selected`。
  - **Everything**：`all`（所有固定 scope，不含 `user.selected`）。
  - **Customize**：任意固定 scope + 可选 `user.selected`。
- **警告字符串**：静态 strings.xml（中/英），测试保证每条路径属于对应 scope；`app.files`/`app.workspace`/`all` 红字；`app.data.*` amber（amber ≠ 已脱敏；logs 是 redactor best-effort，telemetry 是 raw）。
- Provider `VfsScopedSource` 按 Share 执行；Relay 不解释 scope；Share 创建 UI 显示 human-readable groups + details。

### 3.8 App UI、后台、状态、feature flag、egress

- Settings → VFS Relay：Relay Server 列表、Add/Edit/Delete、Test connection。
- **Store 容量**：App 显示当前未 revoke link 占用（含 offline）；配额 8/64；revoked tombstone 不设硬上限、不会被正常使用撑死；admin prune 只删已 deviceRevoked 记录。
- **切换 Portal**：切换前断开当前 WS；旧 Portal 的 Share/Provider key/deviceSecret 保留并显示为 offline；新 Portal 用同一 deviceId 但新 hook 领独立 key、建新 Share；切回旧 Portal 可恢复旧 Share。CLI 本地 socket 只连当前 Portal，切换期间旧 Share 404/503，不是 Revoke。
- Shares 列表：label、scope 摘要、created、pairedAt、lastUsed、状态（waiting/paired/offline）、New code、Revoke。
- New Link：名字、Recommended/Everything/Customize、per-scope 警告、Create。
- Pairing 页：5:00 倒计时、一行配对说明、Copy pairing line、New code；无 QR；不进通知/状态栏；不设置 `FLAG_SECURE`，用户可主动截图；clipboard 自动清。
- **多 Share 状态**：`VfsRelayState`：`Connected(shareCount, shares[{shareId,label,status,pairedAt,lastUsedAt}], totalBytesSent)`、`Idle`、`Backoff`、`Error`；通知「Serving N Links」（不统计 Agent 数）；`totalBytesSent` 是 App 实际发到 WS 的 VFS 响应 JSON UTF-8 字节（含 list/stat/read/error 和缓存命中，不含 WS/TCP 连接、心跳与帧开销），Dashboard 实时显示。
- **Stop sharing = 暂停**：遍历所有 Link，逐个发 `pair paused` 等 ack（2s；不可达按 Link 存 `pendingPairState=paused`，下次 Connect 补发），再 zeroize 所有 code、关闭 WS、清 pair cache；Share/Share Key 保留；Connect 恢复，不重配对。**Revoke = 永久**：best-effort unregister，删除 Share/linkId/secret，tombstone。**New code 不 eject 已配对 Agent**；只有 Revoke 断开。
- **Disconnect vs Stop**：Disconnect 对每个已注册 Link 发 `unregister shutdown`（links 变 offline）、`desiredConnected=false`、关闭 WS；Stop 只发 `pair paused`（links 保持登记、Relay 有 paused 状态时 CLI 得到 `LINK_PAUSED`）、`desiredConnected=false`、关闭 WS；两者都不删 Share/Share Key，New code 不 unregister。
- **后台与恢复**：复用 `VfsSharingService` 的 FGS 外壳；新增 `VfsRelayPolicy`/`VfsRelayState`（多 Share、`totalBytesSent`，不沿用单连接/uploadBytes 逻辑）；FGS 活着时后台可读；WS 掉线在退避内自动重连/重注册（不要求用户操作）；进程死/Doze 后 503。进程死亡后重开 App：若停之前是 Connected（持久化 `desiredConnected=true`），自动恢复 Connect 一次；若用户 Stop 过，不自动连；所有 Share 都 Revoke 后 `desiredConnected=false`，停止 FGS/WS，避免空连接常驻。不引入 always-on。
- **App store 持久化契约**：`deviceSecret` 在首次 `POST /v1/provider-keys` **之前** atomic+fsync 落盘，同一 `(canonicalBase,hookId,deviceId)` 的 deviceSecret 永不重新生成；Share 创建、registrationState/registeredAt、pairState active/consumed、Stop、pending revocation 都先 atomic+fsync 再发网络 ack；Provider key 丢失可从 issue-once 恢复，Share Key 丢失才不可恢复；`lastUsedAt`/`desiredConnected` 同一 store/config atomic 写，允许丢失。
- **at-rest**：Share `secret`/`deviceSecret`/Provider key 用 `VfsShareCipher`（Android Keystore AES-256-GCM，alias `wondersing-vfs-share-v1`，`setUserAuthenticationRequired(false)` 以便后台读取）；文件格式 `0x01||iv(12)||ct||tag(16)`，**App VfsShareCipher 侧 AAD** 为 UTF-8 用途字面量（`|` 分隔、不含 secret 本身）`v1|<shareId>|secret`（shareId 用 wire base64url 字符串）、`v1|<canonicalBase>|<hookId>|<deviceId>|deviceSecret`、`v1|<canonicalBase>|<hookId>|<deviceId>|providerKey|<providerKeyId>`（**不把 secret 本身放进 AAD**，否则解密前无法计算）；`canonicalBase` 变化前必须先按旧 AAD 解密、再按新 AAD 重加密并原子写记录与新 base，不能只改 base 字段；解密失败标记 Share/device error，不静默删除/不返回明文；卸载会删 Keystore key，旧 Share/deviceSecret 不可恢复（与 reinstall 一致）。
- **egress**：`VfsRelayConfig.egressMode` v1 **只支持 `public`**（HTTPS Funnel）；`tailnet` 推迟到 Tailscale Serve HTTPS/MCP 配置有 tailnet base 后；HTTPS `.ts.net` Funnel 允许，但不按 hostname 推断 tailnet、不用明文 HTTP；URL 无法区分 Funnel/Serve，v1 只支持 Funnel，Serve base 连接失败并提示；复用现有 `SocketProtector` + `VpnService.protect`；新 VFS 的 OkHttp client 显式配置 `Dns`（`underlyingNetwork.getAllByName`，TelemetryUploader 模式）和 `SocketFactory`（创建 socket → `vpn.protect` → `network.bindSocket`）；`network==null` 明确失败。public 不让 DNS 走隧道并 protect；tailnet 模式 v1 不启用。测试 VPN on/off、per-app include、root/proxy、rule switch、network switch、DNS。
- **平台门控**：Share Key VFS 只在 API 24+ 启用。`SHARE_KEY_VFS` build flag、旧 VFS 代码和旧 MCP 路径已于 2026-11-28 移除。
- **API21 otherLegacy**：隐藏 Share Key VFS 入口；API21 的 MCP token 目前是明文，不能经 VFS 暴露；Share Key VFS 只在 API24+ 启用，runtime `SDK_INT` gate 仍在。
- **Pairing code 输入**：UI 用 char/byte 数组；clipboard 自动清；skill/CLAUDE.md 提示不要把 code 留在 shell history；日志/通知/telemetry/export 不出现 code/key；用户主动截图不被 App 阻止。

### 3.9 CLI

- 安装：Portal `pnpm service-install` 装 `$HOME/bin/vfsc`；skill/CLAUDE.md 提示；App 复制一行 `Use "$HOME/bin/vfsc" to access my shared files: Link ID=<linkId>, Pair Code=<code>.`（绝对路径）。UI/CLI 用户面统一叫 Link/Links；wire、存储和内部类型仍是 Share。
- `--help` 一页：App 路径、pair 流程、每命令一个示例、JSON 默认、大文件、Portal 盲区、not-paired 错误。
- 命令：
- `pair --link ... --code ...`（CLI 从 code+linkId 派生 pairId，兼容非交互 Agent）；也支持 `--code-stdin` / `VFSC_CODE`（避免 argv/shell history 的可选方式）；App 复制行只含 linkId/code，提示“含一次性 code”。
  - `list [path] --json [--cursor] [--limit]`
  - `stat <path> --json`
  - `read <path> [--max-bytes 65536] --json`
  - `get <path> --out <file>`
  - `links --json`（列出本地保存的 Link；不含 secret）
  - `status [--probe] --json`（socket/Relay/本地配对；`--probe` 用 `stat /` 探测 provider）
  - `forget --link <linkId|label>`（410 后清理本地 key；不删远端）
  - `--link <linkId|label>`；`--socket <path>`（默认 `<stateDir>/run/vfs-relay.sock`；`<stateDir>` 未设 `PORTAL_STATE_FILE` 时为 `~/.clawdy-portal`，可用 `VFSC_STATE_DIR`/`PORTAL_STATE_FILE` 覆盖）；远端 Consumer v1 不提供（无 `--relay/--api-key`）。
- JSON envelope：`{"ok":true,"data":...}` / `{"ok":false,"error":{"code","message","retryable","hint"}}`。
- 输出 schema：
  - `pair`：`{"connected":true,"link":{"linkId","label","pairedAt"},"roots":["..."]}`
  - `list`：`{"entries":[{"path","name","isDirectory","size","mtimeMs","mimeType?"}],"nextCursor":null}`
  - `stat`：`{"path","name","isDirectory","size","mtimeMs","mimeType?","etag?"}`
  - `read`：`{"path","offset","length","eof","encoding":"utf-8"|"base64","data","truncated","nextOffset"}`
  - `get`：`{"path","out","bytes","sha256"}`
  - `links`：`{"links":[{"linkId","label","pairedAt"}]}`
  - `status`：`{"socket":"ok"|"unreachable","relay":"ok"|"unreachable"|"unknown","paired":true|false}`；`relay` 来自 Unix socket `GET /v1/health`（`{vfsRelay:"ok",links:N}`），不是仅 connect 成功。加 `--probe` 时多一个 `provider:"ok"|"offline"|"error"|"ambiguous"` 字段；多个 Link 未指定 `--link` 时为 `ambiguous`。
- `pair` 的 pair-error 映射按 §3.4.1；`pair` 成功前必须已持久化 key；失败不写 key。**同一 CLI 进程内** pair 超时/204 用同一 tuple 重试，不换 pairId；CLI 进程重启会丢失 code，必须 New code（不持久化 code/tuple）；`RATE_LIMITED` 按 `Retry-After` 退避。
- Exit codes：0 成功；2 usage；3 transient；4 permanent；5 not found。
- Error codes：`NOT_PAIRED`、`LINK_PAUSED`、`CODE_EXPIRED`、`CODE_INVALID`、`CODE_CONSUMED`、`PROVIDER_OFFLINE`、`PAIR_WARMING`、`PROVIDER_TIMEOUT`、`LINK_UNKNOWN`、`LINK_REVOKED`、`RELAY_UNREACHABLE`、`RELAY_STATE_UNAVAILABLE`、`RATE_LIMITED`、`TOO_LARGE`、`PATH_NOT_FOUND`、`PERMISSION_LOST`、`LINK_AMBIGUOUS`、`PROVIDER_KEY_REVOKED`、`PROTOCOL_ERROR`、`IN_FLIGHT`、`RESPONSE_UNKNOWN`、`REQID_CONFLICT`、`NONCE_REUSED`、`STALE_TS`、`AUTH`、`CURSOR_INVALID`、`BAD_RANGE`、`PROVIDER_ERROR`。
- 明文 `link-error`/`pair-error` 是未验证提示，不触发 `forget`/删 key/重建 Share；`LINK_REVOKED` 只由 HTTP 410 触发；inner error 不触发终止。
- `CLI_NOT_FOUND` 由 skill/CLAUDE.md 发，不由 CLI 发。
- Key store：`~/Library/Application Support/vfsc/keys.json` 目录 0700、文件 0600、atomic+fsync；Share Key 明文，文档提示排除 Time Machine/云同步；每 Share `{shareId,label,linkId,secret}`；不打印、不进 argv/env/日志/错误；code 在 pair argv 里是设计允许的，错误中脱敏。
- `GET /v1/response/{consumerId}/{linkId}/{reqId}` 单次 long-poll 60s；超时 204 后 CLI 用**同一 `(consumerId,linkId,reqId)`** 继续等；若 404 `RESPONSE_UNKNOWN`，在 120s 内**重发同一 tuple POST**（Provider 120s 缓存/重算）一次，再失败则新 reqId；不要无限 GET。
- list 分页：VFS `list` request `{cursor?,limit?}`，response `{entries,nextCursor?}`；默认 1k，最大 10k；Provider 先按 1 MiB 页预算截断再给 `nextCursor`；entries 按 path bytewise 排序；单 entry 过大 → `TOO_LARGE`；`CURSOR_INVALID` 时丢弃 cursor、从头 list。
- read 默认 64 KiB preview；文本 MIME UTF-8，二进制 base64；大文件 `get --out`，按 25–28 秒/MB 估时并显示进度；写临时文件、用 offset/etag 续传、完成后 sha256 校验再 rename；模型不一次吃 1.33 MiB base64。
- 未配对错误教用户去 App 的路径；本机默认 Unix socket；远端后置。
- 清理旧设备 key（reinstall/orphan）用 Portal 的 `portal-knock provider-keys list/delete`，不属于 `vfsc`；见 §4.5。

### 3.10 错误目录

| 情况 | code | exit | 动作 |
|---|---|---|---|
| CLI 未安装/PATH | skill 提示 | 2 | 用 `"$HOME/bin/vfsc"` 或 `pnpm service-install`。 |
| 未配对 | `NOT_PAIRED` | 4 | WonderSing → VFS → New Link。 |
| Link 暂停 | `LINK_PAUSED` | 3 | 打开 WonderSing → VFS 检查 Link；已配对 Agent 直接用 key；未配对/旧 code 需 New code。 |
| | code 过期 | `CODE_EXPIRED` | 4 | New code（5 分钟）。 |
| code 错误/`pair_unknown` | `CODE_INVALID` | 4 | 检查 16 字符；New code。 |
| code 已用/缓存丢失 | `CODE_CONSUMED` | 4 | New code。 |
| pair 限速 | `RATE_LIMITED` | 3 | 等 `Retry-After`；不换 code。 |
| Provider 离线 | `PROVIDER_OFFLINE` | 3 | 打开 WonderSing，确认 Connected；重试。 |
| Pair 状态预热中 | `PAIR_WARMING` | 3 | 等 1–2 秒重试，最多 10 秒；超时按 `PROVIDER_OFFLINE`；不要 New code。 |
| Provider 超时 | `PROVIDER_TIMEOUT` | 3 | 重试同一请求；仍超时检查网络/大文件改 `get --out`。 |
| Link revoked | `LINK_REVOKED` | 4 | 新建 Link。 |
| 404 link 无记录 | `LINK_UNKNOWN` | 3 | 保留 key；让 App 重新 claim/register；确认 Revoke 后 `forget`/新建 Link。 |
| Relay 状态损坏 | `RELAY_STATE_UNAVAILABLE` | 3 | Portal link store 损坏/丢失；让管理员恢复，不要 claim/重建。 |
| 响应未到 | `RESPONSE_UNKNOWN` | 3 | 120s 内重发同一 tuple POST 一次；仍失败换 reqId；若同时 `LINK_UNKNOWN` 按 LINK_UNKNOWN。 |
| 同 reqId 冲突 | `REQID_CONFLICT` | 4 | 换新 `reqId`（不能复用旧 nonce/ct）。 |
| Provider key revoked/expired | `PROVIDER_KEY_REVOKED` | 4 | 打开 App 重新 Connect；rotate 后无需重配对；已 delete 才新建 Link。CLI 不能 rotate。 |
| Portal 不可达 | `RELAY_UNREACHABLE` | 3 | 检查 Portal/Unix socket；确认 `pnpm service-install`。 |
| 限速 | `RATE_LIMITED` | 3 | 等 `Retry-After`；不要 New code（不清 rate）。 |
| 大文件/envelope/list 页 | `TOO_LARGE` | 4 | `get --out`；减小 range/limit；用 cursor。 |
| 坏 envelope | `PROTOCOL_ERROR` | 4 | CLI 升级/检查 base64url/字段；不带 secret 重试。 |
| 认证失败 | `AUTH` | 4 | 检查 key；重新 Connect。 |
| 多 Link 未选/重复 label | `LINK_AMBIGUOUS` | 2 | `--link linkId`。 |
| SAF 权限丢失 | `PERMISSION_LOST` | 4 | App 重新选择文件。 |
| SAF 文件删除 | `PATH_NOT_FOUND` | 5 | 检查 path；重新选择。 |
| nonce 复用 | `NONCE_REUSED` | 3 | 新请求用新 `clientNonce+reqId`；重试复用原 tuple。 |
| ts 过期 | `STALE_TS` | 3 | 校准时钟；±5 分钟。 |
| 处理中 | `IN_FLIGHT` | 3 | 等 Retry-After/重试同一 tuple。 |
| cursor 失效/被篡改 | `CURSOR_INVALID` | 4 | 丢弃 cursor，重新从头 list。 |
| offset/length 越界 | `BAD_RANGE` | 4 | 检查 `offset`/`length` 与 `eof`/`nextOffset`。 |
| Provider 内部错误 | `PROVIDER_ERROR` | 3 | 重试；检查 App 日志；不要删 key。 |
| 协议/控制错误 | `PROTOCOL_ERROR` | 4 | 升级 CLI/App；保留元数据日志（不含 secret）。 |

### 3.11 重启、新手机、多 Agent

- Relay 重启：ownership/tombstone 从 `vfs-relay-links.json` 恢复；连接态丢失，数据 503；App 重连并重注册 Share；Relay 的 pairState 是内存，重启后为空；每条 Share 先 `register`，再由 App 按持久状态补 `pair`：`waiting`（尚无 code）不补发；`paused`（Stop）→ paused；active code → `active`（pairId/ttl/pairEpoch）；已配对 → `consumed`（必须带 pairId，保留 exact tuple 至 TTL）；自然过期 → `expired`（保留 activePairId，同 pairId 请求回 code_expired）。补发前按 §3.4.4 返回 503（未注册 `PROVIDER_OFFLINE`、已注册 `PAIR_WARMING`），不是 404/pair_unknown。CLI key 不变；pending 请求重试；不重配对；404 只表示未登记/无记录。
- App 重启/进程死亡：Share 记录/deviceId/generation 持久化；重开 App 若 `desiredConnected=true` 自动重连一次，否则保持停止；CLI key 不变；不重配对。Pairing Code memory-only，进程死则 code 丢失，New code。
- **Provider key rotate**：Portal/App 撤销旧 key 并返回新 key；旧 WS close 4413（rotated）；App 用新 key 重连并重注册同一 linkId；**不 tombstone、不重配对、Share/CLI key 不变**。App 收到 4413 用 `deviceSecret` 调 issue-once 取当前 active key；收到 4412（expired）调 rotate；4401（suspend）等待 regrant 后 issue-once；4414（revoked/device/hook deleted）永久停止，不 rotate；master loss 归入 4413，先 issue-once 恢复。**App 有 deviceSecret 时，握手 401 `AUTH` 也先尝试 issue-once（master 丢失/旧 key 无效），成功重连，失败才停；Advanced 手工 key（无 deviceSecret）遇到 401/4412/4413 不自动 rotate/issue，UI 显示管理员指引。**响应丢失重试同一路径。
- **Provider key delete（per hook）**：admin delete → 关闭 WS 4414、标记 `deviceRevokedAt`、tombstone 该 `(hookId,deviceId)` 的 Link，之后该组合 issue/rotate `DEVICE_REVOKED`，恢复需新 deviceId/新 Share；Provider key 自删只撤销 key，Link 变 offline，WS close 4413（可恢复），可用 deviceSecret 重新 issue；不用 4414。CLI 得到 410/404/503；旧 CLI key 用 `forget`。
- 新手机/重装：Share 不迁移；reinstall 清 noBackup + Keystore + deviceSecret，旧 Share 不能在新装使用/撤销；用同一 knock 执行 `portal-knock provider-keys list --hook <hookId>` 找到旧 device，`portal-knock provider-keys delete --hook <hookId> --device <id>` 撤销；新装生成新 deviceId 并新建 Share/重新配 CLI。旧 Share 在旧装 Revoke 前仍可能对旧 App 有效，不能声称自动失效。
- 多 Agent：一 Agent 一 Link；CLI 按 Link 存多把 key，`--link` 选择；上限 8；重复 label 报错。
- Stop = 暂停；Revoke = 永久；New code 同 Share Key，不 eject。

### 3.12 旧路径退役、文档迁移、feature flag

- App 移除（2026-11-28 完成）：`VfsConnectTool`、`VfsConsent`/`VfsTrust`/`VfsCooldown(Store)`/`VfsResumeStore`/`VfsServerBase`/`VfsLink`/`VfsLinkParser`/旧 `VfsScopeAliases`/`VfsAccessScreen`/旧 `VfsProviderProtocol` server-hello/`OkHttpVfsProviderConnector`/`VfsRuntime`/`VfsConnectionManager`/旧 source 树；Conductor prompt/tool catalog/sanitizer/MCP device header；旧 status bar/notification/strings/tests；`SHARE_KEY_VFS` flag。JVM 用例 830 → 681（只删除旧路径用例）。
- Portal 移除（2026-11-28 完成）：`vfs_link_request`/`vfs_link_revoke`/`vfs_export`/`job_run(vfs=true)` VFS 分支；`lib/vfs.mjs`、`lib/vfsd-transport.mjs`、`/vfs/provider`；vfsd 数据面；容器 `/vfs` 挂载；旧 tests/scripts。`job_run` 保留本机私有 `/work`，`job_file` 不受影响。Portal 用例 208 → 187。
- Portal 能力面（post-v1）：`capabilities` 管理与 legacy `vfs_link_request` one-click grant 未实现；v1 只保留 Relay 页面和 CLI。
- 文档：Portal `VFS.md` 改成 Relay/pairing/CLI 契约；`agent-oriented-vfs.md`、`docs/jobs.md`、`docs/mcp-grants.md`、`AUTH.md`、`HOOKS.md`、`docs/vfs-campaign.md`、`TODO.md`；WonderSing `vfs-dry-run.md`、`vfs-connection-stability.md`、`vfs-soak-test.md`、`vfs-cases/`、`mcp.md`、`agent-control-plane.md`、`agent-runtime.md`、`conductor-*`、`local-testing.md` 改成新路径或标退役；App strings/translations 全部更新；`TODO.md` 改 v16。
- AO 的 Activity/Groups 分散导出：P5（Share Key VFS 真机验收后）移除；P4 AP 保留 old path/AO 导出。

## 4. 实现方案

### 4.1 阶段

- **P0**：评审并冻结本文档 + 附录 A vectors + 附录 B/C golden；不改代码。
- **P1 App provider core**：Portal server 选择/auto-provision；Share store/deviceId/generation/pairEpoch/deviceSecret；`POST /v1/links`；Keystore `VfsShareCipher`；pairing/pairId/pair-error；control ack/error（含 pairEpoch）；AEAD/HKDF；scope source/denylist/redaction（所有 source 改 `list(path,cursor,limit)`）；`VfsRelayPolicy`/`VfsSharingService` 重写多 Share + 自动重连；Settings/Share UI（创建/New code 需 ack）；`ShareKeyVfs` flag；旧路径保留；JVM 测试。
- **P2 Portal/Relay**：provider key issue/rotate/revoke/list（hook secret + deviceSecret + admin）；hook capability 发放/迁移；persistent link store/`POST /v1/links`/ownership；Link 路由/注册 generation/tombstone；pairing/pairId/pair state/pairEpoch/pair-error；control ack/error；rotate 不 tombstone；close codes；Unix/admin socket、listener profile、public provider-key/links 路由；新 transport gate（不复用 `live()`）；60s long-poll；limits/audit；flag；旧路径保留；契约测试。
- **P3 CLI**：`vfsc` 安装（修改 `install-launchd.sh` 实际落 `$HOME/bin`，显式 Node shebang/路径）；pair/list/stat/read/get/links/status/forget；consumerId；`GET /v1/response/{consumerId}/{linkId}/{reqId}`；key store；pair-error/link-error 映射；JSON/exit/errors；skill；测试。
- **P4 E2E**：真机 + Mini + Agent；flag on；Relay 只见密文；revoke/offline/restart/rotate；release AP（old path/AO 导出仍保留）；验收。
- **P5 清理**：移除旧 App/Portal 路径、vfsd、容器 `/vfs`、旧 strings/tests/docs；移除 AO 导出；移除 flag；清理版 AP。
- P1/P2/P3 在 v16 参考 + vectors 提交后可并行；实现以 [v1 合同](vfs-share-key.md) 为准；P4 依赖 P1–P3。

### 4.2 数据模型

- App Share：`shareId,label,linkId,secretCiphertext,scopes(concrete),portalServerId,hookId,canonicalBase,deviceId,generation,registrationState(pending|registered|failed),registeredAt?,pairEpoch,pairId?,pairState(waiting|active|consumed|paused|expired),pairExpiresAt?,revokingAt?,createdAt,pairedAt,lastUsedAt,expiresAt(null),revokedAt`；`noBackupFilesDir/vfs/`，`VfsShareCipher`（Keystore AES-256-GCM，alias `wondersing-vfs-share-v1`）加密；不进 telemetry/export；lastUsed 节流。
- PairingCode：**memory-only** `{shareId,pairId,code(byte/char),createdAt,expiresAt,consumedAt}`；不持久化 code/hash；消费/过期/进程死 best-effort zeroize。
- Relay pair state：`{linkId,activePairId,pairExpiresAt,pairState(active|paused|consumed|expired),pairEpoch}`；内存；Stop/unregister 清；自然过期置 `expired` 并保留 activePairId。
- Pending revocation：`{linkId,reason,portalServerId,canonicalBase,hookId,deviceId,providerKeyId?,pendingUntil}`；持久化；Connect 只向 owning Portal 重试 `unregister`；Share 同时存 `revokingAt`；401/403 `DEVICE_REVOKED`/410 清 pending，503 保留。
- Pending pair state（per link）：`{linkId,pendingPairState:"paused",pendingUntil}`；Stop 时 Relay 不可达写入，下次 Connect 先补发。
- Provider key store marker：`<stateDir>/provider-keys.initialized`；首次先写 store 再写 marker；合法 store + 缺 marker → adopt；marker + 缺 store → 503 `RELAY_STATE_UNAVAILABLE`。
- Provider key（Portal）：`hookId,deviceId,deviceSecretHash,providerKeyId,providerKeyHash,keyCiphertext,name,lastUsedAt,relayUrl,createdAt,rotatedAt,expiresAt?,keyRevokedAt?,deviceRevokedAt?`；Portal master key 加密可逆；一 `(hookId,deviceId)` active；hook 多 device；orphan list/revoke；`deviceSecretHash=SHA-256(deviceSecret)`。
- Provider key（App）：`portalServerId,hookId,canonicalBase,deviceId,providerKeyId,providerKeyCiphertext,deviceSecretCiphertext,expiresAt?,relayUrl,createdAt,rotatedAt`；`VfsShareCipher` 加密；每 `(hookId,deviceId)` 独立 `deviceSecret`/Provider key。
- Relay link store（持久）：`{v:1,initializedAt,links:[{linkId,hookId,deviceId,createdAt,revokedAt?}]}`；`<stateDir>/vfs-relay-links.json` 0600/atomic；`connection,generation,activePairId,pairExpiresAt,pairState,pairEpoch` 内存，pairState 由 App 重连补发。
- Response cache：`(consumerId,linkId,reqId) -> response/error + clientNonce,expiresAt`；data/pair-error/link-error 120s；pair 成功 `(consumerId,linkId,pairId,reqId,clientNonce)` ≤code expiry 5min；`consumed` 保留 exact tuple 至 TTL；New code/新 pairId purge 旧 pair cache。
- Replay/seen-nonce：per Share，`(linkId,reqId,clientNonce)` 20k/10min；nonce 去重 `(linkId,clientNonce)`。
- CLI key：`shareId,label,linkId,secret`；目录 0700/文件 0600/atomic+fsync。

### 4.3 API 契约

- **Provider key**：`POST /v1/provider-keys`（hook secret + `shareKeyVfs`，body `{deviceId,name,deviceSecret}`；首次登记 `SHA-256(deviceSecret)`，后续必须同 secret；返回 `{providerKey,providerKeyId,hookId,deviceId,createdAt,expiresAt?}`；`403 DEVICE_AUTH`/`DEVICE_REVOKED`/`CAPABILITY_REQUIRED`/`ROTATE_REQUIRED`（self-delete 的 keyRevoked 可重新 issue）；deviceSecret 32B b64url、providerKey `wsk_`+32B b64url、providerKeyId 16B b64url）。同一 `(hookId,deviceId)` 重复返回当前 key；同一 deviceId 在另一 hook 下独立记录。`GET` 用该 device 的 Provider key 只返回自己；admin 用 `?hook=<hookId>` 列该 hook；响应 `{devices:[{deviceId,name,providerKeyId,createdAt,expiresAt?,lastUsedAt,rotatedAt,keyRevokedAt?,deviceRevokedAt?}]}`，**不含 providerKey**。`POST /v1/provider-keys/rotate` body `{deviceId,deviceSecret}`。`DELETE /v1/provider-keys/{deviceId}?hook=<hookId>`：admin → 204、标记 `deviceRevokedAt`、close 4414、tombstone 该 `(hook,device)` links；Provider key 自删 → 204、标记 `keyRevokedAt`、不 tombstone links；hook secret 单独 list/delete → `403 ADMIN_REQUIRED`；OAuth token 不用于 provider-key。
- **Link 登记**：`POST /v1/links {linkId}`（Provider key auth）写入持久 `vfs-relay-links.json`，同 owner 且未 revoke 幂等返回 `200 {linkId,createdAt}`；revoked `410 REVOKED`；不同 owner `409 LINK_OWNED`；未 revoke 配额 8/64（含 offline，tombstone 不占）；`register` 只接受已登记且 owner 匹配的 linkId，未登记 `LINK_UNKNOWN`，不自动 claim。
- **Hook**：`hooks.json` `capabilities:["shareKeyVfs"]`；`portal-knock capabilities list|grant|revoke`、`/knocks/api`、`knocks.html` 同一操作；disable/delete/移除 capability 撤销 keys/links；Provider WS 连接/每 5min 校验；OAuth-only server v1 不选。
- **Provider WS**：`GET /v1/provider`，`Authorization: Bearer <Provider key>`；带 Origin 必须与 Host 同源；auth 得 `(hookId,deviceId)`；`register` 只接受已登记且 owner 匹配的 linkId、`register.deviceId` 必须等于 auth deviceId；`pair {v:1,controlId,linkId,pairId,ttlSeconds,state,pairEpoch}`（active 必带 pairId+ttlSeconds；consumed 必带 pairId；paused 不带）、`register {v:1,controlId,linkId,deviceId,generation}`、`unregister {v:1,controlId,linkId,reason}`、`request/response` envelope（含 consumerId）、`pair-error`、`link-error`、应用层 `ping`/`pong`；Relay 回 `ack`/`error`（§3.6）；`register`/`pair`/`unregister` 及该 WS 的数据帧统一校验 auth `(hookId,deviceId)` == link owner；ownership/generation/tombstone/control error frames（`LINK_OWNED`，不 close）。
- **Pair error**：明文 `{"v":1,"op":"pair-error","consumerId","linkId","pairId","reqId","code","message","retryAfterMs?"}`；codes `pair_unknown|code_invalid|code_expired|code_consumed|share_paused|stale_ts|rate_limited`；Provider/Relay 都可返回；CLI 映射见 §3.4.1。
- **Link error**：明文 `{"v":1,"op":"link-error","consumerId","linkId","reqId","code","message"}`；codes `link_unknown|share_revoked|not_paired|auth_failed`；App 无法解密/本地无 Share 时返回；CLI 映射见 §3.4.1。
- **Consumer**：v1 只 Unix socket `POST /v1/request` body `{v,op,consumerId,linkId,pairId?,reqId,clientNonce,ts,iv,ct}`（`op=pair` 必须带 `pairId`）；Relay 先校验 outer，坏 → `400 BAD_ENVELOPE`；正常 202 `{reqId}`，pair 被 Relay 本地拒绝时 200 + `pair-error`；`GET /v1/response/{consumerId}/{linkId}/{reqId}` long-poll ≤60s，超时 204；response body 同 wire；公网 listener 无 `/v1/request`。
- **Admin 控制**：Unix `portal-admin.sock` 0600，`POST /v1/admin/provider-keys/delete {hookId,deviceId}` → 204（立即 close 4414/tombstone）、`POST /v1/admin/hooks/disable|delete {hookId}`（disable suspend 4401；delete tombstone 4414）、`POST /v1/admin/capabilities/revoke {hookId,capability}`（suspend 4401）、`POST /v1/admin/provider-keys/issue {hookId,deviceId,name}` → key once、`GET /v1/admin/provider-keys?hook=`、`POST /v1/admin/links/prune {before}` → `{pruned:N}`；`portal-knock` 优先走此 socket，server 未运行才写 store。
- **Health**：Unix socket `GET /v1/health` → `{vfsRelay:"ok",links:N,serverTs:0}`；用于 CLI `status`，不暴露 Share/路径。
- **List**：inner `list {path,cursor,limit}`；inner response `{entries,nextCursor}`；`VirtualDirectorySource.list(path,cursor,limit): VfsListPage`；entries 按 path UTF-8 bytewise 排序；cursor 格式与 tag 见 §3.4.1；Provider 按 1 MiB 页预算截断，单 entry >64 KiB → `too_large`；lastKey 不存在/绑定不符 → `cursor_invalid`。
- **状态**：Relay `400 BAD_ENVELOPE`/401/403 `ADMIN_REQUIRED`/`DEVICE_AUTH`/`DEVICE_REVOKED`/`404 LINK_UNKNOWN`/`404 RESPONSE_UNKNOWN`/`410 REVOKED`/`409 IN_FLIGHT`/`409 REQID_CONFLICT`/`413`/`429`/`503`（`PROVIDER_OFFLINE`/`PAIR_WARMING`/`PROVIDER_KEY_REVOKED`/`RELAY_STATE_UNAVAILABLE`）/`504 PROVIDER_TIMEOUT`；provider-key 升级额外 `HOOK_SUSPENDED`/`ROTATE_REQUIRED`/`CAPABILITY_REQUIRED`；pair 状态经 `pair-error`；link 无法解密经 `link-error`；inner `nonce_reused`/`stale_ts`/`in_flight`/`too_large`/`permission_lost`/`cursor_invalid`；客户端 404/503 保留 key，410 才重新配对。`LINK_OWNED` 仅在 link claim/register 控制层。
- **AAD/crypto**：§3.4/§3.5；附录 A vectors；data request random IV；pair random IV；pairId；replay tuple `(linkId,reqId,clientNonce)`；Relay pending/cache `(consumerId,linkId,reqId)`。
- **Limits**：8 MiB envelope；1 MiB read；1 MiB list 页/10k entries/4 KiB path；per-link concurrency 8；per-consumer pending 8；per-`(hookId,deviceId)` 未 revoke link 8、per-hook 64（**含 offline**；revoked tombstone 不计且不设硬上限）；120 req/min per Link + 500 req/min per consumer connection anti-flood；64 MiB/min；5 pair/min；seen-nonce 20k/10min；per-hook key/device ≤32、WS ≤16、control ≤60/min、global WS ≤256；response cache 120s/64 MiB；Provider 90s timeout → 504；revoked tombstone 永久保留（admin prune 只删 deviceRevoked）、offline record 不自动删除（orphan 30d/admin prune）；generation 1..2^53-1；应用层 ping 30s/pong 10s、90s close 1001。
- **审计**：event/linkId/pairId/key prefix/bytes/duration/result；不记内容/路径/scope/label/inner error/deviceSecret。
- **Cache purge**：Revoke/`unregister revoked` purge；New code 新 pairId 使旧缓存不可达；Stop `pair paused` purge；pair cache ≤code expiry。

### 4.4 App 改动

- 新增：`VfsRelayConfig`（portalServerId/canonicalBase/relayUrl/egressMode）、`VfsProviderKeyProvisioner`（deviceSecret）、`VfsRelayClient`（control ack/error、pair-error、link-error、应用层 ping/pong）、`VfsShareStore`/`VfsShareRepository`（按 `portalServerId` 分区）、`VfsShareCipher`（Keystore）、`VfsPairing`（char/byte code、pairEpoch）、`VfsShareCipher`/`RelayCrypto`、`VfsRelayRuntime`（一 WS 多 Link/deviceId/generation/pairEpoch/自动重连）、`AndroidRelaySources`/`RelayScope`、`UserSelectedVirtualDirectorySource`、`VfsRelayState`。
- 改写：`VfsSharingService` 只观察 `VfsRelayState` 并维护通知；`InternalFilesVirtualDirectorySource`、`VirtualDirectoryProtocol` 保留 Relay 复用的只读类型；`VfsFileMetadata`/`VfsPaths` 提供共享元数据与相对路径规则。
- 删除（2026-11-28 完成）：旧 `VfsRuntime`、`VfsConnectionManager`、`VfsProviderProtocol`、`VirtualDirectorySession`/`Transport`、旧 source 树、`VfsConnectTool`/consent/trust/cooldown/resume/link/server-base、`VfsScopeAliases`、`VfsSharingPolicy`、`VfsScopeLabel`、`SHARE_KEY_VFS` flag；Conductor 的 VFS 弹卡与 MCP `vfs_link_request`/device header 一并移除。
- API 24：Share/crypto 代码用 main 接口 + `src/minApi24` Keystore 实现（仿 `McpTokenCipherFactory`），API21 分支不提供（VFS 隐藏），避免 lint NewApi。
- Egress 复用现有 `SocketProtector` + `vpn.protect`；OkHttp 显式 `dns` + `socketFactory`（protect + `network.bindSocket`），`network==null` 失败；public Funnel protect；tailnet 模式 v1 不启用。
- Pending revocation store/retry；Share `registrationState/registeredAt` 持久化（启动重试未登记 pending）；canonicalBase 变化按旧 AAD 解密、新 AAD 重加密后再原子更新；lastUsed 节流；code zeroize/clipboard；`desiredConnected` 持久化驱动重启恢复。
- `deviceSecret` 32B 首次生成，按 `(hookId,deviceId)` 存 `noBackupFilesDir/vfs/device-secrets/<hookId>/<deviceId>`；首次 issue 前暂存 `pending/<portalServerId>/<hash(canonicalBase|deviceId)>`，pending AAD 用 `v1|<canonicalBase>|<deviceId>|deviceSecret`（**不含 hookId**）；拿到 hookId 后重新加密并原子迁移到 `device-secrets/<hookId>/<deviceId>`（AAD 含 canonicalBase/hookId/deviceId）；provider key issue/rotate 必带；不进日志/export。不同 Portal 不复用 secret。

### 4.5 Portal/Relay 改动

- 新增：`lib/vfs-relay.mjs`（provider key issue/rotate/revoke/list + deviceSecret hash + admin gate、Provider WS Origin、`POST /v1/links` + 持久 link store/ownership、control ack/error、link ownership/generation/tombstone、pairing/pairId/pair state/pairEpoch/pair-error、link-error、consumer 202+60s poll、response/replay cache、Unix socket、public auth、limits/audit、cache purge、close codes、offline record 保留/admin prune）。
- 修改：`server.mjs` 公共 Relay 只挂 `/relay/v1/provider`、`/relay/v1/provider-keys`、`/relay/v1/links`，旧 `/v1/*` 根路径 404；`/vfs/provider` 与 vfsd 已删除。`mcp.mjs` 的 `job_run` 不再有 `vfs` 参数，每个 job 固定获得本机私有 `/work` 目录，`job_file` 继续可用。`lib/hooks.mjs` 不再向 `call.mcp` 下发 `serverBase`。
- 退役（2026-11-28 完成）：`lib/vfs.mjs`、`lib/vfsd-transport.mjs`、`/vfs/provider`、`vfs_export`、`vfs_link_request`/`vfs_link_revoke`、`job_run(vfs=true)`、容器 `/vfs`、旧 e2e/tests；Portal 测试从 208 降到 187（保留 Relay/CLI 用例）。
- v1 远端 Consumer 不开放；Consumer key 后置。

### 4.6 CLI 改动

- 新增 `$HOME/bin/vfsc`（Node/Go；优先 Node）；安装脚本；skill/CLAUDE.md；key store；socket；JSON/exit/errors；pair-error/link-error 映射；list cursor；read preview/get/forget/links/status；60s long-poll；v1 无 `--relay`。
- 输出 schema/错误/退出码按 §3.9/§3.10；测试各 schema、key 0700/0600/atomic/fsync、pair 成功前不写 key、pair-error/link-error 映射、Unix socket、`$HOME/bin`、multi-Share、duplicate label、code 脱敏、大文件、list 1 MiB 页、60s 超时用同一 `(consumerId,linkId,reqId)`、IN_FLIGHT/RESPONSE_UNKNOWN/REQID_CONFLICT/PROVIDER_TIMEOUT/NONCE_REUSED/STALE_TS/AUTH/413。

### 4.7 测试

- Pairing：5 分钟、单次、memory-only/process death、pair 状态顺序（active pairId/expiry → exact tuple cache → AEAD）、New code/Stop 的 `pairEpoch` 单调、同 epoch 幂等、`STALE_PAIR_EPOCH`、pairId/pairState App 持久化（进程死保持；Relay 重启后 App 重连补发 consumed/paused/expired；无状态时 503/PAIR_WARMING）、invalid AEAD tag 返回 code_invalid、并发兑换、同进程同 tuple 重试、CLI 进程重启 → New code、pair-error（unknown/expired/consumed/share_paused/stale_ts/rate + retryAfterMs）缓存可 GET、Relay 重启/Provider 离线返回 503 而非 pair_unknown、Provider 在 paused 时回 share_paused、自然过期 code_expired 与 Stop paused 区分、expired 重连补发保留 activePairId、Stop 遍历所有 Link 逐个 paused ack、pairExpiresAt 过期、roots 字段、New code 第二 CLI 同 Share Key 语义、pair 前拒绝读、code/key 不进日志。
- Provider key：deviceSecret 首次登记/hash 不匹配/revoked、issue-once 无 deviceSecret 不能取回、过期返回 ROTATE_REQUIRED、admin-only list/delete、hook secret 单独不能管理、OAuth-only server 拒绝、register.deviceId 必须等于 auth deviceId、跨 hook 同 deviceId 不能互相 pair/unregister/tombstone、master loss 4413→issue-once 恢复、registrationState pending/registered 崩溃恢复、base 变化重加密、rotate 外部触发后 4413→issue-once 恢复；过期 4412→rotate；4401 suspend→regrant；4414 永久停止；HTTP 编码/golden（providerKeyId 16B）；master 两阶段/丢失恢复；sweep suspend、delete 统一 tombstone；Advanced 手工 key；每 (hookId,deviceId) secret；Origin 同源、hook 多 device、同一 deviceId 多 hook、(hookId,deviceId)/base 变更/删除重加同名不复用、双配置复用、orphan/reinstall、Advanced、shareKeyVfs gate、capability grant/revoke/legacy one-click、hook disable/expiresAt、hooks.json ENOENT fail closed、EACCES/CORRUPT 不撤销、maxCalls 不阻断 transport、provider-key 独立锁并发 issue/rotate/delete、加密存储、rotate 响应丢失、delete 持久 deviceRevoked + tombstone、self-delete key 可重新 issue、capability remove→regrant 恢复、per-hook 删除不跨 hook、unknown unregister 不建 tombstone。
- Crypto：RFC 5869 HKDF、NIST AES-GCM、附录 A exact vectors（含 roots 后的 pairResp）、Kotlin/Node 交叉、AAD tamper、`v`/`ts` 双重前缀负例（期望 tag 见 A.6）、non-canonical base64url 拒绝、cursor 可逆格式/tag/lastKey 删除/篡改（A.6）、附录 C wire `ct` base64url、nonce/iv 复用、reqId replay、response swap、clock skew、API24、Keystore `VfsShareCipher` 加解密/失败、at-rest AAD（deviceSecret/providerKey/binding）A.6 golden、master/provider-keys v1 格式/两阶段轮换/并发/损坏、provider-key store marker crash/adopt。
- Nonce/replay：same nonce/new reqId、same reqId/new nonce/ct → `REQID_CONFLICT`、同 tuple 同响应、replay after cache eviction、process-death retry、STALE_TS、NONCE_REUSED；random IV；Relay cache 含 consumerId。
- Relay：malicious swap/replay/reorder/drop、400 BAD_ENVELOPE/401/403/404/409/410/413/429/503/504、pair-error/link-error（含缓存 GET）、control ack/error、consumerId 与 HTTP 连接池换连接、两个 consumer 撞 reqId、close codes 4401/4403/4404/4412/4413/4414/4429、DEVICE_MISMATCH per-link frame 不 close、consumed exact cache 优先、heartbeat 30s/90s JSON ping、frame/body/list 1 MiB caps、per-hook key/WS/control caps、restart/reconnect、持久 link store 跨重启、第二 device 抢注册/抢 revoked 被拒、generation takeover/rotate 重注册、duplicate linkId、public listener 挂 provider-key/links 且豁免登录、HTTPS `.ts.net` Funnel、Unix 只 consumer/health、Provider WS 升级 401/403 错误、admin socket 立即 hook suspend/delete、离线 delete 两 store、link store init crash/adopt/marker（不被 auth.saveState 抹）、revoked tombstone 无硬上限、stale socket lock、audit grep、tombstone、offline record 503 保留/admin prune、cache purge、`GET /v1/response/{consumerId}/{linkId}/{reqId}`、60s long-poll/204、v1 public Consumer 404、旧 4409 rebind 不冲突、unregister 真值表、同 deviceId 不同 hook owner 隔离、Relay 重启无 pairState 的 503 分支。
- Scopes：每个 scope × 路径矩阵；appdata/groups/diagnostics/telemetry 生成；logs redaction corpus；scope→root 映射；app.workspace denylist/canonical/reserved roots/symlink；vfs/ 与物理 conductor/ 不可达；all 创建时展开、旧 Share 不自动加 scope、不含 user.selected；user.selected 权限丢失/删除/目录/seek；app.files 警告真实高风险路径（configuration/configs/logs/telemetry/activity）且不含 settings.db/profiles.db；`app.files`/`app.workspace` 红字；amber 不等于已脱敏；warning strings 中英；app.workspace `/` 与物理 conductor 同名覆盖测试。
- App：Share create/copy/New code/Revoke/Stop；registrationState/registeredAt 崩溃恢复；创建/New code 需 Connected + link/register/pair ack；切换 Portal 保留旧 Share/独立 key；多 Share 状态/通知；background/Doze/process death；desiredConnected 自动重连/Stop 后不自动连；deviceSecret 首次生成/加密/丢失/issue 前 fsync；Share 状态 atomic+fsync；Keystore encrypt/decrypt/失败；egressMode=public + OkHttp dns/socketFactory + protect/bind（VPN on/off/rule switch）；API21 runtime gate；时钟偏差 serverTs/skewMs；默认 pair 命令 argv 形式 + stdin/env 替代；expired 不发 paused；Stop/Disconnect/shutdown 语义；flag off/on；key/code/deviceSecret 不进 telemetry/export/screenshot/clipboard/notification。
- CLI：JSON/exit/errors、key store 0700/0600/atomic/fsync、pair 成功前不写 key、pair-error/link-error 映射、Unix socket/默认 stateDir、`$HOME/bin`、list cursor（A.6）/排序/1 MiB 页/CURSOR_INVALID、read preview/get/forget/links/status、60s 超时同 `(consumerId,linkId,reqId)`、multi-Share、duplicate label、no code/key in logs、IN_FLIGHT/RESPONSE_UNKNOWN/REQID_CONFLICT/PROVIDER_TIMEOUT/NONCE_REUSED/STALE_TS/AUTH/413。
- E2E：真机 + Mini + Agent；已知文件；Relay 只见密文/元数据；revoke/tamper/offline；overnight；large files；multi-device；容器 `/vfs` 退役记录。
- UX：新用户按 6 步完成；术语；`--help`；错误可行动；`all`/per-scope 警告清楚。

### 4.8 发布与回滚

- 顺序：Portal/Relay + CLI 就绪 → App AP（`ShareKeyVfs` flag on，旧路径/AO 导出保留）→ P4 E2E/真机验收 → P5 移除旧路径/flag/AO 导出 → 清理版 AP。
- 发布门：P4 E2E；Relay 日志无明文/key/code/deviceSecret；scope matrix；旧路径 flag off 可回退；附录 A vectors + 附录 B golden 通过；`./scripts/local-test.sh`；`./scripts/e2e-emulator.sh`；CI Test/Release。
- 回滚：P4 前 flag off 回旧路径；P5 后回滚 App/Portal 版本；旧路径退役不可逆；P5 前确认数据保留/清理（vfs-links、trust/resume、CLI keys）。
- 下一个 App label 若无其他改动是 AP；版本规则见 [`versioning.md`](versioning.md)。

### 4.9 风险

- Pairing Code 在聊天/模型上下文；一次性/5 分钟；Agent 宿主可读 CLI key store；不要过度宣称。
- `all` 敏感文件暴露；红字/amber 警告 + 用户确认；`vfs/` 硬排除；真实路径清单要准确。
- 可用性：FGS/Doze/进程死亡；不承诺 always-on；503 可行动。
- 无 PFS；未来 X25519。
- Relay 元数据；不记内容/路径/scope/label/inner error。
- CLI 安装/发现/PATH；`$HOME/bin` 绝对命令；skill/CLAUDE.md。
- Provider key auto-provision 依赖 Portal server 选择；无则 Advanced；key 可 rotate/revoke；hook gate/liveness。
- 文档迁移是重写；旧文档/strings/tests/scripts/Agent prompt 必须一起改。
- API 21 `otherLegacy` 隐藏 Share；API 24 source set/guard。
- Cap 8；溢出提示；不静默驱逐。
- 大文件 1 MiB read 25–28 秒/MB；CLI preview + `get --out`。
- Egress：`egressMode` v1 只 public（HTTPS Funnel），HTTPS `.ts.net` Funnel 可用，但不按 hostname 推断 tailnet；public 用 `vpn.protect`/`SocketProtector` + underlying-network DNS；`network==null` 失败可诊断；tailnet 推迟。
- Memory-only pairing code：进程死即丢；New code；不持久化 secret。
- Provider key master key 存储/轮换；orphan 清理；master/provider-keys 泄露可冒充 Provider/DoS，但不能解 Share Key 数据；文件 0600、fail closed、可撤销重发。
- hook secret 泄露（Agent 也持有）：可为**新** deviceId 领 key，若已知未注册 deviceId 可抢先登记（DoS）；不能取回/管理已有 device；v1 接受，管理员用 `delete`/重装处理。
- 同机 Portal/CLI/Agent 同用户：Portal 被攻破/同用户进程可读 CLI key store 解密；同机 Agent 也可执行 `portal-knock`/读 master，admin gate 不防同用户；v1 接受该主机信任边界，强隔离需独立用户/VM 或 Keychain ACL。
- Local Unix socket 无 transport auth：data 仍加密，但本地进程可 flood/probe；500 req/min per consumer connection + 120 per Link + 5 pair/min；文档写明。

## 5. 验收标准

| 验收 | 证据/测试 |
|---|---|
| Agent 用 CLI 读取选定 scope，无需手动导出/容器 | 真机 + Mini + Agent E2E；已知文件 sha256。 |
| 公网 listener 可领 provider key/link（豁免登录）；Unix 只 consumer/health；App egress v1 只 public；切换 Portal 保留旧 Share；4412 走 rotate、4413 走 issue-once；pair-error/link-error 可 GET；unregister 真值表 | 路由/切换/过期/错误缓存/真值表测试。 |
| Share 持久 pairId/state；进程死保持，Relay 重启后 App 补发 consumed/paused；provider-key HTTP 编码/golden；master 两阶段/master loss 4413；每 Portal 独立 secret；admin 离线 delete 两 store | 持久化/恢复/HTTP/master/Portal 测试。 |
| link store marker 不依赖 auth state；per-hook suspend/delete/regrant；at-rest AAD；(hookId,deviceId) 绑定/base 变更 | 初始化崩溃/adopt、per-hook 撤销、AAD、binding 测试。 |
| providerKeyId 16B golden；存储主键 `(hookId,deviceId)`；hook sweep suspend/delete tombstone；consumed exact cache；DEVICE_MISMATCH per-link | golden/存储/sweep/consumed/frame 测试。 |
| HTTPS `.ts.net` Funnel 可用；Stop paused 在 Relay 仍有状态时可回答；pending Revoke 保留 Portal 归属；link store 无 256 死锁 | base/Stop/Revoke/retention 测试。 |
| cursor 可逆/排序/绑定/tamper；同 deviceId 跨 hook 不能互删；pending Revoke 全字段；master loss 4413 恢复；Relay 重启 pairState 503 语义；provider-key marker | cursor/跨 hook/Revoke/master/重启/marker 测试。 |
| Pairing Code 5 分钟、一次性；长期 Share Key 不打印、不进聊天；CLI 按 Share 保存；code memory-only；New code pairId 隔离；pair-error/Relay pair 过期/Stop paused（LINK_PAUSED）/Relay 不可达 503/CLI 重启 New code/roots 可复现 | pairing 测试；transcript/log 扫描；CLI key store 权限；process death；old pairId cache；pair-error 映射。 |
| Portal 只见密文 + 路由元数据；无 key/code/路径/inner error；同机主机信任边界、明文控制帧可伪造已写入 | malicious Relay 测试；audit grep；tcpdump/WSS 检查；inner error 加密；控制错误伪造测试；文档扫描“Portal 宿主机可信”。 |
| Revoke 后 Relay 可达时 410；持久 tombstone 跨 Relay 重启仍 410；第二 device 抢注册/抢 revoked 被 `LINK_OWNED`/`LINK_UNKNOWN` 拒绝；离线/重启按 tombstone/503；迟到请求回 `link-error`；New code 可重配对；Provider 离线 503；Relay/App 重启不重配对；Provider key rotate 不 tombstone、不重配对；delete 持久 revoked；新手机新 Share；reinstall 文案正确 | revoke/tombstone/restart/new-phone/reinstall/rotate/delete/第二 device 测试。 |
| Provider WS 控制帧 ack/error、generation/ownership/LINK_OWNED error frame、pairEpoch 可复现；`register.deviceId` 必须等于 Provider key auth；`(hookId,deviceId)` owner 覆盖 pair/unregister/数据；deviceSecret 防同 hook 取回；admin-only list/delete；`shareKeyVfs` grant/revoke/list 与 legacy one-click grant 可用；`HOOK_SUSPENDED` 显示等待 regrant | 控制帧契约测试；deviceSecret/权限测试；hook capability 测试；App 选无 capability server 的错误提示。 |
| Link 登记先于 Pairing Code；第二 device 抢注册/抢 revoked 被拒；Relay 重启 ownership/tombstone 保留 | 持久 link store / 第二 device / Relay 重启测试。 |
| `all`/`app.files`/`app.workspace` 警告可见；物理 `vfs/`、`conductor/`、`.runtime/` 不可达；`user.selected` 不在 `all`；reserved roots；`all` 创建时展开；app.files 警告不含 `settings.db`/`profiles.db`；amber 不等于已脱敏 | scope matrix；UI 截图/strings 测试；symlink 测试。 |
| 旧 `/vfs/provider`/vfsd/`job_run(vfs=true)`/`VfsConnectTool` 在 P5 禁用；P4 前 flag off 可回退 | flag off/on；旧路径回归；docs 更新。 |
| 多 Share 状态/通知正确；Stop=pause，Revoke=permanent；totalBytesSent 正确；desiredConnected 自动重连；Keystore at-rest；deviceSecret 首次生成/加密；API21 隐藏新旧 VFS | 多 Share UI 测试；通知/strings；重启/Stop 测试；Keystore 失败测试；API21 分支测试。 |
| CLI JSON/exit/errors/key store/socket/PATH/list cursor 正确；`links`/`status`；pair-error/link-error 映射；60s long-poll/204 同 `(consumerId,linkId,reqId)`；list 1 MiB 页；IN_FLIGHT/RESPONSE_UNKNOWN/REQID_CONFLICT/PROVIDER_TIMEOUT/NONCE_REUSED/STALE_TS/AUTH/413 可行动 | CLI 契约测试；Agent 实际调用。 |
| 202+poll 的 POST/GET 可跨 HTTP 连接；两个 consumer 撞 reqId、同 reqId 不同 nonce/ct 可区分 | consumerId/REQID_CONFLICT 契约测试。 |
| 附录 A vectors 在 Kotlin/Node 逐字节通过；附录 B/C golden 通过；malicious Relay/scope/background/egress 测试通过 | 测试报告/记录路径。 |

## 6. 评审记录与变更

- 安全/协议评审：sound with changes；2 个 blocking 已定：容器 `/vfs` 退役；一 Agent 一 Share（linkId/secret 在 Share 创建时生成，一条 WS 多 Link）。
- 简洁性/易懂性/易用性评审：概念 4/5、易懂 3/5、易用 3/5；0 blocking；5 个默认已记录。
- v3 文档评审：3 个新 blocking；v4 修正。
- v4 文档评审：4 个 blocking（B1/B2/B3 + nonce/replay）；v5 修正。
- v5 文档评审：6 个 blocking（provider key 身份/orphan、New code cache、inner payload/Relay 状态、wire/AAD、Portal 发现/gate、instanceId/generation）+ high；v6 按 §0 修正并附 vectors。
- v6 文档评审：4 个 blocking（pair 失败/错误契约、Provider WS 控制 ack/error、rotate/delete 与 link ownership、shareKeyVfs 发放/迁移）+ high；v7 按 §0 修正。
- v7 文档评审：3 个 blocking（data response cache key 自相矛盾、Provider key 身份/丢失设备未绑定、Portal/CLI 同机使“Portal 不可读”表述不成立）+ high/medium；v8 按 §0 修正。
- v8 文档评审：2 个 blocking（202+poll consumer/reqId/nonce 关联不闭合、公开 linkId 的 claim/tombstone 未持久绑定）+ high/medium；v9 按 §0 修正。
- v9 文档评审：5 个 blocking（listener 漏 provider-key/links、跨 Portal deviceId 冲突、provider key 过期恢复矛盾、pair/link-error 不缓存、unregister 真值表矛盾）+ high/medium；v10 按 §0 修正。
- v10 文档评审：5 个 blocking（consumed 缺 pairId、4401 语义、hooks ENOENT fail-open、provider-key HTTP 契约、每 (hookId,deviceId) secret）+ high/medium；v11 按 §0 修正。
- v11 文档评审：4 个 blocking（link store marker 跨文件、deviceId/per-hook 撤销、at-rest AAD、portalServerId 绑定）+ high/medium；v12 按 §0 修正。
- v12 文档评审：4 个 blocking（4401 语义、paused 双契约、tailnet 不可落地、read golden 长度）+ high/medium；v13 按 §0 修正。
- v13 文档评审：5 个 blocking（providerKeyId 17B、binding/hook 冲突、hook sweep/delete、consumed 真值表、DEVICE_MISMATCH frame/close）+ high/medium；v14 按 §0 修正。
- v14 文档评审：4 个 blocking（.ts.net 公网入口、Stop/paused 真值表、pending Revoke 归属、link store/prune 死锁）+ high/medium；v15 按 §0 修正。
- v15 文档评审：5 个 blocking（cursor 不可逆/排序未定义、同 deviceId 跨 hook 授权、§4.2 pending revocation 回退、master loss 4414 不可恢复、Relay pairState 内存/持久矛盾）+ high/medium；v16 按 §0 修正；待新一轮评审。

## 附录 A：P0 test vectors

以下 hex 是 v16 的 source of truth；Kotlin/Node 必须逐字节复现。输入与输出由同一参考算法生成；任何实现差异先改 vectors，不先写代码。控制帧（`pair`/`register`/`unregister`/`ack`/`error`）与 `pair-error`/`link-error` 是明文 JSON，不走 AAD/AEAD，用附录 B 的 schema + golden examples 测试；`list`/`read`/inner `error` 复用 data envelope，inner JSON 用附录 C 的 golden examples 测试。pair response 的 `roots` 已纳入 A.4/A.5。

### A.1 输入

```
v = 1
linkId = EBESExQVFhcYGRobHB0eHw        (raw 10..1f)
pairId = UFFSU1RVVldYWVpbXF1eXw        (raw 50..5f)
reqId = ICEiIyQlJicoKSorLC0uLw         (raw 20..2f)
clientNonce = MDEyMzQ1Njc4OTo7PD0-P0BBQkNERUZHSElKS0xNTk8   (raw 30..4f)
ts = 1760000000000
code = ABCDEFGHJKMNPQRS
pairReqIv = YGFiY2RlZmdoaWpr            (raw 60..6b)
pairRespIv = bG1ub3BxcnN0dXZ3          (raw 6c..77)
dataReqIv = eHl6e3x9fn-AgYKD            (raw 78..83)
dataRespIv = hIWGh4iJiouMjY6P          (raw 84..8f)
responseTs = 1760000001000
shareId = cHFyc3R1dnd4eXp7fH1-fw        (raw 70..7f)
shareSecret = gIGCg4SFhoeIiYqLjI2Oj5CRkpOUlZaXmJmam5ydnp8 (raw 80..9f)
```

### A.2 派生 key

```
K        = 63807d96748d53879b23127778d6a875d4b02679910881c6d9af6207b081e20a
Kreq     = ed2e51adaffa5eec1bd5958b996330117dd45388ac9d842a19b409f323761724
Kresp    = 52d0def9dd176490fb83597ef75eb06855a87c1d88d51a46f17056a79cae7d6b
Kpair    = c6aa8690b32bec4a8e8dfe6af4cb237880bffe4b2d22ab8fc79a6c8fb68dcd20
KpairResp= 4e7aae0fa4db7daabbd75b2edc1382a3bc43a06db96dd6fcd3d2f8a532fc1ac0
Kcursor  = 532ce7eae5974910d8c535f5ab4733b1786df1197374b7fae792a536819e9e67
```

### A.3 AAD hex

```
pairReqAAD = 0000000101000000047061697200000010101112131415161718191a1b1c1d1e1f00000010505152535455565758595a5b5c5d5e5f00000010202122232425262728292a2b2c2d2e2f00000020303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f0000000800000199c82cc0000000000c606162636465666768696a6b00000008706169722d726571
pairRespAAD = 0000000101000000047061697200000010101112131415161718191a1b1c1d1e1f00000010505152535455565758595a5b5c5d5e5f00000010202122232425262728292a2b2c2d2e2f00000020303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f0000000800000199c82cc0000000000c606162636465666768696a6b0000000800000199c82cc3e80000000c6c6d6e6f707172737475767700000009706169722d72657370
dataReqAAD = 00000001010000000376667300000010101112131415161718191a1b1c1d1e1f00000010202122232425262728292a2b2c2d2e2f00000020303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f0000000800000199c82cc0000000000c78797a7b7c7d7e7f808182830000000772657175657374
dataRespAAD = 00000001010000000376667300000010101112131415161718191a1b1c1d1e1f00000010202122232425262728292a2b2c2d2e2f00000020303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f0000000800000199c82cc0000000000c78797a7b7c7d7e7f808182830000000800000199c82cc3e80000000c8485868788898a8b8c8d8e8f00000008726573706f6e7365
```

### A.4 plaintext

```
pairReq     = {"pairId":"UFFSU1RVVldYWVpbXF1eXw","clientNonce":"MDEyMzQ1Njc4OTo7PD0-P0BBQkNERUZHSElKS0xNTk8"}
pairResp    = {"shareId":"cHFyc3R1dnd4eXp7fH1-fw","shareSecret":"gIGCg4SFhoeIiYqLjI2Oj5CRkpOUlZaXmJmam5ydnp8","scopes":["app.data.activity"],"roots":["/appdata/activity"],"label":"Claude"}
dataReq     = {"v":1,"type":"stat","path":"/appdata/activity/events.jsonl"}
dataResp    = {"v":1,"type":"entry","entry":{"path":"/appdata/activity/events.jsonl","name":"events.jsonl","isDirectory":false,"size":12345,"mtimeMs":1760000000000}}
```

### A.5 ciphertext（`ciphertext || 16B tag`）

```
pairReq     = 88d5c28412ed6921608efcddf1ff5921693d1d1b77eba8529500831fe58a9cf3bf3720f22c5fbce8a7eb0988f404056e26e6e25afeaa75de09b2bbadce5a8b59ef040ffbdb6a95a56b2e47b2dc14bd23e08b25961282c16015a4cfef365f6524fb26f8901a93d82d627d4a61e59947
pairReqTag  = 24fb26f8901a93d82d627d4a61e59947
pairResp    = 7ed26b4187fb538d7ebb568ebc775074948bf453aadddf3134d07b39bd762e54e485c471b7934dcb4c4d8a51b3b10f4e4396cbc6f5dfd325c7aec58ed8cdddaeb24c8c1c62ce0375c7ae902b6d5baeff07fa8207861ab80f7a822a9c6263703ca3265e7c126089bb13ce6ffbff05f87af5bc82c210b3b5f80fb5b1d78f8e9279eb897fa8ddcd3e904506051b48193d7c3da0b21396a22e1d9e6108d150167f3cdea370aeaeeae424046e66a6576ad0bfb12baa02dc7561d89c1617c13925
pairRespTag = d0bfb12baa02dc7561d89c1617c13925
dataReq     = 47944c5a97e321bb9359dd947342e1d9fc0a50abfd8cb8ff984542011817e38b2bfa8bf51b125dcfea7b9c6e361736cc0ac1a70d434550eb71b7afd871ee8d75824a5cecc6bc8eacbfd3798e1d
dataReqTag  = ee8d75824a5cecc6bc8eacbfd3798e1d
dataResp    = 2771f07f6f24f789cacfe8866d38b9f1296e51a4967ab76271cf010732bd5d3a0a62be26fc39e4ffb5115b3e07a3446cf4eeb057625401b6eeba3fe5d777bd8bef4e38a6ad7ad7c78c1b320f71ceacc958ccd6ee35a10786a2734f0b05413978cfd0169bed46804be0cb3053d44172c94a099f730406f5501f9dd05ca6d04afe0c76aa994320dd8732f4badf3445bc971120f53dd6ca39a2204e2c8d58156fa1ed1ae9298ab4cb
dataRespTag = a2204e2c8d58156fa1ed1ae9298ab4cb
```

### A.6 v16 追加 golden（cursor/负向/at-rest）

```
Kcursor       = 532ce7eae5974910d8c535f5ab4733b1786df1197374b7fae792a536819e9e67
cursorCtx     = 00000010101112131415161718191a1b1c1d1e1f000000112f617070646174612f616374697669747900000004000003e80000000131000000192f617070646174612f61637469766974792f612e6a736f6e6c
cursorTag     = eb01aa3d5f052033e8071e56269818f1
cursorRaw     = 01000000192f617070646174612f61637469766974792f612e6a736f6e6ceb01aa3d5f052033e8071e56269818f1
cursorB64url  = AQAAABkvYXBwZGF0YS9hY3Rpdml0eS9hLmpzb25s6wGqPV8FIDPoBx5WJpgY8Q
doublePrefixPairReqAAD = 000000010000000101000000047061697200000010101112131415161718191a1b1c1d1e1f00000010505152535455565758595a5b5c5d5e5f00000010202122232425262728292a2b2c2d2e2f00000020303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f000000080000000800000199c82cc0000000000c606162636465666768696a6b00000008706169722d726571
doublePrefixPairReqTag = 482c7c6e1d51fdda69a1308fb9c4c6b5
```

at-rest AAD UTF-8 示例（hex 是字面量的 UTF-8 bytes；密文依赖设备 Keystore key，不做跨设备 golden）：

```
v1|cHFyc3R1dnd4eXp7fH1-fw|secret
  = 76317c6348467963335231646e6434655870376648312d66777c736563726574
v1|https://clawdy-mini.tailf665f5.ts.net|hk_0123456789abcdef0123456789abcdef|7d444840-9dc0-11e0-9b7a-0800200c9a66|deviceSecret
  = 76317c68747470733a2f2f636c617764792d6d696e692e7461696c6636363566352e74732e6e65747c686b5f30313233343536373839616263646566303132333435363738396162636465667c37643434343834302d396463302d313165302d396237612d3038303032303063396136367c646576696365536563726574
v1|https://clawdy-mini.tailf665f5.ts.net|hk_0123456789abcdef0123456789abcdef|7d444840-9dc0-11e0-9b7a-0800200c9a66|providerKey|MDEyMzQ1Njc4OTo7PD0-P0
  = 76317c68747470733a2f2f636c617764792d6d696e692e7461696c6636363566352e74732e6e65747c686b5f30313233343536373839616263646566303132333435363738396162636465667c37643434343834302d396463302d313165302d396237612d3038303032303063396136367c70726f76696465724b65797c4d4445794d7a51314e6a63344f546f375044302d5030
```

cursor 判定：Provider 重算 tag 必须等于 cursorTag，且 linkId/path/limit/scopeVersion 与当前请求一致；`lastKey` 必须仍在当前排序结果中，否则 `cursor_invalid`。cursor 不加密、无服务端状态；它只泄漏 lastKey（已由 list 返回）和路由绑定。

## 附录 B：控制帧 schema 与 golden examples

控制帧是明文 JSON，不走 AEAD/AAD；golden examples 按语义比较（字段顺序不参与），但字段名、类型、枚举必须一致。公共规则：`v=1`（int）；`controlId`/`linkId`/`pairId`/`reqId` 为 16B base64url；`deviceId` 为 8..128 位 `[A-Za-z0-9_-]`（新生成 UUID）；`generation`/`currentGeneration`/`pairEpoch` 为 1..2^53-1 的 JSON number；error frame 的 `currentPairEpoch` 可为 0 表示尚无状态；`ttlSeconds` 为 1..300；`state` ∈ `active|paused|consumed|expired`；`reason` wire 只允许 `revoked|shutdown`（`replaced` 仅 Relay 内部）；`code` 枚举见各帧；`message` 只用固定短语（如 `generation behind`、`link owned by another device`、`device mismatch`、`stale pair epoch`、`link revoked`、`pairing code expired`、`share revoked`），不拼接路径/文件名。未知字段忽略；未知枚举/长度/类型 → `INVALID_CONTROL`。

| 帧 | 必需字段 | 条件/可选字段 |
|---|---|---|
| `pair` | `v,op,controlId,linkId,state,pairEpoch` | `active`: 必须 `pairId,ttlSeconds`；`consumed`: 必须 `pairId`；`paused`: 不带 `pairId/ttlSeconds`；`expired`: 必须 `pairId`、不带 `ttlSeconds` |
| `register` | `v,op,controlId,linkId,deviceId,generation` | — |
| `unregister` | `v,op,controlId,linkId,reason` | — |
| `ack` | `v,op,control,controlId,linkId,ok` | `generation`（register） |
| `error` | `v,op,control,controlId,linkId,code,message,retryable` | `currentGeneration`（STALE_GENERATION）、`retryAfterMs`（STALE_GENERATION/RATE_LIMITED） |
| `pair-error` | `v,op,consumerId,linkId,pairId,reqId,code,message` | `retryAfterMs`、`serverTs`、`skewMs` |
| `link-error` | `v,op,consumerId,linkId,reqId,code,message` | — |
| `ping`/`pong` | `v,op,id` | — |

Golden examples（`deviceId` 为示例 UUID；`controlId` 复用附录 A 的 reqId；`consumerId` 为示例 `QEFCQ0RFRkdISUpLTE1OTw`）：

```json
{"v":1,"op":"pair","controlId":"ICEiIyQlJicoKSorLC0uLw","linkId":"EBESExQVFhcYGRobHB0eHw","pairId":"UFFSU1RVVldYWVpbXF1eXw","ttlSeconds":300,"state":"active","pairEpoch":3}
{"v":1,"op":"pair","controlId":"ICEiIyQlJicoKSorLC0uLw","linkId":"EBESExQVFhcYGRobHB0eHw","pairId":"UFFSU1RVVldYWVpbXF1eXw","state":"expired","pairEpoch":6}
{"v":1,"op":"register","controlId":"ICEiIyQlJicoKSorLC0uLw","linkId":"EBESExQVFhcYGRobHB0eHw","deviceId":"7d444840-9dc0-11e0-9b7a-0800200c9a66","generation":7}
{"v":1,"op":"unregister","controlId":"ICEiIyQlJicoKSorLC0uLw","linkId":"EBESExQVFhcYGRobHB0eHw","reason":"revoked"}
{"v":1,"op":"ack","control":"register","controlId":"ICEiIyQlJicoKSorLC0uLw","linkId":"EBESExQVFhcYGRobHB0eHw","ok":true,"generation":7}
{"v":1,"op":"error","control":"register","controlId":"ICEiIyQlJicoKSorLC0uLw","linkId":"EBESExQVFhcYGRobHB0eHw","code":"STALE_GENERATION","message":"generation behind","retryable":true,"currentGeneration":8,"retryAfterMs":1000}
{"v":1,"op":"pair-error","consumerId":"QEFCQ0RFRkdISUpLTE1OTw","linkId":"EBESExQVFhcYGRobHB0eHw","pairId":"UFFSU1RVVldYWVpbXF1eXw","reqId":"ICEiIyQlJicoKSorLC0uLw","code":"code_expired","message":"pairing code expired"}
// 负例：active 缺 pairId/ttlSeconds → INVALID_CONTROL（error frame）
{"v":1,"op":"pair","controlId":"ICEiIyQlJicoKSorLC0uLw","linkId":"EBESExQVFhcYGRobHB0eHw","state":"active","pairEpoch":3}
{"v":1,"op":"link-error","consumerId":"QEFCQ0RFRkdISUpLTE1OTw","linkId":"EBESExQVFhcYGRobHB0eHw","reqId":"ICEiIyQlJicoKSorLC0uLw","code":"share_revoked","message":"share revoked"}
{"v":1,"op":"ping","id":"ICEiIyQlJicoKSorLC0uLw"}
{"v":1,"op":"pong","id":"ICEiIyQlJicoKSorLC0uLw"}
```

## 附录 C：inner/wire golden examples

以下按语义比较（字段顺序不参与）；`ct` 仍以附录 A 为 source of truth。`consumerId` 为示例 `QEFCQ0RFRkdISUpLTE1OTw`。

```json
// POST /v1/links
{"linkId":"EBESExQVFhcYGRobHB0eHw"}

// POST /v1/provider-keys
{"deviceId":"7d444840-9dc0-11e0-9b7a-0800200c9a66","name":"Pixel","deviceSecret":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"}
{"providerKey":"wsk_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","providerKeyId":"MDEyMzQ1Njc4OTo7PD0-P0","hookId":"hk_0123456789abcdef0123456789abcdef","deviceId":"7d444840-9dc0-11e0-9b7a-0800200c9a66","createdAt":1760000000000,"expiresAt":null}

// POST /v1/provider-keys/rotate
{"deviceId":"7d444840-9dc0-11e0-9b7a-0800200c9a66","deviceSecret":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"}
// rotate response 同 issue response（新 providerKey/providerKeyId + hookId/deviceId/createdAt/expiresAt）

// GET /v1/provider-keys?hook=hk_0123456789abcdef0123456789abcdef
{"devices":[{"deviceId":"7d444840-9dc0-11e0-9b7a-0800200c9a66","name":"Pixel","providerKeyId":"MDEyMzQ1Njc4OTo7PD0-P0","createdAt":1760000000000,"expiresAt":null,"lastUsedAt":1760000001000,"rotatedAt":null,"keyRevokedAt":null,"deviceRevokedAt":null}]}
// 错误 body（所有 provider-key 错误）
{"code":"DEVICE_AUTH","message":"device auth failed"}

// data request wire（A.5 dataReq hex 的 base64url）
{"v":1,"op":"vfs","consumerId":"QEFCQ0RFRkdISUpLTE1OTw","linkId":"EBESExQVFhcYGRobHB0eHw","reqId":"ICEiIyQlJicoKSorLC0uLw","clientNonce":"MDEyMzQ1Njc4OTo7PD0-P0BBQkNERUZHSElKS0xNTk8","ts":1760000000000,"iv":"eHl6e3x9fn-AgYKD","ct":"R5RMWpfjIbuTWd2Uc0Lh2fwKUKv9jLj_mEVCARgX44sr-ov1GxJdz-p7nG42FzbMCsGnDUNFUOtxt6_Yce6NdYJKXOzGvI6sv9N5jh0"}

// pair request wire（A.5 pairReq hex 的 base64url）
{"v":1,"op":"pair","consumerId":"QEFCQ0RFRkdISUpLTE1OTw","linkId":"EBESExQVFhcYGRobHB0eHw","pairId":"UFFSU1RVVldYWVpbXF1eXw","reqId":"ICEiIyQlJicoKSorLC0uLw","clientNonce":"MDEyMzQ1Njc4OTo7PD0-P0BBQkNERUZHSElKS0xNTk8","ts":1760000000000,"iv":"YGFiY2RlZmdoaWpr","ct":"iNXChBLtaSFgjvzd8f9ZIWk9HRt366hSlQCDH-WKnPO_NyDyLF-86KfrCYj0BAVuJubiWv6qdd4JsrutzlqLWe8ED_vbapWlay5HstwUvSPgiyWWEoLBYBWkz-82X2Uk-yb4kBqT2C1ifUph5ZlH"}

// pair response wire（pairId + consumerId；ct 用 A.5 pairResp 的 base64url）
{"v":1,"op":"pair","consumerId":"QEFCQ0RFRkdISUpLTE1OTw","linkId":"EBESExQVFhcYGRobHB0eHw","pairId":"UFFSU1RVVldYWVpbXF1eXw","reqId":"ICEiIyQlJicoKSorLC0uLw","clientNonce":"MDEyMzQ1Njc4OTo7PD0-P0BBQkNERUZHSElKS0xNTk8","ts":1760000001000,"iv":"bG1ub3BxcnN0dXZ3","ct":"ftJrQYf7U41-u1aOvHdQdJSL9FOq3d8xNNB7Ob12LlTkhcRxt5NNy0xNilGzsQ9OQ5bLxvXf0yXHrsWO2M3drrJMjBxizgN1x66QK21brv8H-oIHhhq4D3qCKpxiY3A8oyZefBJgibsTzm_7_wX4evW8gsIQs7X4D7Wx14-OknnriX-o3c0-kEUGBRtIGT18PaCyE5aiLh2eYQjRUBZ_PN6jcK6u6uQkBG5mpldq0L-xK6oC3HVh2JwWF8E5JQ"}

// data response wire（Provider 原样 echo consumerId；A.5 dataResp hex 的 base64url）
{"v":1,"op":"vfs","consumerId":"QEFCQ0RFRkdISUpLTE1OTw","linkId":"EBESExQVFhcYGRobHB0eHw","reqId":"ICEiIyQlJicoKSorLC0uLw","clientNonce":"MDEyMzQ1Njc4OTo7PD0-P0BBQkNERUZHSElKS0xNTk8","ts":1760000001000,"iv":"hIWGh4iJiouMjY6P","ct":"J3Hwf28k94nKz-iGbTi58SluUaSWerdicc8BBzK9XToKYr4m_Dnk_7URWz4Ho0Rs9O6wV2JUAbbuuj_l13e9i-9OOKatetfHjBsyD3HOrMlYzNbuNaEHhqJzTwsFQTl4z9AWm-1GgEvgyzBT1EFyyUoJn3MEBvVQH53QXKbQSv4MdqqZQyDdhzL0ut80RbyXESD1PdbKOaIgTiyNWBVvoe0a6SmKtMs"}

// inner list request / page 1 / page 2
{"v":1,"type":"list","path":"/appdata/activity","limit":1000}
{"v":1,"type":"entries","entries":[{"path":"/appdata/activity/a.jsonl","name":"a.jsonl","isDirectory":false,"size":12,"mtimeMs":1760000000000}],"nextCursor":"AQAAABkvYXBwZGF0YS9hY3Rpdml0eS9hLmpzb25s6wGqPV8FIDPoBx5WJpgY8Q"}
{"v":1,"type":"list","path":"/appdata/activity","limit":1000,"cursor":"AQAAABkvYXBwZGF0YS9hY3Rpdml0eS9hLmpzb25s6wGqPV8FIDPoBx5WJpgY8Q"}
{"v":1,"type":"entries","entries":[],"nextCursor":null}

// inner read text / binary
{"v":1,"type":"read","path":"/appdata/activity/a.jsonl","offset":0,"length":4096}
{"v":1,"type":"data","data":"eyJhIjoxfQ==","etag":"e1","offset":0,"length":7,"nextOffset":7,"eof":true}
{"v":1,"type":"read","path":"/appfiles/bin.dat","offset":0,"length":4096}
{"v":1,"type":"data","data":"AAEC","offset":0,"length":3,"nextOffset":3,"eof":true}

// inner error（所有 code 同 schema；invalid_path 示例）
{"v":1,"type":"error","error":{"code":"invalid_path","message":"path not normalized"}}
// inner error code 集合：not_found, invalid_path, bad_request, not_supported, provider_error, range_not_satisfiable, nonce_reused, stale_ts, in_flight, too_large, permission_lost, cursor_invalid, timeout

// pair paused / consumed 控制帧
{"v":1,"op":"pair","controlId":"ICEiIyQlJicoKSorLC0uLw","linkId":"EBESExQVFhcYGRobHB0eHw","state":"paused","pairEpoch":4}
{"v":1,"op":"pair","controlId":"ICEiIyQlJicoKSorLC0uLw","linkId":"EBESExQVFhcYGRobHB0eHw","pairId":"UFFSU1RVVldYWVpbXF1eXw","state":"consumed","pairEpoch":5}

// 控制错误
{"v":1,"op":"error","control":"register","controlId":"ICEiIyQlJicoKSorLC0uLw","linkId":"EBESExQVFhcYGRobHB0eHw","code":"LINK_OWNED","message":"link owned by another device","retryable":false}
{"v":1,"op":"error","control":"register","controlId":"ICEiIyQlJicoKSorLC0uLw","linkId":"EBESExQVFhcYGRobHB0eHw","code":"DEVICE_MISMATCH","message":"device mismatch","retryable":false}
{"v":1,"op":"error","control":"pair","controlId":"ICEiIyQlJicoKSorLC0uLw","linkId":"EBESExQVFhcYGRobHB0eHw","code":"STALE_PAIR_EPOCH","message":"stale pair epoch","retryable":false}
{"v":1,"op":"error","control":"unregister","controlId":"ICEiIyQlJicoKSorLC0uLw","linkId":"EBESExQVFhcYGRobHB0eHw","code":"REVOKED","message":"link revoked","retryable":false}
{"v":1,"op":"ack","control":"unregister","controlId":"ICEiIyQlJicoKSorLC0uLw","linkId":"EBESExQVFhcYGRobHB0eHw","ok":true}
```
