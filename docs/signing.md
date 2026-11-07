# WonderSing signing on Mini

Release key: `~/workspace/CFG/private/wondersing-release.jks`.
Parameters: `~/workspace/CFG/private/wondersing-signing.env` (private, never commit
or print). Keep the private directory mode 0700 and files 0600.

## Existing Gradle inputs

`app/build.gradle.kts` reads base64-encoded Java properties from the
`LOCAL_PROPERTIES` environment variable first, then root `local.properties`.
The exact property mapping (placeholders only; do not commit real values) is:

```properties
KEYSTORE_PASS=<SIGNING_KEYSTORE_PASSWORD>
ALIAS_NAME=<SIGNING_KEY_ALIAS>
ALIAS_PASS=<SIGNING_KEY_PASSWORD>
```

These correspond to the names in the private signing env file and the release
workflow. Setting `SIGNING_KEYSTORE_PASSWORD` etc. alone does not configure Gradle:
the workflow converts them to the above properties and exports `LOCAL_PROPERTIES`.
If preparing local.properties locally, use Java-properties escaping and mode 0600;
it is gitignored. Do not print credentials or pass them on command lines.

## Keystore path limitation

Gradle currently hardcodes `app/release.keystore`; there is **no keystore-path
property/environment hook** for the private Mini key. Therefore credentials alone
do not select the WonderSing key for a local build. Do not overwrite the tracked
upstream `app/release.keystore`, and do not assume a local APK has WonderSing's
signature. Local signing is not automatically wired up by this documentation;
adding a safe path override is separate work. Existing GitHub Actions release
signing and its secrets remain unchanged. Continue using the arm64-v8a release
workflow for signed distribution until local keystore selection is implemented.
