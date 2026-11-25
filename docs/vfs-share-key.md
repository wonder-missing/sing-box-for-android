# Share Key VFS：概念、方案与实现

状态：设计草案 v6（2026-11-26），按 v5 文档评审的 6 个 blocker 与 high 修正；附 P0 精确测试向量；待独立评审；未实现。
相关旧路径：`~/workspace/clawdy-portal/VFS.md`、`~/workspace/clawdy-portal/docs/agent-oriented-vfs.md`、`~/workspace/clawdy-portal/docs/jobs.md`、[`vfs-dry-run.md`](vfs-dry-run.md)、[`vfs-connection-stability.md`](vfs-connection-stability.md)、[`mcp.md`](mcp.md)。本设计通过后按 §3.12 改写或标退役。

## 0. v6 修复摘要

| 评审项 | v6 处理 |
|---|---|
| B1 provider key 身份/device/orphan 矛盾 | 一个 hook 可拥有多台 device；唯一 `(hookId,deviceId)`；新 device 不再 403；同 hook 可 `GET` 列全部 device key、`DELETE` 旧 device、reinstall 后清理；`DELETE`/rotate 关闭该 key 的 WS 并 tombstone 其 Link；另一 hook 占用同一 deviceId → `409 DEVICE_OWNED`。 |
| B2 New code 无法清 Relay pair cache | 每个 Pairing Code 增加公开 `pairId`；pair envelope/AAD/cache key 都带 `pairId`；New code 生成新 `pairId+code`，旧 `pairId` 立即不可用、旧缓存 key 不可达，无需 Relay purge frame；Revoke 仍走 unregister/tombstone。 |
| B3 加密 payload/错误契约缺失 | 定义 inner request/response/error JSON；Relay 只返回 transport/routing 状态（401/404/410/409 IN_FLIGHT/413 envelope/429/503）；语义错误（nonce/ts/limit/path）全部在密文里由 App 返回；`VirtualDirectorySource.list` 增加 cursor/limit。 |
| B4 wire/AAD 不够精确 | base64url 无 padding；AAD 每字段 `u32be(len)||bytes`；逐 op 定义 pair/data 请求/响应的 AAD 顺序；pair plaintext canonical JSON 字段；`ct=ciphertext||16B tag`；附录给 exact hex vectors。 |
| B5 Portal 发现/VFS gate 未定义 | App 在 Share files 里显式选择 Portal MCP server（`portalServerId`），只向该 origin 发 token；Portal `hooks.json` 增加 durable `shareKeyVfs` capability，provider-key 与 Provider WS 都检查；hook disable/delete 撤销 key/links；P5 退役 `vfs_link_request` 但 capability 保留。 |
| B6 instanceId/generation 未定义 | `instanceId` = 每 install 持久化 UUID（noBackup）；`generation` = 每 Link 持久化 u64，每次 register 前递增；Relay 规则：同 instance 高 generation 替换、低/等 409 STALE_GENERATION、异 instance/hook 409 LINK_OWNED、tombstone 410。 |
| High | data request 改随机 12B IV（不再 iv=0）；scope warning 补 `settings.db`/`profiles.db`/`trace-turns`/`images` 并去掉 `datastore/`；hook 吊销撤销 key/links；linkId 绑定 hook；v1 公网只开 Provider WS、Consumer 只开 Unix socket；保留虚拟 root；log redaction 规则；CLI 输出 schema；feature flag 机制；protect+DNS；code 用 byte/char 数组零化；provider-key 生命周期细节。 |
| Medium | Stop 使 active code/pair cache 失效；unregister reason/tombstone 优先级；response poll 绑定 consumer connection；shareId/label/linkId 格式；groups core 停止时 `available:false`；diagnostics schema；user.selected 错误；list 接口；local socket DoS；stale socket lock；provider key master file；multi-Share schema；五概念 + “CLI 保存的 key”；术语表；本地化；文档迁移；验收映射。 |

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

### 2.1 角色与术语

| 术语 | 含义 | UI 用词 |
|---|---|---|
| App / Provider | 数据在手机上；主动连 Portal/Relay；验证 Share Key；按 Share 的 scope 读 VFS。 | App |
| Agent / Consumer | 远端 Claude Code；用 `wonder-vfs` CLI 读。 | Agent |
| Portal / Relay | 盲路由；鉴权 transport、按 Link ID 转发密文、限速/审计元数据。 | Portal |
| Share | 一次授权：label + scope 集合 + 一个 Link；一个 Agent 一个 Share。 | Share |
| Pairing Code | 5 分钟、一次性、16 字符 Crockford base32。 | Pairing Code |
| Share Key | 长期 AEAD 密钥；CLI 保存，用户不直接管理。 | “the key the Agent saves” |
| Scope | Share 允许读的路径组。 | What the Agent can read |
| New code | 同一 Share 的新 Pairing Code；不换 Share Key。 | New code |
| Revoke | 永久删除 Share/Link/Share Key。 | Revoke this Share |
| Stop sharing | 暂停所有 Agent 的 transport；Share/Key 保留。 | Stop sharing |

用户需要理解的 **五个主概念**：App、Agent、Portal、Share、Pairing Code。Share Key 由 CLI 保存，用户只需知道“Agent 会保存一把 key”，不需要手动管理。Provider/Consumer、Link ID、instanceId、generation、reqId、AEAD/HKDF、202+poll 不出现在主 UI。

### 2.2 信任边界

- **Transport auth**：App 默认复用现有 Portal 连接，自动换/发一个 Relay 专用 Provider key；本机 Agent 走 Unix socket，免 transport key；远端 Consumer v1 不开放。Transport key 只防滥用，不授予数据。
- **Data auth**：Share Key。App 和 CLI 各持有；Portal/Relay 永远看不到。
- **Authorization**：App 按 Share 的 scope 逐请求检查；Portal 不解释 scope。
- **Consent**：创建 Share、生成 Pairing Code 就是授权；每个敏感 scope 有自己的红字/黄字警告；`all` 的警告是并集。

必须对用户说清楚：

- **Portal 看得到**：哪个 Share、什么时候、多少字节、Provider/Consumer key 的**前缀/身份**（不是 Share Key）、Link ID、pairId、reqId、op、ts；看不到文件内容、路径、scope、label、inner error。
- **Agent 看得到**：你分享的文件；Agent 可能把它们发给它的模型提供方。
- **Pairing Code**：一次性、5 分钟、在聊天里可见；看到它的人在这 5 分钟内可能抢先配对；`pairId` 是公开路由，不是 secret。
- **Share Key**：由 Agent 的 CLI 保存；Portal 读不到；Agent 宿主机被信任（同一用户可读 CLI 的 key store）；不要宣称模型绝对拿不到 key。
- **Relay 不可读内容**：密文和 AEAD tag 保证 Portal 不能读/伪造/篡改；Portal 仍能看到路由元数据，可以丢包、延迟。
- **可用性**：读取只在 WonderSing 的 sharing 通知/FGS 活着时可用；App 停止/Doze 后 CLI 得到 503，重开 App 再试。不承诺 24/7。

### 2.3 用户模型（一页话术）

> WonderSing 连上你的 Portal 后，创建一个 Share：起个名字，选 Agent 可以读的文件。复制 App 给的一行配对命令（5 分钟有效）到 Agent 聊天。Agent 的 CLI 用它换一把自己保存的 key，然后 list/read 这些文件。Portal 只转发加密数据和路由元数据，看不到内容。撤销 Share 就断掉访问。

### 2.4 用户流程

一次性设置：

1. 安装 `wonder-vfs` CLI（Portal `pnpm service-install` 装到 `$HOME/bin`；Claude Code skill/CLAUDE.md 告诉 Agent 命令）。
2. Settings → Share files → 选择 Portal connection（现有 MCP server；Advanced 可手填 URL/key）→ Connect。

