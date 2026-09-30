# Antigravity Mobile — agent handoff

## Read first

1. `docs/PROJECT_CHECKPOINT.md` — saved progress, published artifacts, constraints and pending work.
2. `docs/compatibility-report.md` — what actually passed, what remains untested and known limitations.
3. `docs/provider-evidence.md` — supported subscription routes and missing evidence.
4. `README.md` — setup and build instructions.

These files describe the state saved on 30 September 2026. Verify the current checkout and newer changes before relying on version numbers, provider availability or test counts. Update the checkpoint when completing substantive work so the next agent can continue.

## User's current objective

Build a **full native Android app** with Projects, Agent chat, Changes, Build and Accounts screens. The latest user-reported target is OnePlus 7 Pro, 12 GB RAM, 256 GB storage (earlier records said 7T Pro); verify the physical model and Android version. The 0.2.0 app is unreleased but now builds with AGP; 34 JVM and 12 Android 12 ARM64 instrumentation tests passed. Manual QA recorded an **unresolved first-upgrade ANR**, then recovery and three cold starts without recurrence. Physical-phone and live-account testing remain unverified. See the latest checkpoint and `docs/emulator-qa-2026-09-30.md`.

Continue from there. Keep unresolved capabilities clearly marked. The request for a full app does not make the mandatory dependencies available.

## Boundaries to preserve

- Personal sideloading, single user, one agent task at a time.
- Native Kotlin, Compose, coroutines and Room; repositories and execution live on the phone.
- Google, Claude and ChatGPT subscription support remain mandatory for full acceptance.
- No root, desktop runtime dependency, cloud builds or paid-API fallback.
- No internal-endpoint reverse engineering, unofficial token lifting/proxying or provider impersonation.
- A login screen, successful OAuth callback or unit test is not proof of real subscription inference.
- Do not invent working providers, fake responses, successful phone builds or physical-device evidence.
- Preserve existing user files and unrelated edits. Constrain file tools to the selected workspace, checkpoint before edits, require approval for commands and never replay uncertain actions after process death.
- Keep account credentials outside logs, repositories, conversation records and exports.

The initial APK may be built on the development machine. That does not satisfy the requirement that the eventual app build user projects locally on the phone.

## Existing code and its actual scope

All Kotlin implementation paths below are under `app/src/main/java/dev/srimi/antigravitymobile/`.

| File | Behavior / limitation |
| --- | --- |
| `AntigravityApp.kt` | Application + `AppContainer`: single Room instance, services, selected project, startup recovery (interrupt, never replay), Git author/token settings. |
| `MainActivity.kt` | Five-tab navigation. Screens: `ProjectsScreen.kt`, `AgentScreen.kt`, `ChangesScreen.kt`, `BuildScreen.kt`, `AccountsScreen.kt`; shared widgets in `UiParts.kt`. |
| `ProjectsViewModel.kt`, `ProjectRepository.kt` | Project records, create/template/clone/SAF import/ZIP export/delete, file browser and editor, Git panel. `Archives` guards traversal and symlinks. `BuildInspector` reports build readiness honestly (BLOCKED). |
| `GitService.kt` | JGit 5.13.5: init, clone, status, commit (all or paths), log, pull, push, unified diffs. `GitRuntime` isolates config and disables auto-gc (`java.lang.management` is missing on Android). Init/commit/status/log passed on ART; device networking/pull/push remain unverified. |
| `ChangeService.kt` | Durable change sets with before/after snapshots on disk, conflict-aware revert, accept, commit marking, interrupted-set recovery. |
| `AgentLoop.kt`, `AgentViewModel.kt` | Provider-neutral tool loop, project-bounded tools, approvals with diff previews, persistent conversations/actions, Stop. No shell or build tool is offered to the model. |
| `ChatGptProbeAdapter.kt`, `ResponsesStream.kt` | Documented Sign in with ChatGPT OAuth plus Responses API streaming with function tools (`store:false`). No live account validation yet. |
| `Providers.kt` | Account states; Claude and Google are BLOCKED with documented reasons. |
| `ReviewViewModels.kt` | Changes, Accounts and Build view models. |
| `ProbeViewModel.kt`, `NativeExecutionService.kt` | Device diagnostics on the Build tab. Still runs only `version`, `exit-7` and `wait` on the packaged childless probe; not a shell. |
| `CredentialStore.kt`, `OidcVerifier.kt` | Keystore-backed encrypted storage (ChatGPT and Git token records) and signed ID-token validation. Preserve the security boundaries. |
| `SessionStore.kt` | Room schema **version 2** with `MIGRATION_1_2`; real instrumentation migration test and in-place release upgrade passed. Any further table change needs a v3 migration. |
| `WorkspaceService.kt` | Relative-path bounds, reads/writes, listing, symlink-safe delete, legacy checkpoint API. |
| `Contracts.kt` | `AgentItem`, `AgentModel`, `ProviderEvent` and the older probe contracts. |

Other important paths:

- `app/src/main/cpp/execution_probe.c`: genuine Android/Bionic test executable, not a compiler.
- `samples/HelloPhone/`: complete Compose sample, used as the "Compose app" project template. Host compilation passed; phone compilation did not.
- `app/src/test/`: 34 JVM tests (passed in the harness and real AGP build). `app/src/androidTest/`: all 12 instrumentation tests passed on Android 12 ARM64, including `FullAppDeviceTest`.
- `tools/android-runtime-lab/`: separate credential-free Android-native build validation. Five device tests passed and a Java Android APK built/installed/launched on the emulator; a later full Compose build/install/launch and Count interaction passed. Not integrated into the main app. Read `docs/native-runtime-qa-2026-09-30.md` before continuing.
- `tools/jvm-harness/`: compile/test fallback for sandboxes without Google Maven. Not a substitute for the real build.
- `assets/branding/`: finished original app icon, repository cover and generation prompts.
- `assets/screenshots/`: actual Android 12 ARM64 captures of 0.1.2 and 0.2.0 QA evidence in `qa-20260930/`.
- `release/`: published 0.1.2 metadata, notes and APK checksum; binary copies are ignored by Git. Do not edit it for unreleased 0.2.0.

## Where to continue

1. **Diagnose the first-upgrade ANR.** Real build, lint, all 46 tests and in-place migration now passed. Start tracing before launch and compare a lone emulator against controlled host contention; the observed 10,410 ms focus timeout remains unresolved. See the QA report. Preserve phone data before physical validation.
2. **Live ChatGPT agent task.** With the user's consent, sign in, run "Test request", then a small agent edit on a scratch project. Record outcome only (no tokens or prompts) in `docs/provider-evidence.md`.
3. **Durability gaps.** Agent tasks run in the ViewModel; a background kill ends them (recorded as interrupted). A foreground service would keep long tasks alive. In-flight tool history is not persisted between app restarts; only user/assistant text is replayed.
4. **Build toolchain.** Continue `tools/android-runtime-lab/`: Java compilation, Gradle startup, APK signing and JVM-family cancellation passed on Android 12 ARM64. A later full Compose scratch build/install/launch passed; see `docs/native-compose-qa-2026-09-30.md`. Original reboot remains unexplained; do not replay uncertain tasks. Integrate approved, credential-isolated foreground execution next. Main-app integration, durable foreground execution, native-memory/MTE compatibility and physical validation remain unresolved. Do not assume ordinary Linux ARM64 binaries or the desktop SDK run on Android.
5. **Claude and Google.** Remain BLOCKED until a supported, approved subscription route exists. Do not add API-key fallbacks or token lifting.

JGit is integrated; init/commit/status/log passed on ART, while device clone/pull/push remain unverified. Android-host tool distribution remains unresolved. Do not add a Termux launcher or desktop-remote wrapper as a substitute for the requested native app.

The complete product remains unaccepted until all mandatory subscriptions perform real coding tasks and the phone alone can edit/review/test/commit an existing repository and generate/build/install/launch a Compose app, with cancellation and recoverable failure states.

## Build, validation and persistence

- Use `./tools/build.sh` for JVM tests, release lint and a signed release build. Java 17, Android SDK 36 and NDK 27.2.12479018 are required; pinned Gradle/Kotlin/AGP versions are in the build files.
- Device checks: `./gradlew --project-cache-dir "$HOME/.cache/antigravity-mobile-gradle" :app:connectedDebugAndroidTest` with an appropriate emulator/phone connected.
- Instrumentation uses a debug signer. A personally signed release on the same test device can cause `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; use a separate test emulator or preserve the device's data before changing installations. Never uninstall the user's phone app just to make tests pass.
- Keep generated output outside iCloud. Root Gradle configuration uses `~/.cache/antigravity-mobile-build`; generate native libraries/assets using `layout.buildDirectory`, not hard-coded old `app/build` paths.
- Source archive: `python3 tools/package_source.py`. It excludes build caches, APKs/ZIPs and private signing keys. Published v0.1.2 archives are immutable snapshots; new source work needs its own later release.
- Application ID is `dev.srimi.antigravitymobile.probe`; changing it breaks the update path. Version code 3 (0.1.2) is published; the source is at version code 4 (0.2.0), unreleased. Increment version code/name and synchronize build packaging and release metadata for a new APK.
- Preserve `.signing/personal.p12` locally. Do not commit, upload or print the key. A checkout on another computer does not contain the original signer; do not claim a newly generated key can update the published APK.
- Preserve the completed artwork and existing release. Do not recreate the icon, re-upload unchanged APKs or repeat completed Drive replacement work.
- Consult the checkpoint for the current GitHub release and Drive URL. Verify a replacement before deleting an old file, and use the human user's authorization for publishing or deletion.
- Check `git status` before edits and preserve changes made by the user or other agents.
- Cloud sandboxes may deny `dl.google.com` (Google Maven and SDK downloads). Use `tools/jvm-harness` there and say plainly that the real build did not run.

## Handoff discipline

Keep `docs/PROJECT_CHECKPOINT.md` current with what changed, what was tested, what is still blocked, exact artifacts and the next actionable step. Distinguish implementation from live acceptance. Do not mark the full app complete just because the UI or a signed APK exists.

## Repository synchronization

The user wants this Mac folder and `Srimi1/antigravity--mobile` on GitHub to reflect the same development progress. Before editing, check the working tree, fetch `origin`, and compare local history with the current remote branch. Reconcile newer remote work before continuing; preserve unrelated local edits and never force-push to resolve divergence.

When completing user-authorized repository synchronization, commit and push only the intended changes and verify the final local and GitHub commit IDs match with a clean working tree. Report any remaining divergence. Keep signing keys, credentials, local configuration and ignored build/release binaries private; source synchronization does not change the published APK or release archives.
