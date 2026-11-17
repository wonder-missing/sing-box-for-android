# WonderSing 待办

只放未完成事项。已完成的事项不再长期保留，见 GitHub Releases 和 git history。
新增一项加到对应优先级；做完就删掉，或在「最近完成」留一行索引。

## 现在待办

> Conductor 审查/评估：[`docs/conductor-review-2026-11-13.md`](docs/conductor-review-2026-11-13.md) / [`docs/conductor-assessment-2026-11-13.md`](docs/conductor-assessment-2026-11-13.md)。VFS 设计见 `~/workspace/clawdy-portal/docs/agent-oriented-vfs.md`；P7 见 [`docs/vfs-dry-run.md`](docs/vfs-dry-run.md)；结论见「最近完成」。

### P1 稳定性与隐私

- [ ] **Conductor VFS 长分析预算**：86 的 10 分钟会话里，模型完成 VFS 挂载、日志定位和两次容器读取后，最后一条 `route_call` 收到 `step_budget_exceeded`，`app.log` 正文分析没开始。下一步给长分析一个可续跑路径（提高工具轮数、显式 continuation，或拆成异步任务），别再让用户重新解释一遍。复盘见 [`docs/conductor-trace-review-2026-11-17.md`](docs/conductor-trace-review-2026-11-17.md)。
- [ ] **`conductor/` 旧诊断文件进入新会话**：新会话的 `file_list` 能看到 `vfs-failure-handoff.md`、`session-trace-w84.md`、`session-transcript-w84.md`、`w84-transcript.md` 和 `w82-*`；模型因此直接知道了 84 的事。先定保留/归档规则，或在新会话开始前把这些一次性文件移出工作目录。
- [ ] **Conductor 进程死亡恢复（剩余）**：进程级 ViewModel 和恢复时的 interrupted 工具卡已落地；进程死亡仍会丢进行中 turn 的 tool 卡片和未完成 assistant。下一步把运行中的 turn 快照持久化，重启后恢复成一条明确的 interrupted turn，不重跑工具。
- [ ] **VFS 大文件批量读取吞吐**：93 新会话在当前蜂窝 + Tailscale 路径上实测：975 KB 静态文件 `wc -c` 25–26 秒、152 KB 静态文件 4.5 秒、1.04 MB `.runtime/trace.md` 27–28 秒；`stat` 只要 0.2 秒、`head -c 200` 0.37 秒，连续重复读也仍慢。说明瓶颈是 provider→Portal 的批量读取吞吐，不是 FUSE stat 或 runtime 生成；先换 Wi-Fi 复测，再决定是否做 provider 分块/压缩或继续压 trace 体积。Conductor 那次 30.5 秒的 `cpk_docker_run` 就是这个 `wc -c`。
- [ ] **服务重启后 core 不可达（83 低端机实测）**：BIRD P50S_PRO / Android 11 上，用户停止并重开 Service 后，`network_snapshot` 连续三次（10:25 / 10:27 / 10:31）返回 `core.running=false`、`groupCount=0`、`outboundCount=0`，错误 `groups stream recv: rpc error: code = Unavailable desc = error reading from server: EOF`；同一时刻 `vpnActive=true`、`serviceMode=vpn`，未见自行恢复。下一步：用 Conductor 的文件工具读 crash/OOM reports 和 AppLog，查 CFG `service_start` / `service_stop` / `vpn_session_id`；判断是 core 进程没起来、被杀，还是 `CommandClient` 的 stream 没重连；在 .83 上复现并记录。若 core 真没起来，补一个明确的「服务已启动但核心不可达」状态和重启入口，不能只显示 VPN 已连接。排障见 [`docs/agent-runtime.md`](docs/agent-runtime.md)。

### P2 数据面与能力

