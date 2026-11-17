# WonderSing 待办

只放未完成事项和最近五个版本的索引；更早的完成记录见 GitHub Releases、git history 和 [`docs/agent-runtime.md`](docs/agent-runtime.md)。
新增一项加到对应优先级；做完就删掉，或在「最近完成」留一行索引。

## 现在待办

> Conductor 审查/评估：[`docs/conductor-review-2026-11-13.md`](docs/conductor-review-2026-11-13.md) / [`docs/conductor-assessment-2026-11-13.md`](docs/conductor-assessment-2026-11-13.md)。VFS 设计见 `~/workspace/clawdy-portal/docs/agent-oriented-vfs.md`；P7 见 [`docs/vfs-dry-run.md`](docs/vfs-dry-run.md)；结论见「最近完成」。

### P1 稳定性与隐私

- [ ] **Conductor VFS 长分析预算**：86 的 10 分钟会话里，模型完成 VFS 挂载、日志定位和两次容器读取后，最后一条 `route_call` 收到 `step_budget_exceeded`，`app.log` 正文分析没开始。下一步给长分析一个可续跑路径（提高工具轮数、显式 continuation，或拆成异步任务），别再让用户重新解释一遍。复盘见 [`docs/conductor-trace-review-2026-11-17.md`](docs/conductor-trace-review-2026-11-17.md)。
- [ ] **`conductor/` 旧诊断文件进入新会话**：新会话的 `file_list` 能看到 `vfs-failure-handoff.md`、`session-trace-w84.md`、`session-transcript-w84.md`、`w84-transcript.md` 和 `w82-*`；模型因此直接知道了 84 的事。先定保留/归档规则，或在新会话开始前把这些一次性文件移出工作目录。
- [ ] **Conductor 进程死亡恢复（剩余）**：进程级 ViewModel 和恢复时的 interrupted 工具卡已落地；进程死亡仍会丢进行中 turn 的 tool 卡片和未完成 assistant。下一步把运行中的 turn 快照持久化，重启后恢复成一条明确的 interrupted turn，不重跑工具。
- [ ] **VFS 大文件批量读取吞吐**：93 新会话在当前蜂窝 + Tailscale 路径上实测：975 KB 静态文件 `wc -c` 25–26 秒、152 KB 静态文件 4.5 秒、1.04 MB `.runtime/trace.md` 27–28 秒；`stat` 只要 0.2 秒、`head -c 200` 0.37 秒，连续重复读也仍慢。说明瓶颈是 provider→Portal 的批量读取吞吐，不是 FUSE stat 或 runtime 生成；先换 Wi-Fi 复测，再决定是否做 provider 分块/压缩或继续压 trace 体积。Conductor 那次 30.5 秒的 `cpk_docker_run` 就是这个 `wc -c`。
- [ ] **服务重启后 core 不可达（83 低端机实测）**：BIRD P50S_PRO / Android 11 上，用户停止并重开 Service 后，`system_snapshot` 连续三次（10:25 / 10:27 / 10:31）返回 `core.reachable=false`、`groupCount=0`、`outboundCount=0`，错误 `groups stream recv: rpc error: code = Unavailable desc = error reading from server: EOF`；同一时刻 `vpnServiceActive=true`、`serviceMode=vpn`，未见自行恢复。下一步：用 Conductor 的文件工具读 crash/OOM reports 和 AppLog，查 CFG `service_start` / `service_stop` / `vpn_session_id`；判断是 core 进程没起来、被杀，还是 `CommandClient` 的 stream 没重连；在 .83 上复现并记录。若 core 真没起来，补一个明确的「服务已启动但核心不可达」状态和重启入口，不能只显示 VPN 已连接。排障见 [`docs/agent-runtime.md`](docs/agent-runtime.md)。
  - 2026-11-18 复盘（wonder.95，真机 + VFS `all`）：不是 core 死了。23:15:28-35 服务两连 stop/start，`appfiles/command.sock` mtime 23:15:35；23:46 `system_snapshot` 仍 `core.reachable=false`，但 `appfiles/telemetry/pending.log` 有持续 tun/router 流量，`app.log` 无 crash、`CrashReport-Application.log` 0 字节。数据面活着，断的是 Conductor 的 CommandClient 管控通道。
  - 代码原因：Conductor 是进程级 ViewModel，`CommandClient` 只在其首次连接后保留；服务重启时如果 Conductor 页面不在前台，`ConductorScreen` 的 `LaunchedEffect` 不会调用 `onServiceState`。旧 stream 收到 EOF 后没有重连；回到 Conductor 时 `wantConnected` 已经是 true，`onServiceState` 的 `if (previous == shouldConnect) return` 直接返回，`onConnectionError` 也只把状态置 false。所以会一直显示服务在跑、core 不可达。
  - 修法：`onServiceState` 在 `shouldConnect && !coreConnected` 时仍调用 `connect()`；Conductor 可见时加约 5 秒健康检查或带退避的重连，不要只依赖 service 状态跳变。修完在真机上验证「服务 stop/start 后不做任何操作，core 工具自行恢复」和「离开 Conductor 期间重启，回来后第一次工具调用已恢复」。
  - 2026-11-18 已修（dev）：抽出 `CoreConnectionState`，服务仍 started 但 CommandClient 已断时按 5 秒重试重连；Conductor 可见期间每 5 秒调用一次 `onServiceState`。`CoreConnectionStateTest` 覆盖「流断后重连」「重试间隔」「服务停/启」三个场景。待真机验证。

