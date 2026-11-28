# Conductor 资源与传输改造方案

状态：已收敛，进入实现。目标版本：wonder.AB 起。

> 2026-11-28：旧 MCP VFS 路径已退役，本文中的 `/vfs/...` 容器路径、`vfs_connect` 和 VFS scope 段落只作历史记录。本地文件、inbox/artifacts 和 MCP content-block 管道仍有效；Share Key VFS 见 [`vfs-share-key.md`](vfs-share-key.md)。

## 1. 目标

让 Conductor 的工具可以组合，而不是为单个场景增加专用工具。

- 输入文件零拷贝：本地工具读原始位置，VFS 读虚拟映射。
- 输出文件一份：Portal job 产物由 App 物化到手机，成为本机唯一副本。
- 模型只看到路径、handle 和元数据，不接触字节、base64 或主机路径。
- 不新增 VFS scope；全部路径在现有 `conductor` scope 下。
- 标准优先：MCP content blocks、`resource_link` 直接使用，不自定义传输字段。

本次改造解决 2026-11-20 A9 会话暴露的问题：43 次模型请求、44 次工具调用、8 分 10 秒，以及一次把 43 KB 产物经公共图床搬回手机的绕路。

## 2. 路径与资源约定

| 路径 | 性质 | 用途 |
|---|---|---|
| `conductor/resources/inbox/<id>/<name>` | 虚拟、只读 | 聊天附件、`request_files` 选中的文件 |
| `conductor/resources/artifacts/<run>/<name>` | 物理、可读 | job 产物、MCP 二进制物化、大文本 offload |
| `/vfs/conductor/resources/inbox/...` | 容器视图 | Portal job 读取输入 |
| `/vfs/conductor/resources/artifacts/...` | 容器视图 | Portal job / 容器读取已落盘产物 |

- `<id>`：聊天附件使用 `AgentImageRef.id`，`request_files` 使用临时 handle。
- `<name>`：清洗后的原文件名；没有名字时按 MIME 生成，例如 `image.jpg`。
- 不再引入 `handles`、`grants`、`selected`、`attachments` 等新 scope 或新虚拟根。

## 3. 输入链路（零拷贝）

### 3.1 聊天附件

1. `ConductorViewModel` 在发送带图消息时，把每个 `AgentImageRef` 注册到进程级 `InboxRegistry`。
2. 注册来源是 `ConductorImageStore` 已保存的真实文件，不复制。
3. 模型看到的 user message 追加一行元数据：

```text
[runtime:attachment id=<id> path=conductor/resources/inbox/<id>/<name> mime=<mime> size=<bytes>]
```

图片仍作为 image content 发给视觉模型；元数据只给工具和 VFS 用。UI 保留原始用户文本。

### 3.2 `request_files`

1. 用户在系统选择器选中文件后，`ConductorViewModel` 注册 `InboxEntry`，来源是 content URI，不复制。
2. 工具结果：

```json
{
  "ok": true,
  "tool": "request_files",
  "output": {
    "cancelled": false,
    "grant": "temporary",
    "files": [
      {
        "id": "<handle>",
        "name": "HTebv5DXgAAvWoj.jpg",
        "size": 161935,
        "mime": "image/jpeg",
        "path": "conductor/resources/inbox/<handle>/HTebv5DXgAAvWoj.jpg"
      }
    ]
  }
}
```

`path` 是逻辑地址，不是 `vfsPath`；VFS 是否挂载由消费者决定。

### 3.3 VFS 虚拟 inbox

新增 `InboxVirtualDirectorySource`，挂到 `ConductorVirtualDirectorySource` 的 `conductor/resources/inbox/**` 前缀：

- `stat` / `list` / `read` 从 `InboxRegistry` 的原始 source 读取：
  - 聊天附件：`ConductorImageStore` 的真实文件；
  - 选中文件：content URI 的输入流。
