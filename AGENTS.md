# AGENTS.md — WonderSing (fork of sing-box-for-android)

Rules for any coding agent (Claude Code, Codex, etc.) working in this repo on clawdy-mini.
Goal: given "check TODO and latest issues, do 1–2h of work", produce and test the change. Release only after the user explicitly asks for a release; a clean `dev` does not imply one.
General working style follows the owner's global working agreement; project-specific contracts below override.

## Repo facts
- Fork: `wonder-missing/sing-box-for-android`, work branch `dev`. Upstream is SagerNet.
- **Always pass `-R wonder-missing/sing-box-for-android` to every `gh` command** (default resolves to upstream).
- Workspace: `~/workspace` on clawdy-mini. CFG repo: `~/workspace/CFG`.
- CFG is a neighboring project. Use its CLI as needed (`python3 ~/workspace/CFG/cli/bin/cfg ...`, e.g. `clientlog report --hours N` for app telemetry); do not modify CFG code from this repo.
- Local bare backup: remote `wondersing` at `~/repos/WonderSing`; after each daily commit push `dev`, and push tags when releasing.
- Local JVM tests and compile checks run on the Mini; setup, versions and disk cleanup live in [`docs/local-testing.md`](docs/local-testing.md). CI is the release gate. `scripts/e2e-emulator.sh` is the startup/UI smoke test; VPN/service behavior still needs a real device.
- Update install path, the system scan prompt, and post-update service restore: [`docs/update-install.md`](docs/update-install.md).
- `version.properties` is the single version source: `CORE_VERSION`, `WONDER_BUILD`, `GO_VERSION`. `WONDER_BUILD` is a two-character base-36 label (`99` → `A0`, `A9` → `AA`, never `A10`); use the next unused label from [`docs/versioning.md`](docs/versioning.md). `VERSION_CODE`/`VERSION_NAME` are derived in `app/build.gradle.kts`; `WonderVersionTest` guards the update-checker base.
- Capability scenarios live in [`docs/conductor-scenarios.md`](docs/conductor-scenarios.md). Before removing/merging a tool or changing a tool parameter, check the scenarios that name it and preserve or explicitly retire them.
- **The user's "Groups page" is the Dashboard Groups bottom sheet** (`showGroupsSheet`, `GroupsCard(asSheet = true)`); its title/actions row is `MainActivity`'s `listHeaderContent`. `Screen.Groups` is a wide-screen `NavigationRail` destination with no phone entry; do not treat it as the Groups page in UI discussions.

## Work loop
1. `git pull --ff-only` on `dev`.
2. Read `TODO.md` and `gh issue list -R wonder-missing/sing-box-for-android`.
3. When working autonomously, pick ~1–2h of scope (issues first, then TODO). Keep `TODO.md` updated.
4. Features/fixes need JVM unit tests.
5. Run `./scripts/local-test.sh` for JVM compile and tests; run `./scripts/e2e-emulator.sh` for startup/UI wiring. Then commit, `git push origin dev`, and `gh run list -R wonder-missing/sing-box-for-android -w Test -L 1` / `gh run watch <id> -R wonder-missing/sing-box-for-android --exit-status`. Red → fix and repeat until green. CI remains the release gate.
6. When an issue is fixed: `gh issue comment` (what changed, which commit or release) and `gh issue close` (with `-R ...`).

## Upstream sync
- Sync infrequently and in one pass: fetch upstream, identify already-applied equivalents, cherry-pick the remaining commits together, resolve all conflicts, verify once, and push once. Do not split functional and translation commits into separate merges.

## Evidence and data pulls
- **Gate before any pull.** Name the artifact that can answer the question and confirm the source can contain it. `client_telemetry` / `conductor_tool` is metadata only (tool name, status, timing); it cannot answer Plan-card usability, message text or final-answer questions. Use a transcript (`Plain Transcript`, `diagnostics_export kind=conversation`) or the API trace. If only metadata is available, stop in the first response and ask for the export; do not probe device, VFS, ADB or alternate transports. State the gate in one line: question → required artifact → source coverage → pull or stop.
- **Check coverage before interpreting:** retained entries vs summaries, `[body truncated]` markers, request/response sizes, whether the final request and answer are present, and whether a full-turn snapshot exists. Prefer `appfiles/conductor/trace-turns/` for a whole turn; live `api-trace.json` is a bounded recent window.
- **Incomplete data stops the assessment.** Report exactly what is missing; do not assess from a partial sample. Narrow the question to what the data supports or ask for a complete export.
- **Coverage is a gate, not the deliverable.** Once the data can answer the question, answer it. Do not re-audit completeness or report it as the finding unless completeness is the question. If the owner redirects or interjects, handle the new question first, say what is paused, and record where the original stopped.
- **Label evidence.** Separate observed facts (trace, code, user reproduction), inferred mechanisms, and subjective assessments. An agent's self-evaluation is opinion, not evidence; state its observational boundary and corroborate factual claims with code, trace or a rendered UI.
- **Boundary first.** Put the coverage boundary in the first paragraph. Tool-call analysis may use the final request transcript; reasoning/final-answer analysis needs response bodies. Do not mix them.

## Planning
- Plans are change plans: files/changes, order, tests and release steps. No time estimates; `Agent-Time` / `Agent-Loops` are release metadata recorded only when measured (see Release flow).
- Complete the whole requested change in one pass. Stop for the owner only when there are two genuinely different designs. For an evidence-backed cleanup, list observed issues and evidence first; if there is no design fork, implement without a second confirmation.
- For visual/interaction exploration, show a concrete effect before an implementation list; technical choices that will remain still need a plan.
- The ~1–2h scope is for autonomous TODO/issue picking; a user-requested change is delivered as one whole change. Product phases in `docs/` are roadmaps.

