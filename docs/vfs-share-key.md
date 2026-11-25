# Share Key VFS：概念、方案与实现

状态：设计草案 v3（2026-11-25），待独立评审；未实现。旧 Conductor VFS / Portal vfsd / MCP VFS 路径在本设计通过后退役。
相关旧路径：`~/workspace/clawdy-portal/VFS.md`、`~/workspace/clawdy-portal/docs/agent-oriented-vfs.md`、`~/workspace/clawdy-portal/docs/jobs.md`、[`vfs-dry-run.md`](vfs-dry-run.md)、[`vfs-connection-stability.md`](vfs-connection-stability.md)、[`mcp.md`](mcp.md)。本设计通过后按 §3.12 改写或标退役。

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
| Share | 一次授权：label + scope 集合 + 一个 Link；一个 Agent 一个 Share；可 New code / Revoke。 |
| Pairing Code | 5 分钟、一次性、16 字符 Crockford base32；用户粘给 Agent；CLI 用它换 Share Key。 |
| Share Key | 长期 AEAD 密钥；只存在 App 和 Agent 的 CLI 里；不打印、不进聊天。 |

用户只需要理解 **App / Agent / Portal / Share / Pairing Code** 五个概念。Provider/Consumer、Share Key、Link ID、instanceId、generation、reqId、AEAD/HKDF、202+poll 都是实现概念，不出现在主 UI。

### 2.2 信任边界

- **Transport auth**：App 默认复用现有 Portal 连接，自动换/发一个 Relay 专用 Provider key；本机 Agent 走 Unix socket，免 transport key；远端 Consumer 的 key 后置。Transport key 只防滥用，不授予数据。
- **Data auth**：Share Key。App 和 CLI 各持有；Portal/Relay 永远看不到。
- **Authorization**：App 按 Share 的 scope 逐请求检查；Portal 不解释 scope。
- **Consent**：创建 Share、生成 Pairing Code 就是授权；每个敏感 scope 有自己的红字警告；`all` 的警告是并集。

必须对用户说清楚：

- **Portal 看得到**：哪个 Share、什么时候、多少字节、key 身份、Link ID；看不到文件内容、路径、scope、label。
- **Agent 看得到**：你分享的文件；Agent 可能把它们发给它的模型提供方。
- **Pairing Code**：一次性、5 分钟、在聊天里可见；看到它的人在这 5 分钟内可能抢先配对。
- **Share Key**：由 Agent 的 CLI 保存；Portal 读不到；Agent 宿主机被信任（同一用户可读 CLI 的 key store）；不要宣称模型绝对拿不到 key。
- **Relay 不可读**：密文和 AEAD tag 保证 Portal 不能读/伪造/篡改；它可以丢包、延迟、看元数据。

### 2.3 用户模型（一页话术）

> WonderSing 连上你的 Portal 后，创建一个 Share：起个名字，选 Agent 可以读的文件。复制 5 分钟配对码（或完整的一行配对命令）到 Agent 聊天。Agent 的 CLI 用它换一把自己保存的 key，然后 list/read 这些文件。Portal 只转发加密数据，能看到元数据，看不到内容。撤销 Share 就断掉访问。

术语：

- 用「Share files / Agent access」，不用 VFS。
- 用「Portal connection」，不用 Relay（除非 Relay 不是 Portal）。
- 用「What the Agent can read」，不摆 scope 技术名。
- 用「New code」「Revoke」，不用 rotate。
- 全局叫「Stop sharing（all Agents）」，单个 Share 叫「Revoke this Share」。

### 2.4 用户流程

一次性设置：

1. Settings → Share files → Connect。默认使用已配置的 Portal 连接；URL/key 收进 Advanced，不要求用户手填第二个 key。

每个 Agent：

2. New Share → 名字（可选）→ Recommended / Everything / Customize → Create。
3. Copy pairing command（5:00 倒计时）；App 复制完整命令，不只是 code。
4. 粘到 Agent 聊天；Agent 运行 `wonder-vfs pair ...`，返回它能看到的 roots。
5. Agent 运行 `wonder-vfs list/read`；不再需要 code。撤销 Share → Revoke。

### 2.5 已接受的取舍