每个 Agent：

3. New Share → 名字（可选）→ Recommended / Everything / Customize → Create。
4. Copy pairing command（5:00 倒计时）；App 复制完整命令 `"$HOME/bin/wonder-vfs" pair --link <linkId> --pair <pairId> --code <code>`。
5. 粘到 Agent 聊天；Agent 运行命令，返回它能看到的 roots。
6. Agent 运行 `"$HOME/bin/wonder-vfs" list/read ...`；不再需要 code。撤销 Share → Revoke。

### 2.5 已接受的取舍

- Pairing Code 会进入聊天/模型上下文；一次性、5 分钟、兑换后失效；长期 Share Key 不打印、不进聊天。用户已拒绝 out-of-band CLI/QR。
- `all` 可以包含敏感 App 文件，但必须有红字警告；`vfs/` 永久排除；`app.files`、`app.workspace`、`conductor.files`、`conductor.conversation` 各自也有红字警告，不只 `all`。
- 可用性保持现状：FGS 活着时后台可读；进程死/Doze 后 503；不做 always-on。
- 无 PFS；静态 Share Key + 短 TTL/Revoke；以后可加 X25519 ephemeral。
- Relay 元数据（Link ID、pairId、reqId、时间、大小、key 前缀）可见；不记录内容/路径/scope/label/inner error。
- Share 功能要求 API 24+；API 21 `otherLegacy` 隐藏该功能。
- 容器 `/vfs` 退役；Conductor 的手机侧 VFS 工具移除；CLI 是替代。
- 保留 `user.selected` 和细粒度 `app.data.*`；实现可分阶段，设计保留。
- Share 默认不过期；`lastUsedAt` 记录；以后可选 TTL。Stop sharing 只是暂停 transport，Share/Key 保留；Revoke 才永久删除。New code 不 eject 已配对 Agent。
- Reinstall 会清 `noBackupFilesDir`：旧 Share 不能在新装里使用/撤销；用同一 knock `GET /v1/provider-keys` 列出旧 device key，`DELETE` 撤销；新建 Share/重新配 CLI。

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
- Relay 只存内存 `linkId -> connection`、`pairId` 路由、bounded tombstone；不存 secret/scope/label/path。
- v1 公网 listener **只开 Provider WS**；Consumer `/v1/request` 只在 Unix socket；远端 Consumer 后置。

### 3.2 App / Portal 配置与 Provider key

- **Portal 选择**：Share files 页列出已配置 MCP server，用户选一个作为 Portal（存 `VfsRelayConfig.portalServerId`）；`VfsProviderKeyProvisioner` 只对该 server 的 origin/token 调用 `POST /v1/provider-keys`，不向任意 MCP server 发 token。没有可选 server 时 Advanced 手填 Relay URL + Provider key。
- **Provider key 发放**：`POST /v1/provider-keys`（auth knock，body `{deviceId,name}`）**issue-once per `(hookId,deviceId)`**；同 key 重复调用返回同一个 key；新 device 创建新 key，不返回 403。Portal 用 master key 加密保存可逆 key（不是 hash-only）。
- **Hook 多设备**：一个 hook 可以拥有多台 device；`GET /v1/provider-keys` 列该 hook 的全部 device key（metadata only：deviceId/name/createdAt/lastUsedAt/revokedAt），`DELETE /v1/provider-keys/{deviceId}` 可撤销任意一台。`(hookId,deviceId)` 唯一；另一个 hook 声明同一 deviceId → `409 DEVICE_OWNED`。
- **Rotate**：`POST /v1/provider-keys/rotate`（auth knock，body `{deviceId}`）撤销旧 key、返回新 key；App 更新并关闭/重连该 key 的 Provider WS；响应丢失时重新调 issue-once 取当前 key（加密可逆存储），不会 strand。
- **Delete/rotate 影响**：关闭该 provider key 的 Provider WS，tombstone/revoke 其全部 Link；`unregister`/`reason` 记录；provider key 失效后 App 重新 Connect/rotate。
- **Hook 能力**：Portal `hooks.json` 增加 durable capability `shareKeyVfs`（不是 `tools` 里的 `vfs_link_request`）；`POST /v1/provider-keys`、`GET`/`rotate`/`DELETE`、Provider WS 都检查 hook `enabled && shareKeyVfs`。hook disable/delete/移除 capability 时立即 revoke 该 hook 的全部 provider key/link，Provider WS 关闭 4401；Provider WS 连接时和每 5 分钟重新验证 hook。
- **Portal 识别**：App 只把 token 发到用户选中的 Portal server；不依赖 `serverBase` 的自动推断。`McpServerConfig` 保持不变；`VfsRelayConfig.portalServerId` 是唯一绑定。
- **Master key**：`~/.clawdy-portal/vfs-provider-key.master` 0600（不是 `hooks.json`）；Provider key 加密存 `~/.clawdy-portal/provider-keys.json` 0600、atomic；master key 轮换需重新加密；读坏 fail closed，不返回 key；`service-install` 不得覆盖/删除。
- 更新 `AUTH.md`/`HOOKS.md`：`/v1/provider-keys` 的 auth、device 绑定、`shareKeyVfs` gate、限速、审计；不记录 secret。

### 3.3 Share 与 Pairing Code

- Share 创建时生成 128-bit `linkId` + 32B `secret` + 16B `shareId`；一个 Agent 一个 Share；多个 Agent 建多个 Share；上限 8，超出提示先 Revoke。
- Share 记录：`shareId,label,linkId,secretCiphertext,scopes,instanceId,generation,createdAt,pairedAt,lastUsedAt,expiresAt(null),revokedAt`。存 `noBackupFilesDir/vfs/`，不进 telemetry/export；`lastUsedAt` 最多每 5 分钟写一次。
- Label：可选，最长 64，显示用；重复 label 允许；`--share` 用 `shareId` 或唯一 label，重复 label 报 `SHARE_AMBIGUOUS`。
- **Pairing Code**：16 字符 Crockford base32（无 I/L/O/U），规范化大写/去空格连字符；5 分钟；单次；一个 Share 同时只有一个 active code。**不设 5-attempt 上限**；用 80-bit + 5 分钟 + per-Link rate limit；无效 AEAD tag 只限速，不烧 code。
- **PairingCode（memory-only）**：`{shareId,pairId(16B public),code(16 chars secret),createdAt,expiresAt,consumedAt}`；只在 App 内存；code 存 `CharArray`/`ByteArray`，绝不转 `String`；消费/过期/进程死 zeroize；不持久化 `codeCiphertext`/`codeHash`。Provider 按 linkId 找 Share 的 active pairId/code。
- **pairId**：16B 公开随机；pair envelope/AAD/cache key 都带；New code 生成新 `pairId+code`，旧 pairId 立即失效；App 向 Relay 发 Provider WS 控制帧 `pair {linkId,pairId}`（只含公开 pairId），Relay 更新该 Link 的 `activePairId` 并 purge 其他 pairId 的 pair response cache；旧 pairId 请求 404 `PAIR_UNKNOWN`。无需 secret/purge 帧，也不会 eject 已配对 Agent。
- App 复制完整命令：`"$HOME/bin/wonder-vfs" pair --link <linkId> --pair <pairId> --code <code>`。16 字符是 secret；linkId/pairId 是公开路由。
- 无 QR；Pairing Code 不进通知/状态栏；`FLAG_SECURE`；clipboard 自动清；shell history 由 skill/CLAUDE.md 提示不要保留。
- 生命周期：**New code**（同 Share Key；新 pairId/code；旧 code/pairId 失效；不 eject 已配对 Agent；旧 pair cache 不可达）和 **Revoke**（先 best-effort `unregister {reason:"revoked"}`，再删除 Share/linkId/secret；Relay tombstone；cache purge）。没有 rotate；换 key 就 Revoke + 新 Share。
- Cap 8：第 9 个提示先撤销。
- Share 默认不过期；`expiresAt` 预留。
- **Pending revocation**：Revoke 时 Relay 不可达，把 `{linkId,reason,pendingUntil}` 存 `noBackupFilesDir/vfs`；下次 Connect 重试 `unregister`；成功后删除。`unregister` purge pair/response cache。
- **Stop sharing**：关闭 Provider WS；丢弃 active PairingCode（新 code 才能再配对）；Relay pair/response cache 自然过期；Share/Share Key 保留；Connect 恢复，不重配对。不等同 Revoke。