## UI and interaction
- Layout uses `dp`; text uses `sp`. Standard scale: spacing `4/8/12/16/24dp`; corner radius `8/12/16dp` (pills half height); touch target `48dp`; body text `14–16sp`. A bare "px" means design dp (`1 design px ≈ 1dp`); inputs/cards usually use `8–12dp`.
- **Standard interactions first**, one action per control: prefer a visible button, chevron or menu item over hidden gestures; do not duplicate entry points; custom gestures need approval first. Copy uses native long-press + System Copy (no tap-to-copy on inline code). Keep interactive rows at least `48dp` unless a compact design was explicitly approved (the reasoning strip is the known exception). Show user-message send time under the bubble.
- The owner uses frontend terms (`padding`/`margin`/`gap`/`flex`); translate to dp and Compose. Prefer relative references over exact numbers, and apply product-level spacing/alignment yourself.
- **Inspect the UI path before explaining behavior.** When the owner reports an interaction, read the Compose/UI code and reproduce it; an agent's explanation of a card it cannot see is not evidence.
- **Conductor Plan card completion is terminal.** When every step is `done`, hide Edit and do not add Reopen; re-planning is a natural-language request to Conductor. While work remains, the card stays editable. Every model/user plan change is a `PlanEvent` (version, time, actor, action) and appears in `## Plan history`; `[runtime:plan]` carries `updatedBy`.
- **Render UI changes locally.** Compile/tests are not a UI proof; use `scripts/e2e-emulator.sh` (`E2E_KEEP_EMULATOR=1` when inspecting) and keep screenshots/XML under `build/e2e-emulator/`. Report the path, not "should be fixed".
- Design specs/tokens live in [`design/README.md`](design/README.md); Conductor-specific UI decisions live in [`docs/agent-control-plane.md`](docs/agent-control-plane.md).

## Conductor tool metadata
- Tool names, descriptions, aliases and search keywords are English-only. The model translates intent to English before `search_tools` and answers in the user's language.
- Do not add localized aliases or a build-time multilingual keyword pipeline. Regression prompts live in [`docs/conductor-eval.md`](docs/conductor-eval.md).

## Release flow
Default: do not release unless the user explicitly asks. Do not bump `WONDER_BUILD` or create tags as routine close-out; advance the label only for a release. Release is a five-step close-out: check promises, align docs, run full local verification, push, and deploy to the owner's install entry (GitHub release + Feishu notice); report evidence for each.
Deterministic steps live in [`scripts/release.sh`](scripts/release.sh): `--check` prints the plan, `--watch <tag>` rechecks a published release. It never force-pushes and never moves/deletes a tag.
1. Set `WONDER_BUILD` to the next unused two-character label. `VERSION_CODE`/`VERSION_NAME` derive from `CORE_VERSION` and `WONDER_BUILD`; change `CORE_VERSION` only for a new upstream core.
2. Commit, `git push origin dev`, and wait for green Test CI.
3. Create an annotated tag `v<CORE_VERSION>-wonder.<label>` on the tested SHA and push only that tag. The first annotation line must be `wonder.<label>`; include a short English release-notes body (an annotation with only the first line is rejected). Prefer `scripts/release.sh --note`, which appends `Changes since ...`. Optional `Agent-Time` (`Nh`, `Nm`, `NhNm`) and `Agent-Loops` lines only when measured; leave them out rather than guessing or writing 0. Do not move a tag later.
4. `gh run watch` Release; `gh release view <tag> -R wonder-missing/sing-box-for-android`; confirm the `arm64-v8a` APK asset and the `Release notify` message; report the download URL.
- Releases are **arm64-v8a only**. Release notes/tag annotations are **English-only** except when Chinese is the quoted source fact. Commit messages and tags omit `Co-Authored-By`.
- Release notes list only app-facing changes under `app/src/main`; exclude AGENTS/docs, CI, local test/emulator tooling, repository governance and merge mechanics. `scripts/release.sh` applies the same path filter to `Changes since`.
- `release.yml` restores the `libbox.aar` cache written by `test.yml` on `dev`; keep cache key and recipe version in sync.
- Never delete/move tags or releases; never force-push; push only `dev` (and release tags).
- In `.claude/settings.json`, keep the delete-refspec deny as `Bash(git push origin ::*)`. A trailing `:*` is the prefix wildcard; `Bash(git push origin :*)` would match every push, including `dev` and tags.

## Signing & private files
- Never change signing config, `app/release.keystore`, or GitHub Actions signing secrets. CI signs releases.
- Private area: `~/workspace/CFG/private/` (outside Git, never commit/print): `wondersing-release.jks`, `wondersing-signing.env`, `sfa-telemetry.env`, `ws-secrets/`, `airport-sub.yaml`.
- Never print tokens, passwords or subscription URLs into logs, commits, issues or chat.

## Open work
- Track open work in `TODO.md` under 「现在待办」; do not keep a second list here.
- Conductor control-plane vision: [`docs/agent-control-plane.md`](docs/agent-control-plane.md). Local signing limitation: [`docs/signing.md`](docs/signing.md).

## Editing this file
- Treat this as policy, not an incident log. Every edit must state what is added, replaced or removed; if an equivalent rule exists, merge it instead of appending.
- Keep one concern per bullet and put explanations, evidence, runbooks and postmortems in `docs/`, not here.
