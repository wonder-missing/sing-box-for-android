# AGENTS.md — WonderSing (fork of sing-box-for-android)

Rules for any coding agent (Claude Code, Codex, etc.) working in this repo on clawdy-mini.
Goal: given "check TODO and latest issues, do 1–2h of work", produce and test the change. Release only after the user explicitly asks for a release; a clean `dev` does not imply one.

## Repo facts
- Fork: `wonder-missing/sing-box-for-android`, work branch `dev`. Upstream is SagerNet.
- **Always pass `-R wonder-missing/sing-box-for-android` to every `gh` command** (default resolves to upstream).
- Workspace: `~/workspace` on clawdy-mini. CFG repo: `~/workspace/CFG`.
- CFG is a neighboring project. Use its CLI tools as needed (`python3 ~/workspace/CFG/cli/bin/cfg ...`, e.g. `clientlog report --hours N` for WonderSing app telemetry); do not modify CFG code from this repo.
- Local bare backup: remote `wondersing` at `~/repos/WonderSing`; after each daily commit, push `dev` there, and push tags when releasing.
- Local JVM unit tests and compile checks run on the Mini; setup, versions and disk cleanup are in [`docs/local-testing.md`](docs/local-testing.md). CI remains the release gate. There is no emulator; UI changes need a device check.
- Update install path, the system scan prompt, and post-update service restore: [`docs/update-install.md`](docs/update-install.md).
- `version.properties` is the single version source: `CORE_VERSION`, `WONDER_BUILD`, `GO_VERSION`. `WONDER_BUILD` is a two-character label: 99 was followed by `A0`, and labels advance in base-36 order (`A9` → `AA` → `AB`), never `A9` → `A10`; use the next unused label from [`docs/versioning.md`](docs/versioning.md). `VERSION_CODE`/`VERSION_NAME` are derived in `app/build.gradle.kts`; `WonderVersionTest` guards the update-checker base.
- Capability scenarios live in [`docs/conductor-scenarios.md`](docs/conductor-scenarios.md). Before removing or merging a tool, or changing a tool parameter, check the scenarios that name it and preserve or explicitly retire them.
- **The user's "Groups page" is the Dashboard Groups bottom sheet** (`showGroupsSheet`, `GroupsCard(asSheet = true)`); its title/actions row is the `listHeaderContent` in `MainActivity`. `Screen.Groups` is a wide-screen `NavigationRail` destination with no phone entry; do not treat it as the Groups page in UI discussions.

## Work loop
1. `git pull --ff-only` on `dev`.
2. Read `TODO.md` and `gh issue list -R wonder-missing/sing-box-for-android`.
3. When working autonomously, pick ~1–2h of scope (issues first, then TODO). Keep TODO.md updated.
4. Features/fixes need JVM unit tests.
5. Run `./scripts/local-test.sh` for JVM compile and unit tests (setup in
   [`docs/local-testing.md`](docs/local-testing.md)). Then commit, `git push origin dev`, and
   `gh run list -R wonder-missing/sing-box-for-android -w Test -L 1` /
   `gh run watch <id> -R wonder-missing/sing-box-for-android --exit-status`. Red → fix and repeat until green.
   CI remains the release gate; a green local run does not replace it.
6. When an issue is fixed: `gh issue comment` (what changed, which commit or release) and `gh issue close` (with `-R ...`).

## Data pulls and evidence

- When the owner asks to pull an API trace, log or session for analysis, check coverage **before** interpreting it: retained entries vs summaries, `[body truncated]` markers, request/response sizes, whether the final request and final answer are present, and whether a full-turn snapshot exists.
- If the data is incomplete for the question, stop and report exactly what is missing. Do not produce an assessment from a partial sample. Either narrow the question to what the data supports or ask for a complete export.
- Put the coverage boundary in the first paragraph of the report. A tool-call analysis may use the final request transcript; a reasoning/final-answer analysis needs the response bodies. Do not mix them.
- If the owner interjects during a data pull, handle the interjection first and say which part of the pull is paused; then resume. If the interjection changes priority, follow it and record where the original task stopped.
- Prefer the per-turn snapshot under `appfiles/conductor/trace-turns/` for a whole turn; the live `api-trace.json` is a bounded recent window, not a complete experiment record.

## Planning

- Plans are change plans: files/changes, order, tests, and release steps.
- Do not put time estimates in plans. `Agent-Time` / `Agent-Loops` are release-tag metadata recorded only when measured; see Release flow.
- Complete the whole requested change in one pass. Stop for the owner only when there are two genuinely different designs.
- For visual/interaction exploration, show a concrete effect before an implementation list; technical choices that will remain still need a plan.
- The ~1–2h scope in the work loop is for autonomous TODO/issue picking. A user-requested change is delivered as one whole change.
- Product phases and batches in `docs/` are roadmaps; a coding task plan uses the format above.

## UI conventions

