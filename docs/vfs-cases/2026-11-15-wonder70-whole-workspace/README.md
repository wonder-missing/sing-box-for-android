# 历史案例：wonder.70 一句话挂载全量工作目录

- 案例 ID：`2026-11-15-wonder70-whole-workspace`
- 状态：冻结，不再修改。以后系统提示词或工具描述变化，用同一提示词复跑并和这份基线对比。
- 记录方：App 侧；材料由 Portal 战役指挥部整理。
- 场景：用户在 Conductor 里用一句自然语言，让 App 把工作目录挂到 Portal，供 Portal 上的 Agent 分析。
- 目的：冻结一次“模型驱动全量 scope”的成功样本，作为之后提示词或工具描述漂移的回溯基线。

## 版本与环境

- App：`1.15.0-alpha.13-wonder.70`
- App 源：`79adb77`（dev `6216254`）
- APK：`~/Downloads/vfs-dry-run-apk/wonder.70/SFA-1.15.0-alpha.13-wonder.70-arm64-v8a.apk`
- APK 大小：34,711,157 字节
- APK SHA-256：`3bfd6e129ab9c0493124a50f9ecf52377e77930bc48fd6924f9ff5c838d54ab7`
- 模型：`deepseek-v4-flash`
- Portal 代码：`a587ef0`（抓取时仓库 HEAD `49bf7d3`，之后只有文档提交）
- vfsd：`9e98497`（provider lastSeen 过期快速失败）
- Link：`l_538b15cd14e5658a73416f85d68908f3`，版本 1，scope 为 `conductor`、`trace`、`system_prompt`、`conversation`
- provider：`p_f20d859986105800`，只读挂载
- 用户设备：Samsung SM-F741U，Android 15

## 用户原话

```text
你帮我把 APP 这边的工作目录挂到 Portal 上去，我让 Poral 上的 Agent 分析一下
```

原文里的 `Poral` 是语音或输入笔误，不是实验变量。模型按 `Portal` 理解并继续。

## 工具链

本轮没有工具报错，也没有出现 scope 反问。实际顺序：

1. `search_tools`，query `vfs mount workspace portal connect`，返回 5 个弱匹配。
2. `search_tools`，空 query，返回 27 个工具的目录。
3. `search_tools`，query `mcp_clawdy_portal_knocks_vfs_link_request`，拿到参数 schema。
4. `mcp_clawdy_portal_knocks_vfs_link_request`，`scope` 显式四项。
5. `search_tools`，query `vfs_connect`，拿到本地工具 schema。
6. `vfs_connect`，传入 Link，App 弹一次授权，连接成功。
7. `file_list`，空路径，读取 App 本地工作目录根。

没有 `docker_run`，没有 `diagnostics_export`。本轮 Portal Agent 读取用的是 vfsd 挂载，不是 Conductor 的容器。

## 关键结果

`vfs_connect` 返回：

```json
{
  "connected": true,
  "issuer": "https://clawdy-mini.tailf665f5.ts.net",
  "scope": ["conductor", "conversation", "system_prompt", "trace"],
  "visibleRoots": [
    ".runtime/conversation.md",
    ".runtime/system_prompt.md",
    ".runtime/trace.md",
    "conductor/**"
  ],
  "expiresAt": 1795969143360
}
```

实际 `/vfs` 根：

```text
/vfs
├── .runtime
└── conductor
```

实际运行时文件：

- `.runtime/conversation.md`：22,002 字节
- `.runtime/system_prompt.md`：6,431 字节
- `.runtime/trace.md`：435,622 字节

App 本地 `file_list` 还能看到 `crash_reports`、`Taildrop`、`tailscale`、`cache.db`、`CrashReport-Application.log`、`PlatformMetadata-Application.json`，但这些不是 VFS 可见根，Portal Agent 读不到。`/vfs` 下已实测全部隐藏。

## 基线判定

一次合格的复跑应满足：

1. 同一句自然语言不要求用户补 scope 名，模型自己请求完整 scope。
2. `vfs_link_request` 成功，Link scope 覆盖 App 当前定义的全部可见根。
3. App 只弹一次人类可读的授权，不反问内部 scope。
4. 工具链没有 `VFS_*` 错误、没有 `search_budget_exceeded`、没有 scope 缺口的用户往返。
5. `vfs_connect` 成功，且返回正确的 `visibleRoots`。
6. 最终回答的可见范围必须与 `visibleRoots` 或 `/vfs` 实测一致，不得把 App 私有目录说成 Portal Agent 能读。
7. 运行时文件可按需生成，`trace`、`conversation`、`system prompt` 三份都可达。

## wonder.70 的已知偏差

这次核心挂载成功，但 Conductor 最终回答有一处可见范围误报：它把 `crash_reports`、`Taildrop`、`tailscale`、`cache.db` 等 App 私有目录说成 Portal Agent 都能读。原因是它拿 App 本地 `file_list` 的根当成了 VFS 可见根，没有使用 `vfs_connect.visibleRoots` 做最终陈述。

这条不算挂载失败，但算答述失败。后续修复方向是工具描述或系统提示澄清“本地工作目录不等于 VFS 可见根”，不是放开私有目录。

另一个非错误摩擦：provider 在 00:22:37 和 00:25:53 退后台，Portal 抓取从 00:27:04 等到 00:29:47 才成功。Portal Agent 读取仍依赖 App 前台。

## 漂移对比方法

1. 用上面同一句用户原话复跑，保持 App、Portal、vfsd 版本记录。
2. 保存新的 `.runtime/trace.md`、`.runtime/conversation.md`、`.runtime/system_prompt.md`。
3. 对比：
   - scope 是否仍为 App 当前定义的全集；
   - 是否出现工具错误、scope 反问、额外授权；
   - `vfs_connect.visibleRoots` 是否正确；
   - 最终回答是否仍误报私有目录；
   - provider 前台依赖是否改善或恶化。
4. 工具调用顺序和搜索 query 可以不同，不作为失败；上面的基线判定才是标准。
5. 本案例是 v1 Link、`.70` 的历史基线。Link v2 和 `.71` 之后另建新案例，不要直接用这份基线判 v2 失败。

## 文件

- `trace.md`：完整 API trace，含 System Prompt 和各轮请求。
- `conversation.md`：Conductor 对话导出，含工具调用和最终回答。
- `system-prompt.md`：本轮完整 System Prompt。
- `metadata.json`：机器可读的版本、Link、工具序列和基线字段。
- `actual-vfs-roots.txt`：Portal 侧实测的 `/vfs` 根和隐藏私有目录。
- `manifest.sha256`：三份原始材料的 SHA-256。

材料只在本机 `~/notes/vfs-cases/`，不含 MCP token、Portal 会话或飞书凭证。Link 本身不是密钥。
