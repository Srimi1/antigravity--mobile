# Antigravity Mobile — agent handoff

## Read first

1. `docs/PROJECT_CHECKPOINT.md` — saved progress, published artifacts, constraints and pending work.
2. `docs/compatibility-report.md` — what actually passed, what remains untested and known limitations.
3. `docs/provider-evidence.md` — supported subscription routes and missing evidence.
4. `README.md` — setup and build instructions.

These files describe the state saved on 30 September 2026. Verify the current checkout and newer changes before relying on version numbers, provider availability or test counts. Update the checkpoint when completing substantive work so the next agent can continue.

## User's current objective

Build a **full native Android app** for the user's OnePlus 7T Pro, with Projects, Agent chat, Changes, Build and Accounts screens. The user explicitly asked to move beyond the diagnostic prototype. That implementation has **not started**: the user then requested that all progress be saved for other agents.

Continue useful implementation toward the full app; do not spend another turn merely presenting the old plan or rebuilding the same diagnostic-only deliverable. Keep unresolved capabilities clearly marked. The request for a full app does not make the mandatory dependencies available.

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

| File | Existing behavior / limitation |
| --- | --- |
| `MainActivity.kt` | Diagnostic Compose screen, action approval, SAF report/APK selection and installer launch; not five-screen navigation. |
| `ProbeViewModel.kt` | Runs diagnostic checks and generates the bundled sample; no full agent orchestrator. |
| `WorkspaceService.kt` | Relative-path bounds, file reads/writes, checkpoints, diffs and conflict-aware rollback. Checkpoint review metadata is in memory; durable recovery is unfinished. |
| `NativeExecutionService.kt` | Runs only `version`, `exit-7` and `wait` on the packaged childless native probe. This is not arbitrary shell execution or process-tree cancellation. |
| `ChatGptProbeAdapter.kt` | Experimental documented OAuth, SSE text streaming, renewal and logout. No live account validation or provider tool-call agent loop. |
| `CredentialStore.kt`, `OidcVerifier.kt` | Keystore-backed encrypted storage and signed ID-token validation. Preserve the security boundaries. |
| `SessionStore.kt` | Room schema version 1 for diagnostic check records only. New conversation/task/action tables require a migration preserving existing records. |
| `Contracts.kt` | Provider/execution interfaces and a readiness-only BuildService contract; several full-app capabilities are not implemented. |

Other important paths:

- `app/src/main/cpp/execution_probe.c`: genuine Android/Bionic test executable, not a compiler.
- `samples/HelloPhone/`: complete Compose sample; host compilation passed, phone compilation did not.
- `app/src/test/` and `app/src/androidTest/`: 11 JVM and 8 emulator checks passed for 0.1.2.
- `assets/branding/`: finished original app icon, repository cover and generation prompts.
- `assets/screenshots/`: actual Android 12 ARM64 emulator captures.
- `release/`: published-version metadata, notes and APK checksum; binary copies are ignored by Git.

## Where to continue

1. Implement Projects with app-private project records, reopen/create/import/export, file browsing and editing, and real Git operations. Preserve user content and validate any selected Git implementation on Android.
2. Implement Agent chat with persistent conversations, streaming, provider status/selection, approvals and stop/recovery. Extend provider contracts for actual tool requests and build the common execution loop; never substitute mock provider responses in the shipped app.
3. Implement Changes with reviewable diffs, accept/revert and local commits. Persist change checkpoints and refusal of rollback that would overwrite a later unrelated edit.
4. Implement Build with real test/build logs, cancellation and APK installation. Resolve the Android-host toolchain dependency rather than assuming ordinary Linux ARM64 binaries or the desktop Android SDK run on Android.
5. Implement Accounts with clear verified, expired, disconnected and blocked states; establish and test applicable supported subscription integrations.

This is a continuation outline, not an assertion that these features already exist. JGit and Android-host tool distribution were only investigated; no dependency or runtime choice has been implemented or validated. Do not add a Termux launcher or a desktop-remote wrapper as a substitute for the requested native app.

The complete product remains unaccepted until all mandatory subscriptions perform real coding tasks and the phone alone can edit/review/test/commit an existing repository and generate/build/install/launch a Compose app, with cancellation and recoverable failure states.

## Build, validation and persistence

- Use `./tools/build.sh` for JVM tests, release lint and a signed release build. Java 17, Android SDK 36 and NDK 27.2.12479018 are required; pinned Gradle/Kotlin/AGP versions are in the build files.
- Device checks: `./gradlew --project-cache-dir "$HOME/.cache/antigravity-mobile-gradle" :app:connectedDebugAndroidTest` with an appropriate emulator/phone connected.
- Instrumentation uses a debug signer. A personally signed release on the same test device can cause `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; use a separate test emulator or preserve the device's data before changing installations. Never uninstall the user's phone app just to make tests pass.
- Keep generated output outside iCloud. Root Gradle configuration uses `~/.cache/antigravity-mobile-build`; generate native libraries/assets using `layout.buildDirectory`, not hard-coded old `app/build` paths.
- Source archive: `python3 tools/package_source.py`. It excludes build caches, APKs/ZIPs and private signing keys. Published v0.1.2 archives are immutable snapshots; new source work needs its own later release.
- Application ID is `dev.srimi.antigravitymobile.probe`; changing it breaks the update path. Version code 3 is published. Increment version code/name and synchronize build packaging and release metadata for a new APK.
- Preserve `.signing/personal.p12` locally. Do not commit, upload or print the key. A checkout on another computer does not contain the original signer; do not claim a newly generated key can update the published APK.
- Preserve the completed artwork and existing release. Do not recreate the icon, re-upload unchanged APKs or repeat completed Drive replacement work.
- Consult the checkpoint for the current GitHub release and Drive URL. Verify a replacement before deleting an old file, and use the human user's authorization for publishing or deletion.
- Current working tree was clean before this handoff addition. Check `git status` before edits and preserve changes made by the user or other agents.

## Handoff discipline

Keep `docs/PROJECT_CHECKPOINT.md` current with what changed, what was tested, what is still blocked, exact artifacts and the next actionable step. Distinguish implementation from live acceptance. Do not mark the full app complete just because the UI or a signed APK exists.
