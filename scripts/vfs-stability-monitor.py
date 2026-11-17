#!/usr/bin/env python3
"""Poll vfsd /status and the FUSE mount while the phone keeps a VFS Link open.

Default run: every 30 s for 30 min. Stop early after three consecutive unhealthy
samples so the failure can be analyzed. Tokens are read from the vfsd endpoint
directory and never printed or written to the result files.

Usage:
    scripts/vfs-stability-monitor.py
    scripts/vfs-stability-monitor.py --duration 1800 --interval 30
    VFS_MONITOR_OUT=~/some/dir scripts/vfs-stability-monitor.py

Requires the deployed vfsd container (`vfsd-vm` by default) and Docker access.
The FUSE checks run inside that container because the mount lives in the
OrbStack Linux VM, not in the macOS filesystem.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

DEFAULT_ENDPOINT = Path.home() / ".clawdy-portal/vfsd/endpoint.json"
DEFAULT_CONTAINER = "vfsd-vm"
TEST_FILE = "conductor/vfs-bg-test.md"
PROVIDER_ID_RE = re.compile(r"^p_[0-9a-f]{16}$")
CHILD_TIMEOUT_S = 10.0
STATUS_TIMEOUT_S = 5.0


def now_ms() -> int:
    return int(time.time() * 1000)


def local_time(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000).astimezone().isoformat(timespec="seconds")


class VfsdEndpoint:
    def __init__(self, path: Path) -> None:
        data = json.loads(path.read_text(encoding="utf-8"))
        if data.get("version") != 1:
            raise RuntimeError(f"unsupported endpoint version: {data.get('version')!r}")
        port = data.get("port")
        token_file = data.get("tokenFile")
        if not isinstance(port, int) or not (0 < port < 65536):
            raise RuntimeError("endpoint port is invalid")
        if not isinstance(token_file, str) or not token_file or "/" in token_file or token_file in {".", ".."}:
            raise RuntimeError("endpoint tokenFile is invalid")
        token_path = path.parent / token_file
        token = token_path.read_bytes().decode("ascii").strip()
        if not token:
            raise RuntimeError("capability token is empty")
        self.port = port
        self.token = token

    def status(self) -> tuple[dict, float, str | None]:
        request = urllib.request.Request(
            f"http://127.0.0.1:{self.port}/status",
            headers={
                "Authorization": f"Bearer {self.token}",
                "Host": f"127.0.0.1:{self.port}",
            },
        )
        # Local loopback must not inherit a developer proxy.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        started = time.monotonic()
        try:
            with opener.open(request, timeout=STATUS_TIMEOUT_S) as response:
                raw = response.read(2 * 1024 * 1024)
                if response.status != 200:
                    return {}, (time.monotonic() - started) * 1000, f"http_{response.status}"
                return json.loads(raw.decode("utf-8")), (time.monotonic() - started) * 1000, None
        except urllib.error.HTTPError as error:
            return {}, (time.monotonic() - started) * 1000, f"http_{error.code}"
        except Exception as error:  # noqa: BLE001 - report the probe failure
            return {}, (time.monotonic() - started) * 1000, type(error).__name__


def docker_exec(container: str, command: str) -> tuple[int, bytes, str, float]:
    started = time.monotonic()
    try:
        completed = subprocess.run(
            ["docker", "exec", container, "sh", "-lc", command],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=CHILD_TIMEOUT_S,
            check=False,
        )
        return (
            completed.returncode,
            completed.stdout,
            completed.stderr.decode("utf-8", errors="replace").strip(),
            (time.monotonic() - started) * 1000,
        )
    except subprocess.TimeoutExpired:
        return 124, b"", f"timeout_{CHILD_TIMEOUT_S:g}s", (time.monotonic() - started) * 1000
    except FileNotFoundError:
        return 127, b"", "docker_not_found", (time.monotonic() - started) * 1000
    except Exception as error:  # noqa: BLE001 - report the probe failure
        return 1, b"", type(error).__name__, (time.monotonic() - started) * 1000


def choose_target(providers: list[dict]) -> str | None:
    valid = [p for p in providers if isinstance(p.get("providerId"), str) and PROVIDER_ID_RE.match(p["providerId"])]
    ready = [
        p
        for p in valid
        if p.get("online") is True and p.get("mounted") is True and p.get("readOnly") is True
    ]
    online = [p for p in valid if p.get("online") is True]
    pool = ready or online or valid
    if not pool:
        return None
    return max(pool, key=lambda p: int(p.get("lastSeen") or 0))["providerId"]


def provider_snapshot(provider: dict | None, now: int) -> dict | None:
    if provider is None:
        return None
    last_seen = provider.get("lastSeen")
    last_seen = int(last_seen) if isinstance(last_seen, (int, float)) else 0
    refs = provider.get("refs") if isinstance(provider.get("refs"), dict) else {}
    return {
        "providerId": provider.get("providerId"),
        "online": provider.get("online") is True,
        "lastSeen": last_seen,
        "lastSeenAgeMs": max(0, now - last_seen) if last_seen else None,
        "mounted": provider.get("mounted") is True,
        "readOnly": provider.get("readOnly") is True,
        "generation": provider.get("generation"),
        "refs": refs.get("count"),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--duration", type=int, default=1800, help="total seconds (default 1800)")
    parser.add_argument("--interval", type=float, default=30.0, help="seconds between polls (default 30)")
    parser.add_argument("--max-failures", type=int, default=3, help="consecutive failures before stopping (default 3)")
    parser.add_argument("--container", default=os.environ.get("VFSD_VM_NAME", DEFAULT_CONTAINER))
    parser.add_argument("--endpoint", type=Path, default=Path(os.environ.get("VFSD_ENDPOINT_FILE", DEFAULT_ENDPOINT)).expanduser())
    parser.add_argument("--out", type=Path, default=None)
    args = parser.parse_args()

    if args.duration <= 0 or args.interval <= 0 or args.max_failures <= 0:
        parser.error("duration, interval and max-failures must be positive")

    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    out_dir = args.out or (Path.cwd() / "build" / f"vfs-monitor-{stamp}")
    out_dir.mkdir(parents=True, exist_ok=True)
    samples_path = out_dir / "samples.jsonl"
    log_path = out_dir / "monitor.log"
    summary_path = out_dir / "summary.md"

    started_ms = now_ms()
    started = time.monotonic()
    deadline = started + args.duration

    target_id: str | None = None
    target_switches = 0
    baseline_sha: str | None = None
    baseline_bytes: int | None = None
    last_generation: int | None = None
    last_online: bool | None = None
    events: list[str] = []
    failures = 0
    total = 0
    healthy_count = 0
    first_failure_at: str | None = None
    last_sample: dict | None = None
    stop_reason = "duration_reached"

    def write_log(line: str) -> None:
        with log_path.open("a", encoding="utf-8") as handle:
            handle.write(line + "\n")
        print(line, flush=True)

    def write_summary(reason: str) -> None:
        nonlocal stop_reason
        stop_reason = reason
        finished_ms = now_ms()
        summary = {
            "startedAt": local_time(started_ms),
            "finishedAt": local_time(finished_ms),
            "durationSeconds": round((time.monotonic() - started), 1),
            "reason": reason,
            "targetProviderId": target_id,
            "targetSwitches": target_switches,
            "samples": total,
            "healthy": healthy_count,
            "unhealthy": total - healthy_count,
            "maxConsecutiveFailures": max_failures_seen,
            "firstFailureAt": first_failure_at,
            "baselineSha256": baseline_sha,
            "baselineBytes": baseline_bytes,
            "lastSample": last_sample,
            "events": events[-50:],
            "output": str(out_dir),
        }
        (out_dir / "summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        lines = [
            "# VFS stability monitor",
            "",
            f"- Started: {summary['startedAt']}",
            f"- Finished: {summary['finishedAt']}",
            f"- Reason: `{reason}`",
            f"- Target: `{target_id or 'not found'}`",
            f"- Target/provider switches: {target_switches}",
            f"- Samples: {total} ({healthy_count} healthy, {total - healthy_count} unhealthy)",
            f"- Max consecutive failures: {max_failures_seen}",
            f"- Baseline SHA-256: `{baseline_sha or 'n/a'}`",
            f"- Last sample: `{json.dumps(last_sample, ensure_ascii=False) if last_sample else 'n/a'}`",
            "",
            "## Events",
            "",
        ]
        lines.extend(f"- {event}" for event in summary["events"]) if summary["events"] else lines.append("- none")
        lines.extend(["", f"Raw samples: `{samples_path}`", ""])
        summary_path.write_text("\n".join(lines), encoding="utf-8")

    max_failures_seen = 0

    def handle_stop(signum: int, _frame: object) -> None:
        raise SystemExit(128 + signum)

    signal.signal(signal.SIGINT, handle_stop)
    signal.signal(signal.SIGTERM, handle_stop)

    try:
        endpoint = VfsdEndpoint(args.endpoint)
    except Exception as error:  # noqa: BLE001 - fail before the loop
        write_log(f"startup failed: {type(error).__name__}: {error}")
        total = 0
        write_summary("startup_error")
        return 2

    write_log(
        f"monitor start {local_time(started_ms)} duration={args.duration}s "
        f"interval={args.interval:g}s max_failures={args.max_failures} out={out_dir}"
    )
    write_log("token: read from endpoint directory, never logged")

    try:
        while time.monotonic() < deadline:
            sample_started = time.monotonic()
            ts = now_ms()
            status, status_ms, status_error = endpoint.status()
            providers = status.get("providers") if isinstance(status.get("providers"), list) else []
            valid_providers = [p for p in providers if isinstance(p, dict)]
            if target_id is None:
                target_id = choose_target(valid_providers)
                if target_id is not None:
                    events.append(f"{local_time(ts)} target selected: {target_id}")
                    write_log(f"target selected: {target_id}")
            target = next((p for p in valid_providers if p.get("providerId") == target_id), None)
            # A wider scope creates a new Link and a new providerId. Follow the
            # new online mount instead of counting the intentional handoff as
            # three failed polls; the switch stays visible in events/summary.
            target_ready = bool(
                target
                and target.get("online") is True
                and target.get("mounted") is True
                and target.get("readOnly") is True
            )
            if target_id is not None and not target_ready:
                next_id = choose_target(
                    [p for p in valid_providers if p.get("providerId") != target_id]
                )
                if next_id is not None:
                    events.append(
                        f"{local_time(ts)} target switched: {target_id} -> {next_id}"
                    )
                    write_log(f"target switched: {target_id} -> {next_id}")
                    target_id = next_id
                    target_switches += 1
                    last_generation = None
                    last_online = None
                    target = next(
                        (p for p in valid_providers if p.get("providerId") == target_id),
                        None,
                    )
            snapshot = provider_snapshot(target, ts)

            fuse_list_ok = False
            fuse_read_ok: bool | None = None
            fuse_bytes: int | None = None
            fuse_sha: str | None = None
            fuse_changed: bool | None = None
            fuse_ms: float | None = None
            fuse_error: str | None = None
            if target_id and PROVIDER_ID_RE.match(target_id):
                base = f"/opt/vfsgate/mnt/{target_id}/conductor"
                list_rc, _, list_err, fuse_ms = docker_exec(args.container, f"ls -1 {base}")
                fuse_list_ok = list_rc == 0
                if not fuse_list_ok:
                    fuse_error = f"list: {list_err or list_rc}"
                read_rc, read_body, read_err, read_ms = docker_exec(args.container, f"cat {base}/vfs-bg-test.md")
                if read_rc == 0:
                    fuse_read_ok = True
                    fuse_bytes = len(read_body)
                    fuse_sha = hashlib.sha256(read_body).hexdigest()
                    if baseline_sha is None:
                        baseline_sha = fuse_sha
                        baseline_bytes = fuse_bytes
                    else:
                        fuse_changed = fuse_sha != baseline_sha
                    fuse_ms = max(fuse_ms, read_ms)
                elif "No such file" in read_err or "not found" in read_err.lower():
                    fuse_read_ok = None
                    fuse_error = (fuse_error + "; " if fuse_error else "") + "test file missing"
                else:
                    fuse_read_ok = False
                    fuse_error = (fuse_error + "; " if fuse_error else "") + f"read: {read_err or read_rc}"
                    fuse_ms = max(fuse_ms, read_ms)

            status_ok = status_error is None and status.get("ok") is True
            status_healthy = bool(
                snapshot
                and snapshot["online"]
                and snapshot["mounted"]
                and snapshot["readOnly"]
            )
            fuse_healthy = fuse_list_ok and (fuse_read_ok is not False)
            healthy = status_ok and status_healthy and fuse_healthy
            if healthy:
                failures = 0
                healthy_count += 1
            else:
                failures += 1
                max_failures_seen = max(max_failures_seen, failures)
                if first_failure_at is None:
                    first_failure_at = local_time(ts)
                events.append(
                    f"{local_time(ts)} unhealthy #{failures}: status={status_error or 'ok'} "
                    f"online={snapshot['online'] if snapshot else None} "
                    f"mounted={snapshot['mounted'] if snapshot else None} "
                    f"readOnly={snapshot['readOnly'] if snapshot else None} "
                    f"fuse_list={fuse_list_ok} fuse_read={fuse_read_ok} error={fuse_error or 'none'}"
                )

            if snapshot:
                generation = snapshot["generation"]
                if isinstance(generation, (int, float)):
                    if last_generation is not None and generation != last_generation:
                        events.append(
                            f"{local_time(ts)} generation {last_generation} -> {generation}"
                        )
                    last_generation = int(generation)
                if last_online is not None and snapshot["online"] != last_online:
                    events.append(
                        f"{local_time(ts)} online {last_online} -> {snapshot['online']}"
                    )
                last_online = snapshot["online"]

            sample = {
                "ts": ts,
                "localTime": local_time(ts),
                "elapsedSeconds": round(time.monotonic() - started, 1),
                "healthy": healthy,
                "consecutiveFailures": failures,
                "status": {
                    "ok": status_ok,
                    "error": status_error,
                    "latencyMs": round(status_ms, 1),
                    "providerCount": len(valid_providers),
                    "onlineProviderIds": [
                        p.get("providerId") for p in valid_providers if p.get("online") is True
                    ],
                },
                "target": snapshot,
                "fuse": {
                    "listOk": fuse_list_ok,
                    "readOk": fuse_read_ok,
                    "testFile": TEST_FILE,
                    "bytes": fuse_bytes,
                    "sha256": fuse_sha,
                    "changedFromBaseline": fuse_changed,
                    "latencyMs": round(fuse_ms, 1) if fuse_ms is not None else None,
                    "error": fuse_error,
                },
            }
            last_sample = sample
            total += 1
            with samples_path.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps(sample, ensure_ascii=False) + "\n")

            marker = "OK " if healthy else "FAIL"
            write_log(
                f"[{total:02d}] {sample['localTime']} {marker} "
                f"online={snapshot['online'] if snapshot else None} "
                f"mounted={snapshot['mounted'] if snapshot else None} "
                f"fuse={fuse_list_ok}/{fuse_read_ok} "
                f"status={status_ms:.0f}ms fuse={fuse_ms if fuse_ms is not None else '-'}ms "
                f"failures={failures}"
            )
            if failures >= args.max_failures:
                write_log(f"stopping early: {failures} consecutive unhealthy samples")
                write_summary("three_consecutive_failures")
                return 1

            now = time.monotonic()
            sleep_for = min(args.interval, max(0.0, deadline - now))
            if sleep_for > 0:
                time.sleep(sleep_for)

        write_log("30 minute window complete")
        write_summary("duration_reached")
        return 0
    except SystemExit:
        write_log("interrupted")
        write_summary("interrupted")
        raise
    except Exception as error:  # noqa: BLE001 - preserve evidence
        write_log(f"monitor crashed: {type(error).__name__}: {error}")
        write_summary("monitor_error")
        return 2
    finally:
        try:
            if not summary_path.exists():
                write_summary(stop_reason)
        except Exception:  # noqa: BLE001 - never mask the original failure
            pass


if __name__ == "__main__":
    sys.exit(main())
