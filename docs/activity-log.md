# 本地活动日志与事件点阵图（2026-11-22）

状态：wonder.AG 起有本地活动页，wonder.AK 完成点阵图分诊版，wonder.AL
按状态着色并加入组级异常，wonder.AM 移除节点级 URLTest 标记。实现位于
`app/src/main/java/io/nekohasekai/sfa/activity/`。

## 要回答的问题

用户体感不舒服时，先给出时间点和范围，再决定去查哪份证据：

- 服务何时启动、停止、被系统撤销或恢复失败。
- 网络何时切换、丢失，Tailscale 状态何时翻转。
- 订阅何时检查、是否有更新，节点组何时切换、切换原因和可用节点数。
- 入口拨号何时开始失败、失败类别和数量。
- 手机何时熄屏、进入 doze 或省电模式，用于解释遥测和连接的空档。
- 崩溃、OOM、异常进程退出发生在什么时候，并直接打开对应报告。

它不是健康评分，也不是夜间体检；任意 24 小时窗口都可以回看，默认看最近
24 小时，保留 48 小时。

## 数据模型

`ActivityEvent` 是唯一落盘结构，字段按用途分组：

| 字段 | 含义 |
| --- | --- |
| `id`, `ts`, `lastTs` | 本地自增 id；首次和最近一次时间（合并事件用 `lastTs`） |
| `lane`, `type` | 纵向轨道和事件类型；`ActivityTypes` 的常量是唯一 wire name |
| `subject`, `detail` | 主体和自由说明（节点、订阅、网络、错误类别等） |
| `group`, `from`, `to`, `reason`, `ms` | 组名、切换前后、切换原因、耗时 |
| `available`, `total`, `ok`, `changed` | 组可用节点数、检查结果与是否有更新 |
| `severity` | `INFO` / `WARN` / `ERROR`；合并时取最高 |
| `evidence` | `CRASH` / `OOM` + 报告 id，详情页据此打开报告 |
| `obstacle` | 是否算问题；同时决定是否在时间轴上投射菱形 |
| `count` | 聚合数量；连接错误和合并事件使用 |

`ActivityLane` 的枚举顺序就是图表自上而下的深度，新增轨道只能追加：

`SERVICE`, `NETWORK`, `CONNECTION`, `TAILSCALE`, `POWER`, `TELEMETRY`,
`SUBSCRIPTION`, `GROUP`, `VERSION`, `SYSTEM`。

## 存储与保留

- 文件：`files/activity/events.jsonl`，单文件上限 4 MB，写临时文件后原子替换。
- 保留：48 小时、最多 4,000 条；超限丢最旧的。
- 合并：`TELEMETRY` 和 `CONNECTION` 错误按 5 分钟桶合并；保留最高 severity、
  任一 `obstacle`、`evidence`，`count` 相加。
- 关闭时：不打开文件、不解析内核日志、不写事件；热路径只做一次偏好检查。

## 采集点

| 事件 | 轨道 | 触发 |
| --- | --- | --- |
| `service_start` / `service_stop` | SERVICE | `TelemetryRuntime.attach` / `detachAndFlushAsync` |
| `service_alert` | SERVICE | `BoxService.stopAndAlert` |
| `vpn_revoked` | SERVICE | `BoxService.onRevoke` |
| `service_restore` | SERVICE | `MainActivity` 启动恢复、`BootReceiver` |
| `config_reload` | SERVICE | 服务重载、订阅更新后重载 |
| `network_change` / `network_lost` | NETWORK | `TelemetrySignals` 网络回调 |
| `connection_error` | CONNECTION | 拨号尝试按 5 分钟桶 + 错误类型（`reason`）聚合 |
| `tailscale_state` | TAILSCALE | 内核日志 `Switching ipn state A -> B` |
| `power_state` | POWER | 电源广播；只在状态变化时落盘 |
| `telemetry_upload` | TELEMETRY | 每次上传尝试（成功或失败）；没有物理上行时不尝试 |
| `profile_update` / `profile_switch` | SUBSCRIPTION | 订阅检查完成、用户切换配置 |
| `group_switch` | GROUP | Groups 流的前后快照差异 |
| `group_unavailable` / `group_stuck` | GROUP | Groups 快照的组级健康判断 |
| `app_install` / `app_update` | VERSION | 启动时版本码变化，时间取 PackageManager |
| `crash` / `oom` / `process_exit` | SYSTEM | 报告刷新、`ActivityManager` 退出历史 |