### 3.4 Wire、AAD 与 Inner Payload

#### 3.4.1 Wire

Data request/response：

```json
{"v":1,"op":"vfs","linkId":"...","reqId":"...","clientNonce":"...","ts":0,"iv":"...","ct":"..."}
```

Pair request/response：

```json
{"v":1,"op":"pair","linkId":"...","pairId":"...","reqId":"...","clientNonce":"...","ts":0,"iv":"...","ct":"..."}
```

- `linkId`/`pairId`/`reqId`：16B base64url（无 padding）；`clientNonce`：32B base64url；`iv`：12B base64url；`ct`：base64url(`ciphertext || 16B GCM tag`)；`v`：JSON int 1；`ts`：JSON int Unix 毫秒。
- Pair request plaintext（UTF-8 JSON，字段顺序如下）：`{"pairId":"<b64url>","clientNonce":"<b64url>"}`。
- Pair response plaintext：`{"shareId":"<b64url>","shareSecret":"<b64url>","scopes":["..."],"label":"..."}`。
- Data inner request（UTF-8 JSON）：`{"v":1,"type":"stat"|"list"|"read","path":"...","offset":0,"length":0,"cursor":"...","limit":0}`；无内层 id，外层 `reqId` 关联。
- Data inner response：`{"v":1,"type":"entry"|"entries"|"data"|"error","entry":{...},"entries":[...],"nextCursor":"...","data":"<base64>","eof":true,"error":{"code":"...","message":"..."}}`。
- Inner error codes：`not_found`、`invalid_path`、`bad_request`、`not_supported`、`provider_error`、`range_not_satisfiable`、`nonce_reused`、`stale_ts`、`in_flight`、`too_large`、`permission_lost`、`cursor_invalid`。全部加密；CLI 映射为稳定错误码。
- `data` 字段是 base64；文本 preview 由 CLI 解码；二进制用 `get --out`。

#### 3.4.2 AAD 编码

每个字段 = `u32be(len) || bytes`，按下面顺序拼接：

| op/direction | 字段顺序 |
|---|---|
| pair-req | `v, op, linkId, pairId, reqId, clientNonce, ts, iv, direction` |
| pair-resp | `v, op, linkId, pairId, reqId, clientNonce, requestTs, requestIv, ts, iv, direction` |
| request | `v, op, linkId, reqId, clientNonce, ts, iv, direction` |
| response | `v, op, linkId, reqId, clientNonce, requestTs, requestIv, ts, iv, direction` |

字段编码：

- `v`：`u32be(1) || 0x01`。
- `op`/`direction`：UTF-8 bytes。
- `linkId`/`pairId`/`reqId`：raw decoded 16B。
- `clientNonce`：raw decoded 32B。
- `ts`/`requestTs`：`u32be(8) || u64be(Unix 毫秒)`。
- `iv`/`requestIv`：raw decoded 12B。
- Response AAD 的 `requestTs`/`requestIv` 来自 request；`ts`/`iv` 来自 response；response wire 含 `clientNonce,ts,iv`，Consumer 用自己保存的 request 值验证。
- 所有 base64url **无 padding**；Kotlin/Node 都按 RFC 4648 §5 无 padding。

#### 3.4.3 Pairing

- `Kpair = HKDF-SHA256(codeASCII, salt=linkId_raw, info="wsvfs/pair/req")`。
- `KpairResp = HKDF-SHA256(codeASCII, salt=clientNonce_raw, info="wsvfs/pair/resp")`。
- Pair request `ct = AES-256-GCM(Kpair, pairPlaintext, aad_pair_req)`；`iv` 随机 12B。
- Pair response `ct = AES-256-GCM(KpairResp, pairRespPlaintext, aad_pair_resp)`；`iv` 随机 12B。
- Provider 顺序：persist Share Key → verify AEAD → consume code → build encrypted response → cache `(linkId,pairId,reqId,clientNonce)` **≤ code expiry 5 分钟** → send。
- 响应丢失时 CLI 用同一 `(pairId,reqId,clientNonce,iv,ts)` 重试，返回缓存；缓存过期/丢失则 New code。New code 换新 pairId，旧缓存不可达；Revoke 清 cache。
- 无效 AEAD tag：按 Link 限速（5 pair/min），不消费 code；有效兑换才消费。code 过期/用完即废；New code 换新。
- 客户端：`pair` 命令持久化 Share Key 后才报告成功；按 Share 存 key；重启不需要 code；pair 前拒绝任何 read。
- **P0 vectors**：附录 A 的 exact hex 是 source of truth；Kotlin/Node 必须逐字节复现，否则不冻结 wire contract。

#### 3.4.4 Relay 状态与语义错误

- Relay 只看 outer envelope；不能解析 inner path/read/list/error。Relay 状态仅：
  - `401 AUTH`、`404 UNKNOWN`、`410 REVOKED/EXPIRED`、`409 IN_FLIGHT`（outer reqId 重复处理中）、`413 ENVELOPE_TOO_LARGE`（>8 MiB）、`429 RATE_LIMITED`、`503 PROVIDER_OFFLINE`。
- `nonce_reused`/`stale_ts`/`in_flight`（inner）/`too_large`（inner read/list）/path 限制由 App 在密文响应里返回；Relay 不产生这些语义码。
- 1 MiB read、4 KiB path、10k list 由 App 执行；Relay 只执行 8 MiB envelope/frame。
- `GET /v1/response/{reqId}` 必须来自同一 `(consumerKeyId|connectionId)`；否则 404。
- 客户端 404/503 不删 key；只有 410 明确 revoke/expired 才提示重新配对。

### 3.5 数据加密

- Share Key 32B；`K = HKDF-SHA256(secret, salt=linkId_raw, info="wsvfs/v1")`。
- 每请求 `clientNonce` 32B raw；`Kreq = HKDF(K, salt=clientNonce_raw, info="request")`，`Kresp = HKDF(K, salt=clientNonce_raw, info="response")`。
- Data 请求/响应：AES-256-GCM；request `iv` **随机 12B**；response `iv` 随机 12B；AAD 按 §3.4.2；response AAD 绑定 request `clientNonce/reqId/requestTs/requestIv`。
- **Nonce 规则**：data `clientNonce` 每 Share 唯一；Provider per-Share seen-`clientNonce` cache 10 分钟（bounded 10k）；重复 `nonce_reused`（inner 409）；`|now-ts| > 5 分钟` 硬拒绝 `stale_ts`（inner 409）；replay cache 10 分钟 ≥ skew。随机 12B IV 使即使 cache 过期/进程重启后 nonce 被复用，GCM IV 碰撞概率也可忽略；仍需唯一 `clientNonce`。
- Provider replay cache：per Share，10k/10 分钟；处理前插入 `reqId`；重复 reqId 返回缓存（已完成）或 `in_flight`（处理中）；失败读可同 tuple 重试。
- Consumer pending `reqId`；`SecureRandom`，不用 `kotlin.random.Random`。
- AES-256-GCM only（ChaCha 需 API 28+）；**默认 minSdk 24**；`otherLegacy` minSdk 21 隐藏 Share 功能（代码放 `src/minApi24` 或 guard）。HKDF 用 HMAC-SHA256（RFC 5869）或 Tink；Kotlin/Node 交叉 vectors。
- 无 PFS：静态 secret + 历史密文被录，key 泄露可解历史；短 TTL/Revoke 降低暴露；以后可加 X25519 ephemeral，不宣称 PFS。
- VFS 错误/状态也在密文里；Portal 看不到路径或错误详情。
- **Pairing code 零化**：code 以 `CharArray`/`ByteArray` 保存，不转 `String`；消费/过期/进程死 fill(0)；clipboard 自动清；UI 不把 code 写日志/截图/通知。

