#!/usr/bin/env python3
"""VFS soak test for the WonderSing provider path.

The script polls vfsd `/status`, the FUSE mount, the app log and (periodically)
a real consumer container. It is designed for long runs: transient outages do
not stop it unless they exceed both a consecutive-failure count and a duration,
while unrecoverable conditions (missing endpoint, repeated auth/container
failures) trip a hard circuit breaker.

Duration and interval accept plain seconds or `30s`, `30m`, `8h`, `1d`.

Examples:
    scripts/vfs-soak.py --duration 30m --interval 30s
    scripts/vfs-soak.py --duration 30m --interval 30s --max-consecutive-failures 3 --max-outage 90s --consumer-every 10
    caffeinate -dimsu scripts/vfs-soak.py --duration 8h --interval 60s --max-consecutive-failures 10 --max-outage 10m --consumer-every 20

Requires the deployed vfsd container (`vfsd-vm` by default) and Docker access.
The capability token is read from the endpoint directory and never logged.
"""

from __future__ import annotations

import argparse
import fcntl
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
from collections import Counter
from datetime import datetime
from pathlib import Path

DEFAULT_ENDPOINT = Path.home() / ".clawdy-portal/vfsd/endpoint.json"
DEFAULT_CONTAINER = "vfsd-vm"
DEFAULT_PORTAL_LOG = Path.home() / "Library/Logs/clawdy-portal.log"
TEST_FILE = "conductor/vfs-bg-test.md"
PROVIDER_ID_RE = re.compile(r"^p_[0-9a-f]{16}$")
STATUS_TIMEOUT_S = 5.0
CHILD_TIMEOUT_S = 10.0
CONSUMER_TIMEOUT_S = 30.0


def parse_seconds(value: str | int | float) -> float:
    if isinstance(value, (int, float)):
        seconds = float(value)
    else:
        match = re.fullmatch(r"\s*(\d+(?:\.\d+)?)\s*([smhd]?)\s*", str(value).lower())
        if not match:
            raise argparse.ArgumentTypeError(f"invalid duration: {value!r}")
        seconds = float(match.group(1)) * {"": 1, "s": 1, "m": 60, "h": 3600, "d": 86400}[match.group(2)]
    if seconds < 0:
        raise argparse.ArgumentTypeError("duration must not be negative")
    return seconds


def now_ms() -> int:
    return int(time.time() * 1000)


def local_time(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000).astimezone().isoformat(timespec="seconds")


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int(round((len(ordered) - 1) * fraction))))
    return round(ordered[index], 1)


class VfsdEndpoint:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.port = 0
        self.token = ""
        self.mtime_ns = 0
        self.load()

    def load(self) -> None:
        data = json.loads(self.path.read_text(encoding="utf-8"))
        if data.get("version") != 1:
            raise RuntimeError(f"unsupported endpoint version: {data.get('version')!r}")
        port = data.get("port")
        token_file = data.get("tokenFile")
        if not isinstance(port, int) or not (0 < port < 65536):
            raise RuntimeError("endpoint port is invalid")
        if not isinstance(token_file, str) or not token_file or "/" in token_file or token_file in {".", ".."}:
            raise RuntimeError("endpoint tokenFile is invalid")
        token = (self.path.parent / token_file).read_bytes().decode("ascii").strip()
        if not token:
            raise RuntimeError("capability token is empty")
        self.port = port
        self.token = token
        self.mtime_ns = self.path.stat().st_mtime_ns

    def maybe_reload(self) -> bool:
        try:
            if self.path.stat().st_mtime_ns == self.mtime_ns:
                return False
            self.load()
            return True
        except Exception:
            return False

    def status(self) -> tuple[dict, float, str | None, bool]:
        reloaded = self.maybe_reload()
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        started = time.monotonic()
        for attempt in range(2):
            request = urllib.request.Request(
                f"http://127.0.0.1:{self.port}/status",
                headers={
                    "Authorization": f"Bearer {self.token}",
                    "Host": f"127.0.0.1:{self.port}",
                },
            )
            try:
                with opener.open(request, timeout=STATUS_TIMEOUT_S) as response:
                    raw = response.read(2 * 1024 * 1024)
                    if response.status != 200:
                        return {}, (time.monotonic() - started) * 1000, f"http_{response.status}", reloaded
                    return json.loads(raw.decode("utf-8")), (time.monotonic() - started) * 1000, None, reloaded
            except urllib.error.HTTPError as error:
                if error.code in (401, 403) and attempt == 0:
                    try:
                        self.load()
                        reloaded = True
                        continue
                    except Exception as reload_error:  # noqa: BLE001 - report the probe failure
                        return {}, (time.monotonic() - started) * 1000, f"endpoint_reload_{type(reload_error).__name__}", reloaded
                return {}, (time.monotonic() - started) * 1000, f"http_{error.code}", reloaded
            except Exception as error:  # noqa: BLE001 - report the probe failure
                return {}, (time.monotonic() - started) * 1000, type(error).__name__, reloaded
        return {}, (time.monotonic() - started) * 1000, "status_retry_exhausted", reloaded


