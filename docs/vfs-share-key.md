# Share Key VFS v1 合同

状态：v1 合同（2026-11-26），实现门槛。协议细节、边缘状态和 golden vectors 见 [参考设计](vfs-share-key-reference.md)；参考文档不作为验收门槛，冲突时以本合同为准。

## 1. 目标与非目标

**目标**：配对的 CLI client（当前是 Claude Code Agent）在 Mini 上直接读取 WonderSing 的 VFS；App 是 Provider，client 侧只用 `vfsc` CLI，Portal/Relay 只做盲路由。用户配置一次 Relay Server；之后每个 client 建一个 Share、粘一次 5 分钟 Pairing Code。连接只为已有 Share 维持，不设手动 Connect/Stop。用户面统一叫 Link/Links；wire、存储和内部类型仍是 Share。

**非目标（v1）**：Consumer MCP、容器 `/vfs`、always-on、FCM 唤醒、PFS、多租户/跨账号、远程 Consumer、多 Portal 同时在线、Provider key 自动轮换。

## 2. 角色与不变量

| 角色 | 职责 |
|---|---|
| App（Provider） | 主动连 Portal；持有 Share Key；按 scope 读 VFS、加密响应。 |
| Client（Consumer） | `vfsc` CLI；本地 Unix socket；持有 Share Key；不做 MCP。 |
| Portal/Relay | 鉴权 transport、按 Link 路由密文、限速/审计元数据；不解析数据。 |

1. **明文只存在于 App 与 CLI**。Portal 可看 Link ID、pairId、reqId、时间、大小、Provider key/deviceSecret、操作类型；不可看文件内容、路径、scope、label、inner error。
2. **Portal 可以丢包/延迟/重排，不能解密或伪造数据**；所有数据、错误和 scope 检查结果都在 AEAD 密文内。
3. **授权在 App 逐请求执行**；`vfs/` 永久排除，`all` 不含 `user.selected`，敏感 scope 必须有红字/黄字警告。
4. **Pairing Code 一次性、5 分钟**；Share Key 不进聊天、不打印；code 不落盘。
5. **v1 信任边界**：Portal 主机与同机同用户进程可信；不宣称 Portal 宿主机被攻破仍读不到数据。

## 3. 用户流程

1. Portal 的顶级 **Relay** 页面提供 Relay URL + Relay token。Settings → **VFS Relay Servers** → **Add Relay Server**（填完整 `/relay/v1` URL + token）。**Test connection** 只验证 URL/token/device，不创建 Share，也不打开 Provider WS。
2. 首页 Dashboard 的 **VFS 卡片**同时是指示器和入口；它不依赖 VPN 服务是否运行。进入 VFS → **New Share**：名字（可选）→ Recommended / Everything / Customize → Create。创建时自动连接 Relay Server。
3. App 显示 5:00 倒计时和一行配对说明（含 Link ID 与 Pair Code）；**Copy pairing line** 复制该行（无换行）：
   `Use "$HOME/bin/vfsc" to access my shared files: Link ID=<linkId>, Pair Code=<code>.`
   `pairId` 由 code 与 linkId 用 HKDF 派生，不进入复制文本；code 只显示一次。
4. Client 运行 `"$HOME/bin/vfsc" --help`，按 help 执行 `vfsc pair --link <linkId> --code <code>`，得到可读 roots；之后 `list/stat/read/get` 不再需要 code。
5. **连接随 Share 维持**：至少一个 Share 存在时 App 自动连接并保持；最后一个 Share 撤销后自动关闭 WS。没有 Connect / Stop sharing 按钮，也没有全局暂停概念。
6. **Revoke** = 永久删除 Share；App 删除本地 secret，best-effort 通知 Relay；Relay 收到后写 tombstone，CLI 收到 410 后 `forget`。通知失败时下次自动连接重试，期间 CLI 得到 503。
7. **New code** = 同一个 Share Key，新 pairId/code；旧 code 失效，不 eject 已配对 CLI。一 client 一 Share，最多 8 个。

## 4. 最小协议

**App ↔ Portal**

- **Relay base**：完整 HTTPS API base（如 `https://host/relay/v1`），App 原样保存，不剥/补 path。
- `WSS <base>/provider`，`Authorization: Bearer <Provider key>`；一条连接复用多个 Link。
- `register/unregister {linkId, generation}` 维护 Link；`pair` 控制帧同步 pairing 状态；`request/response` 只转发不透明 envelope。
- `POST <base>/provider-keys`：用 Portal **Relay token**（`relay_...`，从顶级 Relay 页面获取）+ `deviceSecret` 换 Provider key；首次创建 `(relayId,deviceId)`，之后 issue-once。v1 只支持管理员在 Relay 页面撤销设备；不支持 rotate/自删/过期。
- `POST <base>/links`：Share 创建时先登记 `linkId` 归属，再显示 Pairing Code；未登记不 claim。
- Link store 持久化 `linkId -> (relayId,deviceId)`；Revoke 后 tombstone 保留 30 天，防复活。

**CLI ↔ Portal**

- 只开本地 Unix socket（0600）；`POST /v1/request` 返回 202；`GET /v1/response/{consumerId}/{linkId}/{reqId}` 60 秒 long-poll，超时 204。
- 公网 listener 只挂 App 的 Provider WS 和 Provider key/link 登记，**不挂 Consumer**。
- 客户端用 `(consumerId,linkId,reqId)` 关联；同一 tuple 变化 nonce/ct 视为冲突。

**Share / Pairing**

