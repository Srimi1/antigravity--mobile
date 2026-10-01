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

## A1 real worker evidence — 2026-10-01

- `BuildWorkerDeviceTest`: `OK (4 tests)`, 30.989 seconds on emulator-5554. Actual Android-native Gradle dispatch, duplicate approval/claim rejection, Agent-owned Build-tab rejection, cancellation and worker death/no old-ID replay passed. The one-shot marker appeared exactly once.
- `AgentBuildDeviceTest`: `OK (1 test)`, 439.976 seconds. Real Compose debug APK built through `PhoneBuildRunner`; verified successful artifacts and `BUILD SUCCESSFUL`. A second snapshot with an undefined Kotlin symbol returned FAILED, the actual compiler log and no APK. This check did not install/launch the sample or use a live provider.
- Read-only standards follow-up confirms the death-during-Stop and cancelled-cleanup fixes; no remaining concrete defect in those reviewed paths. Manual Build dismissal still awaits Lane B's keyed cancel helper.
- Rebased onto Lane B's source through `69a4f5d`, producing Lane A checkpoint `3257fd4`; then successfully fast-forwarded local main to it. Both lanes' source preserved despite the earlier iCloud incident. GitHub remains unsynchronized; new lane notes/requests and code retain an external bundle backup.
- CLI foundation started with mutual pairing proofs, direction-bound HMAC frames, replay/size/schema rejection. Four JVM security tests pass. No CLI process, sign-in or inference enabled; ARM64/sandbox phone checks and Gate 0.7.0 integration still pending.

## A1 manual approval closure and A2 foundation — 2026-10-01 (not a release gate)

- Manual Build dialog now binds callbacks to the captured build ID, hides consumed prompts, records Cancel/Back as CANCELLED and reserves DECLINED for labelled Decline. Actual Build-tab UI regression: `OK (1 test)`, 3.685 seconds on emulator-5554; none of the three rejected/cancelled snapshots was dispatched to the worker. This resolves the remaining A1 spec review finding; the original physical phone incident still has no trace.
- Full JVM suite plus release lint: `BUILD SUCCESSFUL in 32s`; XML reports 28 classes, 134 tests, zero failures/errors/skips. Python stdlib bridge tests: `Ran 11 tests in 0.346s`, `OK`. JVM fixtures do not prove live CLI execution or subscription inference.
- Authenticated loopback connection, bounded event frames and protocol adapters for the documented Codex app-server / Antigravity headless stream are implemented as an unwired foundation. The helper persists one-shot process/write claims and events; restart never repeats uncertain starts or writes. Official references: https://learn.chatgpt.com/docs/app-server and https://www.antigravity.google/docs/cli/headless/.
- Separate read-only spec and standards reviews exposed process descendants surviving Stop, blocking stdin holding cancellation lock, valid notification ordering rejection, oversized observation events, unbounded approval metadata, stale terminal approvals and malformed outcomes reported as success. Regression tests reproduce each; source fixes pass. Unconfirmed process shutdown now retains the single-task restriction across restart; the process-group tests use mocks, not real Debian/phone execution.
- Private workspace transfer/import, runner/router/service/UI integration, native build/install MCP, pairing bootstrap and physical ARM64/sandbox checks remain pending. No CLI capability enabled. Linux setup panel/manifest hooks and suspend artifact verification are being applied as requested by Lane B; Gate 0.7.0 remains pending its owner.
- App stays 0.5.2/code 11, worker code 2. No personal signing or release publication. Source push remains hook-blocked; physical phone/game acceptance unverified.

## A2 private workspace and message validation — 2026-10-02 (not a release gate)

- Bounded, hashed snapshots now transfer through the paired bridge into a private Debian workspace. Returned ZIPs become diffs against the saved baseline, with traversal, duplicates, CRC, size, credential-path and later-owner-edit checks. Native project files are not changed by transfer or diff generation; Changes import and runner wiring are still pending.
- Read-only reviews found copy-time link/content swaps could evade the helper's before/after hashes. Capture now opens each path component without following links, streams with byte limits and verifies the bytes actually archived. A replacement/restoration attack and a growth attack are rejected by regression tests. Atomic native task-directory reservation prevents concurrent creators from overwriting or deleting the winning snapshot.
- Definitive missing-binary/permission spawn failures release the helper's global slot while retaining the old task ID's non-replayable claim. Uncertain spawn, pipe and cancellation failures retain the slot across restart.
- Android's permissive JSON parser is replaced at the bridge boundary with strict syntax, duplicate-key, numeric, Unicode and nesting checks. Real Android bridge parser and manual Build-dialog regressions on emulator-5554: `OK (2 tests)`, 8.745 seconds. Python helper/workspace tests: `Ran 19 tests in 0.881s`, `OK`; targeted bridge JVM tests and debug/instrumentation APK compilation: `BUILD SUCCESSFUL in 4s`.
- Full JVM suite and release lint after these changes: `BUILD SUCCESSFUL in 29s`; XML reports 31 classes, 143 tests, zero failures/errors/skips.
- Real Python/Kotlin authenticated transfer and capture interop passed with a file fixture. No CLI sign-in, inference, ARM64 execution or sandbox acceptance is implied. CLI capabilities remain disabled; pairing bootstrap, Agent chat routing, native MCP and physical phone acceptance remain outstanding.
- Local Lane A source before this checkpoint: `11d1899f2192c73f0d976530ea1eb97000cf2a54`; local main `7e98d435649bf5df60768967b40b469294c85bc1`; GitHub last verified `fea895fe6918ff8e022f6d85c544b90401403133`. No version bump, personal signing or release publication.