- `read(offset, length)` 对真实文件走 `FileChannel`；content URI 优先用 `openFileDescriptor` 定位，不能 seek 时退回 skip。
- 只读：provider 没有写操作；本地 `file_write`/`http_fetch`/`file_edit` 写入 `conductor/resources/inbox/**` 会被拒绝，虚拟条目不会被物理文件遮挡。
- 条目失效或授权过期使用现有的 `NOT_FOUND`（隐藏而不是 forbidden），不新增协议错误码。
- `conductor/resources` 的列表会合并虚拟 `inbox/` 目录；不存在的物理目录不报错。
- **`workdir` scope 不能读 inbox**：`VfsScopedSource` 对 `conductor/resources/inbox/**` 要求 `CONDUCTOR` scope，避免已有 15 天信任的 workdir Link 静默读取聊天附件或选中的内容 URI。`conductor` scope 仍覆盖该路径。

### 3.4 本地读取边界

- 本地工具继续用 handle/id，不解析虚拟 inbox：
  - 聊天图片：`analyze_image(file_id=<imageId>)`；
  - `request_files` 文本：`file_read_selected(id)`；
  - `request_files` 图片：`analyze_image(file_id=<handle>)`；
  - 工作区文件：`file_*`。
- 本地 `file_list` 不显示虚拟 inbox。
- 模型若用 `file_read` 打开 inbox 路径，返回明确提示：使用 `file_read_selected` 或 `analyze_image`。
- UI 不渲染虚拟 inbox；聊天附件已在用户消息中显示。

## 4. 输出链路（唯一副本）

### 4.1 Portal `job_file`

`job_file` 返回标准 MCP content blocks：

| 情况 | 返回 |
|---|---|
| 文本（≤ 1 MiB） | `{"type":"text","text":"..."}`（保持现有 JSON 文本形态） |
| 小二进制（≤ 1 MiB） | `{"type":"resource","resource":{"uri":"job://<jobId>/<path>","mimeType":"...","blob":"<base64>"}}` |
| 大二进制或大文本（> 1 MiB） | `{"type":"resource_link","uri":"https://<portal>/job-output/<token>/<name>","name":"...","mimeType":"...","size":123}` |
| 错误 | `isError: true` 和 text |

- 二进制结果额外带一个短 `text` block 作为旧客户端兼容提示；旧 App 会看到提示而不是空成功，新 App 同时物化 artifact。
- 工具结果包装器允许 `content` 数组透传，不再统一包成 `{type:"text"}`。
- MIME 检测：扩展名 + 简单魔数，未知用 `application/octet-stream`。
- `resource_link` URL：HTTPS、与 MCP server URL 同 origin、短时 TTL 10 分钟、token 绑定 job/knock、不可列目录、不跟随重定向。
- 更新 `job_file` 描述，说明三种结果和 1 MiB 分界。
- 不需要 VFS scope 改动。

### 4.2 App MCP 解析与物化

`McpProtocol` 解析全部 content blocks：

| block | App 动作 |
|---|---|
| `text` | 拼接；超过 24k 字符由 `AgentRuntime` offload |
| `structuredContent` | JSON；超过 24k 字符同样 offload |
| `image` / `audio` / `resource`(blob) | base64 解码，≤ 1 MiB，物化 |
| `resource_link` | 流式下载，≤ 64 MiB，物化 |
| `isError` | 结构化错误，不物化 |

物化规则：

- 目标路径：`conductor/resources/artifacts/<run>/<name>`；如果结果 JSON 含 `jobId`，`<run>` 使用 jobId，否则使用 `<tool>-<timestamp>`。`<run>` 只做路径 sanitize，不追加文件扩展名。同一 run 的重名 artifact 追加 `-2`、`-3`。
- 原子写：临时文件 + rename；大小、MIME/魔数校验；替换时不先删除目标，优先直接 rename，失败再回退。
- `resource_link` 校验：HTTPS、与配置的 MCP server URL 同 origin、不跟随重定向、不带 Authorization header、流式下载上限 64 MiB、可取消。
- 模型看到的 observation：

```json
{"path":"conductor/resources/artifacts/<run>/<name>","size":43395,"mime":"image/jpeg","sha256":"..."}
```

- base64 不进入 transcript；物化发生在构造 tool observation 之前。
- `McpHttpTransport` 的单次响应上限从 512 KiB 提高到 2 MiB，以容纳 1 MiB embedded resource 的 base64 开销；超过 1 MiB 的内容走 `resource_link`。