- Pairing Code 会进入聊天/模型上下文；一次性、5 分钟、兑换后失效；长期 Share Key 不打印、不进聊天。用户已拒绝 out-of-band CLI/QR。
- `all` 可以包含敏感 App 文件，但必须有红字警告；`vfs/` 永久排除；`app.files` 和 `conductor.conversation` 各自也有红字警告，不只 `all`。
- 可用性保持现状：FGS 活着时后台可读；进程死/Doze 后 503；不做 always-on。
- 无 PFS；静态 Share Key + 短 TTL/Revoke；以后可加 X25519 ephemeral。
- Relay 元数据（Link ID、时间、大小、key 身份）可见；不记录内容/路径/scope/label。
- Share 功能要求 API 24+；API 21 `otherLegacy` 隐藏该功能。
- 容器 `/vfs` 退役；Conductor 的手机侧 VFS 工具移除；CLI 是替代。
- 保留 `user.selected` 和细粒度 `app.data.*`；实现可分阶段，设计保留。

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

### 3.2 App / Portal 配置

- 默认：Settings → Share files 显示已有 Portal 连接；Connect 时用现有 Portal 会话/token 自动换/发一个 **Relay 专用 Provider key**，加密存储。VFS 核心不使用 MCP 工具；现有 Portal 连接只做一次性 bootstrap。
- 没有现有 Portal 连接时，Advanced 允许手填 Relay URL + Provider key。
- Provider key 与 Portal MCP token 分离；撤销 Provider key 不影响其他 Portal 功能。
- Connect/Disconnect 是全局；Stop sharing 停掉所有 Share；单个 Share 走 Revoke。

### 3.3 Share 与 Pairing Code

- Share 创建时生成 128-bit `linkId` + 32B `secret`；一个 Agent 一个 Share；多个 Agent 建多个 Share；上限 8，超出提示先 Revoke。
- Share 记录：`shareId`、label、linkId、secret（加密）、scopes、instanceId、createdAt、lastUsedAt、expiresAt、revokedAt。存 `noBackupFilesDir/vfs/`，不进 telemetry/export。
- Pairing Code：16 字符 Crockford base32（无 I/L/O/U），分组显示，大小写不敏感，5 分钟，单次，5 次尝试；一个 Share 同时只有一个 active code；New code 使旧 code 立即失效。
- App 复制的是完整命令：`wonder-vfs pair --link <linkId> --code <code>`。16 字符是 secret；linkId 是公开路由；不要把 50 字符 URI 当默认，远端场景再议。
- 无 QR；Pairing Code 不进通知/状态栏；`FLAG_SECURE`；clipboard 自动清。
- 生命周期只有两个动作：**New code**（同 Share Key，给重装/丢失的 CLI 重新配对）和 **Revoke**（删除 Share/linkId/secret，立即断 access）。没有 rotate；要换 key 就 Revoke 后新建 Share。
- Cap 8：创建第 9 个 Share 时显示「已达到上限，先撤销一个」，不静默驱逐。

### 3.4 Pairing 协议

请求：

```json
{"v":1,"op":"pair","linkId":"...","reqId":"...","ts":0,"iv":"...","ct":"..."}
```

- `Kpair = HKDF-SHA256(code, salt=linkId, info="wsvfs/pair/req")`
- `ct = AES-256-GCM(Kpair, plaintext={clientNonce}, aad={v,op,linkId,reqId,iv,ts,direction:"pair-req"})`
- code 不发送；Relay 只见密文；linkId 是公开路由。

响应：

```json
{"v":1,"linkId":"...","reqId":"...","iv":"...","ct":"..."}
```

- `KpairResp = HKDF-SHA256(code, salt=clientNonce, info="wsvfs/pair/resp")`
- 明文 `{shareId, shareSecret, scopes, label}`；AAD 绑定 `{v,op,linkId,reqId,clientNonce,iv,direction:"pair-resp"}`。
- AEAD tag 就是认证；客户端存 Share Key 到 keychain/0600，按 Share 分开；tool/CLI 输出只说 connected/roots，不打印 key。

