# AGENTS.md — WonderSing (fork of sing-box-for-android)

Rules for any coding agent (Claude Code, Codex, etc.) working in this repo on clawdy-mini.
Goal: given "check TODO and latest issues, do 1–2h of work, then release", close the whole loop yourself.

## Repo facts
- Fork: `wonder-missing/sing-box-for-android`, work branch `dev`. Upstream is SagerNet.
- **Always pass `-R wonder-missing/sing-box-for-android` to every `gh` command** (default resolves to upstream).
- Workspace: `~/workspace` on clawdy-mini. CFG repo: `~/workspace/CFG`.

## Work loop
1. `git pull --ff-only` on `dev`.
2. Read `TODO.md` and `gh issue list -R wonder-missing/sing-box-for-android`.
3. Pick ~1–2h of scope (issues first, then TODO). Keep TODO.md updated.
4. Features/fixes need JVM unit tests.
5. No JDK locally: commit, `git push origin dev`, then
   `gh run list -R wonder-missing/sing-box-for-android -w Test -L 1` and
   `gh run watch <id> -R wonder-missing/sing-box-for-android --exit-status`. Red → fix and repeat until green.
6. When an issue is fixed: `gh issue comment` (what changed, which version) and `gh issue close` (with `-R ...`).

## Release flow
1. Bump `version.properties`: `WONDER_BUILD=N`, `VERSION_CODE=11404000+N`, `VERSION_NAME=1.15.0-alpha.13-wonder.N`.
2. Commit, `git push origin dev`, wait for green Test CI.
3. `git tag v1.15.0-alpha.13-wonder.N && git push origin v1.15.0-alpha.13-wonder.N`.
4. `gh run watch` the Release workflow; then `gh release view v1.15.0-alpha.13-wonder.N -R wonder-missing/sing-box-for-android`
   and confirm the `arm64-v8a` APK asset exists. Report its download URL.
- Releases are **arm64-v8a only**.
- Never delete/move tags or releases; never force-push; push only to `dev` (and release tags).

## Signing & private files
- Never change signing config, `app/release.keystore`, or GitHub Actions signing secrets. CI signs releases.
- Private area: `~/workspace/CFG/private/` (outside Git, never commit/print contents):
  - `wondersing-release.jks` — release key; `wondersing-signing.env` — its parameters (for local builds; see `docs/signing.md`).
  - `sfa-telemetry.env` — telemetry ingest token. `ws-secrets/` — Actions secret backups. `airport-sub.yaml` — not for this repo.
- Never print tokens/passwords/subscription URLs into logs, commits, issues or chat.

## Known open items
- Add a local signing hook so a Mini build can read the key from `~/workspace/CFG/private/wondersing-release.jks` + `wondersing-signing.env` (Gradle properties/env; no secrets in Git).
- dev already carries unreleased telemetry changes (vpn_session_id, default_network, selected node/switch_reason, update-check result); they ship with the next release.