- [ ] **Conductor 短时后台后的 prompt cache**：90 trace 显示离开约 8m48s 和 6m32s 后，首轮 cache hit 从约 95% 掉到 23–24%（hit 2304 / miss 7269、7585），延迟升到 6.8s / 3.7s。缓存 TTL 是分钟级，不是 w88 报告推测的“>1h”。评估是否需要在回到前台时提示，或压缩上下文降低首轮成本。
- [ ] **VFS 根与本地路径提示（已改描述，待验证）**：86 trace 里模型把 `workdir` 的 `**` 误判成 `conductor` 未授权，并把本地 `file_tail(".runtime/trace.md")` 和 VFS `/vfs/.runtime/trace.md` 混在一起。`vfs_connect` 描述已明确 `workdir` 覆盖 `conductor/`、`.runtime/*` 仅 VFS 可见，并要求连接后先看 `/vfs`；下次真机确认模型是否还犯。复盘见 [`docs/conductor-trace-review-2026-11-17.md`](docs/conductor-trace-review-2026-11-17.md)。
- [ ] **ApplicationExitInfo 进程退出取证**：API 30+ 用 `ActivityManager.getHistoricalProcessExitReasons` 读取本进程退出原因、说明、时间戳和 ANR/native trace，写到外部 `diagnostics/exit-info.json`；API 24–29 回退到 crash report 和 `onTrimMemory` 内存快照。用于服务重启后 core 不可达、ANR、native crash。
- [ ] **VFS 新 scope 真机验收**：按 [`docs/vfs-dry-run.md`](docs/vfs-dry-run.md) 的 P7-2b 到 P7-2f 验证 `workdir` 外部目录、`appfiles` 内部目录与 `vfs/` 排除、`all` / `conductor_session` 预设展开、`.runtime` 独立 scope；再确认 `/work` 隔离和只读写入失败不变。
- [ ] **数据面 Phase B：Taildrop 收件**：`taildrop_inbox`、`taildrop_receive`；沿用 typed 工具 + `ALWAYS` 确认，只允许 tailnet peer。设计见 [`docs/conductor-work-plan-2026-11-13.md`](docs/conductor-work-plan-2026-11-13.md)。
- [ ] **数据面 Phase C：自省与证据**：`export_log_tail`、`self_test` 固定套件、`conductor_eval` 开关；脱敏 `diagnostics_network_history`（最近 N 条 network/service/VPN 事件），报告写工作区并可用 Taildrop 交给 Mini。
- [ ] **Provider capability 剩余**：`stream_options.include_usage`、`max_completion_tokens`、Azure 类鉴权；DeepSeek `none`/`max`、xAI `xhigh` 等不能三家共用的档位。现有矩阵见 `ModelLimits`。
- [ ] **网络诊断**：`dns_lookup(domain, via=direct|outbound)`、`route_decision(domain)`；libbox 没有 DNS/route API，先做能落地的部分。83 低端机实测：经 `CF-cf-67` 访问 Baidu 返回 200 / 304ms，`github.com`、`api.github.com`、`www.google.com` 都是 `no such host`（约 10s），`release_notes` 连续 3 次因 `github.com` 解析失败；模型只能从 probe 结果猜 DNS 故障。
- [ ] **`probe_via_node` 的结果分类**：加 `http_fetch_probe(outbound, url, max_bytes)`，把 HEAD 被拒和节点坏分开；输出区分 DNS / TCP / TLS / HTTP 阶段。83 实测 `https://1.1.1.1/` 返回 `ok=false`，但错误是证书没有 IP SAN，TCP 和 TLS 已到对端；`speed_test` 后续。
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