### 4.3 产物读取

- 本地 `file_*` 可直接读写 artifacts 下的真实文件。
- `analyze_image` 增加 `path=`，支持 `conductor/` 下的 workspace/artifacts 路径。
- UI 的本地图片和链接共用 `conductorLocalResourcePath`：接受 `conductor/resources/...`、`resources/...`、`/conductor/resources/...`、`/resources/...`、`./conductor/resources/...`。`resourceFile` 必须先经过该归一化，不能把原始 URL 直接交给 `ConductorFileStore`。
- `ensureWorkspace()` 预先创建 `conductor/resources/artifacts/`，本地 `file_list` 在第一个产物前返回空目录而不是 `Not a directory`。

## 5. 工具结果契约

- 输出由契约负责有界；旧的历史 compaction 保留为极端兜底，不作为日常机制。
- 空查询 `search_tools` 返回紧凑目录：名字 + 分类 + server/namespace + 80 字符以内的一行说明，不带 `parameters` 和 `requiresConfirmation`，目标 < 8k；详细 schema 和确认信息按名字查。
- 非本地文件工具（排除 `file_*`）的 observation 超过 24k 字符时，完整 JSON 写入
  `conductor/resources/artifacts/tool-output-<tool>-<timestamp>.txt`，模型收到路径、字节数和 1,000 字符预览。
- 二进制结果按第 4 节物化。
- `compactOldObservations` 继续作为长会话的兜底；不删除。验收按“不超过 24k 的正常工具结果”执行。

## 6. Prompt、consent、发现

System Prompt 增加资源约定：

- `conductor/resources/inbox/<id>/...`：共享输入，只读；本地用 handle/id，VFS 用该路径。
- `conductor/resources/artifacts/...`：产物，可读。
- `conductor` VFS scope 覆盖两者。
- 不要用 inbox 路径做本地 Markdown 图片引用；产物用 artifacts 路径。

工具描述：

- `request_files`：返回逻辑路径，不复制，不强制挂 VFS。
- `vfs_connect`：`conductor` scope 包含 inbox/artifacts，只读。
- `analyze_image`：`file_id` 支持聊天附件和选中文件；`path` 支持 workspace/artifacts，并接受 `/conductor/...`、`/resources/...`、`./...` 根相对写法。

Consent：

- `VfsScope.CONDUCTOR.label`：`Conductor files (including files you share with Conductor)`。
- 中文资源 `vfs_scope_conductor`：`Conductor 文件（conductor/，含你分享给 Conductor 的文件）`。
- 说明只读。

## 7. 安全与生命周期

- inbox 只读；远程不能写手机路径；`file_write`/`file_edit`/`http_fetch` 写入 inbox 前缀会被拒绝。
- 路径校验：id 不透明、name 清洗、禁止 `..`、禁止绝对路径。
- content URI 授权会话级；过期/失效使用现有 `NOT_FOUND`（隐藏而不是 forbidden），模型重新请求。
- 未知大小的 content URI 在选中时通过 `openAssetFileDescriptor.length` 或一次有上限的流测量补齐；仍未知则不注册 VFS 条目。
- artifacts 原子写；单文件上限 64 MiB；总量 256 MiB / 30 天清理；MIME/魔数校验。
- `resource_link` 只接受与已配置 MCP server 同 origin 的 HTTPS URL；token 绑定 job/knock；不可列目录。
- artifacts 清理：保留 30 天、上限 256 MiB；`/clear` 不删除。
- inbox registry 按会话清理；聊天附件按 image store 现有策略。

## 8. 代码落点

### App

