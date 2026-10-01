# Lane A — runtime and CLI bridge

## Day 0 — 2026-10-01

- Baseline: `fea895fe6918ff8e022f6d85c544b90401403133`, fetched `origin/main`, no divergence. Original checkout has only the owner's untracked `goal/`; preserved.
- Isolated worktree: `~/dev/agm-lane-a`, branch `lane-a/runtime`. Gradle commands use `--project-cache-dir ~/.cache/agm-lane-a -PagmBuildRoot=$HOME/.cache/agm-lane-a-build`.
- Root build directory now honours `agmBuildRoot`; default remains `~/.cache/antigravity-mobile-build/<project>`. No version or signer changes.
- Validation: reviewed root configuration diff and `git diff --check`. This host currently has no configured Java runtime, Android SDK, or ADB; Gradle/device checks have not run.
- Next: freeze runtime contracts, coordinate Lane B's contracts, persist approvals and tool/model checkpoints in Room v4; preserve `AgentModel` signature.
- Gate 0.6.0 remains pending Lane B diagnostics, regression/device evidence and physical phone trace. No signing or release publication authorized.

## A1 implementation checkpoint — 2026-10-01 (not a release gate)

- Persisted keyed approval decisions and receipts, per-action atomic claims, typed tool outcomes, Room v4 task checkpoints and the exact provider tables. `AgentModel` unchanged.
- Foreground service owns execution. ViewModel observes; rotation/navigation no longer stop jobs. Process death restores a paused checkpoint, reconciles observed worker builds and never dispatches uncertain actions.
- Builds return real worker outcomes. Log tails and verified artifact hashes persist; install approval pins a successful build in the same project across messages. `build_result` / `read_build_log` answer from recorded evidence.
- Regression red: `AgentBuildToolsTest.failedBuildReturnsLogAndCannotBeInstalled` failed with expected `FAILED`, actual `COMPLETED` on the old loop. Green: both existing loop/build classes, 9 JVM tests, passed after the typed outcome change. AGP/kapt main and instrumentation Kotlin compilation passed (Gradle 8.13, Java 17, SDK 36).
- Prepared pinned Android-native inputs with the repository's `prepare.py`; downloads and generated files remain outside source/iCloud. No personal signing key accessed, no release APK or publication.
- ADB inventory empty: no phone trace or physical acceptance. Device/runtime regression suite, full JVM suite and lint still pending. Lane B Day 0/B1 code is on newer local main; rebase/integrate next.
- GitHub push was rejected by PreToolUse hook (`git push` dangerous-pattern rule); GitHub remains at baseline until owner allows source push or pushes it. Day 0 commits on local main: `d90a81c`, `5bfe0ae`.

## A1 reliability follow-up — 2026-10-01

- Targeted replay, build-result, evidence, workspace and change-ledger JVM checks pass. A deliberate red conflict test showed approval could overwrite a later owner edit; baseline comparison and atomic file replacement now preserve that edit.
- Bound Agent build snapshots to their task and persisted approval claim. Build-tab buttons cannot approve or decline an Agent-owned build. This closes a second approval path; the original phone incident still needs its trace.
- Room-backed provider usage store is implemented; unknown token counts stay null. Installer permission setup returns RuntimeUnavailable rather than claiming the APK installer opened.
- Release lint and debug APK build passed before these follow-up changes. Emulator 5554 booted with API 36 ARM64; no physical phone connected. New Room/lifecycle/device checks pending. Worker protocol/code 2 and app 0.5.2/code 11 unchanged.

## A1 lifecycle and review evidence — 2026-10-01 (not a release gate)

- `RuntimeStoreDeviceTest`, initial `NativeRuntimeDeviceTest` and `FullAppDeviceTest`: `OK (12 tests)`, 0.676 seconds on emulator-5554. Covers concurrent keyed decisions/claims, edit-only blanket approval, migrations 1/2/3 → 4, preserved history and nullable provider usage, typed failures and safe retry. Providers/workers in the runtime tests are fixtures; this is not live subscription inference.
- Real foreground-service lifecycle test: `OK (1 test)`, 6.422 seconds. Home/background and rotation kept the task and pending prompt; Stop recorded Cancelled. The test-only application/runner lives entirely in the instrumentation APK.
- Actual main-process force-stop, followed by normal Activity launch and explicit retry: seed `OK (1 test)`, 1.761 seconds; recovery `OK (1 test)`, 1.694 seconds. Recovery showed Paused, preserved the completed write and its mtime, marked the pending delete Interrupted, and retried only the unfinished model request with recorded results.
- Separate standards/spec reviews found recovered Stop could leave a worker running and cancelled cleanup could overlap the next task. Fixed persisted-build cancellation both in Stop and recovery of an already-Cancelled task; handoff waits until previous execution is completed. Expanded `NativeRuntimeDeviceTest`: `OK (5 tests)`, 0.67 seconds, including both death-during-Stop and cancelled-cleanup cases.
- Screen commands now use process scope so rotation cannot cancel approval/start/retry submission. Persisted edit-permission receipts include the all-edits flag. OpenAI-compatible routing is explicit; non-secret selection snapshots prevent retries after Accounts changes. Lane B has a request to pin that snapshot inside its adapter.
- Spec review also found manual Build dialog dismissal/Cancel calls decline. Lane B owns its ViewModel; requested a distinct cancellation helper, then Lane A will wire the dialog. This remains unresolved until that helper lands.
- Lane B reports low disk space evicted shared iCloud Git objects. Lane A commits were backed up to `~/dev/agm-lane-a-backup.bundle` outside iCloud; 17 GiB currently free. No new large downloads. Local main is `cc2240d`; partner commits are not yet merged. Source push remains hook-blocked, physical phone absent, signing/publication unauthorized.
- Next: real one-shot worker/build regressions, full JVM suite and lint, apply partner helpers, reconcile local main. Gate 0.6.0 and physical approved-but-declined trace remain pending; app/worker versions unchanged.
