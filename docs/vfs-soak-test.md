# VFS 浸泡测试

状态：2026-11-17 建立。脚本：[`scripts/vfs-soak.py`](../scripts/vfs-soak.py)。短测结果和修复历史见 [`vfs-connection-stability.md`](vfs-connection-stability.md)。

## 目标

回答三件事：

1. 手机切后台、锁屏、通知栏操作后，同一个 VFS Link 能否持续供数据；
2. 路径短暂抖动或多长时间内能自动恢复，而不是等用户手动申请新 Link；
3. 夜间长时间运行时，哪些故障是暂时的、哪些已经不可恢复，需要熔断留下证据。

## 判定口径

一个样本健康，必须同时满足：

- vfsd `/status` 返回 200 且 `ok=true`；
- 目标 provider `online`、`mounted`、`readOnly`；
- 容器内 FUSE `ls` 和读 `conductor/vfs-bg-test.md` 成功（文件暂时缺失不算目录失败）；
- 如果本次样本触发消费者检查，独立 `docker run -v <mount>:/vfs:ro` 读取成功。

有意变更不算故障：

- provider 切换（扩大/更换 scope、重建 Link）：自动跟随新的 online provider，重置连续失败计数，并给 `--grace-samples` 个样本宽限；
- app 版本变化：同样重置并宽限。

## 熔断

避免因临时故障提前停掉：

- **连续失败**和**持续不健康时长**必须同时超过阈值才熔断（默认 10 次且 10 分钟）。
- 真实短测可以显式收紧，例如 `--max-consecutive-failures 3 --max-outage 90s`。

不可恢复条件走硬熔断：

- endpoint 文件缺失/无效、持续 401/403（重读 endpoint 后仍失败）；
- Docker CLI 不存在或容器持续不存在；
- 默认连续 5 次硬错误。

可选的比例熔断：`--max-failure-ratio 0.2`（默认 0，关闭）。长时间低比例抖动只记录，不提前停。

## 参数

| 参数 | 默认 | 说明 |
|---|---|---|
| `--duration` | `30m` | 总时长，支持 `30s`/`30m`/`8h`/`1d` |
| `--interval` | `30s` | 轮询间隔 |
| `--max-consecutive-failures` | `10` | 连续失败次数阈值 |
| `--max-outage` | `10m` | 连续不健康时长阈值 |
| `--max-hard-failures` | `5` | endpoint/auth/container 硬错误阈值 |
| `--grace-samples` | `2` | provider 切换或 app 升级后的宽限样本数 |
| `--max-failure-ratio` | `0` | 可选的总不健康比例熔断，0 关闭 |
| `--ratio-min-samples` | `20` | 比例熔断生效前的最小样本数 |
| `--consumer-every` | `10` | 每 N 个样本跑一次真实消费者容器；0 关闭 |
| `--target-provider` | 自动 | 固定一个 providerId，不自动跟随切换 |
| `--container` | `vfsd-vm` | vfsd 容器名 |
| `--endpoint` | `~/.clawdy-portal/vfsd/endpoint.json` | endpoint 发现文件 |
| `--portal-log` | `~/Library/Logs/clawdy-portal.log` | Portal 审计日志；结束时抓取窗口内 VFS 事件 |
| `--no-capture-logs` | 关闭 | 不抓取 Portal/vfsd 日志 |
| `--out` | `build/vfs-soak-<时间>` | 输出目录；同一目录用文件锁防止重复启动 |
| `--self-test` | - | 校验时长解析、目标选择等纯逻辑 |

## 运行

短测（30 分钟，故障即停）：

```sh
cd ~/workspace/WonderSing
./scripts/vfs-soak.py --duration 30m --interval 30s \
  --max-consecutive-failures 3 --max-outage 90s --consumer-every 10
```

夜间 8 小时（先确认 VFS 已连接）：

```sh
cd ~/workspace/WonderSing
caffeinate -dimsu ./scripts/vfs-soak.py --duration 8h --interval 60s \
  --max-consecutive-failures 10 --max-outage 10m \
  --consumer-every 20 --out "build/vfs-soak-night-$(date +%Y%m%d-%H%M)"
```

- `caffeinate` 防止 Mini 睡眠；不改变系统电源设置。
- 建议放在 tmux 里跑，避免 SSH 断开带走前台 shell；脚本本身也会写锁文件。
- 结束或中途停止：`Ctrl-C` 或 `kill -TERM <pid>`，脚本会写 summary。
- 确认 endpoint 可读、vfsd 容器在跑，再启动；无 online provider 时脚本会在阈值后熔断，不会假装在测。

## 输出

| 文件 | 内容 |
|---|---|
| `samples.jsonl` | 每个样本的 status、provider、FUSE、app 版本/阶段、消费者结果、熔断字段 |
| `summary.md` / `summary.json` | 健康率、outage 列表、最长 outage、延迟分位、provider 切换、app 版本、Portal 事件计数 |
| `portal-vfs-events.log` | 测试窗口内 Portal 的 VFS connect/disconnect/link 事件 |
| `vfsd-events.log` | 测试窗口内 vfsd 的 provider/mount/error/watchdog 事件 |
| `soak.log` | 逐样本人读日志 |
| `soak.lock` | 同目录单实例锁 |

日志不含 capability token；Portal/vfsd 抓取只保留 VFS/provider/mount/error 相关行。

## 验收

一次合格的夜间浸泡：

- 健康率 ≥ 99%，没有触发熔断；
- 每次短暂 outage 都在 `--max-outage` 内恢复，summary 有明确的 start/end/reasons；
- 目标 provider 的 online/mounted/readOnly 始终满足；消费者检查全部成功，或失败后有恢复记录；
- `providerId` 只因为计划内的 scope 变更/升级而切换，且切换后自动恢复；
- Portal 没有无法解释的 1006/4000 长连接；vfsd 没有反复 watchdog/restart；
- `vfs_lifecycle` 能看到 `background`/`screen_off`/`screen_on`（从 wonder.92 起；93/94 已验证）。

不合格或熔断时，先读 `summary.md` 的 Outages 和 Events，再看 `portal-vfs-events.log` / `vfsd-events.log`；不要直接重跑掩盖现场。

## 已知基线

- 2026-11-17 第一轮 30 分钟：前 5 次健康，08:09:42 手机到 Portal 路径 `1006`，App 未自动重连；详见 [`vfs-connection-stability.md`](vfs-connection-stability.md)。wonder.91 加入 20 秒协议 ping。
- 2026-11-17 第二轮 30 分钟（wonder.90）：56/56 健康，6/6 消费者读取成功，无 provider 切换。该轮不包含 91 的死链修复。
- 2026-11-17 5 分钟观察（wonder.93）：10/10 健康，2/2 消费者读取，0 outage，0 provider 重连；这是短时验证，不等于整夜结论。
- 夜间测试应使用 wonder.94 或更新版本；它包含 91 的死链检测、92 的生命周期遥测/恢复策略、93 的 UI/启动修复和 94 的 telemetry/草稿修复。
- 大文件批量读取（如 1 MB trace）在当前蜂窝 + Tailscale 下可能需要 25–28 秒；浸泡脚本的小文件探针不受影响，分析大文件前先看 TODO。
