# WonderSing / Claude Code 任务：wonder.9 攒批（2026-11-07）

仓库：`wonder-missing/sing-box-for-android`，在 **`dev`** 上改。

**不要**打 Release / 不要 `git tag` / 不要改版本号 / 不要改发版签名钥匙。Soul 验收绿了以后才升 wonder.9 发版。

闭环：改完 → 提交 → `git push origin dev` → 用 `gh run watch` 盯 CI Test 工作流 → 红了看 `--log-failed` 再改再推，直到绿（最多 5 轮失败后停下写明原因）。本机**没有** JDK，单测只靠 GitHub Actions（`.github/workflows/test.yml` 已在 `dev`）。

权限：仓库内 `.claude/settings.local.json` 已允许 status/diff/log/add/commit、`git push origin dev`、`gh run list/view/watch`、`gh workflow run`。禁止 force-push、打 tag、推 `v*`、读 `/workspace/CFG/private/**`。

---

## 背景（当作事实）

已在 `dev`、未发版：

- `a2cf5f1` Tools Agent POC（3 工具 + 确认闸门，27 测）
- `3182e1b` pending.log 写盘隔离（遥测异常不得带崩 VPN）
- `ca3ea07` CI Test 工作流；[CI 40/40 绿](https://github.com/wonder-missing/sing-box-for-android/actions/runs/42338332887)

主人选定：先攒一批再发 wonder.9。铁律：**不再做 / 不再规划 Tailscale SSH 登机器入口**（公钥 sshd 授信；WonderSing 也不要 SSH 便利入口）。

现有遥测：`TelemetryEventLog` / `TelemetrySignals` 已有 `network_change`（transport 类型，无 SSID/IP）、启停、urltest、上传成败等。Agent 侧已能从 `CommandClient` Groups 读到每组 `selected`（见 `CommandNodeSnapshotSource` / `AgentTools`）。**本任务要把「实际选中节点」等写进遥测事件，而不是只留在 Agent UI。**

设置页：`TelemetrySettingsScreen` 已有 probe、「立即上报」、上次成功时间。TODO 里「保存提示 + 一键连通测试 / 外层文案」多半过时——核对后若已满足就在 TODO 勾掉并注明；只在仍缺保存反馈或外层文案堆砌时做最小改动。

---

## 要做

### 1. 遥测：选中节点 / 切换原因 / 会话 / 默认网络（核心）

目标：后台分析能回答「当时走的是哪个节点、为什么切、同一次 VPN 会话、当时默认上行是什么」。

请落地（名字可微调，但语义要对上；写入 `client_events` / 结构化事件，走现有 scrub，**禁止**订阅 URL / token / SSID / 精确 GPS）：

1. **`vpn_session_id`**：每次 `BoxService` 成功拉起 VPN 生成一个短会话 id（UUID 截断即可），挂在该会话期内相关事件上（至少 `service_start` / `service_stop` / 节点切换 / 上传标签）；停止后下一轮新 id。
2. **`default_network_id` 或等价字段**：默认上行的稳定-ish 标识。优先用 `Network` 的 hash / id 字符串 + transport（wifi/cellular/…），**不要** SSID、蜂窝运营商名、IP。可在现有 `TelemetrySignals` 的 network_change 上扩展；`service_start` 时也记一笔当前默认网。
3. **实际选中节点**：服务运行中，用与 Agent 相同的 CommandClient Groups 路径（或 libbox 已有回调）得到各出站组当前 `selected` tag；至少覆盖主选择组（如 urltest / selector）。在以下时机记事件（建议 type=`outbound_selected` 或 `group_selected`）：
   - VPN 刚启动、首次拿到 Groups 快照
   - 选中 tag 变化时（自动 urltest 切换或用户手动选）
4. **切换原因 `switch_reason`**（尽力而为，未知就 `unknown`）：
   - `urltest` / `auto`：自动测速选出
   - `user`：UI / Remote Control 手动选
   - `start`：会话开始时的初始选中
   - 若日志里已有 urltest 行可交叉印证，不要重复灌水；事件里带上 `group`、`from`、`to`、`reason`、可选 `ms`
5. 实现约束：
   - 异常必须吞掉，**不得**影响 VPN（沿用 pending.log 隔离原则）
   - 不要为遥测另起拨号栈；复用 CommandClient / 已有 outbound 更新回调
   - 补 JVM 单测：会话 id 生命周期、选中变化去重、reason 映射、scrub（无订阅原文）
   - Worker 若只需原样存 events，可不改；若字段被 cleanEvents 丢掉再最小放行

### 2. 删掉「登录机器 / Tailscale SSH」规划痕迹

- `TODO.md`：去掉或改成「不做」任何 WonderSing Tailscale SSH / 登机器入口；Agent POC 相关条目若仍暗示可登机器，改成明确禁止。
- Agent 代码/文案/测试：保持「禁止登机器」边界；若有未完成的 SSH 入口设计注释、TODO、计划段落，删掉或改成「按主人铁律不做」。
- **不要**删除原版 Remote Control / 已有 SSH terminal 若那是上游 SFA 能力且与「WonderSing 新开 Tailscale SSH 便利入口」不是同一回事——若不确定，只删 WonderSing 侧新增规划，并在结果里写明你怎么区分的。

### 3. TODO / 轻量核对（非核心）

- 勾上本批已落地项；崩溃隔离 / Agent POC 标「随 wonder.9」或「未发版待 wonder.9」。
- 核对「遥测设置保存提示 / 连通测试 / 外层文案」：已满足则勾掉并写清依据；否则最小修补。
- **应用内检查更新**：只做轻量——若 github flavor 的 UpdateChecker 已从本仓 Releases 拉、但还没打遥测结果事件，补一个成功/失败事件（无 token）；不要重做整套更新 UX。范围一旦膨胀就停、写进结果留给下一版。

### 4. CI 闭环

- 提交信息清楚（可 1–2 个 commit）。
- `git push origin dev`
- `gh run list --workflow=test.yml --branch=dev -L 3` 找到本次 run，`gh run watch <id> --exit-status`；失败则 `gh run view <id> --log-failed`，修，再推。最多 5 轮失败后停下。
- 结束时写清：commits、CI run URL、测试数、TODO 变更摘要、未做项。

---

## 验收（Soul）

- `dev` 上有本批提交；CI Test 绿
- 单测覆盖会话 id / 选中变化 / reason；VPN 路径无新的未捕获异常
- TODO 不再规划 WonderSing Tailscale SSH 登机器
- 无版本号 bump、无 tag、无私密文件

停下等 Soul 验收；不要问主人要不要发版。