def docker_exec(container: str, command: str, timeout: float = CHILD_TIMEOUT_S) -> tuple[int, bytes, str, float]:
    started = time.monotonic()
    try:
        completed = subprocess.run(
            ["docker", "exec", container, "sh", "-lc", command],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            check=False,
        )
        return (
            completed.returncode,
            completed.stdout,
            completed.stderr.decode("utf-8", errors="replace").strip(),
            (time.monotonic() - started) * 1000,
        )
    except subprocess.TimeoutExpired:
        return 124, b"", f"timeout_{timeout:g}s", (time.monotonic() - started) * 1000
    except FileNotFoundError:
        return 127, b"", "docker_not_found", (time.monotonic() - started) * 1000
    except Exception as error:  # noqa: BLE001 - report the probe failure
        return 1, b"", type(error).__name__, (time.monotonic() - started) * 1000


def read_app_log(container: str, provider_id: str) -> dict:
    command = f"cat /opt/vfsgate/mnt/{provider_id}/appfiles/logs/app.log"
    rc, body, error, latency_ms = docker_exec(container, command)
    if rc != 0:
        return {"ok": False, "error": error or f"cat_exit_{rc}", "latencyMs": round(latency_ms, 1)}
    version: str | None = None
    last_ts: int | None = None
    last_line: str | None = None
    for line in body.decode("utf-8", errors="replace").splitlines()[-500:]:
        parts = line.split("|", 2)
        if len(parts) != 3:
            continue
        if parts[0].isdigit():
            last_ts = int(parts[0])
        last_line = parts[2][:240]
        marker = "App started, version "
        if marker in parts[2]:
            version = parts[2].split(marker, 1)[1].strip()
    return {
        "ok": True,
        "version": version,
        "lastTs": last_ts,
        "lastLine": last_line,
        "latencyMs": round(latency_ms, 1),
    }


def test_stage(body: bytes) -> str | None:
    for line in body.decode("utf-8", errors="replace").splitlines():
        if "阶段:" in line:
            return line.split("阶段:", 1)[1].strip()[:160]
    return None


def consumer_read(container: str, provider_id: str) -> dict:
    command = [
        "docker",
        "run",
        "--rm",
        "--network",
        "none",
        "-v",
        f"/opt/vfsgate/mnt/{provider_id}:/vfs:ro",
        "alpine",
        "cat",
        "/vfs/conductor/vfs-bg-test.md",
    ]
    started = time.monotonic()
    try:
        completed = subprocess.run(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=CONSUMER_TIMEOUT_S,
            check=False,
        )
        body = completed.stdout
        return {
            "ok": completed.returncode == 0 and bool(body),
            "bytes": len(body),
            "sha256": hashlib.sha256(body).hexdigest() if body else None,
            "latencyMs": round((time.monotonic() - started) * 1000, 1),
            "error": None if completed.returncode == 0 else completed.stderr.decode("utf-8", errors="replace").strip()[:240],
        }
    except Exception as error:  # noqa: BLE001 - report the probe failure
        return {
            "ok": False,
            "bytes": None,
            "sha256": None,
            "latencyMs": round((time.monotonic() - started) * 1000, 1),
            "error": type(error).__name__,
        }


