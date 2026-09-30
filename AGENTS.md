# Antigravity Mobile — agent handoff

## Read first

1. `docs/PROJECT_CHECKPOINT.md` — saved progress, published artifacts, constraints and pending work.
2. `docs/compatibility-report.md` — what actually passed, what remains untested and known limitations.
3. `docs/provider-evidence.md` — supported subscription routes and missing evidence.
4. `README.md` — setup and build instructions.

These files describe the state saved on 30 September 2026. Verify the current checkout and newer changes before relying on version numbers, provider availability or test counts. Update the checkpoint when completing substantive work so the next agent can continue.

## User's current objective

Build a **full native Android app** with Projects, Agent chat, Changes, Build and Accounts screens. The latest user-reported target is OnePlus 7 Pro, 12 GB RAM, 256 GB storage (earlier records said 7T Pro); verify the physical model and Android version. Current source is **0.4.0** with a static-website workflow (create, edit, approve one-use copy, preview in the worker UID, console, export ZIP). On 1 October 2026 it passed `./tools/build.sh` (56 JVM tests, release lint, signed release), worker release lint, **20/20 device tests on an API 36 emulator** and website device tests on API 31 (WebView 91), plus manual emulator QA; see `docs/website-qa-2026-10-01.md`. Known gap: WebRTC is only JavaScript-guarded (bypassable via `srcdoc` on old WebView). Read [CONTINUE_WITH_ANY_AI.md](CONTINUE_WITH_ANY_AI.md) for the portable handoff. Earlier real-toolchain/emulator results are historical. Physical-phone/live-account acceptance and the earlier first-upgrade ANR remain unresolved.

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
| `ProjectsViewModel.kt`, `ProjectRepository.kt` | Project records, create/template/clone/SAF import/ZIP export/delete, file browser and editor, Git panel. `Archives` guards traversal and symlinks. `BuildInspector` reports source metadata; execution compatibility requires a real build. |
| `GitService.kt` | JGit 5.13.5: init, clone, status, commit (all or paths), log, pull, push, unified diffs. `GitRuntime` isolates config and disables auto-gc (`java.lang.management` is missing on Android). Init/commit/status/log passed on ART; device networking/pull/push remain unverified. |
| `ChangeService.kt` | Durable change sets with before/after snapshots on disk, conflict-aware revert, accept, commit marking, interrupted-set recovery. |
| `AgentLoop.kt`, `AgentViewModel.kt` | Provider-neutral tool loop, project-bounded tools, approvals with diff previews, persistent conversations/actions, Stop. No shell or build tool is offered to the model. |
| `ChatGptProbeAdapter.kt`, `ResponsesStream.kt` | Documented Sign in with ChatGPT OAuth plus Responses API streaming with function tools (`store:false`). No live account validation yet. |
| `Providers.kt` | Account states; Claude and Google are BLOCKED with documented reasons. |
| `ReviewViewModels.kt` | Changes, Accounts and Build view models. |
| `ProbeViewModel.kt`, `NativeExecutionService.kt` | Device diagnostics on the Build tab. Still runs only `version`, `exit-7` and `wait` on the packaged childless probe; not a shell. |
| `CredentialStore.kt`, `OidcVerifier.kt` | Keystore-backed encrypted storage (ChatGPT and Git token records) and signed ID-token validation. Preserve the security boundaries. |
| `SessionStore.kt` | Room schema **version 3** with `MIGRATION_1_2` and `MIGRATION_2_3` (build history); both paths have device checks. Future table changes need a v4 migration. |
| `WorkspaceService.kt` | Relative-path bounds, reads/writes, listing, symlink-safe delete, legacy checkpoint API. |
| `Contracts.kt` | `AgentItem`, `AgentModel`, `ProviderEvent` and the older probe contracts. |

Build execution paths:

- `BuildSnapshot.kt`: bounded immutable source ZIP, metadata/cache/output exclusions, link refusal and source consistency check.
- `BuildCoordinator.kt`: one-shot durable approval claims, observation, cancellation and artifact transfer; no uncertain command replay.
- `BuildWorkerClient.kt`: signature and exact UID checks; copied Messenger replies and reconnection after worker death.
- `build-worker/`: same-signer companion with a different Android UID; Gradle runs in a foreground service with per-build caches. Main account storage is not passed or mounted.
- `runtime-contract/`: shared request constants and ID/task validation. Main contains its matching companion APK for Android's installer.

Other important paths:

