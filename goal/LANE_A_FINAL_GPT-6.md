# Goal — finish Antigravity Mobile gates 0.7.0 and 0.8.0 without a USB-connected phone

**Assigned model:** GPT-6 (medium effort)
**Owner decision (2 Oct 2026):** the owner tests only by downloading APKs from GitHub Releases and installing them on the OnePlus 7 Pro. ADB phone access is **not** available, so do not wait for it. You may take over Lane B's remaining Gate 0.7.0 work, including Lane B files (`linux/*`, `tools/linux-runtime/*`, provider files). Read `AGENTS.md`, `goal/LANE_B_OPUS-5.5_providers-linux.md`, `docs/lanes/lane-a.md` and `docs/lanes/lane-b.md` once before you start.

## State (verified 2 Oct 2026, 13:06)

- Release **0.6.0 / code 12** is built, committed and on local `main` at `13368e7ede732d0c23a5f3cccfe18f24ff7438b9` (also `lane-a/runtime`).
  - GitHub `origin/main` is still `fea895f`.
  - The owner pushes (agents' `git push` is blocked by a hook) and then creates release `v0.6.0` from `~/dev/agm-release-0.6.0/`.
  - Check with `gh release view v0.6.0 --repo Srimi1/antigravity--mobile`.
- 0.6.0 evidence:
  - JVM: 34 classes/150 tests, 0 failures. Release lint passes. Python bridge: 29 OK.
  - Emulator-5554: CliRuntime 11, NativeRuntime 5, CliRealTermux 4.
  - Release 0.5.2 → 0.6.0 in-place upgrade on the emulator kept data.
  - Signer SHA-256 `791980edfce3d623dfe1ba2e3209a506b01c4be7da74065805d400a5051210b5`. The worker is code 2 with the same signer.
- Lane B (`~/dev/agm-lane-b`, branch `lane-b/providers-linux`, HEAD `e8565ca`) is paused. Its provider registry, free/trial providers, network diagnostics and Linux/Termux installer are already merged into `main`. Its last commit (`e8565ca`) is only notes.
- The emulator `emulator-5554` (AVD `AgmLaneA_API36`) currently has the **release-signed 0.6.0** installed, not debug builds.
  - Before device tests, uninstall `dev.srimi.antigravitymobile.probe` and `dev.srimi.antigravitymobile.worker`, then install the debug app, the debug worker (`:build-worker:installDebug`) and the androidTest APK.
  - Re-grant Termux RUN_COMMAND: `adb shell pm grant dev.srimi.antigravitymobile.probe com.termux.permission.RUN_COMMAND`.
  - That emulator has Termux 0.118.3, `agm-debian`, `codex-cli 0.159.3` and `agy 1.2.14` (`/root/.local/bin/agy`).
- Still **unverified**:
  - everything on the physical phone
  - CLI sign-in and inference
  - `agy` `init`/`step_update`/`tool_info` field shapes (need a signed-in run)
  - every provider except Kilo anonymous
  - the XFCE desktop (black screen)

## Tasks, in order

### Step 0
1. In `~/dev/agm-lane-a`, run `git status`, `git fetch origin`, then compare `lane-a/runtime`, `main` and `origin/main`.
2. Create a `git bundle` of both lane branches outside iCloud.
3. Check `df -h ~` (keep at least 15 GB free).
4. Check that `find "<main checkout>/.git/objects" "<main checkout>/.git/refs" -flags +dataless | wc -l` prints 0. If it doesn't, run `brctl download` on those paths first.
5. If `origin/main` is still `fea895f`, remind the owner of the push and release commands in the "Hand-off format" section and continue locally.

### Task 1 — Owner phone report for 0.6.0 (do this whenever a report arrives)
The owner runs `docs/PHONE_TEST_0.6.0.md` and sends pass/fail per step, screenshots and possibly a bug-report ZIP from Developer options.

1. Read the bug report's logcat and filter for `dev.srimi.antigravitymobile`.
2. For each failure, write a regression test (JVM or emulator) that fails first, then fix it.
3. Ship fixes as **0.6.x patch releases**: bump versionCode and versionName, keep the worker at code 2, and use the same signing and release procedure as Task 4.
4. Never claim a phone result the owner did not report.

### Task 2 — Linux base packages need `python3` (blocks every CLI on the phone)
1. In `tools/linux-runtime/agm-linux.sh`, add `python3` to the Debian base-package install. Keep the bundled copy byte-identical; a unit test enforces this.
2. Run the shell test suite (21 tests, run 3 times).
3. Then run on emulator-5554 through the app's real RUN_COMMAND path (`am instrument`, not `adb run-as`). Use `TermuxBridgeDeviceTest` and `CliRealTermuxDeviceTest`, and confirm the bridge starts **without** a manually installed python3.
4. Record the result in `docs/lanes/lane-b.md` under "Gate 0.7.0 — taken over by Lane A" and add a short note to `docs/lanes/REQUESTS.md`.

### Task 3 — Gate 0.7.0 (code 13): close Lane B Phase B2
1. Compare B2 items 1–8 in the Lane B goal with the code and with `docs/lanes/lane-b.md`. Only finish items that are actually missing; do not rebuild existing work.
2. Provider claims without the owner's keys stay **unverified**. Free mode must admit only verified zero-cost routes. Paid access needs the user to select it explicitly. Never add automatic paid fallback.
3. Update `docs/provider-evidence.md`, `docs/PROJECT_CHECKPOINT.md` and `docs/compatibility-report.md`.
4. Write `docs/PHONE_TEST_0.7.0.md`, an owner checklist that needs no ADB, in the same style as 0.6.0.
5. Build and release 0.7.0 as in Task 4.

### Task 4 — Release procedure (used for 0.6.x, 0.7.0 and 0.8.0)
1. Signing key: `<main checkout>/.signing/personal.p12`. The worktree has none.
   - Create a temporary symlink `ln -s "<main checkout>/.signing" .signing` in the worktree.
   - Stage files by name only; never `git add -A` while the link exists.
   - Delete the link after the build.
   - Never print, copy or commit the key.
   - **Never run `tools/build.sh` in a checkout without the key: it generates a new key, and a new key cannot update the owner's app.**
2. Build with:
   ```
   JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew --project-cache-dir ~/.cache/agm-lane-a -PagmBuildRoot=$HOME/.cache/agm-lane-a-build :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
   ```
3. Verify with `apksigner verify --print-certs`: the signer must equal `791980ed…10b5`. Check versionCode/versionName with `aapt2 dump badging`, and check the bundled `assets/build-worker.apk` code and signer.
4. Upgrade test on emulator-5554:
   1. Uninstall the debug builds (emulator only; never on the phone).
   2. Download and install the **previous published** release APK with `gh release download`.
   3. Create a project, then run `adb install -r` with the new APK.
   4. Confirm the data is kept, all five tabs open and `adb logcat -b crash` is empty.
5. Put the APK, `SHA256SUMS` and `RELEASE_NOTES.md` in `~/dev/agm-release-<version>/`.
6. Commit on `lane-a/runtime`. Check `.git` for dataless files, then run `git merge --ff-only lane-a/runtime` into local `main`.
7. Hand the owner the push and release commands (see "Hand-off format").

### Task 5 — Gate 0.8.0 (code 14): CLI backends
Start only after 0.7.0 is built.

1. Re-run the full JVM suite, release lint, the Python bridge tests and these emulator classes: CliRuntime, NativeRuntime, CliRealTermux, TermuxBridge, RuntimeStore, FullApp, AgentBackendSelector.
2. The capability gate stays exactly as it is: a backend opens only after the probe on that device passes (aarch64, the CLI runs, and the Codex sandbox refuses writes outside the workspace). Do **not** weaken it to make it pass on the emulator. Codex currently fails the sandbox check under proot (exit 182). The Antigravity CLI has no headless sandbox probe and stays closed.
3. Add an owner-runnable **"Run CLI checks"** action, if it doesn't already exist in `LocalCliPanel`. It runs the same probe on the phone and shows: architecture, CLI versions, the sandbox result and whether each backend opened. That way the owner can report phone results from screenshots.
4. Write `docs/PHONE_TEST_0.8.0.md`:
   - The owner signs in to `codex` and `agy` **themself** inside Termux.
   - They run the CLI checks.
   - They run one small task per enabled backend.
   - They send screenshots, or the exported diagnostics without credentials.
5. Release 0.8.0 as in Task 4.

### Task 6 — Final acceptance (owner reports, you verify)
Use the owner's reports from the PHONE_TEST files. The game task (approve once → exactly one build → install → launch → play → follow-up questions answered from the recorded result) is accepted **only** when the owner reports it passing on the phone.

## Rules

- `AGENTS.md` applies in full:
  - No root, token lifting, proxying, provider impersonation or automatic paid fallback.
  - Never invent results.
  - Credentials never appear in logs, Room, exports, notes or chat.
  - Never uninstall the owner's app or Termux.
  - Application ID stays `dev.srimi.antigravitymobile.probe`.
- Every claim in the notes needs a test you actually ran, with its real output. Anything not proven stays **unverified**.
- TDD for every fix: the regression test fails first, then passes.
- Git:
  - Never force-push or rewrite history.
  - Never discard uncommitted work.
  - Commit messages end with a `Co-Authored-By` line.
  - Do not edit or reset the `~/dev/agm-lane-b` worktree. Do Lane B file changes on `lane-a/runtime`.
- Stop and ask the owner when any of these happens:
  - deleting anything that isn't your own temporary file
  - something that would risk the owner's data
  - the same failure after 2–3 attempts
  - a provider or CLI that would need the owner's credentials

## Hand-off format (end of every gate)

Report:

- what changed
- the tests with real output
- what is still unverified on the phone
- the exact local and GitHub commit IDs
- the APK SHA-256 and signer
- the exact owner commands, filled in. For example:

```
cd "/Users/srimi/Library/Mobile Documents/com~apple~CloudDocs/Antigravity--Mobile" && git push origin main
gh release create v<version> ~/dev/agm-release-<version>/antigravity-mobile-<version>.apk ~/dev/agm-release-<version>/SHA256SUMS --repo Srimi1/antigravity--mobile --target <commit> --title "Antigravity Mobile <version>" --notes-file ~/dev/agm-release-<version>/RELEASE_NOTES.md --latest
```