Provider 顺序：persist Share Key → verify AEAD → consume code → build encrypted response → cache `(linkId, reqId, clientNonce)` 60 秒 → send。响应丢失时 CLI 用同一个 reqId/nonce 重试，Provider 返回缓存，不重复消费。无效 AEAD tag 只按 Link 限速，不烧 code；只有有效兑换才消费 code。code 过期/用完即废；New code 换新。

客户端：`pair` 命令把 Share Key 持久化后才报告成功；同一个 Share 可以重新 New code 再配对；CLI 重启不需要 code；pair 前拒绝任何 read。

### 3.5 数据加密

- Share Key 32B；`K = HKDF-SHA256(secret, salt=linkId, info="wsvfs/v1")`。
- 每请求 client nonce `N`；`Kreq = HKDF(K, salt=N, info="request")`，`Kresp = HKDF(K, salt=N, info="response")`。
- AES-256-GCM，zero IV under unique per-request key；AAD length-prefixed `{v, linkId, reqId, N, ts, direction}`；response AAD 包含 request `N`/`reqId`。
- Provider replay cache 在处理前插入 `reqId`；Consumer pending `reqId`；`ts` ±5 分钟为辅助；`SecureRandom`，不用 `kotlin.random.Random`。
- AES-256-GCM only（ChaCha 需 API 28+，minSdk 21）；HKDF 用 HMAC-SHA256（RFC 5869）或 Tink；Kotlin/Node 交叉 test vectors。
- 无 PFS：静态 secret + 历史密文被录，key 泄露可解历史；短 TTL/Revoke 降低暴露；以后可加 X25519 ephemeral，不宣称 PFS。
- VFS 错误/状态也在密文里；Portal 看不到路径或错误详情。

### 3.6 Relay

- Provider：`GET /v1/provider` WS + Provider key；帧 `register {linkId, instanceId, generation}`、`unregister`、`request/response` envelope、app-level ping/pong；一条 WS 多 Link；重连重注册。
- Consumer：`POST /v1/request` → 202 `{reqId}`；`GET /v1/response/{reqId}` long-poll ≤30s；response cache 60s；retry 503/429；404 unknown/revoked；401 auth；429 rate。
- response cache 按 `(consumerKeyId|connectionId, reqId)`；reqId 高熵；客户端 404/503 不删 Share Key；只有明确 revoked/expired 才提示重新配对。
- 本机：**只用 Unix socket 0600 in 0700**；免 transport key；只开 Consumer request/pair；删掉 loopback TCP 选项。Portal 在 Tailscale Funnel 后面，Funnel peer 是 `127.0.0.1`，loopback TCP 分不清本机 Agent 和公网。
- 公网/tailnet listener：Provider WS + remote Consumer；永远要 key；key hash/constant-time；按 key/linkId 限速；不信 `X-Forwarded-For`；Funnel peer 不触发本机 bypass。
- linkId 注册 generation/takeover：新连接替换旧连接，旧 generation 忽略/关闭；同 instanceId 重注册；不同 instance 的重复 linkId 拒绝。
- limits：8 MiB frame/body；1 MiB read（base64 后约 1.33 MiB）；list entry/path-length cap + cursor；per-link concurrency 8；byte/request budget。
- audit 只记 event/linkId prefix/key prefix/bytes/duration/result；不记 ciphertext、路径、scope、label。
- 部署在现有 Portal origin/tailnet 下，不新开 Funnel 端口；Relay 是 Portal 的新路由或独立本机服务，app 只认 URL。
- 不需要 vfsd；旧 `/vfs/provider` 和 Portal VFS 数据面禁用。

### 3.7 Scope

| Scope | 路径 | 用户标签 | 警告 |
|---|---|---|---|
| `app.data.activity` | `appdata/activity/**` | Activity history | amber |
| `app.data.groups` | `appdata/groups/**` | Groups snapshot | amber |
| `app.data.logs` | `appdata/logs/**` | App logs | amber |
| `app.data.telemetry` | `appdata/telemetry/**` | Telemetry | amber |
| `app.data.diagnostics` | `appdata/diagnostics/**` | Diagnostics | amber |
| `app.files` | `appfiles/**` | App internal files | red |
| `app.workspace` | 外部工作目录 allowlist | Working files | amber |
| `conductor.files` | `conductor/**` | Conductor files | amber |
| `conductor.trace` | `.runtime/trace.md` | API trace | amber |
| `conductor.system_prompt` | `.runtime/system_prompt.md` | System prompt | amber |
| `conductor.conversation` | `.runtime/conversation.md` | Chat transcript | red |
| `user.selected` | `selected/**` | User-selected files | 单独 picker |
| `all` | 目录 scope 的并集 | Everything | red 并集 |

