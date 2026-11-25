# Share Key VFS：概念、方案与实现

状态：设计草案 v5（2026-11-25），按 v4 文档评审的 4 个 blocker 与 high 修正；待新一轮独立评审；未实现。
相关旧路径：`~/workspace/clawdy-portal/VFS.md`、`~/workspace/clawdy-portal/docs/agent-oriented-vfs.md`、`~/workspace/clawdy-portal/docs/jobs.md`、[`vfs-dry-run.md`](vfs-dry-run.md)、[`vfs-connection-stability.md`](vfs-connection-stability.md)、[`mcp.md`](mcp.md)。本设计通过后按 §3.12 改写或标退役。

## 0. v5 修复摘要

| 评审项 | v5 处理 |
|---|---|
| B1 pairing code 存储矛盾 | 改为 **memory-only**：16 字符 secret 只在 App 内存，零化；不持久化 `codeCiphertext`/`codeHash`；进程死即 code 丢失，New code。Provider 按 linkId/Share 找 active code，不需要 hash 查找。 |
| B2 provider key hash-only 矛盾 | §3.6 改为「Provider key 加密可逆存；Consumer key hash/constant-time」；增加 `hookId + deviceId` 唯一、knock 绑定、rotate/revoke/list schema、Portal master key、orphan 处理、Portal 识别、VFS-enabled knock。 |
| B3 iv/AAD/编码 | 增加 wire/AAD 字段表；定义 data response envelope、`v`/`ts` 编码、base64url vs raw、HKDF salt、`ct = ciphertext || 16B tag`、pair response AAD 的 request/response iv 来源；P0 必须提交 exact hex vectors。 |
| 新 nonce/replay blocker | data `clientNonce` 每 Share 唯一；Provider seen-nonce cache 10 分钟；`|now-ts| > 5 分钟` 硬拒绝；重复 `NONCE_REUSED`；replay cache TTL ≥ skew；retry 必须复用 `(reqId, clientNonce, iv)`；测试四条路径。 |
| H1 scope/warnings | `app.workspace` 标红并加 warning；`app.files` 补 `conductor/api-trace.json`、`conductor/images/`、`datastore/`；`app.data.diagnostics` 改生成元数据；`app.data.logs` 明确 redacted view；`conductor.files` 包含 inbox；symlink canonical 检查。 |
| H2 `all` | `all` = 所有固定 scope（含 `.runtime`），排除 `user.selected`，硬排除 `vfs/`；`Everything = all`；`conductor.files` 包含 inbox。 |
| H3/H11 cache/revoke | App 持久化 pending-unregister；Relay `unregister` purge pair/response cache；response cache key 加 linkId；pair cache 不超过 code expiry；New code purge pair cache。 |
| H4 phase flag | v4 已定；保持 `ShareKeyVfs` flag，旧路径保留到 P4，P5 移除。 |
| H5 egress/DNS | 公网 Funnel + `VpnService.protect()`；protected/custom DNS 或 VPN on 测试；tailnet URL 不 protect。 |
| H6 provider key 生命周期 | 补 issue/rotate/revoke/list schema、hookId/deviceId、Portal master key、orphan、Portal 识别、VFS-enabled knock。 |
| H7 reinstall 文案 | 重装会清 `noBackupFilesDir/vfs`，旧 Share 不能在新装里撤销/使用；修文案和恢复路径。 |
| H8 CLI 契约 | 移除 CLI 自身不可发的 `CLI_NOT_FOUND`；`PROVIDER_KEY_REVOKED` 动作改为请用户开 App/Portal；加 `IN_FLIGHT`/`NONCE_REUSED`/`STALE_TS`；key store 0700 + fsync/atomic。 |
| H9 多 Share | 通知改「Sharing N Shares with M Agents」；`totalBytes` 定义为实际读取字节。 |
| H10 部署/迁移 | stale Unix socket unlink；provider key 文件/路径；`service-install` CLI；`tests/jobs.test.mjs` 只退役 VFS 用例。 |
| M1–M8 | 删除 5-attempt；New code/Revoke 文案；app.workspace/app.data 警告；minSdk 24/21 事实；user.selected 错误拆分；limits；迁移清单；错误目录补 401/409/413/NONCE_REUSED/STALE_TS。 |

## 1. 背景与目标

现有 VFS 由 Conductor 通过 MCP `vfs_link_request` / `vfs_connect` 建立 Link，Portal 把 provider WebSocket 交给 vfsd，容器通过 `/vfs` 挂载读取。它已经能工作，但概念和运维都重：MCP、vfsd、FUSE、Docker、Link、consent 五层；Portal 能接触明文；App 升级/进程死亡后 Link 不自动恢复；大文件在蜂窝 + Tailscale 上实测 25–28 秒/MB。Conductor 是试验品，App 的核心是规则化网络出口选择；VFS 应该退回成简单、可独立演进的观测数据出口。

目标：

- Claude Code Agent 在 Mini 上直接读取 App 的 VFS，不用手动导出、不用容器挂载。
- Portal/Relay 全程不能读取 App 的数据内容，只能看到路由和大小等元数据。
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

### 2.1 角色

| 角色 | 说明 |
|---|---|
| App（Provider） | 数据在手机上；主动连 Portal/Relay；验证 Share Key；按 Share 的 scope 读 VFS。 |
| Agent（Consumer） | 远端 Claude Code；用 `wonder-vfs` CLI 读；不需要 MCP。 |
| Portal/Relay | 盲路由；鉴权 transport、按 Link ID 转发密文、限速/审计元数据；不解析数据。 |
| Share | 一次授权：label + scope 集合 + 一个 Link；一个 Agent 一个 Share；New code / Revoke。 |
| Pairing Code | 5 分钟、一次性、16 字符 Crockford base32；用户粘进 Agent 聊天；CLI 用它换 Share Key。 |
| Share Key | 长期 AEAD 密钥；只存在 App 和 Agent 的 CLI 里；不打印、不进聊天。 |

用户只需要理解 **App / Agent / Portal / Share / Pairing Code** 五个概念。Provider/Consumer、Share Key、Link ID、instanceId、generation、reqId、AEAD/HKDF、202+poll 都是实现概念，不出现在主 UI。

### 2.2 信任边界

- **Transport auth**：App 默认复用现有 Portal 连接，自动换/发一个 Relay 专用 Provider key；本机 Agent 走 Unix socket，免 transport key；远端 Consumer 的 key 后置。Transport key 只防滥用，不授予数据。
- **Data auth**：Share Key。App 和 CLI 各持有；Portal/Relay 永远看不到。
- **Authorization**：App 按 Share 的 scope 逐请求检查；Portal 不解释 scope。
- **Consent**：创建 Share、生成 Pairing Code 就是授权；每个敏感 scope 有自己的红字/黄字警告；`all` 的警告是并集。

必须对用户说清楚：