- Layout sizes, spacing and corner radii use `dp`; text uses `sp`.
- Standard scale: spacing `4/8/12/16/24dp`; corner radius `8/12/16dp` (pills use half the height); touch target `48dp`; body text `14–16sp`.
- **Standard interactions first**: do not invent hidden tap, double-tap or swipe gestures for operations Android already provides. Copy uses native long-press selection + System Copy; inline code must not have tap-to-copy. Prefer a visible button, chevron or menu item over an invisible hit area. If a custom gesture is still necessary, explain why and get approval first.
- **One action, one control**: do not duplicate entry points. A search icon that toggles the search bar needs no second close button inside the bar; hide options that do not apply to the current mode (for example, auto-wrap only exists in Raw view).
- **Touch targets**: keep interactive rows at least `48dp` unless the owner explicitly approved a compact design (the reasoning strip is the known exception).
- **User messages**: show the send time under the bubble; do not hide it behind a tap.
- The owner has web frontend experience. Accept `padding` / `margin` / `gap` / `border-radius` / `flex` / `align-items` wording and translate it to dp and Compose `Row` / `Column` / `weight`.
- Prefer references (DeepSeek input bar, WeChat composer, Material 3 components) and relative descriptions (“same size as the left button”, “icon centred in its circle”) over requiring exact numbers.
- Apply product-level padding, alignment and safe margins yourself; do not wait for the owner to point out basic spacing or centring.
- A bare “px” means design dp (`1 design px ≈ 1dp`) and 5dp is a very small radius. For this app, inputs and cards usually use `8–12dp` radii.
- Design specs, tokens and the handoff checklist live in `design/README.md`.
- Conductor-specific UI decisions live in `docs/agent-control-plane.md`.

## Conductor tool metadata

- Tool names, descriptions, aliases and search keywords are English-only. The model translates the user's intent into English before `search_tools` and answers in the user's language.
- Do not add localized aliases or a build-time multilingual keyword pipeline. Regression prompts live in `docs/conductor-eval.md`.

## Release flow
Default: do not release unless the user explicitly asks. Do not bump `WONDER_BUILD` or create tags as a routine close-out; advance the label only when actually cutting a release and keep the remaining labels for later releases.
Release is a five-step close-out: check anything promised in the session but not done, align docs with behavior, run the full local verification, push, and deploy to the owner's actual install entry (GitHub release + Feishu notice). Report evidence for each step.
Deterministic steps live in [`scripts/release.sh`](scripts/release.sh): `--check` prints the plan, `--watch <tag>` rechecks a published release. It never force-pushes and never moves or deletes a tag.
1. Set `WONDER_BUILD` to the next unused two-character label in `version.properties` (see [`docs/versioning.md`](docs/versioning.md); the order is `A9` → `AA`, not `A10`). `VERSION_CODE` and `VERSION_NAME` are derived from `CORE_VERSION` and `WONDER_BUILD`; only change `CORE_VERSION` when moving to a new upstream core.
2. Commit, `git push origin dev`, wait for green Test CI.
3. Create an annotated tag `v<CORE_VERSION>-wonder.<label>` on the tested SHA and push only that tag. The first annotation line must be `wonder.<label>`. The annotation must also contain a short release-notes body (what changed); an annotation with only the first line is rejected by the Release workflow and must not be used. Prefer `scripts/release.sh` with `--note`, which writes the note and `Changes since ...` into the annotation. The annotation may include `Agent-Time: 2h15m` and `Agent-Loops: 4` for the work since the previous release tag. `Agent-Time` is wall time spent by coding agents, written as `Nh`, `Nm`, or `NhNm`. `Agent-Loops` is the number of work-loop passes (pick work, implement, test), not individual tool calls. Leave a line out when you cannot account for it. Do not write 0, and do not guess. A lightweight tag, or an annotation without these lines, still releases; the notice omits the missing rows. Do not move a tag later to fix the annotation.
4. `gh run watch` the Release workflow; then `gh release view <tag> -R wonder-missing/sing-box-for-android`
   and confirm the `arm64-v8a` APK asset exists. Also confirm the `Release notify` run sent the Feishu message. Report the download URL.
   The publish step retries up to 5 times if creating or uploading the release fails, and checks that the arm64 APK asset is present before it treats the attempt as done. The Feishu notice includes the unit-test count from the Test artifact `unit-test-count`, the unit-test job duration, the release build duration when that work actually ran (a cached libbox restore is omitted), the `Agent-Time` and `Agent-Loops` lines from the annotated tag, and a short commit list since the previous release. Missing values are left out. The notice stays text under 320 characters and 8 lines, and becomes an interactive card past that. A normal release with the APK link crosses that and is sent as a card.
- Releases are **arm64-v8a only**.
- Release-tag annotations and GitHub release notes are **English-only**. Keep Chinese only when the Chinese text itself is the source fact being quoted (for example an exact UI string or error message); do not mix Chinese prose into the note.
- Commit messages and release-tag notes omit `Co-Authored-By`.
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