- `app.files` 只暴露 `filesDir`，不暴露 `noBackupFilesDir`；`vfs/` 在每个 base 都排除，canonical path 检查。警告列出真实路径：`configuration.json`、`configs/` + profile credentials、Tailscale state、logs、MCP token 文件（API 24+ 加密）、conversation/system prompt。
- `app.workspace` 是 allowlist：排除 `conductor/` 和 `.runtime/`；`.runtime/*` 只能通过三个 `conductor.*` scope 读。当前 `workdir` 覆盖 `conductor/`，这是行为变化，文档和测试都要写明。
- `user.selected` 是 SAF 多选，persistable read，存 URI+metadata，`pfd` + `Os.pread` 读取，0 copy（不落 APP 存储），read-only；不在 `all` 里，避免 `all` 静默包含别处选的文档。
- 预设：**Recommended / Everything / Customize**。Recommended 是最小可用集（默认不含 `app.files`、`conductor.conversation`、`user.selected`）；Everything = `all`；Customize 展开全部 scope。技术名只出现在 Advanced/JSON。
- 每个敏感 scope 有自己的红字/黄字警告；`all` 的警告是并集，不是唯一警告。
- Provider `VfsScopedSource` 按 Share 执行；Relay 不解释 scope；Share 创建 UI 显示 exact roots。

### 3.8 App UI 与后台

- Settings → Share files：Portal connection（默认现有连接；Advanced 手动 URL/key）、Connect/Disconnect、状态。
- Shares 列表：label、scope 摘要、created、last used、状态（waiting/paired/offline）、New code、Revoke。
- New Share：名字、Recommended/Everything/Customize、per-scope 警告、Create。
- Pairing Code 页：5:00 倒计时、Copy pairing command、New code；无 QR；不进通知/状态栏；`FLAG_SECURE`；clipboard 自动清。
- 全局 Disconnect 文案「Stop sharing（all Agents）」；单 Share「Revoke this Share」。
- 后台复用 `VfsSharingService`/`VfsSharingPolicy`：FGS 活着时后台可读；进程死/Doze 后 503；不引入 always-on；Share 记录持久化，重开 App 显示 one-tap Reconnect（不静默自动连）。
- egress：Relay socket 用 `VpnService.protect()`/SocketFactory，独立于规则化出口；切规则/切网络不能断 Share。
- 旧路径移除：`VfsConnectTool`、`VfsConsent`/trust/cooldown/resume/`VfsAccessScreen`、`VfsRuntime` MCP 解析、`resolveServerForVfs`、`/vfs/provider`。

### 3.9 CLI

- 安装：Portal `pnpm service-install` 装到 `~/bin/wonder-vfs`；提供 Claude Code skill / CLAUDE.md 片段，告诉 Agent CLI 存在、命令、App 路径；App 复制完整 pair 命令。
- 命令：
  - `wonder-vfs pair --link <linkId> --code <code>`
  - `wonder-vfs list [path] --json [--cursor]`
  - `wonder-vfs stat <path> --json`
  - `wonder-vfs read <path> [--max-bytes 65536] --json`（preview）
  - `wonder-vfs get <path> --out <file>`（全文件，带进度）
  - `--share <label|id>` 多 Share 时选择；只有一个时默认。
- 默认 `--json`；`--pretty` 给人看；默认本机 Unix socket；远端 `--relay/--api-key` 后置/Advanced。
- Share Key 按 Share 存 keychain/0600；不打印、不进 argv/env/日志/错误；Pairing Code 在错误中脱敏。
- 未配对错误要教用户去 App 的路径；list 带 cursor；read 默认 64 KiB preview；大文件用 `get --out`，提示可能数分钟；错误可行动。
- 模型只看到一次性 code 和结果；CLI 保存 key；Agent 宿主被信任。

