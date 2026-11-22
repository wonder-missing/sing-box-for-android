# 本地活动日志与事件点阵图（2026-11-22）

状态：wonder.AG 起有本地活动页，wonder.AK 完成点阵图分诊版。实现位于
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
| `available`, `total`, `ok`, `changed` | URLTest 可用数、检查结果与是否有更新 |
| `severity` | `INFO` / `WARN` / `ERROR`；合并时取最高 |
| `evidence` | `CRASH` / `OOM` + 报告 id，详情页据此打开报告 |
| `obstacle` | 是否算问题；同时决定是否在时间轴上投射菱形 |
| `count` | 聚合数量；连接错误和合并事件使用 |

`ActivityLane` 的枚举顺序就是图表自上而下的深度，新增轨道只能追加：

`SERVICE`, `NETWORK`, `CONNECTION`, `TAILSCALE`, `POWER`, `TELEMETRY`,
`SUBSCRIPTION`, `URLTEST`, `GROUP`, `VERSION`, `SYSTEM`。

## 存储与保留

- 文件：`files/activity/events.jsonl`，单文件上限 4 MB，写临时文件后原子替换。
- 保留：48 小时、最多 4,000 条；超限丢最旧的。
- 合并：`TELEMETRY`、`URLTEST`（仅 `urltest`）和 `CONNECTION` 错误按
  5 分钟桶合并；保留最高 severity、任一 `obstacle`、`evidence`，`count` 相加。
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
| `connection_error` | CONNECTION | 拨号尝试按 5 分钟桶 + 错误类别聚合 |
| `tailscale_state` | TAILSCALE | 内核日志 `Switching ipn state A -> B` |
| `power_state` | POWER | 电源广播；只在状态变化时落盘 |
| `telemetry_upload` | TELEMETRY | 每次上传尝试（成功或失败） |
| `profile_update` / `profile_switch` | SUBSCRIPTION | 订阅检查完成、用户切换配置 |
| `urltest` / `urltest_health` | URLTEST | 内核日志、Groups 快照健康翻转 |
| `group_switch` | GROUP | Groups 流的前后快照差异 |
| `app_install` / `app_update` | VERSION | 启动时版本码变化，时间取 PackageManager |
| `crash` / `oom` / `process_exit` | SYSTEM | 报告刷新、`ActivityManager` 退出历史 |

## 图表规则（事件点阵图）

这套规则借鉴 event raster / strip plot / actogram，以及把问题事件投影到
时间轴的 rug plot。目标是密度和指向性，不做装饰：

- X 轴是时间，Y 轴是事件所在轨道的垂直深度；不画网格，左侧不画标签。
- 顶部图例是唯一的形状说明；形状标识轨道，颜色只做强化。轨道顺序与图例
  顺序一致。
- 周期性事件画在所属 5 分钟桶的起点，而不是桶内第一次上传的时间，避免
  上传触发间隔造成虚假的不均匀。
- `SERVICE` 轨道先画会话线，再画点；窗口开始前已运行的服务从窗口左边开始。
- `obstacle` 事件在 X 轴上额外投射一个红色菱形；原轨道标记保留。
- 选中标记只加描边圆环和一条贯穿所有轨道的竖线，不覆盖原形状；同一事件的
  轴投影也加环。
- 点击半径 22dp；详情底部表显示时间、组、前后值、原因、耗时、可用数、
  计数和备注。
- 「聚焦 ±15 分钟」把范围连续地设为事件前后 15 分钟；「±10 分钟内另有
  N 个事件」从存储查询，不受当前可见窗口裁剪。

## 新增事件类型

1. 在 `ActivityTypes` 加常量，wire name 用 snake_case。
2. 选已有轨道；只有新语义才追加轨道（会改变纵向顺序）。
3. 在 `ActivityLog` 加 `recordX`，优先复用现有 record 方法。
4. 从最窄的既有钩子调用，避免在热循环里分配。
5. 在 `ActivityScreen` 加标题映射，`values/` 与 `values-zh-rCN/` 同步。
6. 可合并事件补 reducer 单元测试；纯函数补纯函数测试。

## 明确不做

- 不把活动日志并入遥测上传队列，也不保留 `events.log`。
- 不做健康评分、自动诊断或定时报告。
- 不把节点组说成「当前组」：组由路由规则选中，切换事件只记录观测到的变化。
- 不加左侧标签、网格或图上的解释性文字。
