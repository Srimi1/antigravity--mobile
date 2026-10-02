# 0.7.0/code 13 — unsigned bug-fix candidate

Prepared on 2 October 2026 from `main`/`origin/main` `246a55d8f27378cd8cf836fe77848c530c25d2e3`. Latest published release remains [v0.6.0](https://github.com/Srimi1/antigravity--mobile/releases/tag/v0.6.0), built from `13368e7ede732d0c23a5f3cccfe18f24ff7438b9`. This candidate is not signed, published or physically accepted.

Implementation commit: `cd2222407b5703164e161ad6a51d558c7e6d44c4` on `fix/0.7.0-bugs`. Initial backup: `~/dev/agm-bugfix-backup-20261002-144953.bundle`. Free space stayed above 61 GiB. iCloud had evicted 23 Git files; targeted hydration brought the required `find .git -flags +dataless | wc -l` check to 0 before any main fast-forward.

## Fixed behavior

- Python's `native_request` now reaches the Kotlin runner and its native approval/build/install tools.
- A committed upload can be prepared again after a lost client checkpoint without retransmitting/replacing its files. Archive identity is still checked.
- If the helper crashes after publishing an extracted workspace but before its ready checkpoint, Retry validates its complete contents against the archive and finishes the checkpoint. Changed files, links, unexpected entries and started tasks are refused; existing files are preserved.
- Linux base setup installs `python3`. Failed/interrupted setup remains failed after rootfs extraction.
- Changing a custom endpoint clears credentials, model/plan/catalog and active tool verification in preferences and Room. The change waits for an in-flight probe's evidence write. Model lists/prices follow the current catalog.
- Accounts explains the official Google CLI login route; native AI Studio-key access stays separate. No CLI capability gate was bypassed.

Version is 0.7.0/code 13; worker stays code 2 and Room stays v4. Unsigned build mode deliberately bundles an unsigned worker. Signing the main APK alone is insufficient: the worker must also be signed with the original key before final packaging.

## Checks actually run

Java 17, Android SDK 36; source worktree `~/dev/agm-lane-a`.

```sh
./gradlew --project-cache-dir ~/.cache/agm-lane-a \
  -PagmBuildRoot="$HOME/.cache/agm-lane-a-build" -PagmUnsignedRelease=true \
  :app:testDebugUnitTest :app:lintRelease :build-worker:lintRelease \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease --console=plain
```

```text
BUILD SUCCESSFUL in 24s
225 actionable tasks: 54 executed, 171 up-to-date
{"classes": 35, "tests": 155, "failures": 0, "errors": 0, "skipped": 0}
```

From `app/src/test/python/bridge`, `python3 -m unittest discover -s . -p 'test_*.py'`:

```text
Ran 31 tests in 28.936s
OK
```

The Python suite still emits an existing unclosed-BufferedReader `ResourceWarning`; the tests pass. `bash tools/linux-runtime/test-agm-linux.sh`:

```text
passed=22 failed=0
```

Each behavioral fix had a regression fail before its fix. The additional commit-crash regression failed with `ProtocolError` during Retry before the recovery change; all seven workspace tests pass afterward. The one-line account catalog refresh correction is covered by compilation/lint, without a separate UI test.

Release lint has 0 errors/fatal issues, with existing warnings (app 61, worker 15). The real-Termux test now reports a terminal pre-Start failure promptly with task/action evidence; its final original-package androidTest compilation passed:

```text
BUILD SUCCESSFUL in 9s
52 actionable tasks: 24 executed, 28 up-to-date
```

## Emulator evidence and pending rerun

Only `emulator-5554` / `AgmLaneA_API36` was used. A separate QA app/worker/test package and signature permission preserved the installed release. Temporary copies outside the repository changed QA package IDs and bridge root only; production IDs, permissions and bridge paths are unchanged.

The combined run completed these classes before the real-Termux startup failure:

```text
dev.srimi.antigravitymobile.CliRuntimeDeviceTest:...........
dev.srimi.antigravitymobile.NativeRuntimeDeviceTest:.....
dev.srimi.antigravitymobile.TermuxBridgeDeviceTest:.
dev.srimi.antigravitymobile.RuntimeStoreDeviceTest:....
dev.srimi.antigravitymobile.FullAppDeviceTest:.....
```

Those are 26 completed checks. The combined suite did **not** pass: it was stopped after the real-Termux restart case timed out. The improved diagnostic then exposed:

```text
CLI Start not recorded: Paused: Paused: CLI bridge disconnected or sent invalid data; actions=[]
Tests run: 1,  Failures: 1
```

Cause: the temporary QA launcher root was isolated, but the QA client still expected the production root. Production launcher/client paths both remain `/root/agm-work/bridge`. Correcting temporary QA copies and rerunning the remaining tests is awaiting owner approval under the Lane A repeated-failure stop rule. No full emulator-suite success or candidate CLI cancellation/recovery acceptance is claimed.

Actual app probe through Termux RUN_COMMAND returned `backend=codex`, `machine=aarch64`, `version=codex-cli 0.159.3`, `sandbox=unavailable`. Codex therefore remains disabled. This is execution/probe evidence, not inference. No owner account was signed in.

## Candidate artifacts

Outside iCloud under `~/.cache/agm-lane-a-build/`:

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `app/outputs/apk/release/app-release-unsigned.apk` | 306224154 | `ed1112e3f7ad3a748f1c4360a1fa580f795794a99efb632462b6c43362f47a24` |
| `build-worker/outputs/apk/release/build-worker-release-unsigned.apk` | 261974156 | `41e856d96eda76c59a4fa4bf38abcb04c35ba8d25cbd04e70440d8494b640f48` |

Embedded `assets/build-worker.apk` equals the unsigned companion byte for byte. `apksigner verify` correctly returns exit 1:

```text
DOES NOT VERIFY
ERROR: Missing META-INF/MANIFEST.MF
```

Reviewable copies and `artifacts.json` are also saved under `~/dev/agm-0.7.0-candidate/`: `antigravity-mobile-0.7.0-unsigned.apk` and `antigravity-build-tools-code2-unsigned.apk`, with the same hashes. They cannot be installed as release updates while unsigned.

## Remaining gates

No physical OnePlus is connected. Update/data preservation, approval trace, phone sandbox checks, signed-in inference/protocol traces and game acceptance remain unverified. [Phone checklist](PHONE_TEST_0.7.0.md), [Google login route](GEMINI_LOGIN.md). Gate 0.7.0 remains open; 0.8.0 is not bumped. Personal signing and publication require owner approval. No provider credentials were used, handled or recorded.