### 3.10 错误目录

| 情况 | 文案/动作 |
|---|---|
| 未配对 | 去 WonderSing → Settings → Share files → New Share，拿 pairing command。 |
| code 过期 | code 有效期 5 分钟；请用户 New code。 |
| code 错误 | 检查 16 字符/分组；仍失败请 New code。 |
| Provider 离线 | 手机离线或 sharing service 停了；打开 WonderSing，确认 Share files 为 Connected，再重试。 |
| Share revoked | 该 Share 已撤销；请新建 Share，不要复用旧 key。 |
| Portal 不可达 | 检查 Portal；本机用默认 socket；远端检查 `--relay`。 |
| 限速 | 等 N 秒；或 New code。 |
| 大文件 | 用 `get --out`；可能数分钟。 |

### 3.11 重启、新手机、多 Agent

- Relay 重启：App 重连并重注册 Share；CLI key 不变；pending 请求重试；不重配对。
- App 重启/进程死亡：Share 记录持久化；重开 App 显示 one-tap Reconnect；不静默自动连；CLI key 不变；不重配对。
- 新手机/重装：Share 不迁移；新建 Share、撤销旧 Share；CLI 重新配对；旧 key 失效。
- 多 Agent：一 Agent 一 Share；CLI 按 Share 存多把 key，`--share` 选择；上限 8；超出提示。

### 3.12 旧路径退役与文档迁移

- App：移除 `VfsConnectTool` 注册；移除 `VfsConsent`/trust/cooldown/resume/`VfsAccessScreen`；`VfsRuntime` 不再解析 MCP；删除 `resolveServerForVfs`；禁用 `/vfs/provider`。
- Portal：退役 `vfs_link_request`/`vfs_connect`/`vfs_export`/`job_run(vfs=true)` 的 VFS 分支；vfsd 数据面不再使用；容器 `/vfs` 挂载退役；旧 Link/trust 清理。
- 文档：`clawdy-portal/VFS.md` 改成新的 Relay/pairing/CLI 契约；`agent-oriented-vfs.md`、`jobs.md` 的 `/vfs` 段落标退役；`AGENTS.md` 的 VFS MCP 工具清单更新；WonderSing `vfs-dry-run.md`、`vfs-connection-stability.md`、`mcp.md`、`agent-control-plane.md` 改成新路径或标退役；App strings 全部更新（不能只留旧 consent 文案）。
- AO 的 Activity/Groups 分散导出：Share Key VFS 真机验收后移除，避免同一版把唯一出口换掉；不在本设计的第一版混入。

## 4. 实现方案

### 4.1 阶段

- **P0**：评审并冻结本文档；不改代码。
- **P1 App provider core**：Portal auto-provision/Relay config；Share store；pairing；AEAD/HKDF；scope allowlist；FGS 复用；Settings/Share UI；移除旧路径；JVM 测试。
- **P2 Portal/Relay**：Link 路由/注册 generation；pairing；Unix socket；公网 auth；202+poll；limits/audit；旧路径禁用；契约测试。
- **P3 CLI**：`wonder-vfs` 安装；pair/list/stat/read/get；key store；错误；Claude Code skill；测试。
- **P4 E2E**：真机 + Mini + Agent；Relay 只见密文；revoke/offline/restart；release AP；AO 导出移除放到验收后。
- **P5 文档/清理**：Portal/WonderSing 文档改写；旧工具/strings/tests 删除；TODO 收尾。

### 4.2 数据模型

- App Share：`shareId,label,linkId,secretCiphertext,scopes,instanceId,createdAt,lastUsedAt,expiresAt,revokedAt`；`noBackupFilesDir/vfs/` 加密；不进 telemetry/export。
- Pairing code：`shareId,codeHash,expiresAt,attempts,consumedAt`；单 active；内存/加密存储。
- Relay link：`linkId,connection,instanceId,generation,registeredAt`；内存；无 secret/scope/label。
- Response cache：`(consumerKeyId|connectionId, reqId) -> encrypted response,expiresAt`；60s。
- CLI key：`shareId,label,linkId,secret`；keychain/0600；按 Share 分开。

