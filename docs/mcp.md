# Conductor MCP 客户端

wonder.44 起，Conductor 可以作为 MCP 客户端，连接用户自己添加的远程服务器。App 不对外暴露 MCP。

## 怎么用

设置 → Conductor → MCP 服务器。

填写名称、`http` 或 `https` URL、可选的 Bearer token，然后点「添加并连接」。App 立刻做 `initialize`、`notifications/initialized`、`tools/list`。成功才算打开，行上显示工具数量。失败则开关回到关，错误留在这一行，不进对话。

已保存的服务器可以再打开、刷新或移除。移除会删掉保存在这台设备上的 token。这一版不能编辑，改 URL 或 token 就移除再加。

下一句 Conductor 消息开始，Agent 能发现这些工具。路由模式仍然只发送 `route_call` 和 `search_tools`。模型用 `search_tools` 拿参数 schema，用 `route_call` 调用。查某一个服务器时搜一次服务器名，不要每个工具搜一次。工具名完全匹配时只返回那一个工具。

对话里输入 `/mcp` 看当前服务器和工具：短名、一句说明、必填参数、会不会先问你。`/mcp knocks` 按名字过滤。这是本地命令，不发给模型，也不把 schema 写进对话上下文。Claude Code 的 `/mcp`、Cursor 的 MCP 面板也是这样：给人看服务器和工具名，不把整份 schema 倒进聊天记录。

## 协议

只做 Streamable HTTP，协议版本 `2025-06-18`。没有 stdio，没有 OAuth，没有 prompts、resources、sampling。

请求带 `Accept: application/json, text/event-stream`、`MCP-Protocol-Version`，以及服务器返回的 `Mcp-Session-Id`。有 token 时加 `Authorization: Bearer`。两种响应都认：单个 JSON，或 SSE 里按 JSON-RPC id 匹配的 `data:`。

`404` 会重新 `initialize` 一次。响应超过 512KB 直接失败。连接超时 15 秒，读取超时 30 秒。不跟随重定向，避免把 token 送到别的主机。

这一版只读这一次 POST 的响应（JSON 或响应体里的 SSE）。不另开 GET SSE 流。服务器需要在 POST 里直接返回结果。

最多 8 个服务器。每个服务器最多取 64 个工具。

## 工具怎么进 Agent

本地名字是 `mcp_<服务器 id>_<工具名>`，避免和 `route_call`、`search_tools` 以及内置工具撞名。分类是 `mcp`，不是 core，所以名字出现在 roster 的分类行里，参数不写进系统提示。schema 用服务器的 `inputSchema`。

参数不走内置工具那套「只能是字符串」。本机只检查 schema `required` 里的键：缺了、是 null、或是空字符串，就拒绝。其余类型交给服务器。

`annotations.readOnlyHint == true` 的工具不弹确认，用来确认 Agent 能自己把调用做完。其他工具是 `AGENT_ONLY`，先确认再发出。权限是 `DATA_EGRESS`。输出仍然过现有 sanitizer，所以结果里的订阅链接和 token 会被打掉。

没有激活的 MCP 工具时，系统提示和静态 `tools` 与原来逐字节相同。工具列表在每轮开始时如果空了，或超过 60 秒，会刷新。刷新失败沿用上一份列表，不让这一轮失败；还没有成功过的列表也不会每条消息都重试。状态留在设置行上。Stop 会断开正在进行的 MCP 连接。

## 网络

请求走手机当前网络。VPN 开着就走 VPN。这一版不绑定底层物理网卡。

Android 默认禁止明文 HTTP。表单接受 `http://`，方便写局域网地址，但系统可能直接拒绝。给服务器用 https。

## 存放

服务器列表在应用私有文件 `conductor/mcp-servers.json`。token 只在这个文件里，不进日志，不进工具 schema，不出现在设置列表里。

## 验证

JVM 单测起一个本机 MCP HTTP 服务器：打开后 `tools/list` 注册工具，`search_tools` 带回 schema，`route_call` 打到 `tools/call`，观察里有服务器返回的文本，请求带了 `Mcp-Session-Id`。只读工具直接执行；非只读工具在确认前不发请求。

设置页没有真机验证。
