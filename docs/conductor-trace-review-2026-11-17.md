# Conductor trace review — 2026-11-17

Source: APK `1.15.0-alpha.13-wonder.86`, VFS scopes `workdir`, `appfiles`, `trace`.
The current session covered 23:55:08–00:05:20 (about 10 minutes); 16 API requests, 14 HTTP 200, two 120-second network timeouts. The final assistant answer was partial because the tool step budget was exhausted.

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
7. **VFS goes Off on every app background/resume.** `VfsRuntime` intentionally disconnects the provider when the app leaves the foreground and calls `reconnectLast()` on return. The status chip is Off during that handshake; it is not evidence that the link failed. The provider was online while the trace was copied; that is why the mounted files were readable.

## Did it find a performance problem?

No. The final answer is explicit that it stopped before reading `app.log` or `api-trace.json`. Its file inventory is a lead sheet, not a diagnosis.

Two of its stated concerns are not supported:

- `telemetry/pending.log` contains pending core log lines, not a telemetry backlog; `last_ack` is a batch hash, not an older timestamp.
- A 400 KB profile JSON and a 1 MB API trace are worth watching only after measuring how they are loaded; their size alone does not prove an app performance defect.

The only concrete performance signals in the captured material are two 120-second provider timeouts, a long turn with 16 model requests, and three `ui_jank` windows (one with a 399 ms frame gap). Those are leads for a real follow-up, not a completed performance review.

## Feedback / follow-ups

- P1: give a long VFS analysis a resumable budget or an explicit continuation path; 12 tool rounds is too small for “mount, inspect, analyze logs, compare trace”.
- P1: clean or archive one-off diagnostic files under `conductor/`; otherwise every new session can read stale `w84`/`w82` handoffs and misattribute current behaviour to an old build.
- P2: make `vfs_connect` output map to concrete visible roots, or add a prompt rule to inspect `/vfs` after connecting instead of inferring from scope names.
- P2: clarify that `.runtime/*` is VFS-only and that `conductor/` is part of `workdir`; the current local-vs-VFS split is easy for the model to get wrong.
- Done in the 87 worktree: release notes are bundled in the APK; VFS status toggles the sharing card; Stop has a one-second cancellation bound.
