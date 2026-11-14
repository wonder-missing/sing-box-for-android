# 给 Clawdy Portal MCP Server 的工具层建议与一个 enhancement proposal

来自 WonderSing/Conductor 的 Agent（Claude Code），2026-11-13。结论：Conductor 的工具契约经验可以迁移；渐进披露、确认 UI、检索预算属于客户端职责，不适用于 Portal。Portal 的授权、排他创建、重名改名、docker 超时/输出上限已经比我们的工具层细，下面写能直接迁移的三点，最后附之前压下的 `annotations` 提案。

## 1. 工具元数据英文优先，中文放第二句

Conductor 现在要求模型先把用户意图翻成英文再检索工具；MCP 工具按 name/description/alias 匹配，其中 alias 只是工具名和服务名的变体，不补充能力词。描述只有中文时，常见英文 query 会弱命中甚至 0 命中：

- `run a script in a container`：`docker_run` 只有 `run` 和噪声 token `a`/`in` 命中，`script`/`container` 在描述里没有落点。
- `upload screenshot`：`file_put` 0 命中，`upload`/`screenshot` 都不在 name/description 里。

另外，Conductor 会把 MCP 描述截到 240 字符（前面还有服务名前缀），所以英文能力句要放在最前面。建议 name 不动，description 用一句英文开头，再跟中文，例如：

`Run a command in an allowlisted container image and wait for the result. 用白名单镜像运行一条命令并等待结果。`

不改协议，也不影响中文用户。

## 2. 上限进 schema，错误带字段

`command` 的 1–16 项/每项 256 字符、text 200KB、`text`/`content_base64` 二选一，现在都只在运行时检查，失败统一返回 `-32602 参数不合法`。模型不知道改哪一项，只能整轮重试。

建议：

- `inputSchema` 补 `minItems`/`maxItems`、`maxLength`（字符数，服务端仍按 UTF-8 字节校验）、`oneOf`（text/base64）、`image` 的 `pattern`；
- `validateArgs` 抛出带字段和边界的消息，例如 `command: 1-16 items`，RPC 码仍是 `-32602`。

Conductor 的注册表用 `missing_parameter` / `invalid_parameter_type` 加字段名，模型第一次就能改对。这里不需要新错误码，先把消息写具体即可。

## 3. docker_run 的调用参数要可审计

grant 授权的是「能用 docker_run」，不是「这次用哪个 image、跑什么 argv」。Conductor 的经验是：工具描述不是安全边界，真正的边界是每次调用的参数可见。建议在现有 `hook mcp` 日志里补 image 和被截断的 argv（注意别把 token 写进去）；管理页能看到最近调用更好。

另外，容器默认有 Docker 网络出口，而描述和 grants 都没提。若不打算增加授权维度，至少在 description 里写明；想收紧就默认 `--network none`，需要联网的调用单独授权。

小点：`note_create` 超时重试会多出 `-2` 副本，可在意的话加一个可选的 `request_id` 做短时幂等；`docker_run` 超时建议同时回 `timedOut: true`，与 `error: "超时"` 并存。

## 4. Enhancement proposal：`tools/list` 补 `annotations`

（之前压下的建议，现在一并提。）

现在 `toolView` 只返回 `name`、`description`、`inputSchema`，客户端不知道哪个工具只读、哪个会动文件或联网。建议加 MCP 标准的 `annotations`：

| tool | readOnlyHint | destructiveHint | idempotentHint | openWorldHint |
| --- | --- | --- | --- | --- |
| `orb_status` | true | false | true | false |
| `note_create` | false | false | false | false |
| `file_put` | false | false | false | false |
| `docker_run` | false | true | false | true |

`destructiveHint` 和 `openWorldHint` 默认都是 true，所以本地新建文件这类要显式写 false；`docker_run` 会拉镜像、跑任意 argv、默认有网络出口，标 true。代码上就是 `toolView` 多带一个 `annotations: ANNOTATIONS[name]`。

安全边界不变：annotations 只是 hint。Conductor 从 wonder.49 起所有 MCP 工具一律确认，不看 `readOnlyHint`；Portal 的 `mcpAdmit` grants 仍是唯一的执行许可。加 annotations 是让客户端能显示「只读」标签或做更细的默认值，不是让它们绕过确认。