### 4.3 API 契约

- Provider WS：`GET /v1/provider`，`Authorization: Bearer <Provider key>`；`register {linkId,instanceId,generation}`、`unregister {linkId}`、`request/response` envelope、ping/pong。
- Consumer：`POST /v1/request`，body `{v,op,linkId,reqId,ts,iv,ct}`；本机 Unix socket 无 auth；远端 `Authorization: Bearer <Consumer key>`；202 `{reqId}`；`GET /v1/response/{reqId}` long-poll ≤30s。
- 状态：401 auth、404 unknown/revoked、410 revoked/expired（明确）、429 rate、503 offline + Retry-After、413 too large。
- Pairing：`op:"pair"` envelope；响应同 reqId；错误码同表；客户端 404/503 不删 key。
- 限制：8 MiB frame/body；1 MiB read；list cap/cursor；per-link concurrency 8；高熵 reqId 128-bit。

### 4.4 App 改动

- 新增：`VfsRelayConfig`（auto-provision/Advanced）、`VfsRelayClient`、`VfsShareStore`、`VfsPairing`、`VfsEnvelopeCrypto`、`VfsRelayManager`（一 WS 多 Link）、`AppDataVirtualDirectorySource`、`UserSelectedVirtualDirectorySource`。
- 改写：`VfsRuntime`（去 MCP，接 Relay manager）、`VfsConnectionManager`（单 WS/多 Link，或拆成 Relay manager）、`VfsProviderProtocol`（envelope request/response）、`VirtualDirectoryProtocol`（复用 VFS frame）、`VfsScopedSource`（allowlist/新 scope）、`WorkspaceVirtualDirectorySource`（排除 conductor/.runtime）、`InternalFilesVirtualDirectorySource`（filesDir only、vfs/）、`AppVirtualDirectorySource`（appdata/selected）。
- 删除：`VfsConnectTool`、`VfsConsent`、`VfsTrust`、`VfsCooldown(Store)`、`VfsResumeStore`、`VfsServerBase`、`VfsLink`/`VfsLinkParser`/旧 `VfsScopeAliases`、`VfsAccessScreen`、旧 `VfsProviderProtocol` server-hello、`OkHttpVfsProviderConnector` 旧 Portal 逻辑。
- UI/strings：Settings → Share files；Shares；Pairing Code；warnings；global Stop；human labels；translations。
- egress：`VpnService.protect()` SocketFactory；测试 per-app include/root/proxy/rule switch。
- 测试：crypto vectors、pairing、scope matrix、Share store、FGS/background、旧路径不可达。

### 4.5 Portal/Relay 改动

- 新增 Relay 模块（可放在 `server.mjs` 路由或独立本机服务）：Provider WS、link register/generation、pairing、consumer 202+poll、response cache、Unix socket、public auth、limits/audit。
- 退役旧 VFS：`vfs_link_request`/`vfs_connect`/`vfs_export`/`job_run(vfs=true)` VFS 分支；vfsd transport；旧 `/vfs/provider`；文档。
- 部署在现有 Portal origin/tailnet 下，不新开 Funnel 端口；Provider key/Consumer key 存 hash、constant-time、限速。
- 测试：pairing、link takeover、404/503、local socket vs Funnel、malicious relay、audit no plaintext。

### 4.6 CLI 改动

- 新增 `wonder-vfs`（Node/Go；优先 Node 便于 Portal `service-install`）；安装到 `~/bin`；Claude Code skill/CLAUDE.md 片段。
- 命令/JSON/key store/errors 按 §3.9/§3.10；本机 Unix socket；远端后置。
- 测试：pair、key storage、read preview/get、list cursor、multi-Share `--share`、no key/code in logs、error copy。

### 4.7 测试