def choose_target(providers: list[dict], exclude: str | None = None) -> str | None:
    valid = [
        p
        for p in providers
        if isinstance(p.get("providerId"), str)
        and PROVIDER_ID_RE.match(p["providerId"])
        and p.get("providerId") != exclude
    ]
    ready = [
        p
        for p in valid
        if p.get("online") is True and p.get("mounted") is True and p.get("readOnly") is True
    ]
    online = [p for p in valid if p.get("online") is True]
    pool = ready or online
    if not pool:
        return None
    return max(pool, key=lambda p: int(p.get("lastSeen") or 0))["providerId"]


def target_ready(provider: dict | None) -> bool:
    return bool(
        provider
        and provider.get("online") is True
        and provider.get("mounted") is True
        and provider.get("readOnly") is True
    )


def should_trip(
    failures: int,
    outage_seconds: float,
    max_failures: int,
    max_outage_seconds: float,
) -> bool:
    """Require both a failure count and a wall-clock outage before circuit break."""
    return failures >= max_failures and outage_seconds >= max_outage_seconds


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


def hard_error(status_error: str | None, fuse_error: str | None, docker_error: str | None) -> str | None:
    text = " ".join(x for x in (status_error, fuse_error, docker_error) if x).lower()
    if "docker_not_found" in text:
        return "docker_not_found"
    if "no such container" in text:
        return "container_missing"
    if "endpoint_missing" in text or "endpoint_reload_filenotfounderror" in text:
        return "endpoint_missing"
    if "endpoint_reload" in text:
        return "endpoint_invalid"
    if "http_401" in text or "http_403" in text or "unauthorized" in text or "forbidden" in text:
        return "auth"
    return None


def self_test() -> None:
    assert parse_seconds("8h") == 8 * 3600
    assert parse_seconds("30m") == 1800
    assert parse_seconds("90s") == 90
    assert parse_seconds(45) == 45
    old = {"providerId": "p_893ccd3757f07b6c", "online": False, "mounted": True, "readOnly": True, "lastSeen": 10}
    new = {"providerId": "p_afea11063a6a7a76", "online": True, "mounted": True, "readOnly": True, "lastSeen": 5}
    assert choose_target([old, new]) == "p_afea11063a6a7a76"
    assert choose_target([old, new], exclude="p_afea11063a6a7a76") is None
    assert target_ready(new) and not target_ready(old) and not target_ready(None)
    assert not should_trip(10, 599, 10, 600)
    assert should_trip(10, 600, 10, 600)
    assert not should_trip(9, 600, 10, 600)
    assert should_trip(12, 900, 10, 600)
    print("self-test ok")