## 什么算异常

- 节点级 URLTest 结果不写入本地活动日志。一个 27 节点的组里长期有 10 个节点
  不可用、另有 13 个可用是正常状态；把每次单节点失败画成一个事件或问题点，
  反映不了用户实际走的路径。节点级结果仍由遥测上报。
- 组内没有任何可用节点（`availableCount == 0`）记一次 `group_unavailable`，
  这是组级异常。启动后有 1 分钟宽限，并要求 0 可用状态再持续 1 分钟，避免
  首次测速尚未完成时误报。
- 选中的节点持续失败 10 分钟仍未切换，记一次 `group_stuck`；组内没有可用节点
  期间不重复记 stuck，避免同一段故障两个标记。
- 连接错误按 5 分钟桶和错误类型聚合；没有物理上行网络时不上传遥测、不记失败
  事件。队列留在磁盘，网络恢复后的下一轮再传。
- 单选组（selector）没有测试语义，不参与组级健康判断。

## 图表规则（事件点阵图）

这套规则借鉴 event raster / strip plot / actogram，以及把问题事件投影到
时间轴的 rug plot。目标是密度和指向性，不做装饰：

- X 轴是时间，Y 轴是事件所在轨道的垂直深度；不画网格，左侧不画标签。
- 顶部图例是唯一的形状说明；形状标识轨道，颜色标识状态。轨道顺序与图例
  顺序一致。电源轨道在图例下多一行状态色标（awake / screen off / doze /
  power save），只在窗口里有电源事件时出现。
- 同一轨道内状态用颜色区分：服务停止是灰色、网络丢失/Tailscale 掉线/组异常是
  红色、订阅未变是同心圆、变更或切换是实心、电源四种状态各有颜色。红色只留给
  同时投影到问题轴的事件。
- 周期性事件画在所属 5 分钟桶的起点，而不是桶内第一次上传的时间，避免
  上传触发间隔造成虚假的不均匀。
- `SERVICE` 轨道先画会话线，再画点；窗口开始前已运行的服务从窗口左边开始。
- `obstacle` 事件在 X 轴上额外投射一个红色菱形；原轨道标记保留。
- 选中标记只加描边圆环（`onSurface`，不依赖标记颜色）和一条贯穿所有轨道的
  竖线，不覆盖原形状；同一事件的轴投影也加环。
- 点击半径 22dp；详情用左对齐的两列（标签固定宽度，值紧随其后），显示时间、
  最近时间、组、前后值、原因、结果、耗时、可用数、计数和备注。连接错误的
  错误类型放 `reason`，`group` 只表示组名；聚合事件总是显示 Count。
- 「聚焦 ±15 分钟」把范围连续地设为事件前后 15 分钟；「±10 分钟内另有
  N 个事件」从存储查询，不受当前可见窗口裁剪。

## 新增事件类型

1. 在 `ActivityTypes` 加常量，wire name 用 snake_case。
2. 选已有轨道；只有新语义才追加轨道（会改变纵向顺序）。
3. 在 `ActivityLog` 加 `recordX`，优先复用现有 record 方法。
4. 从最窄的既有钩子调用，避免在热循环里分配。
5. 在 `ActivityScreen` 加标题映射，`values/` 与 `values-zh-rCN/` 同步。
6. 可合并事件补 reducer 单元测试；纯函数补纯函数测试。
7. 组级健康规则放进 `GroupHealthMonitor`，用快照序列写测试，再由
   `TelemetryRuntime.updateGroups` 落事件。

## 明确不做

- 不把活动日志并入遥测上传队列，也不保留 `events.log`。
- 不做健康评分、自动诊断或定时报告。
- 不把节点组说成「当前组」：组由路由规则选中，切换事件只记录观测到的变化。
- 不加左侧标签、网格或图上的解释性文字。