### P2 数据面与能力

- [ ] **Conductor 短时后台后的 prompt cache**：90 trace 显示离开约 8m48s 和 6m32s 后，首轮 cache hit 从约 95% 掉到 23–24%（hit 2304 / miss 7269、7585），延迟升到 6.8s / 3.7s。缓存 TTL 是分钟级，不是 w88 报告推测的“>1h”。评估是否需要在回到前台时提示，或压缩上下文降低首轮成本。
- [ ] **ApplicationExitInfo 进程退出取证**：API 30+ 用 `ActivityManager.getHistoricalProcessExitReasons` 读取本进程退出原因、说明、时间戳和 ANR/native trace，写到外部 `diagnostics/exit-info.json`；API 24–29 回退到 crash report 和 `onTrimMemory` 内存快照。用于服务重启后 core 不可达、ANR、native crash。
- [ ] **VFS scope 验收（剩余）**：93 真机已验证 `conductor_session` 与 `all` 的 visibleRoots/missingScopes、`workdir` 覆盖 `conductor/`、`appfiles`/`.runtime` 可读；仍需确认 `vfs/` 私有目录排除、`/work` 隔离和只读写入失败不变，按 [`docs/vfs-dry-run.md`](docs/vfs-dry-run.md) 收尾。
- [ ] **数据面 Phase B：Taildrop 收件**：`taildrop_inbox`、`taildrop_receive`；沿用 typed 工具 + `ALWAYS` 确认，只允许 tailnet peer。设计见 [`docs/conductor-work-plan-2026-11-13.md`](docs/conductor-work-plan-2026-11-13.md)。
- [ ] **数据面 Phase C：自省与证据**：`export_log_tail`、`self_test` 固定套件、`conductor_eval` 开关；脱敏 `diagnostics_network_history`（最近 N 条 network/service/VPN 事件），报告写工作区并可用 Taildrop 交给 Mini。
- [ ] **Provider capability 剩余**：`stream_options.include_usage`、`max_completion_tokens`、Azure 类鉴权；DeepSeek `none`/`max`、xAI `xhigh` 等不能三家共用的档位。现有矩阵见 `ModelLimits`。
- [ ] **网络诊断**：`dns_lookup(domain, via=direct|outbound)`、`route_decision(domain)`；libbox 没有 DNS/route API，先做能落地的部分。83 低端机实测：经 `CF-cf-67` 访问 Baidu 返回 200 / 304ms，`github.com`、`api.github.com`、`www.google.com` 都是 `no such host`（约 10s），`release_notes` 连续 3 次因 `github.com` 解析失败；模型只能从 probe 结果猜 DNS 故障。
- [ ] **`http_fetch` 的阶段分类**：工具已落地（GET、可选出口/headers/max_bytes，200 有 body，非 200 只回状态码），剩余把 DNS / TCP / TLS / HTTP 阶段从 libbox 异常里拆出来。83 实测 `https://1.1.1.1/` 返回 `ok=false`，但错误是证书没有 IP SAN，TCP 和 TLS 已到对端；`speed_test` 后续。
- [ ] **批量确认：预览与分级**：整批预检合成一张卡；`Approve rest of turn` 只覆盖同工具或只读工具，`DATA_EGRESS`/MCP 写工具逐次确认。
- [ ] **工具编排计划（tool_plan / DAG）**：把有依赖的多步调用压成一次提交，支持字段绑定、`when`/`retry`/`fallback`；先只读工具。目标是减少往返和 token。
- [ ] **对话级 LLM 摘要**：现有只做旧大 tool observation 的确定性压缩；assistant/user 长文本和跨 turn 的语义摘要未做。
- [ ] **trace 长 body 与 release 驻留**：单条 body 超 200k 截断，旧 body 超 20k 先缩短；`AgentApiTrace` 在 release 默认驻留完整请求/响应，持久化文件按 2.5M 字符封顶。跨重启的完整 body 已解决，剩余的是单条上限和隐私/驻留策略。83 低端机实测 `trace.md` 531,854 → 1,036,124 → 1,038,540 → 931,397 字节，停服重开后下降；总量超过 `MAX_TOTAL_BODY_CHARS = 1_000_000` 后旧 body 被缩到 20k，符合预算行为，不是条目丢失（导出仍为 20 条、`missingBefore=false`）。
- [ ] **连接与规则命中可观测性**：最近 N 条连接 + 命中规则 + 最终出站；需要 CommandClient 支持。
- [ ] **消息内富 Markdown 与本地文件查看**：业界约定就是标准 Markdown，不用自定义标记：链接用 `[label](url)`，文件名/路径/命令用行内代码。已完成基础：system prompt 要求字面量必须用行内代码，气泡内行内代码加 `surfaceContainerHigh` 背景。剩余：把 `markdownAnnotated` 升级为可点击，链接交给浏览器，行内代码点按复制；本地文件链接点按弹出只读查看器（标题、路径、内容、复制），不执行。需要真机确认点按/长按手感。
- [ ] **Reasoning 前置阶段**：流式请求用 OkHttp `EventListener` 区分 DNS、TCP、TLS、请求已发出、收到响应头；首字前 turn 状态行显示细分阶段，首字后回到「Receiving response」。新增 `AgentModelChunk.Stage` 和 VM 状态映射；保留 `Waiting for the provider` 作为兜底。77 验收 trace 里有两次 120 秒 `network_timeout`（17:00:53、17:02:54），正好是这条需求的证据。
- [ ] **77/78 旧 release 正文**：77 是模板，78 是 commit message；79 起已按 tag 注解生成并验证。需要单独编辑旧版本时再动，不影响新版本。
- [ ] **MCP 旧服务器前缀**：旧配置没有 namespace 时回退到 id 并截到 16，实测 `clawdy_portal_kn_orb_status` 仍然很长；83 低端机会话里仍能看到旧前缀（`clawdy_portal_kn_*`，旧 .52 会话里还有 `mcp_clawdy_portal_knocks_*`），83 新调用已带英文 `MCP server:` 前缀。给旧配置加一次性提示或在卡片上标出过长前缀，用户可在 Edit 里改成 `portal`。
- [ ] **ask_user 取消原因结构化**：工具输出增加 `reason`（`dismissed` / `timeout` / `stopped`），当前只有 `message`；实测模型无法稳定区分超时和主动取消。
- [ ] **analyze_image 的 max_tokens 与 reasoning 回退**：82 trace 的视觉调用 `finish=length`、completion=1024、reasoning_tokens=1024，content 为空，最终把 reasoning 草稿当 analysis 返回。提高 `max_tokens`（至少 4096，优先 8192），优先返回 content；content 空且 `finish=length` 时明确报错，不直接把整段 reasoning 当答案。
- [ ] **file_grep 默认 literal 与超长行**：82 trace 里 query 含 `|` 但没开 regex，连续零匹配，模型误判 trace 没有 vision 请求；匹配行也可能是几十万字符的大 JSON 行，输出被压缩。无匹配时提示 `regex=true`（或对含元字符的 query 自动 regex），并给每条匹配行加最大字符数/分页。
- [ ] **provider 工具调用格式异常（观察）**：82 trace 里模型有一次把 `route_call` 的 tool/args 写成带 `<｜｜DSML｜｜>` 的畸形串，运行时按 `file_grep` 缺参返回，模型随后恢复。先记录；如果复现，检查流式 tool-call 解析和 provider 兼容。