def capture_logs(
    out_dir: Path,
    container: str,
    started_at: datetime,
    portal_log: Path,
) -> dict:
    result: dict = {"portalFile": None, "vfsdFile": None, "connect": 0, "disconnect": 0, "portalError": None, "vfsdError": None}
    try:
        if portal_log.is_file():
            kept: list[str] = []
            for line in portal_log.read_text(encoding="utf-8", errors="replace").splitlines():
                try:
                    stamp = datetime.strptime(line[:23], "%Y-%m-%d %H:%M:%S.%f").astimezone()
                except ValueError:
                    continue
                if stamp < started_at or "vfs " not in line:
                    continue
                kept.append(line)
            target = out_dir / "portal-vfs-events.log"
            target.write_text("\n".join(kept) + ("\n" if kept else ""), encoding="utf-8")
            result["portalFile"] = str(target)
            for line in kept:
                if "vfs provider connect" in line:
                    result["connect"] += 1
                elif "vfs provider disconnect" in line:
                    result["disconnect"] += 1
    except Exception as error:  # noqa: BLE001 - logs are optional evidence
        result["portalError"] = type(error).__name__
    try:
        completed = subprocess.run(
            ["docker", "logs", "--since", started_at.astimezone().isoformat(), container],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=CONSUMER_TIMEOUT_S,
            check=False,
        )
        kept = [
            line
            for line in completed.stdout.decode("utf-8", errors="replace").splitlines()
            if any(key in line.lower() for key in ("provider", "fuse", "mount", "watchdog", "restart", "error"))
        ]
        target = out_dir / "vfsd-events.log"
        target.write_text("\n".join(kept) + ("\n" if kept else ""), encoding="utf-8")
        result["vfsdFile"] = str(target)
    except Exception as error:  # noqa: BLE001 - logs are optional evidence
        result["vfsdError"] = type(error).__name__
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--duration", type=parse_seconds, default=1800.0, help="run time, e.g. 30m or 8h (default 30m)")
    parser.add_argument("--interval", type=parse_seconds, default=30.0, help="seconds between polls (default 30s)")
    parser.add_argument("--max-consecutive-failures", type=int, default=10)
    parser.add_argument("--max-outage", type=parse_seconds, default=600.0, help="continuous unhealthy time before circuit break (default 10m)")
    parser.add_argument("--max-hard-failures", type=int, default=5, help="repeated endpoint/auth/container failures before break")
    parser.add_argument("--grace-samples", type=int, default=2, help="ignore failures right after a provider/version change")
    parser.add_argument("--max-failure-ratio", type=float, default=0.0, help="optional unhealthy ratio circuit (0 disables)")
    parser.add_argument("--ratio-min-samples", type=int, default=20)
    parser.add_argument("--consumer-every", type=int, default=10, help="consumer container read every N samples (0 disables)")
    parser.add_argument("--target-provider", default=None, help="pin one providerId instead of following an online target")
    parser.add_argument("--container", default=os.environ.get("VFSD_VM_NAME", DEFAULT_CONTAINER))
    parser.add_argument("--endpoint", type=Path, default=Path(os.environ.get("VFSD_ENDPOINT_FILE", DEFAULT_ENDPOINT)).expanduser())
    parser.add_argument("--portal-log", type=Path, default=DEFAULT_PORTAL_LOG)
    parser.add_argument("--no-capture-logs", action="store_true")
    parser.add_argument("--out", type=Path, default=None)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return 0
    if args.duration <= 0 or args.interval <= 0:
        parser.error("duration and interval must be positive")
    if args.max_consecutive_failures <= 0 or args.max_outage < 0 or args.max_hard_failures <= 0:
        parser.error("failure thresholds must be positive")
    if args.grace_samples < 0 or args.consumer_every < 0 or args.ratio_min_samples < 1:
        parser.error("grace/consumer/ratio values are invalid")
    if not (0.0 <= args.max_failure_ratio <= 1.0):
        parser.error("max-failure-ratio must be between 0 and 1")
    if args.target_provider and not PROVIDER_ID_RE.match(args.target_provider):
        parser.error("target-provider must look like p_<16 lowercase hex>")

    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    out_dir = args.out or (Path.cwd() / "build" / f"vfs-soak-{stamp}")
    out_dir.mkdir(parents=True, exist_ok=True)
    samples_path = out_dir / "samples.jsonl"
    log_path = out_dir / "soak.log"
    summary_path = out_dir / "summary.md"
    lock_path = out_dir / "soak.lock"

    lock_handle = lock_path.open("a+")
    try:
        fcntl.flock(lock_handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        print(f"another soak is already using {out_dir}", file=sys.stderr)
        lock_handle.close()
        return 2

    started_mono = time.monotonic()
    started_ms = now_ms()
    started_at = datetime.fromtimestamp(started_ms / 1000).astimezone()
    started_local = local_time(started_ms)
    deadline = started_mono + args.duration

    try:
        endpoint = VfsdEndpoint(args.endpoint)
    except Exception as error:  # noqa: BLE001 - fail before the loop
        message = f"startup failed: {type(error).__name__}: {error}"
        print(message, file=sys.stderr)
        (out_dir / "summary.md").write_text(
            f"# VFS soak\n\n- Reason: `startup_error`\n- Error: {message}\n",
            encoding="utf-8",
        )
        lock_handle.close()
        return 2

    target_id: str | None = args.target_provider
    pinned = bool(args.target_provider)
    target_switches = 0
    last_app_version: str | None = None
    last_stage: str | None = None
    last_generation: int | None = None
    last_online: bool | None = None
    baseline_sha: str | None = None
    baseline_bytes: int | None = None
    failures = 0
    max_failures_seen = 0
    hard_failures = 0
    hard_reason: str | None = None
    grace_until = 0
    outage_started_mono: float | None = None
    outage_started_local: str | None = None
    outage_samples = 0
    outage_reasons: Counter[str] = Counter()
    outages: list[dict] = []
    total = 0
    healthy_count = 0
    consumer_checks = 0
    consumer_failures = 0
    status_latencies: list[float] = []
    fuse_latencies: list[float] = []
    consumer_latencies: list[float] = []
    last_seen_ages: list[int] = []
    provider_ids: set[str] = set()
    app_versions: set[str] = set()
    stages: set[str] = set()
    events: list[str] = []
    last_sample: dict | None = None
    first_failure_at: str | None = None
    reason = "duration_reached"
    logs_captured: dict | None = None
    summary_written = False

    def write_log(line: str) -> None:
        with log_path.open("a", encoding="utf-8") as handle:
            handle.write(line + "\n")
        print(line, flush=True)

    def event(line: str) -> None:
        events.append(f"{local_time(now_ms())} {line}")

    def close_outage(end_local: str) -> None:
        nonlocal outage_started_mono, outage_started_local, outage_samples, outage_reasons
        if outage_started_mono is None:
            return
        outages.append(
            {
                "start": outage_started_local,
                "end": end_local,
                "seconds": round(time.monotonic() - outage_started_mono, 1),
                "samples": outage_samples,
                "reasons": dict(outage_reasons),
                "ongoing": False,
            }
        )
        outage_started_mono = None
        outage_started_local = None
        outage_samples = 0
        outage_reasons = Counter()

    def write_summary(summary_reason: str) -> None:
        nonlocal summary_written, logs_captured, reason
        if summary_written:
            return
        summary_written = True
        reason = summary_reason
        finished_ms = now_ms()
        finished_local = local_time(finished_ms)
        if outage_started_mono is not None:
            outages.append(
                {
                    "start": outage_started_local,
                    "end": finished_local,
                    "seconds": round(time.monotonic() - outage_started_mono, 1),
                    "samples": outage_samples,
                    "reasons": dict(outage_reasons),
                    "ongoing": True,
                }
            )
        if logs_captured is None:
            logs_captured = (
                {}
                if args.no_capture_logs
                else capture_logs(out_dir, args.container, started_at, args.portal_log)
            )
        elapsed = time.monotonic() - started_mono
        unhealthy = total - healthy_count
        summary = {
            "startedAt": started_local,
            "finishedAt": finished_local,
            "durationSeconds": round(elapsed, 1),
            "reason": summary_reason,
            "config": {
                "requestedDurationSeconds": args.duration,
                "intervalSeconds": args.interval,
                "maxConsecutiveFailures": args.max_consecutive_failures,
                "maxOutageSeconds": args.max_outage,
                "maxHardFailures": args.max_hard_failures,
                "graceSamples": args.grace_samples,
                "consumerEvery": args.consumer_every,
                "maxFailureRatio": args.max_failure_ratio,
                "pinnedProvider": args.target_provider,
            },
            "targetProviderId": target_id,
            "targetSwitches": target_switches,
            "providerIds": sorted(provider_ids),
            "appVersions": sorted(app_versions),
            "stages": sorted(stages),
            "samples": total,
            "healthy": healthy_count,
            "unhealthy": unhealthy,
            "healthPercent": round(healthy_count / total * 100, 2) if total else None,
            "maxConsecutiveFailures": max_failures_seen,
            "hardFailures": hard_failures,
            "hardReason": hard_reason,
            "firstFailureAt": first_failure_at,
            "outages": outages,
            "totalOutageSeconds": round(sum(item["seconds"] for item in outages), 1),
            "longestOutageSeconds": max((item["seconds"] for item in outages), default=0),
            "consumerChecks": consumer_checks,
            "consumerFailures": consumer_failures,
            "latencyMs": {
                "status": {
                    "p50": percentile(status_latencies, 0.5),
                    "p95": percentile(status_latencies, 0.95),
                    "max": percentile(status_latencies, 1.0),
                },
                "fuse": {
                    "p50": percentile(fuse_latencies, 0.5),
                    "p95": percentile(fuse_latencies, 0.95),
                    "max": percentile(fuse_latencies, 1.0),
                },
                "consumer": {
                    "p50": percentile(consumer_latencies, 0.5),
                    "p95": percentile(consumer_latencies, 0.95),
                    "max": percentile(consumer_latencies, 1.0),
                },
            },
            "lastSeenAgeMs": {
                "p50": percentile(last_seen_ages, 0.5),
                "p95": percentile(last_seen_ages, 0.95),
                "max": percentile(last_seen_ages, 1.0),
            },
            "baselineSha256": baseline_sha,
            "baselineBytes": baseline_bytes,
            "lastSample": last_sample,
            "events": events[-100:],
            "logs": logs_captured,
            "output": str(out_dir),
        }
        (out_dir / "summary.json").write_text(
            json.dumps(summary, indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )
        lines = [
            "# VFS soak",
            "",
            f"- Started: {summary['startedAt']}",
            f"- Finished: {summary['finishedAt']}",
            f"- Reason: `{summary_reason}`",
            f"- Duration: {summary['durationSeconds']} s (requested {int(args.duration)} s)",
            f"- Target: `{target_id or 'not found'}` (switches: {target_switches})",
            f"- Samples: {total} ({healthy_count} healthy, {unhealthy} unhealthy, {summary['healthPercent']}%)",
            f"- Max consecutive failures: {max_failures_seen}; hard failures: {hard_failures} ({hard_reason or '-'})",
            f"- Outages: {len(outages)}; total {summary['totalOutageSeconds']} s; longest {summary['longestOutageSeconds']} s",
            f"- Consumer checks: {consumer_checks} ({consumer_failures} failed)",
            f"- App versions: {', '.join(sorted(app_versions)) or 'unknown'}",
            f"- Stages: {', '.join(sorted(stages)) or 'unknown'}",
            f"- Portal events: {logs_captured.get('connect', 0)} connect / {logs_captured.get('disconnect', 0)} disconnect",
            f"- Raw samples: `{samples_path}`",
            f"- Portal log: `{logs_captured.get('portalFile') or '-'}`",
            f"- vfsd log: `{logs_captured.get('vfsdFile') or '-'}`",
            "",
            "## Outages",
            "",
        ]
        if outages:
            for outage in outages:
                lines.append(
                    f"- {outage['start']} → {outage['end']} ({outage['seconds']} s, {outage['samples']} samples, "
                    f"{outage['reasons']}, ongoing={outage['ongoing']})"
                )
        else:
            lines.append("- none")
        lines.extend(["", "## Events", ""])
        lines.extend(f"- {item}" for item in events[-100:]) if events else lines.append("- none")
        lines.append("")
        summary_path.write_text("\n".join(lines), encoding="utf-8")

    def stop_handler(signum: int, _frame: object) -> None:
        raise SystemExit(128 + signum)

    signal.signal(signal.SIGINT, stop_handler)
    signal.signal(signal.SIGTERM, stop_handler)

    write_log(
        f"soak start {started_local} duration={args.duration:g}s interval={args.interval:g}s "
        f"max_failures={args.max_consecutive_failures} max_outage={args.max_outage:g}s "
        f"max_hard={args.max_hard_failures} out={out_dir}"
    )
    write_log("token: read from endpoint directory, never logged")

    try:
        while time.monotonic() < deadline:
            ts = now_ms()
            status, status_ms, status_error, endpoint_reloaded = endpoint.status()
            status_latencies.append(status_ms)
            if endpoint_reloaded:
                event("endpoint reloaded")
            providers = status.get("providers") if isinstance(status.get("providers"), list) else []
            valid_providers = [p for p in providers if isinstance(p, dict)]
            if target_id is None:
                target_id = choose_target(valid_providers)
                if target_id is not None:
                    event(f"target selected: {target_id}")
                    write_log(f"target selected: {target_id}")
            target = next((p for p in valid_providers if p.get("providerId") == target_id), None)
            if target_id is not None and not pinned and not target_ready(target):
                next_id = choose_target(valid_providers, exclude=target_id)
                if next_id is not None:
                    event(f"target switched: {target_id} -> {next_id}")
                    write_log(f"target switched: {target_id} -> {next_id}")
                    target_id = next_id
                    target_switches += 1
                    failures = 0
                    hard_failures = 0
                    hard_reason = None
                    grace_until = total + args.grace_samples
                    outage_started_mono = None
                    outage_started_local = None
                    outage_samples = 0
                    outage_reasons = Counter()
                    last_generation = None
                    last_online = None
                    target = next((p for p in valid_providers if p.get("providerId") == target_id), None)
            snapshot = provider_snapshot(target, ts)
            if snapshot:
                provider_ids.add(str(snapshot["providerId"]))
                if snapshot["lastSeenAgeMs"] is not None:
                    last_seen_ages.append(snapshot["lastSeenAgeMs"])

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
                    if fuse_ms is not None:
                        fuse_latencies.append(fuse_ms)
                elif "No such file" in read_err or "not found" in read_err.lower():
                    fuse_read_ok = None
                    fuse_error = (fuse_error + "; " if fuse_error else "") + "test file missing"
                else:
                    fuse_read_ok = False
                    fuse_error = (fuse_error + "; " if fuse_error else "") + f"read: {read_err or read_rc}"
                    fuse_ms = max(fuse_ms, read_ms)
                    if fuse_ms is not None:
                        fuse_latencies.append(fuse_ms)

            app_info = {"ok": False, "error": "no_provider"}
            stage = None
            if target_id and PROVIDER_ID_RE.match(target_id):
                app_info = read_app_log(args.container, target_id)
                app_version = app_info.get("version") if app_info.get("ok") else None
                if app_version:
                    app_versions.add(app_version)
                    if last_app_version and app_version != last_app_version:
                        event(f"app version {last_app_version} -> {app_version}")
                        write_log(f"app version {last_app_version} -> {app_version}")
                        failures = 0
                        hard_failures = 0
                        hard_reason = None
                        grace_until = total + args.grace_samples
                        outage_started_mono = None
                        outage_started_local = None
                        outage_samples = 0
                        outage_reasons = Counter()
                        last_generation = None
                        last_online = None
                    last_app_version = app_version
                if fuse_read_ok and read_body:
                    stage = test_stage(read_body)
                    if stage:
                        stages.add(stage)
                        if stage != last_stage:
                            event(f"stage: {stage}")
                            last_stage = stage

            consumer = None
            if args.consumer_every > 0 and target_id and PROVIDER_ID_RE.match(target_id) and total % args.consumer_every == 0:
                consumer = consumer_read(args.container, target_id)
                consumer_checks += 1
                if consumer["latencyMs"] is not None:
                    consumer_latencies.append(consumer["latencyMs"])
                if not consumer["ok"]:
                    consumer_failures += 1
                    event(f"consumer read failed: {consumer.get('error') or 'empty output'}")

            status_ok = status_error is None and status.get("ok") is True
            status_healthy = bool(snapshot and snapshot["online"] and snapshot["mounted"] and snapshot["readOnly"])
            fuse_healthy = fuse_list_ok and (fuse_read_ok is not False)
            consumer_healthy = consumer is None or consumer["ok"]
            healthy = status_ok and status_healthy and fuse_healthy and consumer_healthy
            in_grace = total < grace_until
            hard = hard_error(status_error, fuse_error, (consumer or {}).get("error"))

            if healthy:
                healthy_count += 1
                failures = 0
                hard_failures = 0
                hard_reason = None
                close_outage(local_time(ts))
            else:
                if hard:
                    hard_failures += 1
                    hard_reason = hard
                else:
                    hard_failures = 0
                    hard_reason = None
                if not in_grace:
                    failures += 1
                    max_failures_seen = max(max_failures_seen, failures)
                    if first_failure_at is None:
                        first_failure_at = local_time(ts)
                    if outage_started_mono is None:
                        outage_started_mono = time.monotonic()
                        outage_started_local = local_time(ts)
                    outage_samples += 1
                    if status_error:
                        outage_reasons[status_error] += 1
                    if not status_healthy:
                        outage_reasons["provider_not_ready"] += 1
                    if target_id and not fuse_healthy:
                        outage_reasons["fuse"] += 1
                    if not consumer_healthy:
                        outage_reasons["consumer"] += 1
                event(
                    f"unhealthy{' (grace)' if in_grace else ''}: status={status_error or 'ok'} "
                    f"online={snapshot['online'] if snapshot else None} "
                    f"mounted={snapshot['mounted'] if snapshot else None} "
                    f"readOnly={snapshot['readOnly'] if snapshot else None} "
                    f"fuse_list={fuse_list_ok} fuse_read={fuse_read_ok} "
                    f"consumer={consumer['ok'] if consumer else None} "
                    f"hard={hard or '-'} error={fuse_error or (consumer or {}).get('error') or 'none'}"
                )

            if snapshot:
                generation = snapshot["generation"]
                if isinstance(generation, (int, float)):
                    if last_generation is not None and generation != last_generation:
                        event(f"generation {last_generation} -> {generation}")
                    last_generation = int(generation)
                if last_online is not None and snapshot["online"] != last_online:
                    event(f"online {last_online} -> {snapshot['online']}")
                last_online = snapshot["online"]

            outage_seconds = round(time.monotonic() - outage_started_mono, 1) if outage_started_mono is not None else 0.0
            sample = {
                "ts": ts,
                "localTime": local_time(ts),
                "elapsedSeconds": round(time.monotonic() - started_mono, 1),
                "healthy": healthy,
                "grace": in_grace,
                "consecutiveFailures": failures,
                "hardFailures": hard_failures,
                "hardError": hard,
                "outageSeconds": outage_seconds,
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
                "app": app_info,
                "stage": stage,
                "consumer": consumer,
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
            marker = "OK  " if healthy else "FAIL"
            write_log(
                f"[{total:04d}] {sample['localTime']} {marker} "
                f"online={snapshot['online'] if snapshot else None} "
                f"mounted={snapshot['mounted'] if snapshot else None} "
                f"fuse={fuse_list_ok}/{fuse_read_ok} "
                f"status={status_ms:.0f}ms fuse={fuse_ms if fuse_ms is not None else '-'}ms "
                f"ver={last_app_version or '?'} stage={(stage or '-')[:24]} "
                f"fail={failures}/{outage_seconds:.0f}s hard={hard_failures} grace={in_grace}"
            )

            if hard_failures >= args.max_hard_failures:
                write_log(f"circuit break: hard failures {hard_failures} ({hard_reason})")
                write_summary("hard_failure")
                return 1
            if (
                not in_grace
                and should_trip(
                    failures,
                    outage_seconds,
                    args.max_consecutive_failures,
                    args.max_outage,
                )
            ):
                write_log(
                    f"circuit break: {failures} consecutive failures for {outage_seconds:.0f}s "
                    f"(threshold {args.max_consecutive_failures}/{args.max_outage:.0f}s)"
                )
                write_summary("circuit_breaker")
                return 1
            if (
                args.max_failure_ratio > 0
                and total >= args.ratio_min_samples
                and (total - healthy_count) / total > args.max_failure_ratio
            ):
                write_log(
                    f"circuit break: unhealthy ratio {(total - healthy_count) / total:.1%} "
                    f"> {args.max_failure_ratio:.1%}"
                )
                write_summary("failure_ratio")
                return 1

            sleep_for = min(args.interval, max(0.0, deadline - time.monotonic()))
            if sleep_for > 0:
                time.sleep(sleep_for)

        write_log("soak window complete")
        write_summary("duration_reached")
        return 0
    except SystemExit:
        write_log("interrupted")
        write_summary("interrupted")
        raise
    except Exception as error:  # noqa: BLE001 - preserve evidence
        write_log(f"soak crashed: {type(error).__name__}: {error}")
        write_summary("soak_error")
        return 2
    finally:
        try:
            if not summary_path.exists():
                write_summary(reason)
        except Exception:  # noqa: BLE001 - never mask the original failure
            pass
        try:
            fcntl.flock(lock_handle, fcntl.LOCK_UN)
        except Exception:
            pass
        lock_handle.close()


if __name__ == "__main__":
    sys.exit(main())
