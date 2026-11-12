#!/usr/bin/env python3
"""Compose the Feishu release notification.

A short notice stays a text message. Once the text passes 500 characters or
8 lines, it becomes an interactive card so the changelog and links stay readable.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime
from typing import Any

TEXT_CHAR_LIMIT = 320
TEXT_LINE_LIMIT = 8
CHANGELOG_LIMIT = 12
SUBJECT_LIMIT = 160


def clean_subject(raw: str) -> str:
    line = raw.replace("\r", " ").split("\n", 1)[0]
    line = "".join(ch for ch in line if ch == "\t" or ord(ch) >= 32)
    line = " ".join(line.split())
    if len(line) > SUBJECT_LIMIT:
        line = line[: SUBJECT_LIMIT - 1] + "…"
    return line


def changelog_from_lines(lines: list[str]) -> list[str]:
    notes: list[str] = []
    for raw in lines:
        subject = clean_subject(raw)
        if subject and not subject.startswith("Merge "):
            notes.append(subject)
    return notes


UNIT_TEST_JOB = "Unit tests (otherDebug)"
BUILD_APK_JOB = "Build release APKs"
LIBBOX_JOB = "Build libbox (arm64)"
LIBBOX_BUILD_STEP = "Build library"


def format_duration(seconds: Any) -> str:
    """Human duration. Zero, missing, and unreadable values stay blank."""
    try:
        total = int(seconds)
    except (TypeError, ValueError):
        return ""
    if total <= 0:
        return ""
    hours, rem = divmod(total, 3600)
    minutes, secs = divmod(rem, 60)
    parts: list[str] = []
    if hours:
        parts.append(f"{hours}小时")
    if minutes:
        parts.append(f"{minutes}分")
    if secs:
        parts.append(f"{secs}秒")
    return "".join(parts)


def _parse_time(value: Any) -> datetime | None:
    text = str(value or "")
    if not text or text.startswith("0001-"):
        return None
    try:
        return datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return None


def _elapsed_seconds(job: dict[str, Any]) -> int | None:
    start = _parse_time(job.get("startedAt"))
    end = _parse_time(job.get("completedAt"))
    if start is None or end is None:
        return None
    seconds = int((end - start).total_seconds())
    if seconds <= 0:
        return None
    return seconds


def _successful_job(jobs: list[dict[str, Any]], name: str) -> dict[str, Any] | None:
    for job in jobs:
        if job.get("name") == name and job.get("conclusion") == "success":
            return job
    return None


def unit_test_seconds(jobs: list[dict[str, Any]]) -> int | None:
    job = _successful_job(jobs, UNIT_TEST_JOB)
    if job is None:
        return None
    return _elapsed_seconds(job)


def build_seconds(jobs: list[dict[str, Any]]) -> int | None:
    """APK build time, plus libbox only when that library was actually compiled."""
    total = 0
    found = False
    apk = _successful_job(jobs, BUILD_APK_JOB)
    if apk is not None:
        elapsed = _elapsed_seconds(apk)
        if elapsed:
            total += elapsed
            found = True
    libbox = _successful_job(jobs, LIBBOX_JOB)
    if libbox is not None:
        compiled = any(
            step.get("name") == LIBBOX_BUILD_STEP and step.get("conclusion") == "success"
            for step in libbox.get("steps") or []
        )
        if compiled:
            elapsed = _elapsed_seconds(libbox)
            if elapsed:
                total += elapsed
                found = True
    if not found:
        return None
    return total


def seconds_from_jobs(kind: str, jobs: list[dict[str, Any]]) -> int | None:
    if kind == "unit-test":
        return unit_test_seconds(jobs)
    if kind == "build":
        return build_seconds(jobs)
    raise ValueError(f"unknown timing kind: {kind}")


_AGENT_TIME_LINE = re.compile(r"^Agent-Time:\s*(?:(\d+)h)?(?:(\d+)m)?\s*$", re.IGNORECASE)
_AGENT_LOOPS_LINE = re.compile(r"^Agent-Loops:\s*(\d+)\s*$", re.IGNORECASE)


def effort_from_tag_message(message: str) -> dict[str, int]:
    """Read effort lines written on an annotated release tag. Zero stays absent."""
    seconds = 0
    loops = 0
    saw_time = False
    saw_loops = False
    for raw in message.splitlines():
        line = raw.strip()
        time_match = _AGENT_TIME_LINE.match(line)
        if time_match and (time_match.group(1) or time_match.group(2)):
            hours = int(time_match.group(1) or 0)
            minutes = int(time_match.group(2) or 0)
            seconds = hours * 3600 + minutes * 60
            saw_time = True
            continue
        loops_match = _AGENT_LOOPS_LINE.match(line)
        if loops_match:
            loops = int(loops_match.group(1))
            saw_loops = True
    found: dict[str, int] = {}
    if saw_time and seconds > 0:
        found["agent_seconds"] = seconds
    if saw_loops and loops > 0:
        found["agent_loops"] = loops
    return found


def stat_lines(facts: dict[str, Any]) -> list[str]:
    lines = [test_line(facts)]
    for label, key in (("单元测试耗时", "test_seconds"), ("构建耗时", "build_seconds")):
        text = format_duration(facts.get(key))
        if text:
            lines.append(f"{label}：{text}")
    agent_time = format_duration(facts.get("agent_seconds"))
    if agent_time:
        lines.append(f"Agent 投入时间：{agent_time}")
    agent_loops = facts.get("agent_loops")
    if isinstance(agent_loops, int) and agent_loops > 0:
        lines.append(f"Agent Loop 数量：{agent_loops}")
    return lines


def test_line(facts: dict[str, Any]) -> str:
    tests = facts.get("tests")
    if tests is None:
        return "单元测试：未记录"
    extras: list[str] = []
    failures = int(facts.get("failures") or 0)
    errors = int(facts.get("errors") or 0)
    skipped = int(facts.get("skipped") or 0)
    if failures or errors:
        extras.append(f"失败 {failures + errors}")
    if skipped:
        extras.append(f"跳过 {skipped}")
    if extras:
        return f"单元测试：{tests}（{'，'.join(extras)}）"
    return f"单元测试：{tests}"


def plain_lines(facts: dict[str, Any]) -> list[str]:
    kind = facts["kind"]
    if kind == "test":
        return [
            "WonderSing 发版通知渠道测试",
            "GitHub Actions 已切换为飞书通知。",
        ]
    if kind == "failure":
        return [
            "WonderSing 发版失败",
            f"标签/分支：{facts.get('version') or 'unknown'}",
            f"结论：{facts.get('conclusion') or 'unknown'}",
            f"运行记录：{facts.get('run_url') or ''}",
        ]
    lines = [
        "WonderSing 发版成功",
        str(facts.get("version") or ""),
        *stat_lines(facts),
        "变更：",
    ]
    notes = list(facts.get("changelog") or [])
    shown = notes[:CHANGELOG_LIMIT]
    if not shown:
        lines.append("- （无）")
    else:
        lines.extend(f"- {item}" for item in shown)
        hidden = len(notes) - len(shown)
        if hidden:
            lines.append(f"- …还有 {hidden} 条")
    apk_url = str(facts.get("apk_url") or "")
    release_url = str(facts.get("release_url") or "")
    if apk_url:
        lines.append(f"安装包（arm64-v8a）：{apk_url}")
    if release_url:
        lines.append(f"Release：{release_url}")
    return lines


def plain_text(facts: dict[str, Any]) -> str:
    return "\n".join(plain_lines(facts))


def _https_url(value: str) -> str:
    value = value.strip()
    if value.startswith("https://"):
        return value
    return ""


def card(facts: dict[str, Any]) -> dict[str, Any]:
    kind = facts["kind"]
    version = str(facts.get("version") or "")
    if kind == "success":
        title, template = f"WonderSing {version}", "green"
    elif kind == "failure":
        title, template = "WonderSing 发版失败", "red"
    else:
        title, template = "WonderSing 发版通知", "blue"
    notes = list(facts.get("changelog") or [])
    shown = notes[:CHANGELOG_LIMIT]
    stats = stat_lines(facts)
    body = [f"**{stats[0]}**", *stats[1:], "", "**变更**"]
    if not shown:
        body.append("- （无）")
    else:
        body.extend(f"- {item}" for item in shown)
        hidden = len(notes) - len(shown)
        if hidden:
            body.append(f"- …还有 {hidden} 条")
    if kind == "failure":
        body = [
            f"标签/分支：{version or 'unknown'}",
            f"结论：{facts.get('conclusion') or 'unknown'}",
            f"运行记录：{facts.get('run_url') or ''}",
        ]
    elements: list[dict[str, Any]] = [
        {
            "tag": "div",
            "text": {"tag": "lark_md", "content": "\n".join(body)},
        }
    ]
    actions = []
    apk_url = _https_url(str(facts.get("apk_url") or ""))
    release_url = _https_url(str(facts.get("release_url") or ""))
    if apk_url:
        actions.append(
            {
                "tag": "button",
                "text": {"tag": "plain_text", "content": "安装包"},
                "type": "primary",
                "url": apk_url,
            }
        )
    if release_url:
        actions.append(
            {
                "tag": "button",
                "text": {"tag": "plain_text", "content": "Release"},
                "type": "default",
                "url": release_url,
            }
        )
    if actions:
        elements.append({"tag": "action", "actions": actions})
    return {
        "config": {"wide_screen_mode": True},
        "header": {
            "title": {"tag": "plain_text", "content": title},
            "template": template,
        },
        "elements": elements,
    }


def use_card(text: str) -> bool:
    line_count = text.count("\n") + 1 if text else 0
    return len(text) > TEXT_CHAR_LIMIT or line_count > TEXT_LINE_LIMIT


def compose(facts: dict[str, Any]) -> dict[str, str]:
    facts = dict(facts)
    facts["changelog"] = changelog_from_lines([str(item) for item in facts.get("changelog") or []])
    text = plain_text(facts)
    if not use_card(text):
        content = json.dumps({"text": text}, ensure_ascii=False)
        return {"msg_type": "text", "content": content}
    content = json.dumps(card(facts), ensure_ascii=False)
    return {"msg_type": "interactive", "content": content}


def _optional_int(value: str) -> int | None:
    value = value.strip()
    if not value:
        return None
    return int(value)


def run_self_test() -> None:
    channel = compose({"kind": "test", "changelog": []})
    assert channel["msg_type"] == "text", channel
    assert "飞书通知" in json.loads(channel["content"])["text"]

    failed = compose(
        {
            "kind": "failure",
            "version": "v1.2.3-wonder.9",
            "conclusion": "failure",
            "run_url": "https://github.com/wonder-missing/sing-box-for-android/actions/runs/1",
            "changelog": [],
        }
    )
    assert failed["msg_type"] == "text", failed
    assert "发版失败" in json.loads(failed["content"])["text"]

    short = compose(
        {
            "kind": "success",
            "version": "1.0.0-wonder.1",
            "tests": 3,
            "failures": 0,
            "errors": 0,
            "skipped": 0,
            "changelog": ["fix: a"],
            "apk_url": "",
            "release_url": "",
        }
    )
    assert short["msg_type"] == "text", plain_text(
        {
            "kind": "success",
            "version": "1.0.0-wonder.1",
            "tests": 3,
            "failures": 0,
            "errors": 0,
            "skipped": 0,
            "changelog": ["fix: a"],
        }
    )
    assert "单元测试：3" in json.loads(short["content"])["text"]
    assert "fix: a" in json.loads(short["content"])["text"]

    typical = compose(
        {
            "kind": "success",
            "version": "1.15.0-alpha.13-wonder.42",
            "tests": 480,
            "failures": 0,
            "errors": 0,
            "skipped": 0,
            "changelog": ["feat: surface app updates and reasoning effort"],
            "apk_url": "https://github.com/wonder-missing/sing-box-for-android/releases/download/v1.15.0-alpha.13-wonder.42/SFA-1.15.0-alpha.13-wonder.42-arm64-v8a.apk",
            "release_url": "https://github.com/wonder-missing/sing-box-for-android/releases/tag/v1.15.0-alpha.13-wonder.42",
        }
    )
    assert typical["msg_type"] == "interactive", plain_text(
        {
            "kind": "success",
            "version": "1.15.0-alpha.13-wonder.42",
            "tests": 480,
            "failures": 0,
            "errors": 0,
            "skipped": 0,
            "changelog": ["feat: surface app updates and reasoning effort"],
            "apk_url": "https://github.com/wonder-missing/sing-box-for-android/releases/download/v1.15.0-alpha.13-wonder.42/SFA-1.15.0-alpha.13-wonder.42-arm64-v8a.apk",
            "release_url": "https://github.com/wonder-missing/sing-box-for-android/releases/tag/v1.15.0-alpha.13-wonder.42",
        }
    )

    long_notes = [f"feat: change number {i} with enough words to grow the message" for i in range(10)]
    long_facts = {
        "kind": "success",
        "version": "1.15.0-alpha.13-wonder.42",
        "tests": 486,
        "failures": 1,
        "errors": 0,
        "skipped": 2,
        "test_seconds": 161,
        "build_seconds": 0,
        "changelog": long_notes,
        "apk_url": "https://github.com/wonder-missing/sing-box-for-android/releases/download/v1.15.0-alpha.13-wonder.42/SFA-arm64-v8a.apk",
        "release_url": "https://github.com/wonder-missing/sing-box-for-android/releases/tag/v1.15.0-alpha.13-wonder.42",
    }
    assert use_card(plain_text(long_facts))
    long_message = compose(long_facts)
    assert long_message["msg_type"] == "interactive"
    card_body = json.loads(long_message["content"])
    blob = json.dumps(card_body, ensure_ascii=False)
    assert "486" in blob
    assert "单元测试耗时：2分41秒" in blob
    assert "构建耗时" not in blob
    assert "失败 1" in blob
    assert "跳过 2" in blob
    assert card_body["header"]["template"] == "green"
    urls = [
        action["url"]
        for element in card_body["elements"]
        if element["tag"] == "action"
        for action in element["actions"]
    ]
    assert any(url.endswith(".apk") for url in urls)
    assert any(url.endswith("wonder.42") for url in urls)

    unknown = plain_text(
        {
            "kind": "success",
            "version": "1.0.0-wonder.1",
            "tests": None,
            "changelog": [],
            "apk_url": "",
            "release_url": "",
        }
    )
    assert "未记录" in unknown
    assert "耗时" not in unknown

    timed = plain_text(
        {
            "kind": "success",
            "version": "1.0.0-wonder.1",
            "tests": 12,
            "test_seconds": 161,
            "build_seconds": 492,
            "changelog": ["fix: a"],
            "apk_url": "",
            "release_url": "",
        }
    )
    assert "单元测试耗时：2分41秒" in timed
    assert "构建耗时：8分12秒" in timed

    absent = plain_text(
        {
            "kind": "success",
            "version": "1.0.0-wonder.1",
            "tests": 12,
            "test_seconds": 0,
            "build_seconds": None,
            "changelog": ["fix: a"],
            "apk_url": "",
            "release_url": "",
        }
    )
    assert "耗时" not in absent
    assert format_duration(48) == "48秒"
    assert format_duration(120) == "2分"
    assert format_duration(3660) == "1小时1分"

    jobs = [
        {
            "name": "Unit tests (otherDebug)",
            "conclusion": "success",
            "startedAt": "2026-11-12T12:32:13Z",
            "completedAt": "2026-11-12T12:34:54Z",
        },
        {
            "name": "Build libbox (arm64)",
            "conclusion": "success",
            "startedAt": "2026-11-12T13:41:16Z",
            "completedAt": "2026-11-12T13:41:28Z",
            "steps": [{"name": "Build library", "conclusion": "skipped"}],
        },
        {
            "name": "Build release APKs",
            "conclusion": "success",
            "startedAt": "2026-11-12T13:41:31Z",
            "completedAt": "2026-11-12T13:49:43Z",
        },
    ]
    assert unit_test_seconds(jobs) == 161
    assert build_seconds(jobs) == 492
    compiled = json.loads(json.dumps(jobs))
    compiled[1]["steps"][0]["conclusion"] = "success"
    compiled[1]["completedAt"] = "2026-11-12T13:47:16Z"
    assert build_seconds(compiled) == 492 + 360
    assert unit_test_seconds([]) is None
    assert build_seconds([jobs[1]]) is None

    effort = effort_from_tag_message(
        "release\n\nAgent-Time: 2h15m\nAgent-Loops: 4\n"
    )
    assert effort == {"agent_seconds": 2 * 3600 + 15 * 60, "agent_loops": 4}
    assert effort_from_tag_message("Agent-Time: 90m\nAgent-Loops: 0\n") == {"agent_seconds": 90 * 60}
    assert effort_from_tag_message("Agent-Time: 0m\nnot a field\n") == {}
    assert effort_from_tag_message("") == {}
    recorded = plain_text(
        {
            "kind": "success",
            "version": "1.0.0-wonder.1",
            "tests": 12,
            "changelog": ["fix: a"],
            "apk_url": "",
            "release_url": "",
            **effort_from_tag_message("Agent-Time: 2h15m\nAgent-Loops: 4\n"),
        }
    )
    assert "Agent 投入时间：2小时15分" in recorded
    assert "Agent Loop 数量：4" in recorded
    unrecorded = plain_text(
        {
            "kind": "success",
            "version": "1.0.0-wonder.1",
            "tests": 12,
            "changelog": ["fix: a"],
            "apk_url": "",
            "release_url": "",
        }
    )
    assert "Agent" not in unrecorded

    overflow = compose(
        {
            "kind": "success",
            "version": '1.0 "wonder"',
            "tests": 1,
            "failures": 0,
            "errors": 0,
            "skipped": 0,
            "changelog": ['fix: say "hello"\nignore this second line'] + ["feat: item"] * 20,
            "apk_url": "http://insecure.example/a.apk",
            "release_url": "https://example.com/release",
        }
    )
    parsed = json.loads(overflow["content"])
    markdown = parsed["elements"][0]["text"]["content"]
    assert "还有" in markdown
    assert "ignore this second line" not in markdown
    assert markdown.count("\n- ") <= CHANGELOG_LIMIT + 1
    buttons = [
        action
        for element in parsed["elements"]
        if element["tag"] == "action"
        for action in element["actions"]
    ]
    assert [button["text"]["content"] for button in buttons] == ["Release"]
    assert "http://insecure.example" not in json.dumps(parsed)
    print("compose_release_message self-test ok", file=sys.stderr)


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        run_self_test()
        return 0
    if len(argv) >= 2 and argv[0] == "--seconds":
        jobs = json.load(sys.stdin)
        value = seconds_from_jobs(argv[1], jobs)
        if value:
            print(value)
        return 0
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kind", required=True, choices=("success", "failure", "test"))
    parser.add_argument("--version", default="")
    parser.add_argument("--conclusion", default="")
    parser.add_argument("--run-url", default="")
    parser.add_argument("--tests", default="")
    parser.add_argument("--failures", default="")
    parser.add_argument("--errors", default="")
    parser.add_argument("--skipped", default="")
    parser.add_argument("--test-seconds", default="")
    parser.add_argument("--build-seconds", default="")
    parser.add_argument("--tag-message-file", default="")
    parser.add_argument("--apk-url", default="")
    parser.add_argument("--release-url", default="")
    parser.add_argument("--changelog-file", default="")
    args = parser.parse_args(argv)
    raw_notes: list[str] = []
    if args.changelog_file:
        with open(args.changelog_file, encoding="utf-8") as handle:
            raw_notes = handle.read().splitlines()
    facts = {
        "kind": args.kind,
        "version": args.version,
        "conclusion": args.conclusion,
        "run_url": args.run_url,
        "tests": _optional_int(args.tests),
        "failures": _optional_int(args.failures),
        "errors": _optional_int(args.errors),
        "skipped": _optional_int(args.skipped),
        "test_seconds": _optional_int(args.test_seconds),
        "build_seconds": _optional_int(args.build_seconds),
        "apk_url": args.apk_url,
        "release_url": args.release_url,
        "changelog": changelog_from_lines(raw_notes),
    }
    if args.tag_message_file:
        with open(args.tag_message_file, encoding="utf-8") as handle:
            facts.update(effort_from_tag_message(handle.read()))
    json.dump(compose(facts), sys.stdout, ensure_ascii=False)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except Exception as exc:  # noqa: BLE001 - CI should see the reason and fail
        print(f"compose_release_message failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
