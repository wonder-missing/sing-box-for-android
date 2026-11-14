# WonderSing 待办

只放未完成事项。已完成的事项不再长期保留，见 GitHub Releases 和 git history。
新增一项加到对应优先级；做完就删掉，或在「最近完成」留一行索引。

## 现在待办

> Conductor 审查/评估：[`docs/conductor-review-2026-11-13.md`](docs/conductor-review-2026-11-13.md) / [`docs/conductor-assessment-2026-11-13.md`](docs/conductor-assessment-2026-11-13.md)。VFS 设计见 `~/workspace/clawdy-portal/docs/agent-oriented-vfs.md`；P7 见 [`docs/vfs-dry-run.md`](docs/vfs-dry-run.md)；结论见「最近完成」。

### P1 稳定性与隐私

- [ ] **Conductor 进程死亡恢复（剩余）**：进程级 ViewModel 和恢复时的 interrupted 工具卡已落地；进程死亡仍会丢进行中 turn 的 tool 卡片和未完成 assistant。下一步把运行中的 turn 快照持久化，重启后恢复成一条明确的 interrupted turn，不重跑工具。
- [ ] **`TOOL_CANCEL_TIMEOUT` 精确 1 秒**：工具级超时和 Tailscale 取消已落地；Stop 后 1 秒内未确认取消应转成 turn error，而不是等 3 秒强制结束。
- [ ] **旧会话 UI 与导出的敏感原文**：transcript 已清洗；会话 items 和对话导出仍保留旧的 `tailscale_status` 原文，分享导出前需清对话或逐条清洗。
- [ ] **服务重启缺口**：CFG 遥测显示两次 `service_start` 中间没有 `service_stop`；用 Conductor 的文件工具读 crash/OOM reports，或对比当时的 `tailscale_status`。排障见 [`docs/agent-runtime.md`](docs/agent-runtime.md)。
- [ ] **会话编解码移出主线程**：`ConductorSessionStore` 的 JSON encode/decode 和 `AppLog` 同步写文件仍在调用线程；先用 `ui_jank` 取证，再把 encode/save 放 IO、load 放后台。

### P2 数据面与能力

- [ ] **数据面 Phase B：Taildrop 收件**：`taildrop_inbox`、`taildrop_receive`；沿用 typed 工具 + `ALWAYS` 确认，只允许 tailnet peer。设计见 [`docs/conductor-work-plan-2026-11-13.md`](docs/conductor-work-plan-2026-11-13.md)。
- [ ] **数据面 Phase C：自省与证据**：`export_log_tail`、`self_test` 固定套件、`conductor_eval` 开关；脱敏 `diagnostics_network_history`（最近 N 条 network/service/VPN 事件），报告写工作区并可用 Taildrop 交给 Mini。
- [ ] **Provider capability 剩余**：`stream_options.include_usage`、`max_completion_tokens`、Azure 类鉴权；DeepSeek `none`/`max`、xAI `xhigh` 等不能三家共用的档位。现有矩阵见 `ModelLimits`。
- [ ] **网络诊断**：`dns_lookup(domain, via=direct|outbound)`、`route_decision(domain)`；libbox 没有 DNS/route API，先做能落地的部分。实验里模型只能从 probe 结果猜 DNS 故障。
- [ ] **`probe_via_node` 的 HEAD 歧义**：加 `http_fetch_probe(outbound, url, max_bytes)`，把 HEAD 被拒和节点坏分开；`speed_test` 后续。
- [ ] **批量确认：预览与分级**：整批预检合成一张卡；`Approve rest of turn` 只覆盖同工具或只读工具，`DATA_EGRESS`/MCP 写工具逐次确认。
- [ ] **工具编排计划（tool_plan / DAG）**：把有依赖的多步调用压成一次提交，支持字段绑定、`when`/`retry`/`fallback`；先只读工具。目标是减少往返和 token。
- [ ] **对话级 LLM 摘要**：现有只做旧大 tool observation 的确定性压缩；assistant/user 长文本和跨 turn 的语义摘要未做。
- [ ] **trace 长 body 与 release 驻留**：单条 body 超 200k 截断，跨重启只有摘要；`AgentApiTrace` 在 release 默认驻留完整请求/响应。先评估按需开启。
- [ ] **连接与规则命中可观测性**：最近 N 条连接 + 命中规则 + 最终出站；需要 CommandClient 支持。

