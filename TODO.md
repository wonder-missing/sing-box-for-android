# WonderSing 待办

只放未完成事项。已完成的事项不再长期保留，见 GitHub Releases 和 git history。
新增一项加到对应优先级；做完就删掉，或在「最近完成」留一行索引。

## 现在待办

> Conductor 审查/评估：[`docs/conductor-review-2026-11-13.md`](docs/conductor-review-2026-11-13.md) / [`docs/conductor-assessment-2026-11-13.md`](docs/conductor-assessment-2026-11-13.md)。VFS 设计见 `~/workspace/clawdy-portal/docs/agent-oriented-vfs.md`；P7 见 [`docs/vfs-dry-run.md`](docs/vfs-dry-run.md)；结论见「最近完成」。

### P1 稳定性与隐私

- [ ] **Conductor 进程死亡恢复（剩余）**：进程级 ViewModel 和恢复时的 interrupted 工具卡已落地；进程死亡仍会丢进行中 turn 的 tool 卡片和未完成 assistant。下一步把运行中的 turn 快照持久化，重启后恢复成一条明确的 interrupted turn，不重跑工具。
- [ ] **`TOOL_CANCEL_TIMEOUT` 精确 1 秒**：工具级超时和 Tailscale 取消已落地；Stop 后 1 秒内未确认取消应转成 turn error，而不是等 3 秒强制结束。
- [ ] **服务重启缺口**：CFG 遥测显示两次 `service_start` 中间没有 `service_stop`；用 Conductor 的文件工具读 crash/OOM reports，或对比当时的 `tailscale_status`。排障见 [`docs/agent-runtime.md`](docs/agent-runtime.md)。
- [ ] **会话编解码移出主线程**：`ConductorSessionStore` 的 JSON encode/decode 和 `AppLog` 同步写文件仍在调用线程；先用 `ui_jank` 取证，再把 encode/save 放 IO、load 放后台。

### P2 数据面与能力