- 当前已发布 `wonder.94`（tag `v1.15.0-alpha.13-wonder.94`，commit `9f13b7a`）。本地 529 用例绿；Test CI `44022925942` 绿；Release `44023340190` 绿；Release notify `44024478815` 绿；arm64 APK、APK 内 `wonder.94` notes 和本地 bare tag 已确认。下一次从 95 开始。
- 真机待验（94）：更新后 `pending.log` 不再出现新的 `endpoint/tailscale` TRACE（历史行也应在装载时被过滤）；Conductor 冷启动草稿从异步 `initialDraft` 恢复，输入不会被空状态覆盖；93 的 UI/后台/lifecycle 项继续按上一条验证。夜间浸泡脚本见 [`docs/vfs-soak-test.md`](docs/vfs-soak-test.md)。
- Mini 侧 Taildrop 收件箱不会自动落盘：Conductor 的 `taildrop_send` 只把文件送进对端 Tailscale inbox，headless Mini 需要 `tailscale file get --conflict=rename <目录>` 才搬出来。主人否决了自动 drain 的 LaunchAgent；App 只按实际能感知的状态返回 `peer_inbox` + `receiverActionRequired`，不承诺落盘时机。
- v80 trace 复盘：`analyze_image` 空答案（模型名 `deepseek-v4-flash`，81 已改 `deepseek-flash`）；`file_grep` 误用 `pattern`（82 已加参数提示）；`diagnostics_export` 的 `overwrite` 在 v80 trace 前半段缺失是因为那几条是 v79 会话，v80 代码和 APK 已带；`taildrop_send` 成功但 inbox 未 drain。
- v82 trace 复盘：直接图片输入成功（text+image content parts，模型读出 Portal 首页）；`analyze_image` 不再空答案，但 `max_tokens=1024` 命中 length，返回 reasoning 草稿，待 83 修；trace 脱敏（803171 chars）、参数提示、Taildrop `peer_inbox` 均验证通过；Rich 查看器打不开；`file_grep` regex/长行和对话导出含 reasoning 待处理。
- wonder.83 低端机实证（2026-11-16）：VFS 12 次 `docker_run` 全部 exit 0，`/vfs` 只读、`/work` 每次运行隔离且可写，providerId `p_0951e053867ac7eb` 跨服务重启复用，VFS 数据面可收口。手动停/重开 Service 后 core 不可达（`core.running=false`，`groups stream ... EOF`），设备仍 `vpnActive=true`，是下一轮 P1 首要取证项。境内外 DNS 分裂（Baidu 200，GitHub/Google `no such host`）需要 `dns_lookup` 或核心 DNS 证据。trace.md 停服后从 1,038,540 降到 931,397，符合 `MAX_TOTAL_BODY_CHARS=1_000_000` 的旧 body 压缩，不是条目丢失。
- 顺序建议：先修服务重启后 core 不可达和「VPN 已连接但 core 不在」的误导状态；再取 Rich 查看器、`analyze_image` max_tokens、`file_grep` 的证据并修；DNS 诊断在拿到核心 DNS / profile 证据后做。VFS 只在出现“远端 Agent 持续读手机数据”的具体用例时再碰，不扩 background provider/bundle。
- 回归文档：Conductor [`docs/conductor-eval.md`](docs/conductor-eval.md)、VFS [`docs/vfs-dry-run.md`](docs/vfs-dry-run.md)；发布流程见 `AGENTS.md`。旧的 49–59 手机检查清单已过期，不再保留。

## 最近完成