### P3 低优先 / 可选 / 阻塞

- [ ] **`file_grep` 多文件/目录支持（低）**：当前只搜单个文件。需要时让 `path` 支持目录并返回文件 + 行号，不新增 `file_search`；先不动。
- [ ] **数据面 Phase D（以后）**：短消息、通知、定时检查、收件箱监听、A → B 中继；仍只走 typed 工具 + 确认。
- [ ] **VFS 后续（可选）**：P6e 进程被杀后恢复最后一条 Link；按 Portal refs/consumer 生命周期自动停止；后台共享时长设置。后台 provider 与前台服务已在 wonder.90 落地，见 [`docs/vfs-connection-stability.md`](docs/vfs-connection-stability.md)。
- [ ] **probe 真正并发（阻塞）**：libbox 没有 per-request outbound binding，`probe_many` 仍顺序执行。
- [ ] **probe 链路复测（等出口健康）**：wonder.34 的 6 连失败是出口节点不健康，不是 Conductor 回归；204 分类待出口健康后复测。
- [ ] **跨版本旧 tool schema 归一**：wonder.51 已加版本 Notice/`app_updated`/导出 header；旧 tool observation 的 `ok` 等字段仍按旧语义留在 transcript。
- [ ] **设备上下文版本历史（低）**：`appVersionHistory`/`updateCount`，当前只有 `appVersion` + `app_updated`。
- [ ] **增量更新（低）**：整包 bsdiff，约 3–4 个工作日；见 [`docs/incremental-update.md`](docs/incremental-update.md)。
- [ ] **路由 A/B 真机执行**：语料见 [`docs/conductor-eval.md`](docs/conductor-eval.md)；native/routing 对比未跑。
- [ ] **确认与事务语义**：模型看不到确认事件；多步调用没有框架级事务；等 2B 流式和 Phase 3 再评估。
- [ ] **最后一条用户消息长按只有「全选」**（[#7](https://github.com/wonder-missing/sing-box-for-android/issues/7)）：已关 Compose 1.11 的新 context menu/smart selection；待真机确认 Copy 回来，再按上游修复移除开关。

## 下个会话接手

- 当前已发布 `wonder.95`（tag `v1.15.0-alpha.13-wonder.95`，commit `de3ccb0`）。本地 540 用例绿；Test CI `44087052920`、Release `44087733400`、notify `44088910964` 均成功；arm64 APK、APK 内 95 notes 和本地 bare tag 已确认。下一次从 96 开始。
- `dev` 已有 95 后的未发布改动：`file_read.last_lines`，删除 `file_tail`/`file_mkdir`，`tailscale_devices` 合并 `taildrop_peers`，`http_fetch` 改用 OkHttp 并支持 method/body，Conductor 草稿改为 ViewModel 持续持有并在重新进入时恢复。下次发版前跑全量并更新版本。
- 真机待验：95 的 Dashboard 四列 VFS 胶囊（capsule、四列等宽、已连接 primaryContainer、320dp/大字体退让、卡片距表面 12dp）；`/tool search_tools` 和 `Run`/`Fill required` 动作；`system_snapshot` 新字段；`file_append`/`file_edit`；`http_fetch` 的 200/非 200 与出口切换。另继续验 94 的 telemetry 过滤和 Conductor 草稿恢复。
- 下一批优先：VFS 大文件读取换 Wi-Fi 复测；长分析 continuation、进程死亡 turn 快照、`conductor/` 旧文件清理、83 core 不可达取证。VFS 后台服务已落地，不再按“只在前台”假设设计。
- 回归文档：Conductor [`docs/conductor-eval.md`](docs/conductor-eval.md)、VFS [`docs/vfs-dry-run.md`](docs/vfs-dry-run.md)、VFS 稳定性/浸泡 [`docs/vfs-connection-stability.md`](docs/vfs-connection-stability.md) / [`docs/vfs-soak-test.md`](docs/vfs-soak-test.md)；发布流程见 `AGENTS.md`。

## 最近完成

- wonder.95（2026-11-17）：Conductor 工具与 Dashboard VFS。`network_snapshot` 改名 `system_snapshot`，core 改 `reachable`，device 增加 `serviceActive`/`vpnServiceActive`/`batterySaver`/`dozeMode`；新增 `file_append`、`file_edit`（exact match、原子多编辑、`expected_sha256`）和 `http_fetch`（GET、可选出口/headers/max_bytes、200 body、非 200 状态码）；`/tool` 可调 `search_tools` 并提供 `Run`/`Fill required`。Dashboard 按 Lyra v3 改成四列等宽 capsule、共享底表面、已连接 VFS primaryContainer、卡片距表面 12dp；窄宽/大字体先隐藏 VFS 文字，Starting/Stopping 用 spinner。540 用例绿；Test/Release/notify、arm64 APK、APK 内 95 notes 和本地 bare tag 已确认。
- wonder.94（2026-11-17）：telemetry 队列边界过滤与草稿异步加载。`TelemetryLogBuffer` 在装载 `pending.log` 和 append 时都调用 `TelemetryLogFilter`，历史/新写入的 tailscale TRACE 不会再穿过；跳过行不计入 disk-cap dropped。Conductor 草稿随异步 session restore 在 IO 读取，`initialDraft` 只在输入未被触碰时应用，首帧不再解析 SharedPreferences。5 分钟 VFS 观察 10/10 健康、2/2 消费者读取、0 outage、0 provider 重连。529 用例绿；Test/Release/notify、arm64 APK、APK 内 94 notes 和本地 bare tag 已确认。
- wonder.93（2026-11-17）：UI 与启动性能。Status bar 统一固定 36dp 视觉高度/48dp 触摸目标的 chip，左 Connections/Groups、右 Stop/VFS；Groups 换 Layers，VFS 换 CloudUpload + tertiary 容器/状态点 + `VFS ↑bytes`（窄屏退化为 `VFS`+点）。VFS 卡片改为 header（服务名、共享/流量、48dp 断开）+ 短 scope chips（FlowRow，4 个后 `+N`）。`VFS_HANDSHAKE_TIMEOUT` 独立错误码和模型提示；telemetry 发送前过滤历史 tailscale TRACE；Conductor trace/会话 restore 移出主线程，early submit 等待 restore，冷启动显示 spinner。527 用例绿；Test/Release/notify、arm64 APK、APK 内 93 notes 和本地 bare tag 已确认。
- wonder.92（2026-11-17）：更新后服务恢复只被当前版本的未读 crash 阻塞；telemetry 过滤 `endpoint/tailscale` TRACE；`conductor_tool`/`app.log` 增加 `wait`/`work`；全量 Link 的 `missingScopes` 不再误列 `conductor`；记录 `background`/`screen_on`/`screen_off`。第二轮 30 分钟浸泡 56/56 健康、6/6 消费者读取。517 用例绿；Release/APK/Feishu 已确认。
- wonder.91（2026-11-17）：VFS 死链检测。20 秒 OkHttp 协议 ping；重连预算耗尽改为 `VFS_CONNECT_FAILED` 并停服务；监测脚本支持 provider 切换。510 用例绿；Release/APK/Feishu 已确认。
- 更早版本见 [`docs/agent-runtime.md`](docs/agent-runtime.md)、GitHub Releases 和 git history。