- **Portal 看得到**：哪个 Share、什么时候、多少字节、Provider/Consumer key 的**前缀/身份**（不是 Share Key）、Link ID；看不到文件内容、路径、scope、label。
- **Agent 看得到**：你分享的文件；Agent 可能把它们发给它的模型提供方。
- **Pairing Code**：一次性、5 分钟、在聊天里可见；看到它的人在这 5 分钟内可能抢先配对。
- **Share Key**：由 Agent 的 CLI 保存；Portal 读不到；Agent 宿主机被信任（同一用户可读 CLI 的 key store）；不要宣称模型绝对拿不到 key。
- **Relay 不可读内容**：密文和 AEAD tag 保证 Portal 不能读/伪造/篡改；Portal 仍能看到 linkId/reqId/ts/op/size 等路由元数据，可以丢包、延迟。
- **可用性**：读取只在 WonderSing 的 sharing 通知/FGS 活着时可用；App 停止/Doze 后 CLI 得到 503，重开 App 再试。不承诺 24/7。

### 2.3 用户模型（一页话术）

> WonderSing 连上你的 Portal 后，创建一个 Share：起个名字，选 Agent 可以读的文件。复制 App 给的一行配对命令（5 分钟有效）到 Agent 聊天。Agent 的 CLI 用它换一把自己保存的 key，然后 list/read 这些文件。Portal 只转发加密数据和路由元数据，看不到内容。撤销 Share 就断掉访问。

术语：

- 用「Share files / Agent access」，不用 VFS。
- 用「Portal connection」，不用 Relay（除非 Relay 不是 Portal）。
- 用「What the Agent can read」，不摆 scope 技术名。
- 用「New code」「Revoke」，不用 rotate。
- 全局叫「Stop sharing（all Agents，暂停）」，单个 Share 叫「Revoke this Share（永久断开）」。

### 2.4 用户流程

一次性设置：

1. 安装 `wonder-vfs` CLI（Portal `pnpm service-install` 装到 `$HOME/bin`；Claude Code skill/CLAUDE.md 告诉 Agent 命令）。
2. Settings → Share files → Connect。默认使用已配置的 Portal 连接；URL/key 收进 Advanced。

每个 Agent：

3. New Share → 名字（可选）→ Recommended / Everything / Customize → Create。
4. Copy pairing command（5:00 倒计时）；App 复制完整命令 `"$HOME/bin/wonder-vfs" pair --link <linkId> --code <code>`。
5. 粘到 Agent 聊天；Agent 运行命令，返回它能看到的 roots。
6. Agent 运行 `"$HOME/bin/wonder-vfs" list/read ...`；不再需要 code。撤销 Share → Revoke。

### 2.5 已接受的取舍

