# Goal — Lane A (continuation): finish runtime reliability, CLI bridge, and phone acceptance

**Assigned model:** Opus 5.5
**Replaces:** the unfinished part of `goal/LANE_A_GPT-6.1_runtime.md`. Read that file once for the original scope, then follow this one.
**Partner:** Lane B (`goal/LANE_B_OPUS-5.5_providers-linux.md`) owns providers, credentials, Accounts, network diagnostics and Linux/Termux setup. Do not edit those files; use `docs/lanes/REQUESTS.md` (five lines or fewer per request).

## Where things stand (audited 2 Oct 2026)

Workspace: `~/dev/agm-lane-a`, branch `lane-a/runtime`. Latest commit `d29c2084ea185282c3673759175da93ad4af6f12`. Local `main` = `7e98d43`. GitHub `origin/main` = `fea895f` (nothing pushed: the owner's git hook blocks agent pushes, so the owner pushes).

**There is uncommitted work in the worktree. It is yours to finish. Never discard it.** Before anything else, back up the branch and the working tree outside iCloud, e.g. `git bundle create ~/dev/agm-lane-a-backup.bundle fea895f..lane-a/runtime` plus a copy of the working tree. Uncommitted files: `AgentScreen.kt`, `AgentViewModel.kt`, `AntigravityApp.kt`, `MainActivity.kt`, `SessionStore.kt`, `bridge/CliProtocol.kt`, `bridge/CliWorkspaceStore.kt`, `runtime/NativeAgentTaskRunner.kt`, `resources/.../bridge/agm_bridge.py`, `docs/lanes/lane-a.md`; new: `bridge/CliAgentTaskRunner.kt`, `bridge/CliEventStore.kt`, `runtime/AgentTaskRouter.kt`, `runtime/TaskRunnerLifecycle.kt`, `androidTest/.../CliRuntimeDeviceTest.kt`, `test/.../bridge/CliEventStoreTest.kt`, `test/python/bridge/test_bootstrap.py`.

### Verified complete (re-run independently, not just read from notes)

- Day 0: `-PagmBuildRoot` build isolation; `runtime/RuntimeContracts.kt` with keyed `ApprovalDecision` (Approved/Declined/Cancelled/Interrupted bound to `ApprovalKey(taskId, actionId, toolCallId, buildId?)`), `ToolOutcome` (Success/Failed/Cancelled/Interrupted/RuntimeUnavailable) and `AgentTaskRunner`. `AgentModel` is byte-identical to baseline.
- Room v4: `MIGRATION_3_4` with the exact `provider_models` / `provider_usage` schema; `MIGRATION_1_2` and `MIGRATION_2_3` kept; `RoomProviderUsageStore` wired into `ProviderStores.usage`.
- A1 code: persisted keyed approvals with atomic claims, edit-only "approve all", separate build/install prompts, foreground `AgentTaskService`, Paused card showing the reason, Lane B's recovery action and **Retry this provider**, recorded build outcomes/logs/APK hashes, Agent-owned builds rejected from the Build tab, manual Build dialog Cancel/Back recorded as CANCELLED.
- Lane B integration: `ACCESS_NETWORK_STATE`, `com.termux.permission.RUN_COMMAND`, `<queries>` for `com.termux`/`com.termux.x11`, `LinuxSetupPanel()` in BuildScreen, `ProviderId.OPENAI_COMPAT` → `CompatProviders` routing.
- Tests at `d29c208`, re-run: **143 JVM tests, 0 failures (31 classes); release lint passed; debug APK and androidTest compile; Python bridge tests 19 OK; Linux helper shell tests 21/21.**
- Uncommitted work, re-run in a copy: **145 JVM tests, 0 failures; release lint passed; builds compile.**
- Emulator results reported in `docs/lanes/lane-a.md` (not re-run during the audit): runtime store/migration/lifecycle 12 + 1 + recovery, real worker builds (`BuildWorkerDeviceTest` 4, `AgentBuildDeviceTest` 1 incl. a real Compose APK and a real compile failure), CLI/native runtime fixtures 10. Re-run the ones you depend on.

### Not complete

**Phase A1 / Gate 0.6.0 (code 12):**
1. Physical phone trace of the owner's "approved but reported as declined" build (OnePlus 7 Pro; take the phone lock). Device-check the 1→2→3→4 upgrade path on the phone with the owner's real data preserved.
2. Gate: bump to 0.6.0 / versionCode 12, keep worker version sync (worker code 2) and the original signer, fold notes into `docs/PROJECT_CHECKPOINT.md` and `docs/compatibility-report.md`. Ask the owner before signing or publishing; the owner pushes.

**Phase A2 / Gate 0.8.0 (code 14):**
3. Commit the uncommitted runner/router/event-journal/import/UI work after it passes review.
4. Fix the four open review findings, each with a regression test that fails first:
   - release the single-task slot only when the helper reports a terminal state **and** `cancellationUnconfirmed=false`;
   - monitor helper liveness while waiting for a CLI approval;
   - safely rebuild an incomplete pre-dispatch snapshot after process death;
   - drain trailing events after a terminal CLI result so a later permission-denial diagnostic can never become success.
5. Fix `test_bootstrap.py`: it fails **7 of 10 runs** (`ChildProcessError` in cleanup because the reaper thread already reaped the child). Make it deterministic and run it 20 times.
6. Production pairing: a Kotlin launcher that installs/starts `agm_bridge.py` inside `agm-debian` through Lane B's `TermuxGateway`/`TermuxLinuxRuntime` (helper path `/data/data/com.termux/files/home/.agm/`), stdin-only pairing secret, then replace `DisabledCliBridge` behind an explicit capability gate. Reject unpaired clients; treat every event as untrusted input.
7. Narrowly scoped MCP tools for the app's build/install operations that keep the native approval prompts. Headless approvals that cannot be answered open the terminal; permission denial is `RuntimeUnavailable`, never a user decline.
8. Codex: documented app-server protocol (chat, sessions, approvals, cancellation). Antigravity CLI: documented headless stream. Re-check both docs first (<https://learn.chatgpt.com/docs/app-server>, <https://www.antigravity.google/docs/cli/headless/>). Lane B verified on an emulator that `codex-cli 0.159.3` (`/opt/agm/node/bin/codex`) and `agy 1.2.14` (`/root/.local/bin/agy`) install and run on ARM64 Debian 12; no sign-in or inference has been done.
9. Enable each CLI capability only after ARM64 execution **and** sandbox checks pass on the phone.
10. Backend selector in AgentScreen (in the uncommitted work): verify it and finish it.
11. Runtime tests against real Termux, not only fixtures: denied permission, disconnected bridge, malformed events, CLI cancellation, conflicting imports.
12. Gate 0.8.0 depends on Lane B's Gate 0.7.0 (code 13), which is **not done**. If Lane B is not active, ask the owner whether you should take over Gate 0.7.0 before touching Lane B files.

**Final physical acceptance (shared, take the phone lock):**
13. Reproduce the owner's game-development task on the OnePlus 7 Pro: approve its build once → exactly one build, build the debug APK on the phone, install, launch, verify gameplay; follow-up questions must answer from the recorded build result and log. Verify real subscription inference through the official CLIs with the owner's accounts. Anything not proven stays marked **unverified**.

## Rules

- `AGENTS.md` boundaries apply in full: no root, no token lifting or proxying, no automatic paid fallback, no invented results; credentials never in logs, Room, exports or diagnostics; never print or commit `.signing/personal.p12`; never uninstall the owner's phone app or an existing Termux.
- File ownership from the original Lane A goal still applies. Your emulator is `emulator-5554` only. Phone: create `~/.cache/agm-phone.lock` (lane name + time) before use, delete it after.
- Git: rebase onto `main` before merging, fast-forward only, never force-push. Only the gate owner bumps versions. Every claim in notes needs a test you actually ran, with its real output.
- Report at each gate: what changed, tests with real output, what is still unverified on the phone, exact local and GitHub commit IDs, next step.

## Environment notes (from 1–2 Oct 2026)

- Toolchain: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`, `ANDROID_HOME=$HOME/Library/Android/sdk` (platform 36, build-tools 36.0.0, NDK 27.2). Gradle: `./gradlew --project-cache-dir ~/.cache/agm-lane-a -PagmBuildRoot=$HOME/.cache/agm-lane-a-build …`. If the Gradle wrapper download times out under Java, place the zip with curl into `~/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/`; its SHA-256 must match `gradle-wrapper.properties`.
- **Disk:** on 1 Oct the disk hit 96–98% full and macOS evicted files of the iCloud checkout, including objects in the shared `.git` (all worktrees use it); git operations then timed out. Check `df -h ~` before big downloads or emulator work, keep ≥15 GB free, and keep a `git bundle` of your branch outside iCloud. Fast-forwarding `main` writes into the iCloud checkout, so check `find "<repo>/.git" -flags +dataless | wc -l` is 0 first.
- `git push` is blocked for agents by the owner's hook; hand the owner the exact push command.
- Termux testing on an emulator: official Termux GitHub release APK (verify its `sha256sums` file), enable `allow-external-apps`, grant RUN_COMMAND with `adb shell pm grant`, then use `am instrument` (Gradle's connected test uninstalls the app and loses the grant). `adb run-as com.termux` runs in a different SELinux domain (no DNS, different socket view), so it is not representative of RUN_COMMAND behaviour.
- Lane B's XFCE desktop does not render yet (black screen); do not depend on it.