- [ ] **数据面 Phase B：Taildrop 收件**：`taildrop_inbox`、`taildrop_receive`；沿用 typed 工具 + `ALWAYS` 确认，只允许 tailnet peer。设计见 [`docs/conductor-work-plan-2026-11-13.md`](docs/conductor-work-plan-2026-11-13.md)。
- [ ] **数据面 Phase C：自省与证据**：`export_log_tail`、`self_test` 固定套件、`conductor_eval` 开关；脱敏 `diagnostics_network_history`（最近 N 条 network/service/VPN 事件），报告写工作区并可用 Taildrop 交给 Mini。
- [ ] **Provider capability 剩余**：`stream_options.include_usage`、`max_completion_tokens`、Azure 类鉴权；DeepSeek `none`/`max`、xAI `xhigh` 等不能三家共用的档位。现有矩阵见 `ModelLimits`。
- [ ] **网络诊断**：`dns_lookup(domain, via=direct|outbound)`、`route_decision(domain)`；libbox 没有 DNS/route API，先做能落地的部分。实验里模型只能从 probe 结果猜 DNS 故障。
- [ ] **`probe_via_node` 的 HEAD 歧义**：加 `http_fetch_probe(outbound, url, max_bytes)`，把 HEAD 被拒和节点坏分开；`speed_test` 后续。
- [ ] **工具参数错误带 schema 提示**：wonder.79 trace 里 `file_read_selected` 先用 `handle` 报 `missing_parameter 'id'`，模型又花一次 `search_tools` 才拿到参数名；wonder.80 trace 里 `file_grep` 又把 `query` 写成 `pattern`。`missing_parameter` / `unknown_parameter` 的错误消息应附上该工具的参数名和必填标记（MCP 透传工具用 properties/required），让模型同轮改对。80 只改了 `request_files.reason` 可选和 `file_read_selected` 文案，注册表提示未做。
- [ ] **Vision 调用可观测性**：wonder.80 trace 里 `analyze_image` 两次返回 `DeepSeek returned an empty vision answer.`，由于 `recordTrace=false`，trace 里看不到视觉请求和 provider 原始响应。改为记录一条**脱敏** trace：请求体里的 `data:image/...;base64,...` 换成尺寸/大小占位，响应体保留 finish_reason 和文本；空答案错误里带 finish_reason 和截断的 content/reasoning，便于判断是模型名、内容格式还是 provider 拒绝。81 已把默认模型改成 `deepseek-flash` 并归一化旧名，需在 81 上复测。
- [ ] **Taildrop 投递语义**：`taildrop_send` 的 `ok=true` 只表示文件进入了对端的 Taildrop inbox，不表示对端已落盘。工具描述和输出应写明「queued / receiver must accept」，避免模型说成「已投递」。Mini 侧还需要自动 drain（`tailscale file get --loop` 的 launchd agent），见「下个会话接手」。
- [ ] **批量确认：预览与分级**：整批预检合成一张卡；`Approve rest of turn` 只覆盖同工具或只读工具，`DATA_EGRESS`/MCP 写工具逐次确认。
- [ ] **工具编排计划（tool_plan / DAG）**：把有依赖的多步调用压成一次提交，支持字段绑定、`when`/`retry`/`fallback`；先只读工具。目标是减少往返和 token。
- [ ] **对话级 LLM 摘要**：现有只做旧大 tool observation 的确定性压缩；assistant/user 长文本和跨 turn 的语义摘要未做。
- [ ] **trace 长 body 与 release 驻留**：单条 body 超 200k 截断，旧 body 超 20k 先缩短；`AgentApiTrace` 在 release 默认驻留完整请求/响应，持久化文件按 2.5M 字符封顶。跨重启的完整 body 已解决，剩余的是单条上限和隐私/驻留策略。
- [ ] **连接与规则命中可观测性**：最近 N 条连接 + 命中规则 + 最终出站；需要 CommandClient 支持。
- [ ] **消息内富 Markdown 与本地文件查看**：业界约定就是标准 Markdown，不用自定义标记：链接用 `[label](url)`，文件名/路径/命令用行内代码。App 侧把 `markdownAnnotated` 升级为可点击：链接交给浏览器，行内代码点按复制；本地文件链接点按弹出只读查看器（标题、路径、内容、复制），不执行。system prompt 只加一行「用户很可能复制的值用行内代码，链接用 Markdown 链接」。需要真机确认点按/长按手感。
- [ ] **直接多模态图片输入（主路径）**：DeepSeek `deepseek-flash` 的 Chat Completions 原生支持图片输入，应把图片作为 user content parts 直接进主线，而不是再调一次 `analyze_image`。需要输入栏相册选图、预览和压缩，`AgentMessage.User` 的 text + `image_url` content parts，会话持久化把图片落到私有文件而不是 base64，API trace 对 `image_url` 做尺寸/大小占位脱敏，provider capability 的 vision 标记和文本-only 回退。先用单图跑通，再决定多图；`analyze_image` 先保留作 fallback，稳定后删除或隐藏。评估见 [`docs/agent-runtime.md`](docs/agent-runtime.md)「直接多模态输入评估」。
- [ ] **Reasoning 前置阶段**：流式请求用 OkHttp `EventListener` 区分 DNS、TCP、TLS、请求已发出、收到响应头；首字前 turn 状态行显示细分阶段，首字后回到「Receiving response」。新增 `AgentModelChunk.Stage` 和 VM 状态映射；保留 `Waiting for the provider` 作为兜底。77 验收 trace 里有两次 120 秒 `network_timeout`（17:00:53、17:02:54），正好是这条需求的证据。
- [ ] **77/78 旧 release 正文**：77 是模板，78 是 commit message；79 起已按 tag 注解生成并验证。需要单独编辑旧版本时再动，不影响新版本。
- [ ] **MCP 旧服务器前缀**：旧配置没有 namespace 时回退到 id 并截到 16，实测 `clawdy_portal_kn_orb_status` 仍然很长。给旧配置加一次性提示或在卡片上标出过长前缀，用户可在 Edit 里改成 `portal`。
- [ ] **ask_user 取消原因结构化**：工具输出增加 `reason`（`dismissed` / `timeout` / `stopped`），当前只有 `message`；实测模型无法稳定区分超时和主动取消。

