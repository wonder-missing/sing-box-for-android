# AGENTS.md — WonderSing (fork of sing-box-for-android)

Rules for any coding agent (Claude Code, Codex, etc.) working in this repo on clawdy-mini.
Goal: given "check TODO and latest issues, do 1–2h of work, then release", close the whole loop yourself.

## Repo facts
- Fork: `wonder-missing/sing-box-for-android`, work branch `dev`. Upstream is SagerNet.
- **Always pass `-R wonder-missing/sing-box-for-android` to every `gh` command** (default resolves to upstream).
- Workspace: `~/workspace` on clawdy-mini. CFG repo: `~/workspace/CFG`.
- The Mini has no JDK/Android emulator; compile and unit tests run in CI. UI changes are compile-checked only and need a device check.
- `version.properties` is the single version source: `CORE_VERSION`, `WONDER_BUILD`, `GO_VERSION`. `VERSION_CODE`/`VERSION_NAME` are derived in `app/build.gradle.kts`; `WonderVersionTest` guards the update-checker base.
- **The user's "Groups page" is the Dashboard Groups bottom sheet** (`showGroupsSheet`, `GroupsCard(asSheet = true)`); its title/actions row is the `listHeaderContent` in `MainActivity`. `Screen.Groups` is a wide-screen `NavigationRail` destination with no phone entry; do not treat it as the Groups page in UI discussions.

## Work loop
1. `git pull --ff-only` on `dev`.
2. Read `TODO.md` and `gh issue list -R wonder-missing/sing-box-for-android`.
3. Pick ~1–2h of scope (issues first, then TODO). Keep TODO.md updated.
4. Features/fixes need JVM unit tests.
5. No JDK locally: commit, `git push origin dev`, then
   `gh run list -R wonder-missing/sing-box-for-android -w Test -L 1` and
   `gh run watch <id> -R wonder-missing/sing-box-for-android --exit-status`. Red → fix and repeat until green.
6. When an issue is fixed: `gh issue comment` (what changed, which version) and `gh issue close` (with `-R ...`).

## UI conventions

- Layout sizes, spacing and corner radii use `dp`; text uses `sp`.
- Standard scale: spacing `4/8/12/16/24dp`; corner radius `8/12/16dp` (pills use half the height); touch target `48dp`; body text `14–16sp`.
- The owner has web frontend experience. Accept `padding` / `margin` / `gap` / `border-radius` / `flex` / `align-items` wording and translate it to dp and Compose `Row` / `Column` / `weight`.
- Prefer references (DeepSeek input bar, WeChat composer, Material 3 components) and relative descriptions (“same size as the left button”, “icon centred in its circle”) over requiring exact numbers.
- Apply product-level padding, alignment and safe margins yourself; do not wait for the owner to point out basic spacing or centring.
- A bare “px” means design dp (`1 design px ≈ 1dp`) and 5dp is a very small radius. For this app, inputs and cards usually use `8–12dp` radii.
- Design specs, tokens and the handoff checklist live in `design/README.md`.
- Conductor-specific UI decisions live in `docs/agent-control-plane.md`.

## Release flow
1. Set `WONDER_BUILD=N` in `version.properties`. `VERSION_CODE` and `VERSION_NAME` are derived from `CORE_VERSION` and `WONDER_BUILD`; only change `CORE_VERSION` when moving to a new upstream core.
2. Commit, `git push origin dev`, wait for green Test CI.
3. Tag `v<CORE_VERSION>-wonder.<N>` and push the tag.
4. `gh run watch` the Release workflow; then `gh release view <tag> -R wonder-missing/sing-box-for-android`
   and confirm the `arm64-v8a` APK asset exists. Also confirm the `Release notify` run sent the Feishu message. Report the download URL.
   The Feishu notice includes the unit-test count from the Test artifact `unit-test-count` and a short commit list since the previous release. It stays text under 320 characters and 8 lines, and becomes an interactive card past that. A normal release with the APK link crosses that and is sent as a card.
- Releases are **arm64-v8a only**.
- `release.yml` restores the `libbox.aar` cache that `test.yml` writes on `dev` (tag runs may read the default-branch cache). Keep the cache key and trailing recipe version in sync between the two workflows; bump both when the libbox build recipe changes.
- Never delete/move tags or releases; never force-push; push only to `dev` (and release tags).
- In `.claude/settings.json`, the delete-refspec deny is `Bash(git push origin ::*)`. A trailing `:*` is consumed as the prefix wildcard, so `Bash(git push origin :*)` matches every command that starts with `git push origin `, including `dev` and release tags. Leave the double colon in place.

## Signing & private files
- Never change signing config, `app/release.keystore`, or GitHub Actions signing secrets. CI signs releases.
- Private area: `~/workspace/CFG/private/` (outside Git, never commit/print contents):
  - `wondersing-release.jks` — release key; `wondersing-signing.env` — its parameters (for local builds; see `docs/signing.md`).
  - `sfa-telemetry.env` — telemetry ingest token. `ws-secrets/` — Actions secret backups. `airport-sub.yaml` — not for this repo.
- Never print tokens/passwords/subscription URLs into logs, commits, issues or chat.

## Open work
- Track open work in `TODO.md` under 「现在待办」; do not keep a second list here.
- Conductor control-plane vision and phased plan: `docs/agent-control-plane.md`.
- Local signing limitation and keystore rules: `docs/signing.md`.