- 创建 Share 生成 128-bit `linkId`、32B `secret`、16B `shareId`；Share 存 `noBackupFilesDir/vfs/`，secret/deviceSecret/Provider key 用 Android Keystore AES-256-GCM 加密。
- `K = HKDF-SHA256(secret, salt=linkId, info="wsvfs/v1")`；每个请求 32B `clientNonce`，派生 `Kreq/Kresp`；AES-256-GCM，AAD 绑定 `v/op/linkId/reqId/clientNonce/ts/iv/direction`；timestamp ±5 分钟，nonce 去重。
- Pairing Code 16 字符 Crockford base32，大小写不敏感，5 分钟、单次；pair 响应返回 Share Key、scopes 和 roots；CLI 成功持久化 key 后才报告 connected。`pairId = base64url(HKDF-SHA256(codeASCII, salt=linkId_raw, info="wsvfs/pair/id", 16))`；App 与 CLI 各自派生，Relay 只看到公开 pairId。
- 数据操作：`stat/list/read`；路径绝对、以 `/` 为根、禁止 `.`/`..`/`//`、≤4 KiB；`list` 按 path bytewise 排序，opaque cursor 绑定 link/path/limit，页 ≤1 MiB/10k entries；`read` 单次 ≤1 MiB，带 `offset/length/etag/eof`；`get` 由 CLI 分块写到临时文件，sha256 校验后 rename。
- P0 向量（HKDF/AAD/AES-GCM/cursor/at-rest）以参考文档附录 A 为 source of truth；Kotlin/Node 必须逐字节复现。

## 5. 状态与生命周期

**Share 状态**：`waiting`（未生成 code）、`active`（code 有效）、`consumed`（已配对）、`paused`（旧状态，当前 UI 不产生）、`expired`（code 过期）、`revoked`（本地已撤销）。

- **连接生命周期**：有 active Share 时自动连接并保持；最后一个 Share 撤销/删除后关闭 WS，`desiredConnected=false`。没有用户可见的 Connect / Stop sharing。
- **Code 自然过期** → `expired`；Relay 对同 pairId 返回 `code_expired`，不返回 `share_paused`。
- **Revoke** → 本地立即拒绝新 pair/读，删除 secret；Relay tombstone 后 CLI 得到 410；失败则下次自动连接重试。
- **App 重启**：若仍有 active Share，自动重连并重新 `register`、补发 pair 状态；没有 active Share 则不连接。均不重配对。
- **Relay 重启**：ownership/tombstone 从 link store 恢复；pair 状态是内存、为空；App 重连补发；补发前请求得到 503（未注册 `PROVIDER_OFFLINE`，已注册 `PAIR_WARMING`）。
- **Provider key**：一个 `(relayId,deviceId)` 一把；丢失设备走 Relay 页面撤销；新手机新 deviceId + 新 Share。多个 Relay Server 可保存，v1 同一时刻只连一个，旧 Share 显示 offline，不自动迁移。

## 6. Scope

| Scope 组 | 内容 | 警告 |
|---|---|---|
| `app.data.*` | activity / groups / logs / telemetry / diagnostics | amber；logs 是 redactor best-effort，telemetry 是 raw |
| `app.files` | `filesDir/**`，硬排除物理 `vfs/` 与 `conductor/` | red |
| `app.workspace` | external files dir，排除 `conductor/`、`.runtime/`、保留虚拟 root | red |
| `user.selected` | SAF 多选文件，read-only，不进 `all` | picker |
| `all` | 所有固定 scope 的并集，**不含 `user.selected`**，硬排除物理 `vfs/` 与 `conductor/` | red 并集 |

Recommended = 仅 `app.data.*`；Everything = `all`；Customize = 任意固定 scope + 可选 `user.selected`。Share 创建时展开 concrete scope，之后新增 scope 不自动进入旧 Share。

## 7. 错误与验收

**用户可见错误**：`NOT_PAIRED`、`CODE_INVALID`、`CODE_EXPIRED`、`CODE_CONSUMED`、`SHARE_PAUSED`、`PROVIDER_OFFLINE`、`PAIR_WARMING`、`SHARE_UNKNOWN`、`SHARE_REVOKED`、`RELAY_UNREACHABLE`、`RATE_LIMITED`、`TOO_LARGE`、`PERMISSION_LOST`、`PROTOCOL_ERROR`。每个都要给下一步动作；未配对/暂停/撤销不得只报技术错误。

**验收（必须全过）**：

1. 真机 + Mini + CLI 读取已知文件，sha256 一致；App 只有出站连接。
2. Relay 抓包/日志只有密文和元数据，无路径/scope/label/内容。
3. Pairing Code 5 分钟、单次；New code 同 Share Key；创建 Share 自动连接、撤销最后一个 Share 自动断开；Revoke、离线、Relay/App 重启行为符合 §5。
4. 附录 A 向量 Kotlin/Node 逐字节通过；scope matrix 通过；`vfs/` 不可达；`all` 不含 `user.selected`。
5. FGS 活着时后台可读；进程死/Doze 返回 503；恢复后自动重连。
6. code、Share Key、deviceSecret、Provider key 不进日志/telemetry/通知；App 不设置 `FLAG_SECURE`，用户可主动截图；clipboard 自动清。

## 8. 明确 deferred（post-v1）

master 两阶段轮换、admin socket、Provider key 自删/外部 rotate/过期、跨 Portal pending Revoke、永久 tombstone、完整 close-code 分类、多 Portal 切换、tailnet egress、远程 Consumer、PFS、SAF 目录、`user.selected` 写操作。参考文档中这些细节保留，但不阻塞 v1。