### P3 低优先 / 可选 / 阻塞

- [ ] **数据面 Phase D（以后）**：短消息、通知、定时检查、收件箱监听、A → B 中继；仍只走 typed 工具 + 确认。
- [ ] **VFS 后续（可选）**：P6e 进程被杀恢复最后一条 Link；后台 provider 模式（前台服务/常驻通知），否则 Portal Agent 只能在 App 前台时读。结论见「最近完成」；其余 vfsd async/refs/30 秒 cache 归 vfs 项目。
- [ ] **probe 真正并发（阻塞）**：libbox 没有 per-request outbound binding，`probe_many` 仍顺序执行。
- [ ] **probe 链路复测（等出口健康）**：wonder.34 的 6 连失败是出口节点不健康，不是 Conductor 回归；204 分类待出口健康后复测。
- [ ] **跨版本旧 tool schema 归一**：wonder.51 已加版本 Notice/`app_updated`/导出 header；旧 tool observation 的 `ok` 等字段仍按旧语义留在 transcript。
- [ ] **设备上下文版本历史（低）**：`appVersionHistory`/`updateCount`，当前只有 `appVersion` + `app_updated`。
- [ ] **增量更新（低）**：整包 bsdiff，约 3–4 个工作日；见 [`docs/incremental-update.md`](docs/incremental-update.md)。
- [ ] **路由 A/B 真机执行**：语料见 [`docs/conductor-eval.md`](docs/conductor-eval.md)；native/routing 对比未跑。
- [ ] **确认与事务语义**：模型看不到确认事件；多步调用没有框架级事务；等 2B 流式和 Phase 3 再评估。
- [ ] **最后一条用户消息长按只有「全选」**（[#7](https://github.com/wonder-missing/sing-box-for-android/issues/7)）：已关 Compose 1.11 的新 context menu/smart selection；待真机确认 Copy 回来，再按上游修复移除开关。

## 下个会话接手

- 当前已发布 `wonder.81`（tag `v1.15.0-alpha.13-wonder.81`，commit `77ad406`）。下一次从 82 开始；tag 用 annotated tag，能说清就写 `Agent-Time`/`Agent-Loops`，说不清留空。发布用 `scripts/release.sh`。
- Mini 侧 Taildrop 收件箱不会自动落盘：Conductor 的 `taildrop_send` 只把文件放进 Tailscale inbox，headless Mini 需要 `tailscale file get --conflict=rename <目录>` 才搬出来。建议在 home 会话给 Mini 装一个 `tailscale file get --loop` 的 LaunchAgent，日志和目录写进 `~/blueprint`；本项目只负责 App 侧把结果说成 queued。
- v80 trace 复盘：`analyze_image` 空答案（模型名 `deepseek-v4-flash`，81 已改 `deepseek-flash`）；`file_grep` 误用 `pattern`（注册表 hint 待做）；`diagnostics_export` 的 `overwrite` 在 v80 trace 前半段缺失是因为那几条是 v79 会话，v80 代码和 APK 已带；`taildrop_send` 成功但 inbox 未 drain。
- 顺序建议：先做 P1 的进程死亡恢复、1 秒取消、敏感导出；再推 P2 的 Taildrop 收件和自省证据。VFS 只在出现“远端 Agent 持续读手机数据”的具体用例时再碰，不扩 background provider/bundle。
- 回归文档：Conductor [`docs/conductor-eval.md`](docs/conductor-eval.md)、VFS [`docs/vfs-dry-run.md`](docs/vfs-dry-run.md)；发布流程见 `AGENTS.md`。旧的 49–59 手机检查清单已过期，不再保留。

## 最近完成

- wonder.81（2026-11-15）：查看器性能与 DeepSeek 模型名。Raw 默认不折行，标题栏三点菜单可切 Auto wrap；Rich 改用 Markwon Recycler（后台解析 + 只渲染可见 block），行内代码/代码块用主题 surfaceContainer 背景和 onSurface 前景；底部按钮紧凑内边距和 8dp 间距。DeepSeek 默认模型改 `deepseek-flash`，旧名读取时归一化。本地 454 用例绿，release/otherLegacy 编译和 R8 通过。
- wonder.80（2026-11-15）：Conductor 图片分析与查看器。新增 `analyze_image`：从相册选一张图，按 `image_url` data URL 发给当前配置的模型，返回文本分析；5MB 上限、5 分钟工具超时、`DATA_EGRESS` 确认，base64 不写 trace。Plain Transcript / API Trace 查看器打开 Markdown 行级高亮，底部左侧 Raw/Rich 开关默认 Raw，Rich 用 `MarkdownText` 渲染。重新进入 Conductor 保持上次滚动位置。三点图标在涟漪内居中且左边缘对齐消息；答题卡选项间距回到 6dp。release 正文不再追加签名 footer，已验证 80 的正文只有 tag 消息。`request_files` 的 `reason` 改为可选，`file_read_selected` 明确 handle 放 `id`、二进制改用 `analyze_image`；Taildrop 工具提升为 core。本地 453 个用例绿，arm64 APK 已发，Feishu 通知成功。注意：图片分析依赖 provider 配置的视觉模型 ID（79 trace 里是 `deepseek-v4-flash`）。
- wonder.79（2026-11-15）：Conductor 消息流与 trace 导出。答题卡选项改用 20dp 自定义圆圈/方框，左边缘对齐题目，控制与文字留 10dp；消息流底部工具条与最后一条消息间距加到 24dp，三点图标左对齐消息；菜单只留 Plain Transcript / API Trace，打开只读 `ProfileCodeEditor`（PLAIN），底部 Copy text / Save to file。`diagnostics_export(kind=trace)` 增加 `since_minutes`，返回 `entries`/`rangeStart`/`rangeEnd`/`availableFrom`/`missingBefore`；完整 trace body 落到应用私有文件（2.5M 字符封顶），更新/重启后可恢复。本地 450 个用例绿。
- wonder.78 真机验收（2026-11-15）：5 个 `cpk_*` MCP 工具的 `search_tools` 结果都带 `server=Clawdy Portal Knocks` 和 `namespace=cpk`，description 以 `MCP server: Clawdy Portal Knocks.` 开头；非 MCP 工具不受影响。`release_notes` 已返回真实改动（78 的正文实际是 commit message，不是 tag 注解；79 的 workflow 修复会换成 tag 消息）。77→78 更新后，19:08 的完整 trace body 已丢，只剩持久化摘要；`tail` 也不能按时间取，19:10 之后的 10 条足够完成 78 验收。
- wonder.78（2026-11-15）：MCP 工具来源元数据。`AgentToolDefinition` 增加 `serverName`/`namespace`，description 以 `MCP server: <名字>.` 开头，`search_tools` 的 matches 和 catalog 都带 `server`/`namespace`；短 namespace `cpk` 不再让模型分不清来源。本地 447 个用例绿。发布正文改用 tag 注解的 CI 修复排到 79。
- wonder.77 真机验收（2026-11-15）：`ask_user` 单选/多选/取消三条通过；`release_notes` 能被调用，但正文是 release workflow 写死的两行，需要改发布正文；MCP 改名后 `clawdy_portal_kn_orb_status` 调用成功，但旧前缀仍长；`diagnostics_export` 与 Taildrop 正常；76 更新到 77 后 `network_snapshot` 仍 `vpnActive=true`、core 在跑，更新后服务恢复验证通过。详细数据和结论见本次会话。
- wonder.77（2026-11-15）：Conductor/MCP 日常体验。新增 `release_notes` 工具读当前版本发布说明；Connections 搜索框常开；用户气泡去掉点按涟漪；答题卡选项去胶囊背景并加 8dp 间距；MCP 工具名改为 `<namespace>_<tool>`（去掉 `mcp_`），添加/编辑服务器支持 Name 和 Namespace，改名不影响工具前缀；MCP 配置从 Provider 卡片独立出来；Tool routing 改为 `/routing on|off`。
- wonder.76（2026-11-15）：更新安装链路和更新后状态恢复。应用内更新走 `FileProvider` + `ACTION_VIEW`，不经过浏览器下载链路，扫描提示由系统/Play Protect 决定；文档见 [`docs/update-install.md`](docs/update-install.md)。安装前记住服务是否在跑，`MY_PACKAGE_REPLACED` 后由 `BootReceiver` 自动拉起；`ServiceRestorePolicyTest` 覆盖判定。
- wonder.75（2026-11-15）：`diagnostics_export` 生成的文件名统一成 `.md`（请求 `api-trace.json` 会写成 `api-trace.md`）；对话复制和导出现在会遮掉邮箱和 Tailscale 地址，旧会话里的 `tailscale_status` 原文不再随分享泄露。发布流程收进 `scripts/release.sh`。
- 本地 JVM 单测环境（2026-11-15）：Mini 装好 JDK 17、Android SDK 37.1/36 和 CI 的 `libbox.aar`，用 `scripts/local-test.sh` 跑全量 433 个用例。依赖、重装步骤和磁盘预算见 [`docs/local-testing.md`](docs/local-testing.md)；签名、发布、通知和 libbox 编译仍在 CI。
- wonder.74（2026-11-15）：MCP 工具 description 不再截断。远程给的描述原样进入 `search_tools`；需要控制输出体积时只整条丢弃工具并标 `truncated`，不在句子中间加省略号。截断掉的是 `docker_run` 的 `output at /work`，正是决定是否调用工具的关键约束。
- wonder.73（2026-11-15）：Conductor 交互批量。`/brief [on|off]` 用 prompt 约束回答长度（温度不是控制啰嗦的杠杆）；用户气泡点按显示发送时间；迷你 Reasoning 折叠换行、点按展开，Show/Hide 保留；工具 Details 固定在表头；新增 `ask_user` 答题卡（单选/多选，随会话保存，超时/停止/清空按取消收口）。`ask_user` 是普通工具调用，不弹确认卡。
- wonder.72（2026-11-15）：Link v2 干净版。只认 `server`，去掉 v1 `issuer`/`issuerBase`/`GRANT_PATH`；scope 由模型按工具描述决定，App 不再猜；`vfs_connect` 加 `expiresInSeconds` 和 30 秒读缓存说明；VFS 可见范围以 `visibleRoots`/`/vfs` 实测为准。正式 Release，arm64 APK 已发，Feishu 通知成功。
- VFS 战役收口（2026-11-15）：P7-4b 通过（idle 91 秒后两次 docker 读，provider 保持到 01:36:41）；P7-5 未验（`/work` 空，e2e 覆盖）。`.70` 全量工作目录案例冻结在 [`docs/vfs-cases/2026-11-15-wonder70-whole-workspace/`](docs/vfs-cases/2026-11-15-wonder70-whole-workspace/)。结论：数据面能力已验证，产品价值仍窄；后台断连是主要阻塞，后续只保留“远程 Agent 读手机 Conductor 数据”的用例。
- wonder.66/68/64（2026-11-14）：Stop 打断重试退避并在 3 秒内强制结束；输入栏 turn 状态行（带秒数）；`/clear` 后过期取消标记修复。细节见对应 commit。