### 3.6 Relay

- **Provider key**：`POST /v1/provider-keys`（knock auth + `shareKeyVfs`，body `{deviceId,name}`，issue-once per `(hookId,deviceId)`）；`GET` 列 hook 所有 device key；`POST /rotate`；`DELETE`；Provider key 加密可逆存，Consumer key 若将来有则 hash/constant-time。
- **Hook 生命周期**：Provider WS 连接时和每 5 分钟校验 `enabled && shareKeyVfs`；disable/delete/capability 移除 → revoke keys/links，close 4401。
- **Provider WS**：`GET /v1/provider` + `Authorization: Bearer <Provider key>`；帧 `pair {linkId,pairId}`（公开 pairId，New code 时更新 activePairId/purge 旧 pair cache）、`register {linkId,instanceId,generation}`、`unregister {linkId,reason}`、`request/response` envelope、ping/pong；一条 WS 多 Link。
- **Registration 规则**：
  - linkId 首次注册绑定 `hookId`/`providerKeyId`；不同 hook/providerKey 注册同 linkId → `409 LINK_OWNED`。
  - 同 linkId + 同 instanceId + 高 generation → 替换旧连接，旧连接 4404；低/等 generation → `409 STALE_GENERATION`。
  - 同 linkId + 不同 instanceId → `409 LINK_OWNED`。
  - tombstoned linkId → `410 REVOKED`。
  - 同 instance 同 generation 重连 → 幂等。
- **instanceId**：每 install 持久化 UUID 于 `noBackupFilesDir/vfs/instance-id`；App 重启不变；reinstall 新 UUID。
- **generation**：Share 记录里的 u64；每次 register 前 `+1` 并持久化；初始 1；溢出实际不可能；reinstall/新 Share 重置。
- **Consumer**：v1 只在 Unix socket 开 `POST /v1/request`（无 transport auth）；公网 listener **不挂** `/v1/request`，远端 Consumer 后置。Unix socket `~/.clawdy-portal/run/vfs-relay.sock`，目录 0700、socket 0600；500 req/min per Link，防本地 flood。
- **Tombstone**：link record `{linkId,hookId,providerKeyId,connection,instanceId,generation,registeredAt,revokedAt?,expiresAt?}`；Revoke best-effort `unregister {reason:"revoked"}`；Relay 可达时 7 天 tombstone，请求 410；不可达/重启后 404；Provider 离线 503。客户端 404/503 保留 key。
- **unregister reasons**：`revoked`（tombstone 410）、`rotate`（tombstone 410）、`shutdown`（无 tombstone，link 可重注册）、`replaced`（无 tombstone）。`New code` 不 unregister（pairId 换新）。
- **Response cache**：`(linkId,consumerKeyId|connectionId,reqId)`；data 60s、pair ≤ code expiry 5min 且只对 activePairId；`pair` 控制帧/`unregister`/Revoke purge 旧 pair 缓存。
- **Limits**：8 MiB frame/body；1 MiB read；10k list/4 KiB path；per-link concurrency 8；120 req/min、64 MiB/min、5 pair/min；tombstone 7d；replay/seen-nonce 10min。
- **audit**：event/linkId prefix/pairId prefix/key prefix/bytes/duration/result；不记 ciphertext、路径、scope、label、inner error。
- **部署**：同一个 Portal 进程 + Unix socket；Provider WS 挂现有 Portal origin/tailnet；不新开 Funnel 端口；launchd 随 Portal；启动时先尝试连接 stale socket，活则失败，死则 unlink + lock；provider key 文件见 §3.2。
- **旧路径**：`/vfs/provider`/vfsd/P5 禁用；新 `/v1/provider` 与旧路由在 P4 前共存。

### 3.7 Scope

| Scope | 物理来源 | VFS 路径 | 生成方式 | 警告 |
|---|---|---|---|---|
| `app.data.activity` | `filesDir/activity/events.jsonl` | `appdata/activity/events.jsonl` | 现有文件虚拟映射 | amber |
| `app.data.groups` | 内存 group state；core 停止时 `available:false` | `appdata/groups/current.json` | on-read snapshot | amber |
| `app.data.logs` | `filesDir/logs/app.log` + AppLog | `appdata/logs/app.log` | 新 source 生成 redacted view | amber |
| `app.data.telemetry` | `filesDir/telemetry/` | `appdata/telemetry/...` | curated view | amber |
| `app.data.diagnostics` | counts/sizes/version/profile rev | `appdata/diagnostics/index.json` | on-read metadata only | amber |
| `app.files` | `filesDir/**` except `vfs/` + reserved roots | `appfiles/**` | raw | red |
| `app.workspace` | external files dir；denylist `conductor/`、`.runtime/`、reserved roots | `**` | raw | red |
| `conductor.files` | external `conductor/**` + artifacts，含 `resources/inbox` | `conductor/**` | raw | red |
| `conductor.trace` | `.runtime/trace.md` | `.runtime/trace.md` | runtime file | amber |
| `conductor.system_prompt` | `.runtime/system_prompt.md` | `.runtime/system_prompt.md` | runtime file | amber |
| `conductor.conversation` | `.runtime/conversation.md` | `.runtime/conversation.md` | runtime file | red |
| `user.selected` | SAF content URIs | `selected/**` | virtual, pfd | picker |
| `all` | 所有固定 scope 的并集 | union | — | red union |

