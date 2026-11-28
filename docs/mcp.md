# Conductor MCP 客户端

wonder.44 起，Conductor 可以作为 MCP 客户端，连接用户自己添加的远程服务器。App 不对外暴露 MCP。

## 怎么用

设置 → Conductor → MCP 服务器。

填写名称、可选命名空间、`http` 或 `https` URL、可选的 Bearer token，然后点「添加并连接」。命名空间留空时按 Name 生成；它是工具名前缀，建议填短词（例如 `portal`）。App 立刻做 `initialize`、`notifications/initialized`、`tools/list`。成功才算打开，行上显示工具数量和当前前缀。失败则开关回到关，错误留在这一行，不进对话。

已保存的服务器可以再打开、刷新、编辑名称/命名空间或移除。刷新或重连以服务器当前 `tools/list` 为准，工具定义变化会直接替换本地目录；MCP 工具仍是 `AGENT_ONLY`，每次调用前照常确认。编辑命名空间会改工具名，Agent 下一轮消息才看到；URL 或 token 仍要移除再加。移除会删掉保存在这台设备上的 token。旧配置没有命名空间时会回退到服务器 id，长名字会被截到 16 个字符（例如 `clawdy_portal_kn`）；升级后在 Edit 里填一个短前缀（例如 `portal`）即可。

下一句 Conductor 消息开始，Agent 能调用这些工具。路由模式仍然只发送 `route_call` 和 `search_tools`。模型用 `search_tools` 拿参数 schema，用 `route_call` 调用。工具名完全匹配时只返回那一个工具；搜服务器名或 `mcp` 会带出该服务器的工具。多个 exact 名用空格分隔一起搜（例如 `job_status job_file`）也按强匹配处理，不标 `weak`。wonder.51 起 system prompt 有一条静态提示：MCP 服务器可能在运行时增加工具，结论前先 `search_tools`；`search_tools` 输出带 `categories` 计数，弱匹配也能看到 `mcp` 组存在。

系统提示不写 MCP 服务器名，也不写 MCP 工具名。添加、关掉或换一套服务器，都不改变 system prompt，前缀缓存因此不受影响。人看清单用 `/mcp`。要让下一句优先用某个工具，输入 `/mcp use orb_status`（或点补全）。这会把一句普通文字放进输入框，光标留在后面，你补上要做的事再发送。这句话是 user 消息，不是 system message。Claude Code 的 slash、Cursor 的 `@` 也是把引用放进输入框。系统提示不罗列你连了哪些服务器。

开发者要直接观察工具，用 `/tool`：`/tool` 列表，`/tool <name>` 看 schema，`/tool <name> <json>` 直接执行（无参数工具要发 `{}` 才会执行）。输入 `/tool ` 会出现工具名补全；选中后面提示必填/可选参数和示例 JSON。结果打开临时 Tool console，不写会话、不进 transcript 和 API trace；确认策略与 Conductor 调工具相同。

## 协议

只做 Streamable HTTP，协议版本 `2025-06-18`。没有 stdio，没有 OAuth，没有 prompts、sampling，也没有 MCP `resources/read`；工具结果里的 embedded `resource` / `resource_link` content blocks 会解析。

请求带 `Accept: application/json, text/event-stream`、`MCP-Protocol-Version`，以及服务器返回的 `Mcp-Session-Id`。有 token 时加 `Authorization: Bearer`。两种响应都认：单个 JSON，或 SSE 里按 JSON-RPC id 匹配的 `data:`。

`404` 对 `initialize` / `tools/list` 会重新 `initialize` 一次；`tools/call` 不自动重试，避免写操作重复执行。响应超过 2MB 直接失败。连接超时 15 秒，读取超时 30 秒。不跟随重定向，避免把 token 送到别的主机。工具结果解析 MCP content blocks：`text` 进入模型可见文本；`image`/`audio`/embedded `resource`（≤1 MiB）物化到 `conductor/resources/artifacts`；`resource_link` 只允许与 MCP server 同 origin 的 HTTPS 地址，流式下载；base64 不进入 transcript。JSON-RPC error 的 `code` 会保留在异常里，工具观察输出 `jsonrpc_code`（例如 `-32602`），错误码仍是兼容的 `mcp_failed`；服务器返回的 `isError` 结果仍走 `mcp_error`。

