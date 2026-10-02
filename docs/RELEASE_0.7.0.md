# Antigravity Mobile 0.7.0 (code 13)

Signed bug-fix update from source commit `8684db8535a5062d134001e5a8f11cd7775906f3`.

Install `antigravity-mobile-0.7.0.apk` over your existing app. Keep the app installed to preserve its data. Main app and bundled Build Tools use the original signing certificate. The separate `antigravity-build-tools-code2.apk` is also included; worker code remains 2 and Room remains v4.

## Fixes

- Deliver real CLI `native_request` events to native approval/build/install tools.
- Retry a committed workspace upload after a lost checkpoint without replacing its files.
- Recover helper death between workspace publication and its ready checkpoint only when the complete workspace still matches the validated archive; refuse changed or already-started work.
- Install `python3` in Linux base setup and retain failed/interrupted setup status after rootfs extraction.
- Clear credentials, model/catalog/plan and active tool verification when changing a custom endpoint; serialize changes with in-flight verification and refresh the displayed catalog.
- Explain the official Antigravity CLI Google login route separately from native Google AI Studio API-key billing. Backend capability gates remain enforced.

## Verification actually run

- 35 JVM classes, 155 tests, 0 failures/errors/skips.
- App and worker release lint: 0 errors/fatal issues; existing warnings remain (61 app, 15 worker).
- Signed release build: `BUILD SUCCESSFUL in 2m 1s`; 143 tasks, 141 executed, 2 up-to-date.
- Python bridge: `Ran 31 tests in 28.850s`, `OK`; existing BufferedReader ResourceWarning remains.
- Linux helper: `passed=22 failed=0`.
- Both APK signatures verified against original certificate SHA-256 `791980edfce3d623dfe1ba2e3209a506b01c4be7da74065805d400a5051210b5`. Embedded worker matches the standalone worker byte for byte.
- API 36 ARM64 emulator (`emulator-5554`): `adb install -r` returned `Success` for 0.6.0 -> 0.7.0. Existing Hello Phone project stayed present, launch returned `Status: ok`, and current app PID had no crash entry. This was Room v4 -> v4, with no schema migration.

## Known limitations and pending acceptance

No physical OnePlus was connected. Physical data-preserving upgrade, the approved-build incident trace, phone-only game build/install/play, cancellation, Git workflow and live subscription inference remain **unverified**. Installing this release does not complete Gate 0.7.0 or authorize a 0.8.0 gate bump.

26 candidate emulator checks completed previously, but the full device suite did **not** pass: isolated real-Termux tests encountered a temporary QA launcher/client path mismatch. Correcting that temporary setup and rerunning still awaits owner approval after repeated failures. Production paths match. No full candidate CLI recovery/cancellation acceptance is claimed.

Codex probe reported `aarch64`, `codex-cli 0.159.3`, sandbox `unavailable`, so Codex stays disabled. Antigravity CLI stays disabled until a documented headless sandbox check passes. Signed-in `agy` protocol fields and real inference remain unverified. No credentials were used or included in these artifacts.

Follow the [phone checklist](https://github.com/Srimi1/antigravity--mobile/blob/8684db8535a5062d134001e5a8f11cd7775906f3/docs/PHONE_TEST_0.7.0.md) and [Gemini login guide](https://github.com/Srimi1/antigravity--mobile/blob/8684db8535a5062d134001e5a8f11cd7775906f3/docs/GEMINI_LOGIN.md). Historical preparation evidence is in the [candidate notes](https://github.com/Srimi1/antigravity--mobile/blob/8684db8535a5062d134001e5a8f11cd7775906f3/docs/RELEASE_CANDIDATE_0.7.0.md).

## Downloads

- Main APK: installable update, Android 10+ / ARM64.
- Build Tools: matching same-signer companion, code 2; also embedded in the main APK.
- Source ZIP: tracked source at the release commit; no private signing keys, credentials or local configuration.
- `SHA256SUMS` and `artifacts.json`: checksums, signature/package details and validation record.

| APK | Bytes | SHA-256 |
| --- | ---: | --- |
| `antigravity-mobile-0.7.0.apk` | 306239446 | `c21c6d1561aaec663421f02ca67897cd4b68934b684c63e037053d93fab9564e` |
| `antigravity-build-tools-code2.apk` | 261978324 | `d9397d543af07e5789bac5209e07d588c47b655a9eea0c8f6406cc9c428a3b64` |

## Reproduction commands and local evidence

Java 17 and Android SDK 36 were used from the main checkout, with output outside iCloud:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew --project-cache-dir "$HOME/.cache/agm-lane-a" \
  -PagmBuildRoot="$HOME/.cache/agm-0.7.0-release-build" \
  -PagmUnsignedRelease=false \
  :app:testDebugUnitTest :app:lintRelease :build-worker:lintRelease \
  :app:assembleRelease --console=plain
```

```text
BUILD SUCCESSFUL in 2m 1s
143 actionable tasks: 141 executed, 2 up-to-date
JVM: {'classes': 35, 'tests': 155, 'failures': 0, 'errors': 0, 'skipped': 0}
Lint: {'app': {'Fatal': 0, 'Error': 0, 'Warning': 61}, 'build-worker': {'Fatal': 0, 'Error': 0, 'Warning': 15}}
```

Python command from `app/src/test/python/bridge`: `python3 -m unittest discover -s . -p 'test_*.py'`.

```text
Ran 31 tests in 28.850s
OK
```

Linux command: `bash tools/linux-runtime/test-agm-linux.sh`.

```text
passed=22 failed=0
```

Signed artifacts, build/test logs, before/after emulator UI XML, actual screenshot and public asset verification are saved in `~/dev/agm-0.7.0-release/`. Backup before publication: `~/dev/agm-before-0.7.0-publication-20261002.bundle`, verified. No private key was copied into these artifacts. Only the emulator was used; no phone lock or phone operation occurred.

The emulator was updated using:

```sh
adb -s emulator-5554 install -r ~/dev/agm-0.7.0-release/antigravity-mobile-0.7.0.apk
adb -s emulator-5554 shell am start -W \
  -n dev.srimi.antigravitymobile.probe/dev.srimi.antigravitymobile.MainActivity
```

```text
Performing Streamed Install
Success
Status: ok
LaunchState: COLD
TotalTime: 391
versionCode=13 minSdk=29 targetSdk=36
versionName=0.7.0
Existing Hello Phone project present before and after in-place update: True
Current app PID crash entry: False
```

GitHub tag and main at publication matched local main: `8684db8535a5062d134001e5a8f11cd7775906f3`. Release published at `2026-10-02T11:31:08Z`, marked Latest. GitHub reported every uploaded asset's size and SHA-256 equal to its local file. An unauthenticated HEAD request followed the public APK URL:

```text
Public APK download HTTP status: 200
Public APK Content-Length: 306239446
GitHub Latest: v0.7.0
```

The source archive scan found no private signing/build paths or token patterns outside the verified offline `GitHubSupportTest.errorsNeverEchoTokens` redaction fixture. No CI workflow is configured, so this release relies on the actual local checks above. Release publication is separate from physical acceptance.