- wonder.94（2026-11-17）：telemetry 队列边界过滤与草稿异步加载。`TelemetryLogBuffer` 在装载 `pending.log` 和 append 时都调用 `TelemetryLogFilter`，历史/新写入的 tailscale TRACE 不会再穿过；跳过行不计入 disk-cap dropped。Conductor 草稿随异步 session restore 在 IO 读取，`initialDraft` 只在输入未被触碰时应用，首帧不再解析 SharedPreferences。5 分钟 VFS 观察 10/10 健康、2/2 消费者读取、0 outage、0 provider 重连。529 用例绿；Test/Release/notify、arm64 APK、APK 内 94 notes 和本地 bare tag 已确认。
- wonder.93（2026-11-17）：UI 与启动性能。Status bar 统一固定 36dp 视觉高度/48dp 触摸目标的 chip，左 Connections/Groups、右 Stop/VFS；Groups 换 Layers，VFS 换 CloudUpload + tertiary 容器/状态点 + `VFS ↑bytes`（窄屏退化为 `VFS`+点）。VFS 卡片改为 header（服务名、共享/流量、48dp 断开）+ 短 scope chips（FlowRow，4 个后 `+N`）。`VFS_HANDSHAKE_TIMEOUT` 独立错误码和模型提示；telemetry 发送前过滤历史 tailscale TRACE；Conductor trace/会话 restore 移出主线程，early submit 等待 restore，冷启动显示 spinner。顺手修复 `release.sh` notes 前缀已生效。527 用例绿；Test/Release/notify、arm64 APK、APK 内 93 notes 和本地 bare tag 已确认。
- wonder.92（2026-11-17）：从浸泡数据里收口。更新后服务恢复只被“当前正在启动的版本”的未读 crash 阻塞，88 的旧 OOM 不再让之后每次更新都不恢复；telemetry 上传过滤 `endpoint/tailscale` 的 verbose TRACE（保留 INFO/WARN 的 ts_* 事件）；`conductor_tool`/`app.log` 增加 `wait`/`work`，确认等待不再冒充执行延迟；全量 Link 的 `missingScopes` 不再误列 `conductor`；VFS 活跃时记录 `background`/`screen_on`/`screen_off`。第二轮 30 分钟浸泡 56/56 健康、6/6 消费者容器读取成功。517 用例绿；Test/Release/notify、arm64 APK、APK 内 92 notes 和本地 bare tag 已确认。
- wonder.91（2026-11-17）：VFS 死链检测。wonder.90 的首轮 30 分钟监测前 5 次健康，08:09:42 provider 以 `1006` 断开（同一秒手机 terminal 也断），App 进程和 sharing service 仍在但未重连；根因是 WebSocket `readTimeout=0`/`pingInterval=0`，黑洞路径下不会触发 `onFailure`。91 加 20 秒 OkHttp 协议 ping 做本地死链检测，应用层 ping/pong 仍是 Portal `lastSeen` 权威；重连预算耗尽改为 `VFS_CONNECT_FAILED` 并停服务。510 用例绿；Test/Release/notify、arm64 APK、APK 内 91 notes 和本地 bare tag 已确认。监测脚本同时改为在 app 版本或 provider 切换时重置失败计数、每 10 个样本跑真实消费者容器读取。
- wonder.90（2026-11-17）：VFS 后台稳定性。新增 `VfsSharingService`（specialUse 前台服务）与常驻通知（服务端、可读 scope、累计上传、Disconnect）；连接管理器和 consent 状态移到进程级 `VfsRuntime`，返回 App/重建 Activity 不再替换连接；切后台不再立即断开，无服务时保留 90 秒可取消宽限；后台只自动重连已信任 Link；重连循环单飞；新增 `vfs_lifecycle` 遥测。508 用例绿，release/otherLegacy 编译和 R8 通过；Release、arm64 APK、APK 内 90 notes、manifest service 和 Feishu 已确认；真机验收待做。顺手修复 `release.sh` tag 注解前缀（`wonder.N`），Release workflow 现在会在前缀不符时直接失败。
- wonder.89（2026-11-17）：PerAppProxyScanner 扫描并发限制为 2，并在 dex 读取前按可用堆检查跳过无法容纳的条目，针对 w88 实测的 OOM；`conductor tool=...` 日志补 `ms`。504 用例绿，Release/otherLegacy 编译和 R8 通过；APK 内 89 release-notes 已核对，Feishu 已发。VFS 连接稳定性方案落到 `docs/vfs-connection-stability.md`。
- wonder.87/88（2026-11-17）：87 已发布，但 APK 内 release notes asset 是提交信息而不是 tag 注解；88 修复 release workflow 显式 fetch annotated tag，并加版本前缀校验，88 的 APK asset 已核对为 `wonder.88` 注解。本批包含 VFS 上传/重连 pill、会话写入移出主线程、20 轮工具预算、VFS 生命周期/取消竞态修复、Stop 1 秒；Rich 查看器已由主人确认可用。
- wonder.87 工作树（2026-11-17）：`release_notes` 改读 APK 内置 tag asset，不再访问 GitHub；主界面 VFS 状态位改为与左侧按钮同款 pill，显示本次连接累计上传字节数（humanize），重连时显示 spinner/连接中，点击 On 状态切换 VFS 分享浮动卡，默认隐藏；Stop 未确认取消的宽限期缩到 1 秒并显示 turn error；Rich 查看器已由主人确认可用。`/tool` 工具名补全、参数/示例提示也在本批。
- wonder.86 更新后服务恢复已验证（2026-11-16）：遥测记录 `service_stop` 后出现 `service_restore`，action=`package_replaced`、result=`started`、`started_by_user=false`、`restore_after_update=true`，17ms 后 `service_start`；84→85 的丢失没有在 86 复现。
- wonder.86（2026-11-16）：状态栏去掉 `Started`，Connections / Groups / Timer+Stop 左对齐，新增可点击的 `VFS 开/关` 状态位（点击切换 VFS 分享卡）；Conductor 要求路径、命令、flag、工具名、配置键使用行内代码，并给行内代码加背景。包含 `/tool`、slash fuzzy、Viewer 修复和更新恢复遥测。496 用例绿；release/otherLegacy 编译和 R8 通过；Release `v1.15.0-alpha.13-wonder.86`、arm64 APK 和 Feishu 通知已确认。
- `/tool` 开发者直调与 slash fuzzy（2026-11-16，工作树）：`/tool` / `/tool <name>` / `/tool <name> <json>`，复用注册表和确认策略；结果只进临时 Tool console，不写 transcript 和 API trace。命令、取值、`/tool` 工具名和 MCP 工具建议都支持 fuzzy；`/tool <name> ` 会提示必填/可选参数和示例 JSON。随 87/88 发布。
- wonder.85（2026-11-16）：Rich 查看器空白修复。84 遥测显示 `conductor_viewer` open/ready 在 26–46ms 内完成且无错误，根因是 `MarkwonAdapterImpl.setParsedMarkdown` 不调用 `notifyDataSetChanged`；现在解析后显式刷新，并记录 `stage=adapter` / items。487 用例绿，release/otherLegacy 编译和 R8 通过；release、arm64 APK 和 Feishu 已确认。
- VFS 新 scope 与预设（wonder.84，2026-11-16）：新增 `workdir`、`appfiles` 两个根，`/vfs` 根暴露外部工作目录，`/vfs/appfiles` 暴露内部 `filesDir` 并排除 `vfs/`；新增 `all`、`conductor_session` 两个 App 侧预设，解析 Link 时展开成根，Portal 和 VFS 协议不改。487 个 JVM 用例绿，release/otherLegacy 编译和 R8 通过；arm64 APK 和 Feishu 通知已确认，待真机验收。
- wonder.83 低端机实证（2026-11-16）：VFS/MCP 链路通过，服务重启后 core 不可达、境内外 DNS 分裂、trace 预算缩身已记入 P1/P2 和 [`docs/agent-runtime.md`](docs/agent-runtime.md)。
- wonder.83（2026-11-16）：输入栏 v1.3、transcript 口径与端侧遥测。按 Lyra `SPEC-v1.3` 去掉 `@`，`/` 与附件 `+` 左分组，发送/停止右侧固定；状态槽空闲显示 `used/limit`，进行中显示 spinner + `Waiting…`/`Thinking…`/`Running…`，不再显示长句、工具名或秒数。Copy/Save/Plain Transcript 统一叫 transcript，默认不包含 `## Thinking`，API trace 保留 reasoning；诊断导出默认文件名改为 `transcript-`，文件头改为 `# Conductor transcript`。图片选择器 launch/result/import 失败会写 `conductor_image` 遥测和 AppLog；Rich 查看器新增 open/ready/parse/bind/render 遥测，工具/整轮失败带脱敏 detail；未捕获 JVM 崩溃仍走 crash report。本地 464 用例绿。
- wonder.82（2026-11-16）：直接图片输入与 vision 修正。输入栏发送按钮左侧加号选图，图片以缩略图停在输入栏，下一条消息用 text + `image_url` content parts 一起请求；图片压缩到长边 1600px/JPEG、私有目录存 ref、不写 base64 到会话 JSON、trace data URL 脱敏。`analyze_image` 按 DeepSeek Chat Completions 文档保留为工具，`deepseek-flash` + `detail=auto`，content 为空时回退 `reasoning_content`，空答案错误带 finish_reason/provider error，视觉请求进脱敏 trace。参数错误带 `Accepted parameters`；Taildrop 返回 `peer_inbox` + `receiverActionRequired`，不再暗示落盘。本地 463 用例绿，release/otherLegacy 编译和 R8 通过；图片选择、缩略图、全屏查看和直接图片请求需真机确认。
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