这一版只读这一次 POST 的响应（JSON 或响应体里的 SSE）。不另开 GET SSE 流。服务器需要在 POST 里直接返回结果。

最多 8 个服务器。每个服务器最多取 64 个工具。

## 工具怎么进 Agent

本地名字是 `<命名空间>_<工具名>`，不再带 `mcp_` 前缀。命名空间在添加或编辑服务器时设置，默认取 Name 的 slug，限制 16 个字符，只允许小写字母、数字和下划线，且必须以字母开头。命名空间只是唯一性前缀，不承载语义，`cpk` 这样的短词是合法的。改名只改展示和描述；改命名空间才会改工具名，下一轮消息生效。旧配置没有命名空间时回退到服务器 id。分类仍是 `mcp`，不是 core；这些名字只留在目录里，给 `search_tools` 用，不进 system prompt。schema 用服务器的 `inputSchema`。命名空间要避开内置工具名（例如 `file_read`），内置工具优先，撞名时 MCP 工具不会注册。

工具来源不靠前缀猜：`AgentToolDefinition` 带 `serverName`（服务器显示名）和 `namespace`（前缀），MCP 工具的 description 以 `MCP server: <名字>.` 开头；`search_tools` 的每条结果（含空 query 的 catalog）也带 `server` 和 `namespace` 字段。这样即使用户把前缀改成 `cpk`，模型仍能明确知道工具属于 Clawdy Portal Knocks。前沿客户端通常把服务器编进模型可见的工具名（Claude Code 的 `mcp__<server>__<tool>`，OpenAI Agents SDK 的可选 server prefix）；这里保留短本地名，把来源放进元数据和描述，避免名字过长。

参数不走内置工具那套「只能是字符串」。本机只检查 schema `required` 里的键：缺了、是 null、或是空字符串，就拒绝。其余类型交给服务器。

App 不解释具体 MCP 工具的参数语义；`jobId` 等参数属于服务器，客户端只透传 JSON、做通用 `required` 检查和 content block 处理。某个工具是否需要前置条件，由服务器在自己的工具描述和返回错误里表达。旧 VFS Link 握手（`vfs_link_request`、`X-WonderSing-Device` header、scope 注入）已于 2026-11-28 随旧 VFS 路径移除；Share Key VFS 只走 `vfsc` CLI，不经 MCP。

所有 MCP 工具都是 `AGENT_ONLY`，先确认再发出；`annotations.readOnlyHint` 只作为服务器提示，不能绕过确认。权限是 `DATA_EGRESS`。确认卡保留 URL 的 host/path，但屏蔽 userinfo 和敏感 query。输出仍然过现有 sanitizer，所以结果里的订阅链接和 token 会被打掉。

有没有激活的 MCP 工具，系统提示和静态 `tools` 都相同。工具列表在每轮开始时如果空了，或超过 60 秒，会刷新。刷新失败沿用上一份列表，不让这一轮失败；还没有成功过的列表也不会每条消息都重试。状态留在设置行上。Stop 会断开正在进行的 MCP 连接。

## 网络

请求走手机当前网络。VPN 开着就走 VPN。这一版不绑定底层物理网卡。

Android 默认禁止明文 HTTP。表单接受 `http://`，方便写局域网地址，但系统可能直接拒绝。给服务器用 https。

## 存放

服务器列表在应用私有文件 `conductor/mcp-servers.json`。API 24+ 构建用 Android Keystore AES-GCM 加密 token 后再落盘；API 21 legacy 构建没有 Agent 入口，token 以明文保存。token 不进日志，不进工具 schema，不出现在设置列表里。

## 验证

JVM 单测起一个本机 MCP HTTP 服务器：打开后 `tools/list` 注册工具，`search_tools` 带回 schema，`route_call` 打到 `tools/call`，观察里有服务器返回的文本，请求带了 `Mcp-Session-Id`。只读工具也要确认；所有工具在确认前都不发请求。

设置页没有真机验证。
