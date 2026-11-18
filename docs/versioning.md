# Wonder build labels

`version.properties` is the only source. `WONDER_BUILD` is a two-character
build label; `CORE_VERSION` is separate. `VERSION_CODE` and `VERSION_NAME` are
derived by `app/build.gradle.kts`.

## Legacy range

Builds 1 through 99 keep their decimal spelling, so old tags such as
`v1.15.0-alpha.13-wonder.99` still parse as before.

## New range

From 100, the label is base-36 with a one-time offset:

```text
ordinal = parseInt(code, 36) - 261
code    = base36(ordinal + 261).toUpperCase()
```

`261` is `base36("A0") - 99`. `A0` would be 99, but 99 already uses the legacy
spelling, so the first new label is `A1`:

| ordinal | label |
| ------- | ----- |
| 98 | 98 |
| 99 | 99 |
| 100 | A1 |
| 101 | A2 |
| 108 | A9 |
| 109 | AA |
| 110 | AB |
| 134 | AZ |
| 135 | B0 |
| 1034 | ZZ |

`A1` through `ZZ` covers 935 labels. When the next ordinal would be 1035, this
scheme is exhausted; define a new one rather than using a three-character
label.

## Derivation

```text
versionCode = 11404000 + ordinal
versionName = <CORE_VERSION>-wonder.<label>
```

`versionCode` stays an integer and increases monotonically. The app's update
checker parses both legacy decimal tags and the new labels through
`WonderBuild.ordinalOf`, then compares `versionCode`.

## Add and subtract

- Next after `99`: ordinal 99 + 1 = 100, label `A1`.
- Next after `A1`: 100 + 1 = 101, label `A2`.
- Next after `A9`: 108 + 1 = 109, label `AA`.
- Next after `AZ`: 134 + 1 = 135, label `B0`.
- Previous before `A1`: 100 - 1 = 99, label `99`.
- Previous before `AA`: 109 - 1 = 108, label `A9`.

To bump a release, set `WONDER_BUILD` to the next label. Use the same label in
the annotated tag and its first annotation line:

```text
tag:      v<CORE_VERSION>-wonder.<label>
first:    wonder.<label>
```

The release workflow writes `release-notes-<versionName>.txt` from the tag
annotation and rejects a first line that does not match the current label.

## Checks

- `WonderBuildTest` covers boundaries and arithmetic.
- `WonderVersionTest` checks the derived `VERSION_CODE` against
  `GitHubReleaseFeed.WONDER_VERSION_CODE_BASE`.
- `GitHubReleaseFeedTest` covers legacy and base-36 tags.

The Gradle build and `WonderBuild` implement the same three formulas. Keep
`docs/versioning.md` and those two parsers in sync when changing the scheme.
