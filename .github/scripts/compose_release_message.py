#!/usr/bin/env python3
"""Compose the Feishu release notification.

A short notice stays a text message. Once the text passes 500 characters or
8 lines, it becomes an interactive card so the changelog and links stay readable.
"""

from __future__ import annotations

import argparse
import json
import sys
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
        test_line(facts),
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
    body = [f"**{test_line(facts)}**", "", "**变更**"]
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
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kind", required=True, choices=("success", "failure", "test"))
    parser.add_argument("--version", default="")
    parser.add_argument("--conclusion", default="")
    parser.add_argument("--run-url", default="")
    parser.add_argument("--tests", default="")
    parser.add_argument("--failures", default="")
    parser.add_argument("--errors", default="")
    parser.add_argument("--skipped", default="")
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
        "apk_url": args.apk_url,
        "release_url": args.release_url,
        "changelog": changelog_from_lines(raw_notes),
    }
    json.dump(compose(facts), sys.stdout, ensure_ascii=False)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except Exception as exc:  # noqa: BLE001 - CI should see the reason and fail
        print(f"compose_release_message failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
