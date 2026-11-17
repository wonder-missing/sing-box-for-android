# Conductor trace review — 2026-11-17

Source: APK `1.15.0-alpha.13-wonder.86`, VFS scopes `workdir`, `appfiles`, `trace`.
The current session covered 23:55:08–00:05:20 (about 10 minutes); 16 API requests, 14 HTTP 200, two 120-second network timeouts. The final assistant answer was partial because the tool step budget was exhausted.

## 后续状态（2026-11-17，wonder.94）

本文是 w86 会话的历史快照，不是当前行为。以下发现已处理：

- release notes 改读 APK 内置 asset（87/88）；
- VFS 前台断开改为进程级 manager + 前台服务，后台不再立即断（90/91）；
- `vfs_connect` 的 missing/visible roots 语义与 `workdir` 覆盖 `conductor/` 已修，93 真机 `conductor_session`/`all` 验证通过（92/93）；
- `ConductorViewModel.init` 的 trace/会话读取已移出主线程，草稿也在 94 移出；
- `pending.log` 是长度前缀的核心日志队列，不是 JSON 批次；92/94 在 runtime 和 buffer 边界过滤 tailscale TRACE。

仍开放：长分析工具预算/continuation、`conductor/` 旧诊断文件清理、120 秒 provider 超时与 Reasoning 前置阶段、trace 体积/驻留策略。后文出现“VFS goes Off”“init main-thread”等描述时，以上述状态为准。

## What it saw

- It did **not** read the old 84 trace from `.runtime/trace.md`; the mounted trace file starts with the current 86 session.
- The old 84 material was already in the app working directory under `conductor/`: `vfs-failure-handoff.md`, `session-trace-w84.md`, `session-transcript-w84.md`, `w84-transcript.md`, and older `w82-*` files. The local `file_list` exposed them before VFS was involved; the model read `conductor/vfs-failure-handoff.md`, whose first line says app `wonder.84`.
- Once VFS connected, `workdir` (`**`) also exposed the same `conductor/` directory to the container. The old files were therefore available both locally and through `/vfs`.

## Behaviour observed

1. **Release notes went to GitHub first.** `release_notes` failed with `Connection reset`, wasting a step before the model found a fallback. The new worktree reads a bundled asset instead.
2. **VFS was offline on the first container attempt.** The first `cpk_docker_run(vfs=true)` returned `VFS_UNAVAILABLE` after 22 ms; a later retry succeeded (5.3 s and 15.1 s). The app provider also reconnected many times while the app moved between foreground and background.
3. **Tool budget stopped the analysis.** The model located `appfiles/logs/app.log`, telemetry files and `api-trace.json`, but never reached the log body analysis. The last `route_call` returned `step_budget_exceeded`; the final answer says this explicitly. The turn is not resumable from the same point.
4. **Scope inference was wrong.** The final answer said `conductor` was not authorized for the container, although the `workdir` scope exposed `/vfs/conductor` and the model had already listed it locally. `conversation` and `system_prompt` were indeed not requested; `conductor` was covered by `workdir`.
5. **Local paths and VFS paths were mixed.** `file_tail(".runtime/trace.md")` correctly failed because local file tools start at the app working directory; the model recovered by using `docker_run` over `/vfs`. The distinction should be stated more explicitly in the tool descriptions.
6. **Two provider requests timed out at 120 s.** These account for most of the wall time; the app retried as designed, but the user sees a long turn with no useful progress.
7. **VFS goes Off on every app background/resume（历史；90 起已改为前台服务保活）。** `VfsRuntime` intentionally disconnects the provider when the app leaves the foreground and calls `reconnectLast()` on return. The 87 worktree now shows a spinner plus `VFS 连接中` during that handshake instead of a misleading Off; it was online while the trace was copied, which is why the mounted files were readable.

## App-side performance findings

These are code-path findings, not measurements attributed to a stack:

- `ConductorViewModel.init` runs on the main thread（历史；93 起 trace/会话读取移出主线程，94 起草稿也异步加载）。It calls `AgentApiTrace.attachPersistence()` (reads and decodes the persisted `conductor/api-trace.json`, 1.1 MB here), `restoreSession()` (reads and decodes the 512 KB-bounded session preference), and `fileStore.ensureWorkspace()` before the screen is ready. That is a plausible startup-jank source; the 399 ms `ui_jank` window at 23:16 is evidence that jank exists, not proof this exact path caused it.
- `persistSession()` was called 25 times in the view model, including from UI event handling. `ConductorSessionStore.encodeBounded()` serializes the session, measures UTF-8 size, and can re-encode several times while halving limits; `SharedPreferences` is then written with `apply()`. The JSON work is now on a single-thread `conductor-session` executor. The remaining startup read was moved off the main thread in 93/94.
- `AgentApiTrace.record()` encodes and rewrites a file of up to 1 MB for every traced model request. It runs on the request thread, not the main thread, but it can delay the next tool/model step and adds I/O load during a long turn.
- The two 120-second `network_timeout` entries dominate the 10-minute turn. The app has a single “waiting for provider” state; there is no DNS/TCP/TLS/first-byte breakdown. This is both a latency and a diagnosability problem.

## App-side logic and race findings

- `VfsRuntime` collected `AppLifecycleObserver.isForeground` and **awaited `reconnectLast()` inside the collector**. A background event arriving during a 1.5 s settle or backoff could not be processed until reconnect returned; a handshake could also outlive foreground state. Fixed by running each lifecycle action in a cancellable child job.
- `VfsConnectionManager.connectLocked()` caught every `Exception`, including `CancellationException`, and converted it into `VFS_CONNECT_FAILED`. Cancelling a foreground reconnect could therefore leave a stale session/state. Fixed by rethrowing cancellation.
- `OkHttpVfsProviderConnector.connect()` did not close its WebSocket/scope when the caller cancelled during the handshake. Fixed with a cancellation branch that closes the socket.
- `VfsRuntime.attach()` replaced its manager without disconnecting the previous active manager. Fixed by closing the previous manager.
- Counting every VFS outbound frame into a `StateFlow` would recompose the status bar for each FUSE chunk. The counter is now throttled to 250 ms or 64 KB.
- The Stop timeout path checks `isRunning` before cancelling the job; a turn can complete between that check and the forced error append. A second completion check should be kept when touching this path.

## Did it find a performance problem?

No. The final answer is explicit that it stopped before reading `app.log` or `api-trace.json`. Its file inventory is a lead sheet, not a diagnosis.

Two of its stated concerns are not supported:

- `telemetry/pending.log` contains pending core log lines, not a telemetry backlog; `last_ack` is a batch hash, not an older timestamp. 92/94 已在该队列的 runtime/buffer 边界过滤 tailscale TRACE。
- A 400 KB profile JSON and a 1 MB API trace are worth watching only after measuring how they are loaded; their size alone does not prove an app performance defect.

The only concrete performance signals in the captured material are two 120-second provider timeouts, a long turn with 16 model requests, and three `ui_jank` windows (one with a 399 ms frame gap). Those are leads for a real follow-up, not a completed performance review.

## Feedback / follow-ups

- P1: give a long VFS analysis a resumable budget or an explicit continuation path. The default tool-round budget was raised from 12 to 20 in the 87 worktree, but a 20-round ceiling is still not a continuation guarantee.
- P1: clean or archive one-off diagnostic files under `conductor/`; otherwise every new session can read stale `w84`/`w82` handoffs and misattribute current behaviour to an old build.
- P2（已修）: make `vfs_connect` output map to concrete visible roots, or add a prompt rule to inspect `/vfs` after connecting instead of inferring from scope names.
- P2: clarify that `.runtime/*` is VFS-only and that `conductor/` is part of `workdir`; the current local-vs-VFS split is easy for the model to get wrong.
- Done in the 87 worktree: release notes are bundled in the APK; VFS status toggles the sharing card; Stop has a one-second cancellation bound.