- Crypto：RFC 5869 HKDF、NIST AES-GCM、Kotlin/Node 交叉、AAD tamper、nonce/reqId replay、response swap、clock skew、API 24 存储。
- Pairing：5 分钟、单次、attempts、invalid-tag DoS、concurrent redeem、lost response/retry、New code、client restart、reads-before-pair、code/key not in logs。
- Relay：malicious swap/replay/reorder/drop、401/404/429/503、body/frame/list caps、restart/reconnect、duplicate linkId takeover、local socket vs Funnel、audit grep。
- App：share create/copy/revoke/New code、scope matrix（`vfs/` 不可达、`.runtime` 不经 `app.workspace`、`all` 警告、per-scope 警告）、background/Doze/process death、egress switch、旧路径禁用、key/code 不进 telemetry/export/screenshot/clipboard/notification。
- E2E：真机 + Mini + Agent；读已知文件；Relay 只见密文；revoke/tamper/offline；overnight；large files；multi-device；容器 `/vfs` 退役记录。
- UX：新用户 5 步完成；术语理解；CLI `--help`/错误可行动；`all` 警告清楚。

### 4.8 发布与回滚

- 顺序：Portal/Relay + CLI 就绪 → App AP（P4）→ 真机/Agent 验收 → 移除 AO 导出 → 文档收尾。
- 发布门：E2E 通过；Relay 日志无明文/key/code；scope matrix 通过；旧路径禁用；`./scripts/local-test.sh`；`./scripts/e2e-emulator.sh`；CI Test/Release。
- 回滚：App 回滚到旧版会失去新 Share；Portal/Relay 回滚到旧 VFS 会恢复旧路径；旧路径退役是不可逆产品决定，发版前确认。No force push/tag move.
- 下一个 App label 若无其他改动是 AP；版本规则见 [`versioning.md`](versioning.md)。

### 4.9 风险

- Pairing Code 在聊天/模型上下文；一次性/5 分钟；Agent 宿主可读 CLI key store；不要过度宣称。
- `all` 敏感文件暴露；红字警告 + 用户确认；`vfs/` 硬排除；真实路径清单要准确。
- 可用性：FGS/Doze/进程死亡；不承诺 always-on；503 要可行动。
- 无 PFS；未来 X25519。
- Relay 元数据；不记内容/路径/scope/label。
- CLI 安装/发现；skill/CLAUDE.md/App 复制命令；否则 5 步不成立。
- Provider key auto-provision 依赖现有 Portal 连接；无则 Advanced 手填。
- 文档迁移是重写，不是 append；旧文档/strings/tests 必须一起改。
- API 21 `otherLegacy` 隐藏 Share；默认 minSdk 24 可用。
- Cap 8；溢出提示；不静默驱逐。
- 大文件 1 MiB read 25–28 秒/MB；CLI preview + `get --out`；不要默认把大数据塞进模型。

## 5. 验收标准

- Agent 用 CLI 读取 App 的选定 scope，无需手动导出/容器。
- Pairing Code 5 分钟、一次性；长期 Share Key 不打印、不进聊天；CLI 按 Share 保存。
- Portal 流量只有密文；日志无 key/code/路径/内容；元数据符合 §2.2。
- Revoke 立即断；New code 可重配对；Provider 离线 503；Relay/App 重启不重配对；新手机新 Share。
- `all`/`app.files`/`conductor.conversation` 警告可见；`vfs/` 不可达；`.runtime` 不经 `app.workspace`；`user.selected` 不在 `all`。
- 旧 `/vfs/provider`/vfsd/`job_run(vfs=true)`/`VfsConnectTool` 禁用；文档/strings 更新。
- 测试/证据：crypto vectors、malicious Relay、scope matrix、真机/Agent E2E、background/Doze、egress switch、key/code leak scan。

## 6. 评审记录与变更

- 安全/协议评审：sound with changes；2 个 blocking 已定：容器 `/vfs` 退役；一 Agent 一 Share（linkId/secret 在 Share 创建时生成，一条 WS 多 Link）。
- 简洁性/易懂性/易用性评审：概念 4/5、易懂 3/5、易用 3/5；0 blocking；5 个默认已记录：Provider auth 复用/自动发放；16 字符 Pairing Code + 完整命令；CLI 本机预装 + skill；New code/Revoke（砍 rotate）+ per-Share key + `all` 不含 `user.selected` + per-scope warning；手机侧 Conductor VFS 退役。
- 本文档 v3 已合并上述修正；待新的文档评审。