### P3 低优先 / 可选 / 阻塞

- [ ] **数据面 Phase D（以后）**：短消息、通知、定时检查、收件箱监听、A → B 中继；仍只走 typed 工具 + 确认。
- [ ] **VFS 后续（可选）**：P6e 进程被杀恢复最后一条 Link；后台 provider 模式（前台服务/常驻通知），否则 Portal Agent 只能在 App 前台时读。结论见「最近完成」；其余 vfsd async/refs/30 秒 cache 归 vfs 项目。
- [ ] **`diagnostics_export(kind=trace)` 扩展名**：当前 `.json` 名会写 Markdown；按 kind 纠正或名称不符时提示。
- [ ] **probe 真正并发（阻塞）**：libbox 没有 per-request outbound binding，`probe_many` 仍顺序执行。
- [ ] **probe 链路复测（等出口健康）**：wonder.34 的 6 连失败是出口节点不健康，不是 Conductor 回归；204 分类待出口健康后复测。
- [ ] **跨版本旧 tool schema 归一**：wonder.51 已加版本 Notice/`app_updated`/导出 header；旧 tool observation 的 `ok` 等字段仍按旧语义留在 transcript。
- [ ] **设备上下文版本历史（低）**：`appVersionHistory`/`updateCount`，当前只有 `appVersion` + `app_updated`。
- [ ] **增量更新（低）**：整包 bsdiff，约 3–4 个工作日；见 [`docs/incremental-update.md`](docs/incremental-update.md)。
- [ ] **路由 A/B 真机执行**：语料见 [`docs/conductor-eval.md`](docs/conductor-eval.md)；native/routing 对比未跑。
- [ ] **确认与事务语义**：模型看不到确认事件；多步调用没有框架级事务；等 2B 流式和 Phase 3 再评估。
- [ ] **最后一条用户消息长按只有「全选」**（[#7](https://github.com/wonder-missing/sing-box-for-android/issues/7)）：已关 Compose 1.11 的新 context menu/smart selection；待真机确认 Copy 回来，再按上游修复移除开关。

## 下个会话接手

- 当前已发布 `wonder.72`（tag `v1.15.0-alpha.13-wonder.72`，commit `e612c70`）。下一次从 73 开始；tag 用 annotated tag，能说清就写 `Agent-Time`/`Agent-Loops`，说不清留空。
- 顺序建议：先做 P1 的进程死亡恢复、1 秒取消、敏感导出；再推 P2 的 Taildrop 收件和自省证据。VFS 只在出现“远端 Agent 持续读手机数据”的具体用例时再碰，不扩 background provider/bundle。
- 回归文档：Conductor [`docs/conductor-eval.md`](docs/conductor-eval.md)、VFS [`docs/vfs-dry-run.md`](docs/vfs-dry-run.md)；发布流程见 `AGENTS.md`。旧的 49–59 手机检查清单已过期，不再保留。

## 最近完成

- wonder.72（2026-11-15）：Link v2 干净版。只认 `server`，去掉 v1 `issuer`/`issuerBase`/`GRANT_PATH`；scope 由模型按工具描述决定，App 不再猜；`vfs_connect` 加 `expiresInSeconds` 和 30 秒读缓存说明；VFS 可见范围以 `visibleRoots`/`/vfs` 实测为准。正式 Release，arm64 APK 已发，Feishu 通知成功。
- VFS 战役收口（2026-11-15）：P7-4b 通过（idle 91 秒后两次 docker 读，provider 保持到 01:36:41）；P7-5 未验（`/work` 空，e2e 覆盖）。`.70` 全量工作目录案例冻结在 [`docs/vfs-cases/2026-11-15-wonder70-whole-workspace/`](docs/vfs-cases/2026-11-15-wonder70-whole-workspace/)。结论：数据面能力已验证，产品价值仍窄；后台断连是主要阻塞，后续只保留“远程 Agent 读手机 Conductor 数据”的用例。
- wonder.66/68/64（2026-11-14）：Stop 打断重试退避并在 3 秒内强制结束；输入栏 turn 状态行（带秒数）；`/clear` 后过期取消标记修复。细节见对应 commit 和 Release。