- Pairing Code 会进入聊天/模型上下文；一次性、5 分钟、兑换后失效；长期 Share Key 不打印、不进聊天。用户已拒绝 out-of-band CLI/QR。
- `all` 可以包含敏感 App 文件，但必须有红字警告；`vfs/` 永久排除；`app.files`、`app.workspace`、`conductor.files`、`conductor.conversation` 各自也有红字警告，不只 `all`。
- 可用性保持现状：FGS 活着时后台可读；进程死/Doze 后 503；不做 always-on。
- 无 PFS；静态 Share Key + 短 TTL/Revoke；以后可加 X25519 ephemeral。
- Relay 元数据（Link ID、时间、大小、key 前缀）可见；不记录内容/路径/scope/label。
- Share 功能要求 API 24+；API 21 `otherLegacy` 隐藏该功能。
- 容器 `/vfs` 退役；Conductor 的手机侧 VFS 工具移除；CLI 是替代。
- 保留 `user.selected` 和细粒度 `app.data.*`；实现可分阶段，设计保留。
- Share 默认不过期；`lastUsedAt` 记录；以后可选 TTL。Stop sharing 只是暂停 transport，Share/Key 保留；Revoke 才永久删除。New code 不 eject 已配对 Agent。
- Reinstall 会清 `noBackupFilesDir`：旧 Share 不能在新装里使用/撤销；必须在旧装 Revoke，或撤销 Provider key 后新建 Share。

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
Agent CLI (Consumer) --Unix socket / HTTPS--> Portal/Relay
```

- App 只出站；Portal 按 Link ID 路由不透明 envelope；不需要 vfsd/FUSE。
- Agent CLI 把 VFS 请求加密；App 解密、验 scope、读 VFS、加密响应；Portal 只转发。
- 一个 Share 一个 Link ID；App 只维护一条 Provider WebSocket，多 Link ID 用 register/unregister 复用，上限 8。
- Relay 只存内存 `linkId -> connection` + bounded tombstone；不存 secret/scope/label。

### 3.2 App / Portal 配置与 Provider key

- 默认：Settings → Share files 显示已有 Portal 连接；Connect 时用现有 MCP knock secret 调 `POST /v1/provider-keys`（按 `hookId + deviceId` **issue-once**），返回 `{relayUrl, providerKey}`；Portal 用 master key 加密保存可逆 key（不是 hash-only），重复调用返回同一个 key。App 加密存 `noBackupFilesDir/vfs`。
- **Knock 绑定**：Provider key 记录 `hookId`（现有 MCP knock）与 `deviceId`；唯一 `(hookId, deviceId)`。同一 knock 已绑定其他 device 时返回 `403 DEVICE_MISMATCH`；不能用一个有效 knock 读/删别的 device 的 key。
- 显式 `POST /v1/provider-keys/rotate`（auth knock，body `{deviceId}`）：撤销旧 key、返回新 key；App 更新存储并关闭/重连现有 Provider WS。响应丢失时，重新调 `POST /v1/provider-keys` 取当前 key（加密可逆存储），不会 strand。
- `GET /v1/provider-keys`（auth knock）列 `{deviceId,name,createdAt,lastUsedAt,revokedAt}`，用于 reinstall 后清理 orphan；`DELETE /v1/provider-keys/{deviceId}` 撤销，幂等。
- **Portal master key**：Keychain 或 0600 独立文件（不是 `hooks.json`）；Provider key 加密存 `~/.clawdy-portal/provider-keys.json` 0600、atomic；master key 轮换需重新加密。
- `VfsProviderKeyProvisioner` 是唯一接触现有 Portal 连接的 bootstrap adapter；VFS core 不 import MCP。**只向已配置的 Portal origin**（serverBase/Portal 标记）发 MCP token，不向任意 MCP server 发。
- 没有 Portal 连接时，Advanced 允许手填 Relay URL + Provider key；Portal 提供 CLI/UI 一次性显示 key。
- 更新 `AUTH.md`：`POST /v1/provider-keys` 的认证、device 绑定、VFS-enabled knock 检查、限速、审计；不记录 secret。

### 3.3 Share 与 Pairing Code

- Share 创建时生成 128-bit `linkId` + 32B `secret`；一个 Agent 一个 Share；多个 Agent 建多个 Share；上限 8，超出提示先 Revoke。
- Share 记录：`shareId`、label、linkId、secret（加密）、scopes、instanceId、createdAt、pairedAt、lastUsedAt、expiresAt（默认 null）、revokedAt。存 `noBackupFilesDir/vfs/`，不进 telemetry/export；`lastUsedAt` 在每次成功读取后更新。
- Pairing Code：16 字符 Crockford base32（无 I/L/O/U），分组显示，大小写不敏感，5 分钟，单次；一个 Share 同时只有一个 active code；New code 使旧 code 立即失效。**不设 5-attempt 上限**；用 80-bit + 5 分钟 + per-Link rate limit；无效 AEAD tag 只限速，不烧 code。
- **Pairing code 是 memory-only**：16 字符 secret 只在 App 内存；不持久化 `codeCiphertext` 或 `codeHash`；消费/过期/进程死 zeroize。Provider 按 linkId 找 Share 的 active code，不需要 hash 查找。进程死则 code 丢失，用户 New code。
- App 复制完整命令：`"$HOME/bin/wonder-vfs" pair --link <linkId> --code <code>`。16 字符是 secret；linkId 是公开路由；不要把 ~50 字符 URI 当默认。
- 无 QR；Pairing Code 不进通知/状态栏；`FLAG_SECURE`；clipboard 自动清。
- 生命周期只有两个动作：**New code**（同 Share Key，给重装/丢失的 CLI 重新配对；不 eject 已配对 Agent；purge 旧 pair response cache）和 **Revoke**（先 best-effort 向 Relay `unregister`，再删除 Share/linkId/secret；Relay 可达时新请求立即 410，离线/重启窗口按 404/503 + tombstone，cache purge）。没有 rotate；要换 key 就 Revoke 后新建 Share。
- Cap 8：创建第 9 个 Share 时显示「已达到上限，先撤销一个」，不静默驱逐。
- Share 默认不过期；`lastUsedAt` 记录；`expiresAt` 预留给以后可选 TTL。
- **Pending revocation**：Revoke 时如果 Relay 不可达，把 `{linkId,reason,pendingUntil}` 存 `noBackupFilesDir/vfs`；下次 Connect/reconnect 重试 `unregister`；成功后删除。Relay 的 `unregister` 必须 purge pair/response cache。

### 3.4 Pairing 协议与 Wire/AAD

Wire（pair 和 data 共用）：

```json
{"v":1,"op":"pair","linkId":"...","reqId":"...","clientNonce":"...","ts":0,"iv":"...","ct":"..."}
```

- `linkId`：16B base64url；`reqId`：16B base64url；`clientNonce`：32B base64url；`iv`：12B base64url；`ct`：base64url(`ciphertext || 16B GCM tag`)；`v`：JSON int 1；`ts`：JSON int，Unix 毫秒。
- **code 编码**：16 字符规范化（大写、去空格/连字符）后的 ASCII 字节（UTF-8）作为 HKDF 输入；不做 base32 decode。Crockford 只用于人工输入。
- **字段编码/AAD 表**：

| 字段 | Wire | AAD/HKDF 编码 |
|---|---|---|
| `v` | JSON int 1 | 1 byte `0x01` |
| `op` | `"pair"`/`"vfs"` | UTF-8 长度前缀 |
| `linkId` | base64url 16B | raw 16B 长度前缀；HKDF salt |
| `reqId` | base64url 16B | raw 16B 长度前缀 |
| `clientNonce` | base64url 32B | raw 32B 长度前缀；HKDF salt |
| `ts` | JSON int ms | uint64 big-endian 8B 长度前缀 |
| `iv` | base64url 12B | raw 12B 长度前缀 |
| `direction` | 不在 wire | UTF-8 长度前缀 |

- 所有 AAD 字段按上表顺序、每个字段 4-byte big-endian 长度前缀 + bytes 拼接。
- **Request AAD**：`v,op,linkId,reqId,clientNonce,ts,iv,direction`（`direction="request"`/`"pair-req"`）。
- **Response AAD**：`v,op,linkId,reqId,clientNonce,requestTs,requestIv,ts,iv,direction`；`requestTs`/`requestIv` 来自 request（双方已知，不在 response wire 重复）；`ts`/`iv` 来自 response；`direction="response"`/`"pair-resp"`。Response wire 含 `clientNonce,ts,iv`，Consumer 用自己保存的 `requestTs/requestIv` 验证。
- **Data response envelope**：`{v,op:"vfs",linkId,reqId,clientNonce,ts,iv,ct}`；与 request 同字段；`ct` 用 `Kresp`。
- **Pair**：`Kpair = HKDF-SHA256(codeBytes, salt=linkId_raw, info="wsvfs/pair/req")`；`KpairResp = HKDF-SHA256(codeBytes, salt=clientNonce_raw, info="wsvfs/pair/resp")`；request `ct = AES-256-GCM(Kpair, {clientNonce}, aad_req)`；response 明文 `{shareId,shareSecret,scopes,label}`，`ct = AES-256-GCM(KpairResp, ..., aad_resp)`。
- **IV**：data request `iv=0`（12 零字节，per-request key 唯一）；data response `iv` 随机 12B；pair request/response `iv` 随机 12B。Pair request retry 必须复用同一 `(reqId, clientNonce, iv, ts)`；Provider 返回同一 response cache；data retry 也必须复用同一 tuple，否则是新请求。
- **Nonce 唯一性（硬规则）**：data 的 `clientNonce` 每个 Share 唯一；Provider 维护 per-Share seen-`clientNonce` 缓存（10 分钟，bounded 10k）；重复返回 `409 NONCE_REUSED`。`|now-ts| > 5 分钟` 硬拒绝 `409 STALE_TS`；replay cache TTL 10 分钟 ≥ skew。新请求用新 `clientNonce + reqId`；读是幂等的，进程死/503 后重试用新 tuple。
- GCM tag 16B；data `iv=0` 只在 unique `Kreq` 下安全；不得在同一 `Kreq` 下用不同明文。
- 无效 AEAD tag：按 Link 限速（5 pair/min），不消费 code；有效兑换才消费。code 过期/用完即废；New code 换新。
- Provider 顺序：persist Share Key → verify AEAD → consume code → build encrypted response → cache `(linkId,reqId,clientNonce)` **5 分钟**（不超过 code expiry）→ send。响应丢失时 CLI 用同一 tuple 重试，返回缓存；缓存过期/丢失则 New code。Revoke/New code 清 pair/response cache。
- 客户端：`pair` 命令持久化 Share Key 后才报告成功；按 Share 存 key；重启不需要 code；pair 前拒绝任何 read。
- **P0 必须提交 exact hex test vectors**（HKDF/AES-GCM/AAD/code/linkId/nonce/ts），作为 Kotlin/Node 的 source of truth；没有 vectors 不冻结 wire contract。

### 3.5 数据加密

- Share Key 32B；`K = HKDF-SHA256(secret, salt=linkId_raw, info="wsvfs/v1")`。
- 每请求 `clientNonce` 32B raw；`Kreq = HKDF(K, salt=clientNonce_raw, info="request")`，`Kresp = HKDF(K, salt=clientNonce_raw, info="response")`。
- Data 请求/响应：AES-256-GCM，request `iv=0`，response `iv` 随机；AAD 按 §3.4；response AAD 绑定 request `clientNonce/reqId/requestTs/requestIv`。
- Provider replay cache：per Share，bounded 10k/10 分钟，处理前插入 `reqId`；重复 reqId 返回缓存结果（若已完成）或 `409 IN_FLIGHT`（若处理中）；失败读可同 tuple 重试。
- **Nonce enforcement**：per-Share seen-`clientNonce` 10 分钟；重复 `409 NONCE_REUSED`；`ts` 硬 ±5 分钟；replay cache 10 分钟。这样 `iv=0` 不会发生同 key/IV 的第二次加密。
- Consumer pending `reqId`；`SecureRandom`，不用 `kotlin.random.Random`。
- AES-256-GCM only（ChaCha 需 API 28+）；**默认 minSdk 24**；`otherLegacy` minSdk 21 隐藏 Share 功能（代码放 `src/minApi24` 或 guard）。HKDF 用 HMAC-SHA256（RFC 5869）或 Tink；Kotlin/Node 交叉 vectors。
- 无 PFS：静态 secret + 历史密文被录，key 泄露可解历史；短 TTL/Revoke 降低暴露；以后可加 X25519 ephemeral，不宣称 PFS。
- VFS 错误/状态也在密文里；Portal 看不到路径或错误详情。

### 3.6 Relay

- **Provider key**：`POST /v1/provider-keys`（knock auth，body `{deviceId,name}`，issue-once per `(hookId,deviceId)`，返回 `{relayUrl,providerKey}`）；`rotate`/`GET`/`DELETE` 见 §3.2；**Provider key 加密可逆存；Consumer key hash/constant-time**。一个 device 一个 active key；`hookId` 绑定；VFS-enabled knock 检查。
- **Provider WS**：`GET /v1/provider` + `Authorization: Bearer <Provider key>`；帧 `register {linkId,instanceId,generation}`、`unregister {linkId,reason}`、`request/response` envelope、app-level ping/pong；一条 WS 多 Link；重连重注册。
- **Registration takeover**：`generation` 单调；新的 authenticated registration 替换旧连接，旧 generation 关闭/忽略；同 instanceId 重注册；不同 instance 的重复 linkId 拒绝或按 generation 替换。
- **Consumer**：`POST /v1/request` → 202 `{reqId}`；`GET /v1/response/{reqId}` long-poll ≤30s；response cache 60s（data）/≤code expiry 5min（pair）；retry 503/429；401 auth；413 too large；404 unknown；410 revoked/expired；409 IN_FLIGHT/NONCE_REUSED/STALE_TS；503 offline + Retry-After；429 rate。
- **Tombstone**：link record `{linkId,connection,instanceId,generation,registeredAt,revokedAt?,expiresAt?}`；App Revoke 先 best-effort `unregister {reason:"revoked"}`；Relay 可达时保留 7 天 tombstone，期间请求 410；不可达/Relay 重启后未知，返回 404；Provider 离线 503。客户端把 404/503 都保留 key，不删；只有明确 410 revoked/expired 才提示重新配对。App 持久化 pending-unregister 重试。
- **response cache key**：`(linkId, consumerKeyId|connectionId, reqId)`；reqId 高熵；New code/Revoke/unregister purge pair/response cache。
- **本机**：只用 Unix socket `~/.clawdy-portal/run/vfs-relay.sock`，目录 0700、socket 0600；免 transport key；只开 Consumer request/pair；删除 loopback TCP 选项。Portal 在 Tailscale Funnel 后面，Funnel peer 是 `127.0.0.1`，loopback TCP 分不清本机 Agent 和公网。Portal 启动/重启先 unlink stale socket，测试 EADDRINUSE。
- **公网/tailnet listener**：Provider WS + remote Consumer；永远要 key；Consumer key hash/constant-time；按 key/linkId 限速；不信 `X-Forwarded-For`；Funnel peer 不触发本机 bypass。
- **limits**：8 MiB frame/body；1 MiB read（base64 后约 1.33 MiB）；list 10k entries、path 4 KiB、cursor；per-link concurrency 8；120 req/min、64 MiB/min per Link；5 pair attempts/min per Link；429 + Retry-After。
- audit 只记 event/linkId prefix/key prefix/bytes/duration/result；不记 ciphertext、路径、scope、label。
- **部署**：同一个 Portal 进程 + Unix socket；Provider WS/remote Consumer 挂现有 Portal origin/tailnet；不新开 Funnel 端口；launchd 生命周期随 Portal；provider key 文件路径见 §3.2。
- 不需要 vfsd；旧 `/vfs/provider` 和 Portal VFS 数据面在 P5 禁用。

### 3.7 Scope

| Scope | 物理来源 | VFS 路径 | 生成方式 | 警告 |
|---|---|---|---|---|
| `app.data.activity` | `filesDir/activity/events.jsonl` | `appdata/activity/events.jsonl` | 现有文件虚拟映射 | amber |
| `app.data.groups` | 内存 group state（AO 导出同一数据源） | `appdata/groups/current.json` | on-read snapshot | amber |
| `app.data.logs` | `filesDir/logs/app.log` + AppLog | `appdata/logs/app.log` | **新 source 生成的 redacted view**，不是 raw | amber |
| `app.data.telemetry` | `filesDir/telemetry/` | `appdata/telemetry/...` | curated view | amber |
| `app.data.diagnostics` | diagnostic counts/sizes/version/profile rev | `appdata/diagnostics/...` | on-read **metadata only**，不读 crash report/config | amber |
| `app.files` | `filesDir/**` except `vfs/` | `appfiles/**` | raw | red |
| `app.workspace` | external files dir；denylist 排除 `conductor/`、`.runtime/` | `**` | raw | **red** |
| `conductor.files` | external `conductor/**` + artifacts，**含 `resources/inbox`** | `conductor/**` | raw | red |
| `conductor.trace` | `.runtime/trace.md` | `.runtime/trace.md` | runtime file | amber |
| `conductor.system_prompt` | `.runtime/system_prompt.md` | `.runtime/system_prompt.md` | runtime file | amber |
| `conductor.conversation` | `.runtime/conversation.md` | `.runtime/conversation.md` | runtime file | red |
| `user.selected` | SAF content URIs | `selected/**` | virtual, pfd | picker |
| `all` | 所有固定 scope 的并集 | union | — | red union |

- **`all` = 所有固定 scope**：`app.data.*`、`app.files`、`app.workspace`、`conductor.files`、`conductor.trace`、`conductor.system_prompt`、`conductor.conversation`；**排除 `user.selected`，硬排除 `vfs/`**。`Everything = all`；旧 `all` 的 trace/system_prompt/conversation 能力保留。
- **`app.workspace` 是 denylist**：external files dir 减去 `conductor/`、`.runtime/`；已知内容有 crash reports（可能含 configuration.json 副本）、core cache/state、Taildrop；标红。当前 `workdir` 包含 `conductor/`，新行为排除，需测试。
- **`app.files` 只暴露 `filesDir`**，不暴露 `noBackupFilesDir`；`vfs/` 在每个 base 都排除，canonical path 检查。红字警告按真实路径列出：`configuration.json`、`configs/*.json`（profile credentials）、`conductor/mcp-servers.json`（API 24+ 加密的 MCP tokens）、`conductor/api-trace.json`、`conductor/images/`、`datastore/`、`logs/`、`telemetry/`、`activity/`。Tailscale state 不在 `filesDir`；若某 scope 实际包含它，警告由真实文件清单生成，不硬编码。
- **`conductor.files` 标红**：包含 external conductor/**、artifacts、inbox、图片、trace-turns、Conductor 配置。
- **`conductor.conversation` 标红**：完整聊天记录。
- **`app.workspace` 红字**：「Includes external working files such as crash reports, core cache/state, Taildrop; crash reports may contain configuration copies. Excludes conductor/ and .runtime/.」
- **`app.files` 红字**：「Includes configuration.json, configs/*.json (profile credentials), conductor/mcp-servers.json (encrypted MCP tokens), conductor/api-trace.json, conductor/images/, datastore/, logs, telemetry, activity. The Agent and its model provider can read them.」
- **`app.data.*` 警告**：「Generated views; activity/telemetry/logs may contain sensitive operational data; logs are redacted.」
- **`conductor.files` 警告**：「Includes Conductor files/artifacts/inbox/trace data; may contain sensitive session material.」
- **`conductor.conversation` 警告**：「Includes the full chat transcript.」
- **`all` 警告**：并集，列出上述 + `app.data.*`，并写明 `vfs/` 永不包含。
- **`user.selected`**：SAF `ACTION_OPEN_DOCUMENT` 多选，persistable read；存 URI+metadata，不复制文件；虚拟名 `<id>-<sanitized-name>`，冲突用 id 去重；v1 只支持文件，不支持目录（目录 → `NOT_SUPPORTED`）；删除 → `PATH_NOT_FOUND`；权限丢失 → `PERMISSION_LOST`；`pfd` + `Os.pread`，不支持 seek 的 provider 退化 stream-skip；read-only；不在 `all`。
- **预设精确列表**：
  - **Recommended**：`app.data.activity`、`app.data.groups`、`app.data.logs`、`app.data.telemetry`、`app.data.diagnostics`；不含 `app.workspace`、`app.files`、`conductor.*`、`user.selected`。
  - **Everything**：`all`（所有固定 scope，不含 `user.selected`）。
  - **Customize**：任意固定 scope + 可选 `user.selected`。
- **Canonical 检查**：`app.workspace` 拒绝 canonical target 落在 `base/conductor` 或 `base/.runtime`；`app.files` 拒绝 canonical target 落在 `base/vfs`；symlink 测试。
- **警告字符串是静态的，测试保证它和当前已知路径一致**；新增敏感文件必须更新 strings + test。
- Provider `VfsScopedSource` 按 Share 执行；Relay 不解释 scope；Share 创建 UI 显示 exact roots。

### 3.8 App UI、后台、状态、feature flag、egress

- Settings → Share files：Portal connection（默认现有连接；Advanced 手动 URL/key）、Connect/Disconnect、状态。
- Shares 列表：label、scope 摘要、created、pairedAt、lastUsed、状态（waiting/paired/offline）、New code、Revoke。
- New Share：名字、Recommended/Everything/Customize、per-scope 警告、Create。
- Pairing Code 页：5:00 倒计时、Copy pairing command、New code；无 QR；不进通知/状态栏；`FLAG_SECURE`；clipboard 自动清。
- **多 Share 状态**：新 `VfsRelayState`：`Connected(shareCount, shares[{label,scopes,status,lastUsedAt}], totalBytesRead)`、`Idle`、`Backoff`、`Error`；通知「Sharing N Shares with M Agents」+ Stop；旧单 server/单 scope 的 status bar/notification/strings 替换。`totalBytesRead` 是实际读取字节，不是上传。
- **Stop sharing（all Agents）= 暂停**：关闭 Provider WS；Share/Key 保留；Connect 恢复，不重配对。**Revoke this Share = 永久**：best-effort unregister，删除 Share/linkId/secret，Relay tombstone。**New code 不 eject 已配对 Agent**；只有 Revoke 断开。
- 后台复用 `VfsSharingService`/`VfsSharingPolicy`：FGS 活着时后台可读；进程死/Doze 后 503；不引入 always-on；Share 记录持久化，重开 App 显示 one-tap Reconnect（不静默自动连）。
- **egress**：默认 `relayUrl` = 公网 Portal Funnel origin；Relay socket 用 `VpnService.protect()`/SocketFactory，并使用 protected/custom DNS 或测试 Funnel hostname 在 VPN on 下解析，避免 DNS 走隧道；tailnet/MagicDNS URL **不 protect**（protect 会绕过 Tailscale 路由）。测试 VPN on/off、per-app include、root/proxy、rule switch、network switch。
- **feature flag `ShareKeyVfs`**：P1/P2 新路径在 flag 后；旧路径/工具保留到 P4 验收；P5 移除旧路径和 AO 导出。flag off 可回旧路径。

### 3.9 CLI

- 安装：Portal `pnpm service-install` 装 `$HOME/bin/wonder-vfs`；Claude Code skill/CLAUDE.md 告诉 Agent 命令；App 复制 `"$HOME/bin/wonder-vfs" pair ...`（绝对路径，避免 PATH 问题）。
- `--help` 一页：App 路径、pair 流程、每命令一个示例、JSON 默认、大文件、Portal 盲区、not-paired 错误。
- 命令：
  - `"$HOME/bin/wonder-vfs" pair --link <linkId> --code <code>`
  - `... list [path] --json [--cursor] [--limit]`
  - `... stat <path> --json`
  - `... read <path> [--max-bytes 65536] --json`（preview）
  - `... get <path> --out <file>`（全文件，带进度）
  - `--share <label|id>` 多 Share 时选择；只有一个时默认；重复 label 报 `SHARE_AMBIGUOUS`。
  - `--socket <path>`；默认 `~/.clawdy-portal/run/vfs-relay.sock`。`--relay/--api-key` 远端后置/Advanced。
- 默认 `--json`；`--pretty` 给人看。JSON envelope：`{"ok":true,"data":...}` / `{"ok":false,"error":{"code","message","retryable","hint"}}`。
- Exit codes：0 成功；2 usage；3 transient（retry）；4 permanent（not paired/revoked/invalid）；5 not found。
- Error codes：`NOT_PAIRED`、`CODE_EXPIRED`、`CODE_INVALID`、`CODE_CONSUMED`、`PROVIDER_OFFLINE`、`SHARE_UNKNOWN`、`SHARE_REVOKED`、`RELAY_UNREACHABLE`、`RATE_LIMITED`、`TOO_LARGE`、`PATH_NOT_FOUND`、`PERMISSION_LOST`、`SHARE_AMBIGUOUS`、`PROVIDER_KEY_REVOKED`、`PROTOCOL_ERROR`、`IN_FLIGHT`、`NONCE_REUSED`、`STALE_TS`、`AUTH`。
- **`CLI_NOT_FOUND` 不由 CLI 发出**：由 Claude Code skill/CLAUDE.md 在 `"$HOME/bin/wonder-vfs"` 不存在时提示安装；CLI 自身不列该 code。
- Key store：`~/Library/Application Support/wonder-vfs/keys.json`（macOS）目录 0700、文件 0600、atomic write + fsync；每 Share `{shareId,label,linkId,secret}`；不打印、不进 argv/env/日志/错误；Pairing Code 在错误中脱敏（code 在 pair argv 里是设计允许的）。
- list 分页：VFS `list` request 增加 `{cursor?,limit?}`，response 增加 `{entries,nextCursor?}`；默认 1k，最大 10k；Provider source 分页。
- read 默认 64 KiB preview；大文件用 `get --out`，提示可能数分钟；list cursor；模型不一次吃 1.33 MiB base64。
- 未配对错误教用户去 App 的路径；本地 Agent 默认 Unix socket；远端后置。

### 3.10 错误目录

| 情况 | code | exit | 动作 |
|---|---|---|---|
| CLI 未安装/PATH | 由 skill/CLAUDE.md 提示 | 2 | 用 `"$HOME/bin/wonder-vfs"` 或跑 `pnpm service-install`；CLI 不发这个 code。 |
| 未配对 | `NOT_PAIRED` | 4 | WonderSing → Settings → Share files → New Share，复制 pairing command。 |
| code 过期 | `CODE_EXPIRED` | 4 | New code（5 分钟有效）。 |
| code 错误 | `CODE_INVALID` | 4 | 检查 16 字符/分组；仍失败 New code。 |
| code 已用/响应丢失超过缓存 | `CODE_CONSUMED` | 4 | New code；不要复用旧 code。 |
| Provider 离线 | `PROVIDER_OFFLINE` | 3 | 打开 WonderSing，确认 Share files Connected；重试。 |
| Share revoked | `SHARE_REVOKED` | 4 | 新建 Share；不要复用旧 key。 |
| Relay 未知/重启 | `SHARE_UNKNOWN` | 3 | 保留 key；重试；不要删 key；持续 404 请用户新建 Share。 |
| Provider key revoked | `PROVIDER_KEY_REVOKED` | 4 | 请用户打开 WonderSing/Portal 重新 Connect 或 rotate；CLI 不能自己 rotate。 |
| Portal 不可达 | `RELAY_UNREACHABLE` | 3 | 检查 Portal；本机默认 socket；远端 `--relay`。 |
| 限速 | `RATE_LIMITED` | 3 | 等 `Retry-After`；不要 New code（New code 不清 rate limit）。 |
| 大文件 | `TOO_LARGE` | 4 | `get --out`；可能数分钟。 |
| 认证失败 | `AUTH` | 4 | 检查 Consumer key/Provider key；重新 Connect。 |
| 请求过大 | `TOO_LARGE` | 4 | 减小 range/limit。 |
| 多 Share 未选 | `SHARE_AMBIGUOUS` | 2 | `--share <label|id>`。 |
| SAF 权限丢失 | `PERMISSION_LOST` | 4 | App 重新选择文件。 |
| SAF 文件删除 | `PATH_NOT_FOUND` | 5 | 检查 path；重新选择文件。 |
| nonce 复用 | `NONCE_REUSED` | 3 | 新请求用新 `clientNonce+reqId`；重试必须复用原 tuple。 |
| ts 过期 | `STALE_TS` | 3 | 校准时钟/重试；窗口 ±5 分钟。 |
| 处理中 | `IN_FLIGHT` | 3 | 等 `Retry-After` 或重试。 |

### 3.11 重启、新手机、多 Agent

- Relay 重启：App 重连并重注册 Share；CLI key 不变；pending 请求重试；不重配对；404/503 不删 key。
- App 重启/进程死亡：Share 记录持久化；重开 App 显示 one-tap Reconnect；不静默自动连；CLI key 不变；不重配对。Pairing Code 是 memory-only，进程死则 code 丢失，需要 New code。
- 新手机/重装：Share 不迁移；**reinstall 清 `noBackupFilesDir/vfs`，旧 Share 不能在新装里使用/撤销**；在旧装 Revoke，或撤销 Provider key 后新建 Share、重新配 CLI；旧 Share 在旧装 Revoke 前仍可能对旧 App 有效，不能声称自动失效。
- 多 Agent：一 Agent 一 Share；CLI 按 Share 存多把 key，`--share` 选择；上限 8；超出提示；重复 label 报错。
- Stop sharing（全局）= 暂停；Revoke（单 Share）= 永久；New code 同 key，不 eject。

### 3.12 旧路径退役、文档迁移、feature flag

- App 移除（P5，flag 后）：`VfsConnectTool`、`VfsConsent`/`VfsTrust`/`VfsCooldown(Store)`/`VfsResumeStore`/`VfsServerBase`/`VfsLink`/`VfsLinkParser`/旧 `VfsScopeAliases`/`VfsAccessScreen`/旧 `VfsProviderProtocol` server-hello/`OkHttpVfsProviderConnector` 旧 Portal 逻辑/`VfsRuntime` MCP 解析/`resolveServerForVfs`/`/vfs/provider`；Conductor prompt/tool catalog/sanitizer/MCP device header；旧 status bar/notification/strings/tests。
- Portal 移除（P5）：`vfs_link_request`/`vfs_connect`/`vfs_export`/`job_run(vfs=true)` VFS 分支；`lib/vfs.mjs`、`lib/vfsd-transport.mjs`、`/vfs/provider`；vfsd 数据面；容器 `/vfs` 挂载；旧 tests/scripts（`tests/vfs*.mjs`、`tests/jobs.test.mjs` **只退役 VFS 用例，保留 job 其他测试**、`scripts/e2e-vfs*.mjs`、`scripts/fake-app-provider.mjs`）。
- 文档：Portal `VFS.md` 改成新的 Relay/pairing/CLI 契约；`agent-oriented-vfs.md`、`docs/jobs.md`、`docs/mcp-grants.md`、`AUTH.md`（`/vfs/provider` 例外）、`HOOKS.md`、`AGENTS.md` 的 VFS 工具清单全部更新/标退役；WonderSing `vfs-dry-run.md`、`vfs-connection-stability.md`、`mcp.md`、`agent-control-plane.md` 改成新路径或标退役；App strings/translations 全部更新；`TODO.md` VFS Broker → Share Key VFS。
- AO 的 Activity/Groups 分散导出：P5（Share Key VFS 真机验收后）移除；P4 AP 保留 old path/AO 导出，避免同一版把唯一出口换掉。
- `ShareKeyVfs` feature flag：P1/P2 新路径 behind flag；旧路径保留；P4 验收；P5 移除旧路径/flag/AO 导出。

## 4. 实现方案

### 4.1 阶段

- **P0**：评审并冻结本文档 + exact test vectors；不改代码。
- **P1 App provider core**：Portal auto-provision/Relay config；Share store；pairing；AEAD/HKDF；scope source/denylist；FGS 复用；Settings/Share UI；新路径 behind `ShareKeyVfs`；旧路径保留；JVM 测试。
- **P2 Portal/Relay**：Provider key issue/rotate/revoke/list；Link 路由/注册 generation/tombstone；pairing；Unix socket；公网 auth；202+poll；limits/audit；新路径 behind flag；旧路径保留；契约测试。
- **P3 CLI**：`wonder-vfs` 安装；pair/list/stat/read/get；key store；JSON/exit/errors；Claude Code skill；测试。
- **P4 E2E**：真机 + Mini + Agent；flag on；Relay 只见密文；revoke/offline/restart；release AP（old path/AO 导出仍保留）；验收。
- **P5 清理**：移除旧 App/Portal 路径、vfsd、容器 `/vfs`、旧 strings/tests/docs；移除 AO 导出；移除 flag；另发版或紧随 P4 的清理版。
- P1/P2/P3 在 contract v5 冻结且 vectors 提交后可并行；P4 依赖 P1–P3。

### 4.2 数据模型

- App Share：`shareId,label,linkId,secretCiphertext,scopes,instanceId,createdAt,pairedAt,lastUsedAt,expiresAt(null),revokedAt`；`noBackupFilesDir/vfs/` 加密；不进 telemetry/export。
- Pairing code：**memory-only** `{shareId,code,createdAt,expiresAt,consumedAt}`；不持久化 code/hash；消费/过期/进程死 zeroize。Provider 按 linkId/Share 找 active code。
- Pending revocation：`{linkId,reason,pendingUntil}`；`noBackupFilesDir/vfs` 持久化；Connect 重试 `unregister`。
- Provider key：`hookId,deviceId,providerKeyCiphertext,relayUrl,createdAt,rotatedAt,revokedAt`；Portal 加密存可逆 key；App 加密存；一 `(hookId,deviceId)` 一 active；orphan 可 list/revoke。
- Relay link：`linkId,connection,instanceId,generation,registeredAt,revokedAt?,expiresAt?`；内存 + bounded tombstone（7 天）。
- Response cache：`(linkId,consumerKeyId|connectionId,reqId) -> encrypted response,expiresAt`；data 60s、pair ≤code expiry。
- Replay cache：per Share，bounded 10k/10min。
- CLI key：`shareId,label,linkId,secret`；`~/Library/Application Support/wonder-vfs/keys.json` 目录 0700/文件 0600；atomic+fsync。

### 4.3 API 契约

- **Provider key**：`POST /v1/provider-keys`（knock auth + VFS-enabled knob，body `{deviceId,name}`，issue-once per `(hookId,deviceId)`，返回 `{relayUrl,providerKey}`）；`POST /v1/provider-keys/rotate`（body `{deviceId}`，返回新 key；丢失可再调 issue-once 取当前 key）；`GET /v1/provider-keys`；`DELETE /v1/provider-keys/{deviceId}`；Portal master key 加密存。
- **Provider WS**：`GET /v1/provider`，`Authorization: Bearer <Provider key>`；`register {linkId,instanceId,generation}`、`unregister {linkId,reason}`、`request/response` envelope、ping/pong；多 Link；generation/takeover。
- **Consumer**：`POST /v1/request`（本机 Unix socket 无 auth；远端 `Authorization: Bearer <Consumer key>`）body `{v,op,linkId,reqId,clientNonce,ts,iv,ct}`；202 `{reqId}`；`GET /v1/response/{reqId}` long-poll ≤30s；response body `{v,op,linkId,reqId,clientNonce,ts,iv,ct}`。
- **List**：VFS `list` request 增加 `{cursor?,limit?}`，response `{entries,nextCursor?}`；默认 1k，最大 10k。
- **状态**：401 auth、404 unknown、410 revoked/expired、409 IN_FLIGHT/NONCE_REUSED/STALE_TS、413 too large、429 rate + Retry-After、503 offline + Retry-After；客户端 404/503 不删 key。
- **AAD/crypto**：按 §3.4/§3.5；GCM tag 16B；data request iv=0、response iv 随机；pair iv 随机；`clientNonce` 32B；code ASCII 规范化；P0 vectors。
- **Limits**：8 MiB frame/body；1 MiB read；10k list/4 KiB path；per-link concurrency 8；120 req/min、64 MiB/min、5 pair/min；tombstone 7d；replay/seen-nonce 10min。
- **审计**：event/linkId prefix/key prefix/bytes/duration/result；不记 secret/ciphertext/路径/scope/label。
- **Relay cache purge**：`unregister`/Revoke/New code purge pair/response cache；`unregister` 幂等。

### 4.4 App 改动

- 新增：`VfsRelayConfig`、`VfsProviderKeyProvisioner`（仅 bootstrap adapter 接触现有 Portal 连接；core 不 import MCP）、`VfsRelayClient`、`VfsShareStore`、`VfsPairing`、`VfsEnvelopeCrypto`、`VfsRelayManager`（一 WS 多 Link）、`AppDataVirtualDirectorySource`、`UserSelectedVirtualDirectorySource`、`VfsRelayState`。
- 改写：`VfsRuntime`（去 MCP，接 Relay manager）、`VfsConnectionManager`（或拆成 Relay manager）、`VfsProviderProtocol`（envelope request/response）、`VirtualDirectoryProtocol`/`Session`（list cursor）、`VfsScopedSource`（新 scope/denylist/canonical）、`WorkspaceVirtualDirectorySource`（排除 conductor/.runtime/canonical）、`InternalFilesVirtualDirectorySource`（filesDir only、vfs/ canonical）、`AppVirtualDirectorySource`（appdata/selected）、`VfsStatusBar`/`ServiceStatusBar`/`VfsScopeLabel`/`MainActivity`/`Navigation`/`VfsConnectionState`/`VfsSharingService`（多 Share 状态/通知/Stop）、`VfsPrivateFiles`/`VfsDeviceId`、strings/translations、Agent `AgentContext`/`AgentDataTools`/`AgentSanitizer`/`McpProtocol`/`ConductorViewModel`（去 VFS 提示/tool）。
- 删除（P5）：§3.12 列表；旧 trust/consent/resume/cooldown/link/parser/serverBase/`VfsAccessScreen`/`VfsConnectTool`。
- API 24：Share/crypto 代码放 `src/minApi24` 或 guard；`otherLegacy` 隐藏/不编译该功能并测试。默认 minSdk 24；`otherLegacy` 21。
- Egress：公网 Funnel URL + `VpnService.protect()` + protected/custom DNS；tailnet URL 不 protect；测试。
- Feature flag `ShareKeyVfs`；P1–P4 旧路径保留。
- Pending revocation store + retry.

### 4.5 Portal/Relay 改动

- 新增：`lib/vfs-relay.mjs`（或同进程模块）：provider key issue/rotate/revoke/list、Provider WS、link register/generation/tombstone、pairing、consumer 202+poll、response/replay cache、Unix socket、public auth、limits/audit、cache purge。
- 修改：`server.mjs` 挂路由；`AUTH.md`（`/v1/provider-keys`、`/v1/provider`、Unix socket、VFS-enabled knock）、`HOOKS.md`、`AGENTS.md`、`docs/jobs.md`、`docs/agent-oriented-vfs.md`、`docs/mcp-grants.md`、`VFS.md`；launchd 同进程；Unix socket 路径/权限/stale unlink；provider key 文件 `~/.clawdy-portal/provider-keys.json` 0600 + master key。
- 退役（P5）：`lib/vfs.mjs`、`lib/vfsd-transport.mjs`、`/vfs/provider`、`vfs_link_request`/`vfs_connect`/`vfs_export`/`job_run(vfs=true)` VFS 分支、vfsd 数据面、容器 `/vfs`、旧 tests/scripts（`tests/jobs.test.mjs` 只退役 VFS 用例）。
- Consumer key 远端后置；v1 只本机 Unix socket。

### 4.6 CLI 改动

- 新增 `$HOME/bin/wonder-vfs`（Node/Go；优先 Node 便于 `service-install`）；安装脚本；Claude Code skill/CLAUDE.md；key store；socket 默认；JSON/exit/errors；list cursor；read preview/get。
- 测试：pair、key 0700/0600/atomic/fsync、socket、PATH/absolute、JSON/exit/errors、multi-Share、code 脱敏、大文件、list cursor、no key in logs、IN_FLIGHT/NONCE_REUSED/STALE_TS/AUTH/413。
- 远端 `--relay/--api-key` 后置；v1 本机。

### 4.7 测试

- Pairing：5 分钟、单次、memory-only/process death、无效 tag 限速、并发兑换、响应丢失/同 tuple 重试、cache ≤ code expiry、New code purge cache、client restart、pair 前拒绝读、code/key 不进日志。
- Provider key：issue-once/重复/rotate/revoke/list、hookId/deviceId/第二 knock、orphan/reinstall、Advanced、未授权、VFS-enabled knock、加密存储、rotate 响应丢失。
- Crypto：RFC 5869 HKDF、NIST AES-GCM、Kotlin/Node 交叉、exact hex vectors、AAD tamper、nonce/iv 复用拒绝、reqId replay、response swap、clock skew、API 24 存储。
- Nonce/replay：same nonce/new reqId、same reqId/new nonce、replay after cache eviction、retry after process death、STALE_TS、NONCE_REUSED。
- Relay：malicious swap/replay/reorder/drop、401/404/410/409/429/503、frame/body/list caps、restart/reconnect、generation takeover、duplicate linkId、local socket vs Funnel、stale socket unlink、audit grep、tombstone、cache purge。
- Scopes：每个 scope × 路径矩阵；`appdata/groups`/diagnostics 生成；`app.workspace` denylist/canonical/symlink；`vfs/` 不可达；`.runtime` 不经 `app.workspace`；`all` 含 `.runtime`/inbox；`user.selected` 权限丢失/删除/目录；`app.files` 警告真实路径；`conductor.files` 红字；`app.workspace` 红字。
- App：Share create/copy/New code/Revoke/Stop；多 Share 状态/通知；background/Doze/process death；one-tap Reconnect；egress VPN/proxy/tailnet/rule switch/DNS；旧路径 flag off/on；key/code 不进 telemetry/export/screenshot/clipboard/notification。
- CLI：JSON/exit/errors、key store 0700/0600/atomic/fsync、Unix socket、`$HOME/bin`、list cursor、read preview/get、multi-Share `--share`、duplicate label、no code/key in logs、IN_FLIGHT/NONCE_REUSED/STALE_TS/AUTH/413。
- E2E：真机 + Mini + Agent；已知文件；Relay 只见密文/元数据；revoke/tamper/offline；overnight；large files；multi-device；容器 `/vfs` 退役记录。
- UX：新用户按 6 步完成；术语；`--help`；错误可行动；`all`/per-scope 警告清楚。

### 4.8 发布与回滚

- 顺序：Portal/Relay + CLI 就绪 → App AP（`ShareKeyVfs` flag on，旧路径/AO 导出保留）→ P4 E2E/真机验收 → P5 移除旧路径/flag/AO 导出 → 清理版 AP。
- 发布门：P4 E2E；Relay 日志无明文/key/code；scope matrix；旧路径 flag off 可回退；exact crypto vectors；`./scripts/local-test.sh`；`./scripts/e2e-emulator.sh`；CI Test/Release。
- 回滚：P4 前 flag off 回旧路径；P5 后回滚 App/Portal 版本；旧路径退役不可逆，发版前确认。No force push/tag move。
- 下一个 App label 若无其他改动是 AP；版本规则见 [`versioning.md`](versioning.md)。

### 4.9 风险

- Pairing Code 在聊天/模型上下文；一次性/5 分钟；Agent 宿主可读 CLI key store；不要过度宣称。
- `all` 敏感文件暴露；红字/amber 警告 + 用户确认；`vfs/` 硬排除；真实路径清单要准确。
- 可用性：FGS/Doze/进程死亡；不承诺 always-on；503 可行动。
- 无 PFS；未来 X25519。
- Relay 元数据；不记内容/路径/scope/label。
- CLI 安装/发现/PATH；`$HOME/bin` 绝对命令；skill/CLAUDE.md；否则流程不成立。
- Provider key auto-provision 依赖现有 Portal 连接；无则 Advanced；key 可 rotate/revoke；Portal 识别/VFS-enabled knock。
- 文档迁移是重写；旧文档/strings/tests/scripts/Agent prompt 必须一起改。
- API 21 `otherLegacy` 隐藏 Share；API 24 source set/guard。
- Cap 8；溢出提示；不静默驱逐。
- 大文件 1 MiB read 25–28 秒/MB；CLI preview + `get --out`；不要默认把大数据塞进模型。
- Egress：公网 Funnel + protect + protected DNS；tailnet URL 不 protect；测试两种路径。
- Memory-only pairing code：进程死即丢；用户 New code；不持久化 secret。
- Provider key master key 存储/轮换；orphan 清理。

## 5. 验收标准

| 验收 | 证据/测试 |
|---|---|
| Agent 用 CLI 读取选定 scope，无需手动导出/容器 | 真机 + Mini + Agent E2E；已知文件 sha256。 |
| Pairing Code 5 分钟、一次性；长期 Share Key 不打印、不进聊天；CLI 按 Share 保存；code memory-only | pairing 测试；transcript/log 扫描；CLI key store 权限；process death。 |
| Portal 只见密文 + 路由元数据；无 key/code/路径/内容 | malicious Relay 测试；audit grep；tcpdump/WSS 检查。 |
| Revoke 后 Relay 可达时 410；离线/重启按 tombstone/404/503；New code 可重配对；Provider 离线 503；Relay/App 重启不重配对；新手机新 Share；reinstall 文案正确 | revoke/tombstone/restart/new-phone/reinstall 测试。 |
| `all`/`app.files`/`app.workspace`/`conductor.files`/`conductor.conversation` 警告可见；`vfs/` 不可达；`.runtime` 不经 `app.workspace`；`user.selected` 不在 `all`；inbox 保留 | scope matrix；UI 截图/strings 测试；symlink 测试。 |
| 旧 `/vfs/provider`/vfsd/`job_run(vfs=true)`/`VfsConnectTool` 在 P5 禁用；P4 前 flag off 可回退 | flag off/on；旧路径回归；docs 更新。 |
| 多 Share 状态/通知正确；Stop=pause，Revoke=permanent；totalBytesRead 正确 | 多 Share UI 测试；通知/strings。 |
| CLI JSON/exit/errors/key store/socket/PATH/list cursor 正确；IN_FLIGHT/NONCE_REUSED/STALE_TS/AUTH/413 可行动 | CLI 契约测试；Agent 实际调用。 |
| 测试/证据完整：crypto vectors、nonce/replay、malicious Relay、scope matrix、background/Doze、egress/DNS、key/code leak scan | 测试报告/记录路径。 |

## 6. 评审记录与变更

- 安全/协议评审：sound with changes；2 个 blocking 已定：容器 `/vfs` 退役；一 Agent 一 Share（linkId/secret 在 Share 创建时生成，一条 WS 多 Link）。
- 简洁性/易懂性/易用性评审：概念 4/5、易懂 3/5、易用 3/5；0 blocking；5 个默认已记录：Provider auth 复用/自动发放；16 字符 Pairing Code + 完整命令；CLI 本机预装 + skill；New code/Revoke（砍 rotate）+ per-Share key + `all` 不含 `user.selected` + per-scope warning；手机侧 Conductor VFS 退役。
- v3 文档评审：3 个新 blocking（pairing code 存储、provider key 幂等、iv/AAD/编码）+ high；v4 修正。
- v4 文档评审：**4 个 blocking**（B1/B2/B3 仍开放 + 新 nonce/replay blocker）+ high；v5 按 §0 修正；待新一轮评审确认 0 blocking。