| 文件 | 改动 |
|---|---|
| 新增 `agent/InboxRegistry.kt` | 纯 JVM registry；`InboxSource`/`FileInboxSource`；注册/清理/查找 |
| 新增 `agent/InboxPaths.kt` | 路径和文件名约定、id/name 校验 |
| 新增 `agent/ObservationOffload.kt` | 大文本 offload 的纯接口 |
| 新增 `vfs/InboxVirtualDirectorySource.kt` | `conductor/resources/inbox/**` 虚拟只读视图；未知大小/seek |
| `vfs/ConductorVirtualDirectorySource.kt` | 构造参数增加 inbox source；stat/list/read 路由与合并 |
| `vfs/AppVirtualDirectorySource.kt` / `AndroidVirtualDirectorySource.kt` | 注入 inbox source |
| `vfs/VfsScopedSource.kt` | `workdir` 不能读 inbox，必须 `CONDUCTOR` |
| `agent/AgentDataTools.kt` | `request_files` 结果增加 `path`；`analyze_image` 支持聊天 id 和 `path`；`chatImage` 注入 |
| `agent/AgentRuntime.kt` | user message 附件元数据；大文本 offload；保留旧压缩兜底 |
| `agent/AgentRuntimeEvents.kt` | 附件元数据格式化；转义 `[runtime:` 前缀 |
| `agent/mcp/McpProtocol.kt` | `McpCallResult` 增加 blocks；解析标准 content blocks；不再 8k 截断 |
| `agent/mcp/McpTools.kt` | 二进制物化、`resource_link` origin 校验、唯一命名、observation 只带元数据 |
| `agent/mcp/McpArtifactSink.kt` | 纯接口 + `FileMcpArtifactSink`；流式下载、origin/HTTPS/no-redirect |
| `agent/mcp/McpRuntime.kt` / `McpHttpTransport.kt` | artifact sink 注入；per-server origin；响应上限 2 MiB |
| `agent/ConductorFileStore.kt` | 流式资源写入、artifact 配额、`countFiles` 排除 resources、inbox 只读、原子替换 |
| `agent/AgentToolCatalog.kt` | 紧凑目录（一行说明） |
| `conductor/ConductorViewModel.kt` | 注册附件/选中文件；未知大小测量；接线 chatImage/offloadStore；consent 文案；清理 |
| `conductor/UriInboxSource.kt` | content URI 读取；`openFileDescriptor` seek 优先 |
| `res/values*/strings.xml` | consent 文案 |

### Portal

| 位置 | 改动 |
|---|---|
| `lib/mcp.mjs` `job_file` | 返回 text / embedded resource / resource_link；MIME/大小检测 |
| 工具结果包装器 | `content` 数组透传 |
| 新增短时 URL | `/job-output/<token>/<name>`，TTL 10 分钟，绑定 job/knock |
| `job_file` 描述 | 说明三种输出形态 |

## 9. 测试与验收

### 单元测试

- `InboxRegistry`：注册、替换、清理、id/name 校验、上限。
- `InboxVirtualDirectorySource`：stat/list/read、offset、EOF、只读、过期、路径穿越、未知大小。
- `ConductorVirtualDirectorySource`：`conductor/resources` 合并虚拟 inbox；物理目录不存在时不报错。
- `VfsScopedSource`：`workdir` 不能读 inbox；`conductor` 可以。
- `McpProtocol`：text / image / embedded resource / resource_link / error 的解析；多 block；1 MiB 边界。
- `McpTools`：二进制物化、无 base64 进入 observation、resource_link origin、重名 artifact。
- `AgentRuntime`：大文本 offload（排除 `file_*`）、附件元数据、`[runtime:` 转义。
- `AgentDataTools`：`request_files` 返回 path；`analyze_image` 聊天 id/path。
- `ConductorFileStore`：流式资源写入、artifact 配额、inbox 只读、`countFiles` 排除 resources、原子替换。

### 设备验收

1. 用户发送图片并提问：模型直接看到 `conductor/resources/inbox/<id>/...`，不需要 `request_files` 重选。
2. VFS job 能读 `/vfs/conductor/resources/inbox/...`，输入零拷贝。
3. `job_file` 产物经 MCP content block 回 App，落到 artifacts，UI 渲染。
4. 大文件经 `resource_link` 流式下载，不把 base64 塞进 transcript。
5. Transcript 中没有 base64；单条正常工具结果不超过 24k 字符，超过即 offload 成 artifacts 路径。
6. `conductor` scope 一次授权，consent 文案透明；`workdir` 不能读 inbox。

### 发布门

- `./scripts/local-test.sh` 全绿。
- GitHub Test CI 全绿。
- Release workflow 全绿，arm64 APK asset 存在。
- Release notify 发送飞书消息。