- `app/src/main/cpp/execution_probe.c`: genuine Android/Bionic test executable, not a compiler.
- `samples/HelloPhone/`: complete Compose template; integrated Android-native emulator build/install/launch passed for 0.3.0. Physical phone remains untested.
- `samples/HelloWeb/`, `WebsiteService.kt`, `WebsitePanel.kt`, shared `WebFiles`/`WebGuard`, worker `WebPreviewStore`/`WebPreviewActivity`: 0.4.0 static-website workflow (Projects Website template/tab, editor preview, one-use approval, preview, console, export). Emulator-validated only; static sites only; WebRTC not fully denied. Current client requires matching worker code 2.
- `app/src/test/`: 56 JVM tests pass for 0.4.0. 20 instrumentation tests passed for 0.4.0 on the API 36 emulator (16 from 0.3.0 plus 4 website tests); the 0.3.0 run on Android 12 is in `docs/build-worker-qa-2026-09-30.md`.
- `tools/android-runtime-lab/`: separate credential-free Android-native build validation. Five device tests passed and a Java Android APK built/installed/launched on the emulator; a later full Compose build/install/launch and Count interaction passed. Not integrated into the main app. Read `docs/native-runtime-qa-2026-09-30.md` before continuing.
- `tools/jvm-harness/`: compile/test fallback for sandboxes without Google Maven. Not a substitute for the real build.
- `assets/branding/`: finished original app icon, repository cover and generation prompts.
- `assets/screenshots/`: actual Android 12 ARM64 captures of 0.1.2 and 0.2.0 QA evidence in `qa-20260930/`.
- `release/`: published 0.1.2 metadata, notes and APK checksum; binary copies are ignored by Git. Do not edit it for unreleased 0.3.0/0.4.0 work.

## Where to continue

1. **Validate the embedded companion update (code 1→2) and signed in-place upgrade, and diagnose the first-upgrade ANR.** The earlier 0.2.0 build, lint, 46 tests and in-place migration passed; current worker/migration checks are in the integration QA report. Start tracing before launch and compare a lone emulator against controlled host contention; the observed 10,410 ms focus timeout remains unresolved. See the QA report. Preserve phone data before physical validation.
2. **Live ChatGPT agent task.** With the user's consent, sign in, run "Test request", then a small agent edit on a scratch project. Record outcome only (no tokens or prompts) in `docs/provider-evidence.md`.
3. **Durability gaps.** Agent tasks run in the ViewModel; a background kill ends them (recorded as interrupted). A foreground service would keep long tasks alive. In-flight tool history is not persisted between app restarts; only user/assistant text is replayed.
4. **Build coverage.** The experimental ARM64 Bionic Java 17/Gradle 8.13/SDK 36 profile is now integrated into an approved foreground companion. Check integration QA before claiming compatibility. Physical phone, wider repositories/languages, websites, native health/MTE/security maintenance and complete redistribution notices remain open. Ordinary Linux ARM64 or desktop SDK binaries are not Android executables.
5. **Claude and Google.** Remain BLOCKED until a supported, approved subscription route exists. Do not add API-key fallbacks or token lifting.

JGit is integrated; init/commit/status/log passed on ART, while device clone/pull/push remain unverified. Broader Android-host tool distribution remains unresolved. Do not add a Termux launcher or desktop-remote wrapper as a substitute for the requested native app.

The complete product remains unaccepted until all mandatory subscriptions perform real coding tasks and the phone alone can edit/review/test/commit an existing repository and generate/build/install/launch a Compose app, with cancellation and recoverable failure states.

## Build, validation and persistence

- Prepare pinned inputs with `tools/android-runtime-lab/prepare.py` first (README), then use `./tools/build.sh` for JVM tests, release lint and a signed release build. Java 17, Android SDK 36 and NDK 27.2.12479018 are required; pinned Gradle/Kotlin/AGP versions are in the build files.
- Install the matching debug companion (`:build-worker:installDebug`) before worker checks. Device checks: `./gradlew --project-cache-dir "$HOME/.cache/antigravity-mobile-gradle" :app:connectedDebugAndroidTest` with an appropriate emulator/phone connected.
- Instrumentation uses a debug signer. A personally signed release on the same test device can cause `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; use a separate test emulator or preserve the device's data before changing installations. Never uninstall the user's phone app just to make tests pass.
- Keep generated output outside iCloud. Root Gradle configuration uses `~/.cache/antigravity-mobile-build`; generate native libraries/assets using `layout.buildDirectory`, not hard-coded old `app/build` paths.
- Source archive: `python3 tools/package_source.py`. It excludes build caches, APKs/ZIPs and private signing keys. Published v0.1.2 archives are immutable snapshots; new source work needs its own later release.
- Application ID is `dev.srimi.antigravitymobile.probe`; changing it breaks the update path. Version code 3 (0.1.2) is published; source is version code 7 (0.4.1), emulator-validated; distributed only as a private GitHub draft release for the user's phone test. Worker source code 2 is required by the client and passed emulator tests (debug-signed, installed by ADB); its Build-tab update path from code 1 passed on emulators (docs/upgrade-qa-2026-10-01.md). Synchronize required worker version and preserve matching signers. Increment version code/name and synchronize build packaging and release metadata for a new APK.
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