- **`all` = 所有固定 scope**：`app.data.*`、`app.files`、`app.workspace`、`conductor.files`、`conductor.trace`、`conductor.system_prompt`、`conductor.conversation`；**排除 `user.selected`，硬排除 `vfs/`**。`Everything = all`；旧 `all` 的 trace/system_prompt/conversation 能力保留。
- **`app.workspace` 是 denylist 且标红**：external files dir 减去 `conductor/`、`.runtime/` 和所有保留虚拟 root；已知内容有 crash reports（可能含 configuration.json 副本）、core cache/state、Taildrop；警告列出。
- **`app.files` 只暴露 `filesDir`**，不暴露 `noBackupFilesDir`；`vfs/` 在每个 base 都排除，canonical path 检查。红字警告列出：`configuration.json`、`configs/*.json`、`conductor/mcp-servers.json`、`conductor/api-trace.json`、`conductor/trace-turns/`、`conductor/images/`、`settings.db`、`profiles.db`、`logs/`、`telemetry/`、`activity/`。`datastore/` 不存在，删除该词；Tailscale state 不在 `filesDir`，警告不硬编码它。
- **`conductor.files` 标红**：external conductor/**、artifacts、inbox、图片、trace-turns、Conductor 配置；`conductor.files` 不包含 filesDir 的 trace-turns/images（那些在 app.files），文档明确。
- **`conductor.conversation` 标红**。
- **`app.data.logs` redaction**：新 source 只输出经过 redactor 的 tail。Redactor 规则：移除 `Authorization`/`Bearer`/token/password/secret/订阅 URL/`http(s)://` 原文；UUID/节点地址按模式脱敏；保留错误类/事件名/时间。测试用已知 secret 字符串断言不出现在输出。
- **`app.data.diagnostics` schema**：`{"generated_at","app_version","device_prefix","profile_rev","event_count","activity_bytes","telemetry_bytes","log_bytes","share_count"}`；不读 crash report/config 内容。
- **`app.data.groups`**：core/group state 不可用时 `{"generated_at","available":false,"groups":[]}`；可用时 snapshot。
- **`user.selected`**：SAF `ACTION_OPEN_DOCUMENT` 多选，persistable read；存 URI+metadata，不复制文件；虚拟名 `<id>-<sanitized-name>`，冲突用 id 去重；v1 只支持文件，目录 → `not_supported`；删除 → `not_found`；权限丢失 → `permission_lost`；`pfd` + `Os.pread`，不支持 seek 的 provider 退化 stream-skip；read-only；不在 `all`；测试 permission loss/provider death/seek failure。
- **保留虚拟 root**：`appfiles`、`appdata`、`conductor`、`.runtime`、`selected` 在 external raw `**` 中一律排除；canonical 检查 + symlink 测试；`app.workspace` 拒绝 canonical target 落在 `base/conductor`、`base/.runtime` 或保留 root。
- **预设精确列表**：
  - **Recommended**：`app.data.activity`、`app.data.groups`、`app.data.logs`、`app.data.telemetry`、`app.data.diagnostics`；不含 `app.workspace`、`app.files`、`conductor.*`、`user.selected`。
  - **Everything**：`all`（所有固定 scope，不含 `user.selected`）。
  - **Customize**：任意固定 scope + 可选 `user.selected`。
- **警告字符串**：静态 strings.xml（中/英），测试保证每条路径属于对应 scope；`app.files`/`app.workspace`/`conductor.files`/`conductor.conversation`/`all` 红字，`app.data.*`/`conductor.trace`/`system_prompt` amber。
- Provider `VfsScopedSource` 按 Share 执行；Relay 不解释 scope；Share 创建 UI 显示 human-readable groups + details。

### 3.8 App UI、后台、状态、feature flag、egress

- Settings → Share files：Portal server 选择（现有 MCP server；Advanced 手填）、Connect/Disconnect、状态。
- Shares 列表：label、scope 摘要、created、pairedAt、lastUsed、状态（waiting/paired/offline）、New code、Revoke。
- New Share：名字、Recommended/Everything/Customize、per-scope 警告、Create。
- Pairing Code 页：5:00 倒计时、Copy pairing command、New code；无 QR；不进通知/状态栏；`FLAG_SECURE`；clipboard 自动清。
- **多 Share 状态**：`VfsRelayState`：`Connected(shareCount, shares[{shareId,label,status,pairedAt,lastUsedAt}], totalBytesRead)`、`Idle`、`Backoff`、`Error`；通知「Sharing N Shares with M Agents」+ Stop；`totalBytesRead` 是实际读取字节（read response data bytes 累加）。
- **Stop sharing = 暂停**：关闭 Provider WS；丢弃 active PairingCode；Relay pair/response cache 自然过期；Share/Share Key 保留；Connect 恢复，不重配对。**Revoke = 永久**：best-effort unregister，删除 Share/linkId/secret，tombstone。**New code 不 eject 已配对 Agent**；只有 Revoke 断开。
- 后台复用 `VfsSharingService`/`VfsSharingPolicy`：FGS 活着时后台可读；进程死/Doze 后 503；不引入 always-on；Share 记录持久化，重开 App 显示 one-tap Reconnect（不静默自动连）。
- **egress**：`SocketProtector` 接口 + 绑定 `VpnService.protect`；默认 `relayUrl` = 公网 Portal Funnel origin；public path 用 protect + protected/custom `Dns`（不让 DNS 走隧道）；tailnet/MagicDNS URL **不 protect**。测试 VPN on/off、per-app include、root/proxy、rule switch、network switch、DNS。
- **feature flag**：`BuildConfig.SHARE_KEY_VFS`（默认 false）+ `PORTAL_SHARE_KEY_VFS` env；P1/P2 新路径 behind flag；旧路径/工具保留到 P4；P5 移除旧路径/AO 导出/flag。flag off 回旧路径；测试 flag off/on、旧 UI 隐藏、Portal 路由。
- **Pairing code 输入**：UI 用 char/byte 数组；clipboard 自动清；skill/CLAUDE.md 提示不要把 code 留在 shell history；日志/截图/通知/telemetry/export 不出现 code/key。

### 3.9 CLI

- 安装：Portal `pnpm service-install` 装 `$HOME/bin/wonder-vfs`；skill/CLAUDE.md 提示；App 复制 `"$HOME/bin/wonder-vfs" pair ...`（绝对路径）。
- `--help` 一页：App 路径、pair 流程、每命令一个示例、JSON 默认、大文件、Portal 盲区、not-paired 错误。
- 命令：
  - `pair --link <linkId> --pair <pairId> --code <code>`
  - `list [path] --json [--cursor] [--limit]`
  - `stat <path> --json`
  - `read <path> [--max-bytes 65536] --json`
  - `get <path> --out <file>`
  - `forget --share <shareId|label>`（410 后清理本地 key；不删远端）
  - `--share <shareId|label>`；`--socket <path>`（默认 `~/.clawdy-portal/run/vfs-relay.sock`）；远端 `--relay/--api-key` 后置。
- JSON envelope：`{"ok":true,"data":...}` / `{"ok":false,"error":{"code","message","retryable","hint"}}`。
- 输出 schema：
  - `pair`：`{"connected":true,"share":{"shareId","label"},"roots":["..."]}`
  - `list`：`{"entries":[{"path","name","isDirectory","size","mtimeMs","mimeType?"}],"nextCursor":null}`
  - `stat`：`{"path","name","isDirectory","size","mtimeMs","mimeType?","etag?"}`
  - `read`：`{"path","offset","length","eof","encoding":"utf-8"|"base64","data","truncated","nextOffset"}`
  - `get`：`{"path","out","bytes","sha256"}`
- Exit codes：0 成功；2 usage；3 transient；4 permanent；5 not found。
- Error codes：`NOT_PAIRED`、`CODE_EXPIRED`、`CODE_INVALID`、`CODE_CONSUMED`、`PROVIDER_OFFLINE`、`SHARE_UNKNOWN`、`SHARE_REVOKED`、`RELAY_UNREACHABLE`、`RATE_LIMITED`、`TOO_LARGE`、`PATH_NOT_FOUND`、`PERMISSION_LOST`、`SHARE_AMBIGUOUS`、`PROVIDER_KEY_REVOKED`、`PROTOCOL_ERROR`、`IN_FLIGHT`、`NONCE_REUSED`、`STALE_TS`、`AUTH`。
- `CLI_NOT_FOUND` 由 skill/CLAUDE.md 发，不由 CLI 发。
- Key store：`~/Library/Application Support/wonder-vfs/keys.json` 目录 0700、文件 0600、atomic+fsync；每 Share `{shareId,label,linkId,secret}`；不打印、不进 argv/env/日志/错误；code 在 pair argv 里是设计允许的，错误中脱敏。
- list 分页：VFS `list` request `{cursor?,limit?}`，response `{entries,nextCursor?}`；默认 1k，最大 10k；Provider source 分页。
- read 默认 64 KiB preview；文本 MIME UTF-8，二进制 base64；大文件 `get --out`，提示数分钟；模型不一次吃 1.33 MiB base64。
- 未配对错误教用户去 App 的路径；本机默认 Unix socket；远端后置。

### 3.10 错误目录

| 情况 | code | exit | 动作 |
|---|---|---|---|
| CLI 未安装/PATH | skill 提示 | 2 | 用 `"$HOME/bin/wonder-vfs"` 或 `pnpm service-install`。 |
| 未配对 | `NOT_PAIRED` | 4 | WonderSing → Settings → Share files → New Share。 |
| code 过期 | `CODE_EXPIRED` | 4 | New code（5 分钟）。 |
| code 错误 | `CODE_INVALID` | 4 | 检查 16 字符；New code。 |
| code 已用/缓存丢失 | `CODE_CONSUMED` | 4 | New code。 |
| Provider 离线 | `PROVIDER_OFFLINE` | 3 | 打开 WonderSing，确认 Connected；重试。 |
| Share revoked | `SHARE_REVOKED` | 4 | 新建 Share。 |
| Relay 未知/重启 | `SHARE_UNKNOWN` | 3 | 保留 key；重试；持续 404 先让 App 重连，再考虑新 Share。 |
| Provider key revoked | `PROVIDER_KEY_REVOKED` | 4 | 请用户打开 App/Portal 重新 Connect/rotate；CLI 不能 rotate。 |
| Portal 不可达 | `RELAY_UNREACHABLE` | 3 | 检查 Portal/默认 socket；远端 `--relay`。 |
| 限速 | `RATE_LIMITED` | 3 | 等 `Retry-After`；不要 New code（不清 rate）。 |
| 大文件/envelope | `TOO_LARGE` | 4 | `get --out`；减小 range/limit。 |
| 认证失败 | `AUTH` | 4 | 检查 key；重新 Connect。 |
| 多 Share 未选/重复 label | `SHARE_AMBIGUOUS` | 2 | `--share shareId`。 |
| SAF 权限丢失 | `PERMISSION_LOST` | 4 | App 重新选择文件。 |
| SAF 文件删除 | `PATH_NOT_FOUND` | 5 | 检查 path；重新选择。 |
| nonce 复用 | `NONCE_REUSED` | 3 | 新请求用新 `clientNonce+reqId`；重试复用原 tuple。 |
| ts 过期 | `STALE_TS` | 3 | 校准时钟；±5 分钟。 |
| 处理中 | `IN_FLIGHT` | 3 | 等 Retry-After/重试。 |

### 3.11 重启、新手机、多 Agent

- Relay 重启：App 重连并重注册 Share；CLI key 不变；pending 请求重试；不重配对；404/503 不删 key。
- App 重启/进程死亡：Share 记录/instanceId/generation 持久化；重开 App one-tap Reconnect；不静默自动连；CLI key 不变；不重配对。Pairing Code memory-only，进程死则 code 丢失，New code。
- 新手机/重装：Share 不迁移；reinstall 清 noBackup，旧 Share 不能在新装使用/撤销；用同一 knock `GET /v1/provider-keys` 列出旧 device key，`DELETE` 撤销；新建 Share/重新配 CLI。旧 Share 在旧装 Revoke 前仍可能对旧 App 有效，不能声称自动失效。
- 多 Agent：一 Agent 一 Share；CLI 按 Share 存多把 key，`--share` 选择；上限 8；重复 label 报错。
- Stop = 暂停；Revoke = 永久；New code 同 Share Key，不 eject。

### 3.12 旧路径退役、文档迁移、feature flag

- App 移除（P5，flag 后）：`VfsConnectTool`、`VfsConsent`/`VfsTrust`/`VfsCooldown(Store)`/`VfsResumeStore`/`VfsServerBase`/`VfsLink`/`VfsLinkParser`/旧 `VfsScopeAliases`/`VfsAccessScreen`/旧 `VfsProviderProtocol` server-hello/`OkHttpVfsProviderConnector` 旧 Portal 逻辑/`VfsRuntime` MCP 解析/`resolveServerForVfs`/`/vfs/provider`；Conductor prompt/tool catalog/sanitizer/MCP device header；旧 status bar/notification/strings/tests。
- Portal 移除（P5）：`vfs_link_request`/`vfs_connect`/`vfs_export`/`job_run(vfs=true)` VFS 分支；`lib/vfs.mjs`、`lib/vfsd-transport.mjs`、`/vfs/provider`；vfsd 数据面；容器 `/vfs` 挂载；旧 tests/scripts（`tests/vfs*.mjs`、`tests/jobs.test.mjs` **只退役 VFS 用例，保留 job 其他测试**、`scripts/e2e-vfs*.mjs`、`scripts/fake-app-provider.mjs`）。
- 文档：Portal `VFS.md` 改成新的 Relay/pairing/CLI 契约；`agent-oriented-vfs.md`、`docs/jobs.md`、`docs/mcp-grants.md`、`AUTH.md`（`/vfs/provider` 例外）、`HOOKS.md`、`AGENTS.md` 的 VFS 工具清单全部更新/标退役；Portal `docs/vfs-campaign.md`、`TODO.md`；WonderSing `vfs-dry-run.md`、`vfs-connection-stability.md`、`vfs-soak-test.md`、`vfs-cases/`、`mcp.md`、`agent-control-plane.md`、`agent-runtime.md`、`conductor-*`、`local-testing.md` 改成新路径或标退役；App strings/translations 全部更新；`TODO.md` 改 v6。
- AO 的 Activity/Groups 分散导出：P5（Share Key VFS 真机验收后）移除；P4 AP 保留 old path/AO 导出。
- `ShareKeyVfs` feature flag：P1/P2 新路径 behind flag；旧路径保留；P4 验收；P5 移除旧路径/flag/AO 导出。

## 4. 实现方案

### 4.1 阶段

- **P0**：评审并冻结本文档 + 附录 A vectors；不改代码。
- **P1 App provider core**：Portal server 选择/auto-provision；Share store/instanceId/generation；pairing/pairId；AEAD/HKDF；scope source/denylist/redaction；FGS 复用；Settings/Share UI；`ShareKeyVfs` flag；旧路径保留；JVM 测试。
- **P2 Portal/Relay**：provider key issue/rotate/revoke/list + hook capability；Link 路由/注册 generation/tombstone/link ownership；pairing/pairId；Unix socket；公网只 Provider WS；limits/audit；flag；旧路径保留；契约测试。
- **P3 CLI**：`wonder-vfs` 安装；pair/list/stat/read/get/forget；key store；JSON/exit/errors；skill；测试。
- **P4 E2E**：真机 + Mini + Agent；flag on；Relay 只见密文；revoke/offline/restart；release AP（old path/AO 导出仍保留）；验收。
- **P5 清理**：移除旧 App/Portal 路径、vfsd、容器 `/vfs`、旧 strings/tests/docs；移除 AO 导出；移除 flag；清理版 AP。
- P1/P2/P3 在 contract v6 冻结 + vectors 提交后可并行；P4 依赖 P1–P3。

### 4.2 数据模型

- App Share：`shareId,label,linkId,secretCiphertext,scopes,instanceId,generation,createdAt,pairedAt,lastUsedAt,expiresAt(null),revokedAt`；`noBackupFilesDir/vfs/` 加密；不进 telemetry/export；lastUsed 节流。
- PairingCode：**memory-only** `{shareId,pairId,code(char/byte),createdAt,expiresAt,consumedAt}`；不持久化 code/hash；消费/过期/进程死 zeroize。
- Pending revocation：`{linkId,reason,pendingUntil}`；持久化；Connect 重试 `unregister`。
- Provider key：`hookId,deviceId,providerKeyCiphertext,relayUrl,createdAt,rotatedAt,revokedAt`；Portal master key 加密可逆；App 加密；一 `(hookId,deviceId)` active；hook 多 device；orphan list/revoke。
- Relay link：`linkId,hookId,providerKeyId,connection,instanceId,generation,registeredAt,revokedAt?,expiresAt?`；内存 + tombstone 7 天。
- Response cache：`(linkId,consumerKeyId|connectionId,reqId) -> encrypted response,expiresAt`；data 60s、pair ≤code expiry。
- Replay/seen-nonce：per Share，10k/10min。
- CLI key：`shareId,label,linkId,secret`；目录 0700/文件 0600/atomic+fsync。

### 4.3 API 契约

- **Provider key**：`POST /v1/provider-keys`（knock + `shareKeyVfs`，body `{deviceId,name}`，issue-once per `(hookId,deviceId)`）；`GET` 列 hook devices；`POST /rotate`；`DELETE /{deviceId}`；`409 DEVICE_OWNED`；key 加密可逆存；master key 文件。
- **Hook**：`hooks.json` `capabilities:["shareKeyVfs"]`；disable/delete 撤销 keys/links；Provider WS 连接/每 5min 校验。
- **Provider WS**：`GET /v1/provider`，`Authorization: Bearer <Provider key>`；`pair {linkId,pairId}`（公开；Relay 更新 activePairId/purge 旧 pair cache）、`register {linkId,instanceId,generation}`、`unregister {linkId,reason}`、`request/response` envelope、ping/pong；link ownership/generation/tombstone 规则。
- **Consumer**：v1 只 Unix socket `POST /v1/request` body `{v,op,linkId,reqId,clientNonce,ts,iv,ct}`；202 `{reqId}`；`GET /v1/response/{reqId}` long-poll ≤30s；response body 同 wire；公网 listener 无 `/v1/request`。
- **List**：inner `list {path,cursor,limit}`；inner response `{entries,nextCursor}`；`VirtualDirectorySource.list(path,cursor,limit): VfsListPage`。
- **状态**：Relay 401/404/410/409 IN_FLIGHT/413/429/503；inner `nonce_reused`/`stale_ts`/`in_flight`/`too_large`/`permission_lost`/`cursor_invalid`；客户端 404/503 保留 key。
- **AAD/crypto**：§3.4/§3.5；附录 A vectors；data request random IV；pair random IV；pairId.
- **Limits**：8 MiB envelope；1 MiB read；10k list/4 KiB path；per-link concurrency 8；120 req/min、64 MiB/min、5 pair/min；tombstone 7d；replay/seen-nonce 10min；Unix socket 500 req/min per Link。
- **审计**：event/linkId/pairId/key prefix/bytes/duration/result；不记内容/路径/scope/label/inner error。
- **Cache purge**：Revoke/unregister purge；New code 新 pairId 使旧缓存不可达；pair cache ≤code expiry。

### 4.4 App 改动

- 新增：`VfsRelayConfig`（portalServerId/relayUrl）、`VfsProviderKeyProvisioner`（仅 bootstrap adapter；core 不 import MCP）、`VfsRelayClient`、`VfsShareStore`、`VfsPairing`（char/byte code）、`VfsEnvelopeCrypto`、`VfsRelayManager`（一 WS 多 Link/generation）、`AppDataVirtualDirectorySource`、`UserSelectedVirtualDirectorySource`、`VfsRelayState`、`SocketProtector`。
- 改写：`VfsRuntime`（去 MCP，接 Relay manager）、`VfsConnectionManager`、`VfsProviderProtocol`（envelope）、`VirtualDirectoryProtocol`/`Session`（inner/outer/list cursor）、`VfsScopedSource`（scope/denylist/canonical/reserved roots）、`WorkspaceVirtualDirectorySource`（排除 conductor/.runtime/reserved/canonical）、`InternalFilesVirtualDirectorySource`（filesDir only、vfs/ canonical）、`AppVirtualDirectorySource`（appdata/selected）、`VfsStatusBar`/`ServiceStatusBar`/`VfsScopeLabel`/`MainActivity`/`Navigation`/`VfsConnectionState`/`VfsSharingService`（多 Share/Stop）、`VfsPrivateFiles`/`VfsDeviceId`（instanceId）、strings/translations、Agent `AgentContext`/`AgentDataTools`/`AgentSanitizer`/`McpProtocol`/`ConductorViewModel`（去 VFS）。
- 删除（P5）：§3.12。
- API 24：Share/crypto 代码 `src/minApi24` 或 guard；`otherLegacy` 隐藏；默认 minSdk 24。
- Feature flag `BuildConfig.SHARE_KEY_VFS` 默认 false；P1–P4 旧路径保留。
- Egress `SocketProtector` + protected Dns；public Funnel protect，tailnet 不 protect。
- Pending revocation store/retry；lastUsed 节流；code zeroize/clipboard.

### 4.5 Portal/Relay 改动

- 新增：`lib/vfs-relay.mjs`（provider key issue/rotate/revoke/list + hook gate、Provider WS、link ownership/generation/tombstone、pairing/pairId、consumer 202+poll、response/replay cache、Unix socket、public auth、limits/audit、cache purge）。
- 修改：`server.mjs` 挂路由（public 只 Provider WS；Unix socket Consumer）；`AUTH.md`（provider-keys/provider/Unix socket/shareKeyVfs）、`HOOKS.md`、`AGENTS.md`、`docs/jobs.md`、`docs/agent-oriented-vfs.md`、`docs/mcp-grants.md`、`VFS.md`、`docs/vfs-campaign.md`、`TODO.md`；launchd 同进程；stale socket unlink/lock；master/provider key 文件。
- 退役（P5）：`lib/vfs.mjs`、`lib/vfsd-transport.mjs`、`/vfs/provider`、`vfs_link_request`/`vfs_connect`/`vfs_export`/`job_run(vfs=true)` VFS 分支、vfsd、容器 `/vfs`、旧 tests/scripts（jobs 只 VFS 用例）。
- v1 远端 Consumer 不开放；Consumer key 后置。

### 4.6 CLI 改动

- 新增 `$HOME/bin/wonder-vfs`（Node/Go；优先 Node）；安装脚本；skill/CLAUDE.md；key store；socket；JSON/exit/errors；list cursor；read preview/get/forget。
- 输出 schema/错误/退出码按 §3.9/§3.10；测试各 schema、key 0700/0600/atomic/fsync、Unix socket、`$HOME/bin`、multi-Share、code 脱敏、大文件、IN_FLIGHT/NONCE_REUSED/STALE_TS/AUTH/413。

### 4.7 测试

- Pairing：5 分钟、单次、memory-only/process death、pairId New code/old cache + `pair` 控制帧、无效 tag 限速、并发兑换、响应丢失/同 tuple 重试、cache ≤ code expiry、client restart、pair 前拒绝读、code/key 不进日志。
- Provider key：issue-once/重复/rotate/revoke/list、hook 多 device、第二 hook 同 device 409、orphan/reinstall、Advanced、未授权、shareKeyVfs gate、hook disable 撤销、加密存储、rotate 响应丢失。
- Crypto：RFC 5869 HKDF、NIST AES-GCM、附录 A exact vectors、Kotlin/Node 交叉、AAD tamper、nonce/iv 复用、reqId replay、response swap、clock skew、API24。
- Nonce/replay：same nonce/new reqId、same reqId/new nonce、replay after cache eviction、process-death retry、STALE_TS、NONCE_REUSED；random IV.
- Relay：malicious swap/replay/reorder/drop、401/404/410/409/413/429/503、frame/body/list caps、restart/reconnect、generation takeover/link ownership、duplicate linkId、local socket vs public、stale socket lock、audit grep、tombstone、cache purge、v1 public Consumer 404。
- Scopes：每个 scope × 路径矩阵；appdata/groups/diagnostics 生成；app.workspace denylist/canonical/reserved roots/symlink；vfs/ 不可达；.runtime 不经 app.workspace；all 含 .runtime/inbox；user.selected 权限丢失/删除/目录/seek；app.files 警告真实路径（settings.db/profiles.db/trace-turns/images）；conductor.files 红字；app.workspace 红字；warning strings 中英。
- App：Share create/copy/New code/Revoke/Stop；多 Share 状态/通知；background/Doze/process death；one-tap Reconnect；egress VPN/proxy/tailnet/DNS/rule switch；flag off/on；key/code 不进 telemetry/export/screenshot/clipboard/notification。
- CLI：JSON/exit/errors、key store 0700/0600/atomic/fsync、Unix socket、`$HOME/bin`、list cursor、read preview/get/forget、multi-Share、duplicate label、no code/key in logs、IN_FLIGHT/NONCE_REUSED/STALE_TS/AUTH/413。
- E2E：真机 + Mini + Agent；已知文件；Relay 只见密文/元数据；revoke/tamper/offline；overnight；large files；multi-device；容器 `/vfs` 退役记录。
- UX：新用户按 6 步完成；术语；`--help`；错误可行动；`all`/per-scope 警告清楚。

### 4.8 发布与回滚

- 顺序：Portal/Relay + CLI 就绪 → App AP（`ShareKeyVfs` flag on，旧路径/AO 导出保留）→ P4 E2E/真机验收 → P5 移除旧路径/flag/AO 导出 → 清理版 AP。
- 发布门：P4 E2E；Relay 日志无明文/key/code；scope matrix；旧路径 flag off 可回退；附录 A vectors 通过；`./scripts/local-test.sh`；`./scripts/e2e-emulator.sh`；CI Test/Release。
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
- Egress：公网 Funnel + protect + protected DNS；tailnet URL 不 protect。
- Memory-only pairing code：进程死即丢；New code；不持久化 secret。
- Provider key master key 存储/轮换；orphan 清理。
- Local Unix socket 无 transport auth：data 仍加密，但本地进程可 flood/probe；500 req/min per Link + 5 pair/min；文档写明。

## 5. 验收标准

| 验收 | 证据/测试 |
|---|---|
| Agent 用 CLI 读取选定 scope，无需手动导出/容器 | 真机 + Mini + Agent E2E；已知文件 sha256。 |
| Pairing Code 5 分钟、一次性；长期 Share Key 不打印、不进聊天；CLI 按 Share 保存；code memory-only；New code pairId 隔离 | pairing 测试；transcript/log 扫描；CLI key store 权限；process death；old pairId cache。 |
| Portal 只见密文 + 路由元数据；无 key/code/路径/inner error | malicious Relay 测试；audit grep；tcpdump/WSS 检查；inner error 加密。 |
| Revoke 后 Relay 可达时 410；离线/重启按 tombstone/404/503；New code 可重配对；Provider 离线 503；Relay/App 重启不重配对；新手机新 Share；reinstall 文案正确 | revoke/tombstone/restart/new-phone/reinstall 测试。 |
| `all`/`app.files`/`app.workspace`/`conductor.files`/`conductor.conversation` 警告可见；`vfs/` 不可达；`.runtime` 不经 `app.workspace`；`user.selected` 不在 `all`；inbox 保留；reserved roots | scope matrix；UI 截图/strings 测试；symlink 测试。 |
| 旧 `/vfs/provider`/vfsd/`job_run(vfs=true)`/`VfsConnectTool` 在 P5 禁用；P4 前 flag off 可回退 | flag off/on；旧路径回归；docs 更新。 |
| 多 Share 状态/通知正确；Stop=pause，Revoke=permanent；totalBytesRead 正确 | 多 Share UI 测试；通知/strings。 |
| CLI JSON/exit/errors/key store/socket/PATH/list cursor 正确；IN_FLIGHT/NONCE_REUSED/STALE_TS/AUTH/413 可行动 | CLI 契约测试；Agent 实际调用。 |
| 附录 A vectors 在 Kotlin/Node 逐字节通过；malicious Relay/scope/background/egress 测试通过 | 测试报告/记录路径。 |

## 6. 评审记录与变更

- 安全/协议评审：sound with changes；2 个 blocking 已定：容器 `/vfs` 退役；一 Agent 一 Share（linkId/secret 在 Share 创建时生成，一条 WS 多 Link）。
- 简洁性/易懂性/易用性评审：概念 4/5、易懂 3/5、易用 3/5；0 blocking；5 个默认已记录。
- v3 文档评审：3 个新 blocking；v4 修正。
- v4 文档评审：4 个 blocking（B1/B2/B3 + nonce/replay）；v5 修正。
- v5 文档评审：6 个 blocking（provider key 身份/orphan、New code cache、inner payload/Relay 状态、wire/AAD、Portal 发现/gate、instanceId/generation）+ high；v6 按 §0 修正并附 vectors；待新一轮评审。

## 附录 A：P0 test vectors

以下 hex 是 v6 的 source of truth；Kotlin/Node 必须逐字节复现。输入与输出由同一参考算法生成；任何实现差异先改 vectors，不先写代码。

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
pairResp    = {"shareId":"cHFyc3R1dnd4eXp7fH1-fw","shareSecret":"gIGCg4SFhoeIiYqLjI2Oj5CRkpOUlZaXmJmam5ydnp8","scopes":["app.data.activity"],"label":"Claude"}
dataReq     = {"v":1,"type":"stat","path":"/appdata/activity/events.jsonl"}
dataResp    = {"v":1,"type":"entry","entry":{"path":"/appdata/activity/events.jsonl","name":"events.jsonl","isDirectory":false,"size":12345,"mtimeMs":1760000000000}}
```

### A.5 ciphertext（`ciphertext || 16B tag`）

```
pairReq     = 88d5c28412ed6921608efcddf1ff5921693d1d1b77eba8529500831fe58a9cf3bf3720f22c5fbce8a7eb0988f404056e26e6e25afeaa75de09b2bbadce5a8b59ef040ffbdb6a95a56b2e47b2dc14bd23e08b25961282c16015a4cfef365f6524fb26f8901a93d82d627d4a61e59947
pairReqTag  = 24fb26f8901a93d82d627d4a61e59947
pairResp    = 7ed26b4187fb538d7ebb568ebc775074948bf453aadddf3134d07b39bd762e54e485c471b7934dcb4c4d8a51b3b10f4e4396cbc6f5dfd325c7aec58ed8cdddaeb24c8c1c62ce0375c7ae902b6d5baeff07fa8207861ab80f7a822a9c6263703ca3265e7c126089bb13ce6ffbff05f87af5bc82c210b3b5f80fb5b1d78f8e9279f58772b9c2cd3ee92445051e5c187e759cc4b513d07da31cc692d5ccceabcef6
pairRespTag = 9cc4b513d07da31cc692d5ccceabcef6
dataReq     = 47944c5a97e321bb9359dd947342e1d9fc0a50abfd8cb8ff984542011817e38b2bfa8bf51b125dcfea7b9c6e361736cc0ac1a70d434550eb71b7afd871ee8d75824a5cecc6bc8eacbfd3798e1d
dataReqTag  = ee8d75824a5cecc6bc8eacbfd3798e1d
dataResp    = 2771f07f6f24f789cacfe8866d38b9f1296e51a4967ab76271cf010732bd5d3a0a62be26fc39e4ffb5115b3e07a3446cf4eeb057625401b6eeba3fe5d777bd8bef4e38a6ad7ad7c78c1b320f71ceacc958ccd6ee35a10786a2734f0b05413978cfd0169bed46804be0cb3053d44172c94a099f730406f5501f9dd05ca6d04afe0c76aa994320dd8732f4badf3445bc971120f53dd6ca39a2204e2c8d58156fa1ed1ae9298ab4cb
dataRespTag = a2204e2c8d58156fa1ed1ae9298ab4cb
```
