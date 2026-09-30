# Antigravity Mobile — saved checkpoint

Last updated **30 September 2026** (0.2.0 full-app source, unreleased).

**For the next agent:** start with the repository-root [AGENTS.md](../AGENTS.md). It maps the current implementation, explains the user's full-app request, and gives continuation, validation and signing guidance. This checkpoint records the completed work; AGENTS.md explains how to resume it.

## Repository and local folder synchronization — 30 September 2026

The user requested that the Mac folder and GitHub reflect the same development progress. The local `main` was clean at `61a638e52d48cae7f0e7366423ae3527188f1fb7`. GitHub `main` was two commits ahead, including the five-screen implementation and merged PR #1. The folder was fast-forwarded to the imported source baseline `cad5bf2cbc09da5f36472bcf8cf9e31c0f22e1ba` (54 changed files).

- All 102 tracked files matched GitHub `main`; there were no local-only commits or uncommitted changes after import. The remote branch `ccr-ab1e41fd-3zdy6l` was fully included in `main`, and there were no open pull requests.
- All 2,768 existing ignored files retained their file metadata, including the original signing key, local configuration, build outputs and release copies. Incoming paths did not overlap them. These intentionally local files remain excluded from GitHub.
- A local recovery ref, `refs/checkpoints/before-sync-20260930T100057Z`, preserves the pre-sync commit. The preservation inventory is local Git metadata under `.git/sync-checkpoints/` and is not published.
- Validation here covered repository history, tracked-file equality and local-file preservation. No app build or tests were rerun; the 0.2.0 validation limits below remain unchanged. The published APK and source archive remain the immutable 0.1.2 release.

Before future work, fetch GitHub and reconcile its progress with this folder. Complete user-authorized synchronization by committing and pushing the intended changes, then verify a clean working tree and matching local/remote commits. Preserve unrelated work and report any unresolved divergence.

## 0.2.0 full-app source (unreleased, not yet built as an APK)

The user asked to make the app "fully developed". The implementation from branch `ccr-ab1e41fd-3zdy6l` was merged into `main` by PR #1 and replaces the single diagnostic screen with the five-screen app. Version code **4**, version name **0.2.0**, same application ID.

### What was implemented

- **Navigation:** `MainActivity` with Projects, Agent, Changes, Build and Accounts tabs; edge-to-edge with keyboard insets. `AntigravityApp`/`AppContainer` own one Room instance and all services.
- **Room v2:** `SessionStore` adds `projects`, `conversations`, `messages`, `actions`, `change_sets`, `change_files`. `MIGRATION_1_2` only creates tables; v1 `checks` rows are kept. Startup recovery marks running checks, conversations and actions `INTERRUPTED` and moves `OPEN` change sets to `REVIEW`. Nothing is replayed.
- **Projects:** app-private records under `files/projects/`; create (with `git init`), Compose template from the bundled `hello-phone.zip`, HTTPS clone, SAF folder import (copy, 512 MB/50k file caps), ZIP export with or without `.git`, rename, delete (app copy only). File browser, text editor (400 KB limit, refuses to save over a file changed since opening), new file/folder, delete.
- **Git (JGit 5.13.5):** init, clone, status, commit all or selected paths, log, pull, push with an optional HTTPS token stored in Keystore-encrypted `git.credentials`. A custom `SystemReader` keeps JGit off host config and `git` binaries and sets `gc.auto = 0`, because JGit's gc pid lock uses `java.lang.management`, which Android lacks.
- **Agent:** provider-neutral `AgentItem` contract, `AgentOrchestrator` tool loop (30 steps max, no automatic retries), `WorkspaceTools` (`list_files`, `read_file`, `search_text`, `write_file`, `delete_file`, `git_status`) bounded to the project and refusing any `.git` path segment. Writes and deletes need approval (per action or "approve all edits in this task"), with a diff preview. Persistent conversations, streaming text, durable action log, Stop.
- **ChatGPT adapter:** now implements `AgentModel` using documented Responses API function tools with `store:false`, replaying items without server IDs and requesting `reasoning.encrypted_content` (retried once without it on HTTP 400). Model catalog cached 10 minutes; model choice in Accounts. `accountState()` reports VERIFIED only after a streamed response completed on this install.
- **Changes:** `ChangeService` keeps before/after byte snapshots under `files/changesets/<id>/`. Keep, Revert (refused if any file changed after the agent's last write) and commit of accepted sets' files only.
- **Build:** on-phone build shown as BLOCKED with the reason; project inspection (Gradle, wrapper, Android module, APKs found); install an APK from the project or storage; the old device diagnostics and report export.
- **Accounts:** ChatGPT connect/test/renew/models/disconnect; Claude and Google shown as BLOCKED with documented reasons (`ProviderPolicy`); Git commit author and HTTPS token.

### What was tested (and how)

This cloud container could not reach Google Maven (`dl.google.com` is denied by the environment's network policy), so AGP, AndroidX, Room kapt and the NDK were unavailable. Validation used `tools/jvm-harness/`: all main sources, including the Compose UI, compiled as Kotlin/JVM against an API 36 `android.jar`, JetBrains Compose 1.8.0 and AndroidX signature stubs.

- **34 JVM tests passed** in the harness (11 existing + 23 new): change ledger, JGit init/commit/partial commit/log/config, unified diff, archive import/export/traversal/symlinks, build inspection, Responses stream parsing and wire format, and the tool loop with approvals, declines, errors and `.git` refusal (scripted model in tests only).
- A one-off JGit HTTPS clone of `octocat/Hello-World` plus clone cancellation worked on the JVM (not committed as a test because it needs the network).
- The 4 new instrumentation tests (`FullAppDeviceTest`: real v1→v2 migration, JGit on ART, ledger through Room, template extraction) and the 8 existing ones **compile but have not run**.

**Not done:** no Gradle/AGP build, no kapt/Room schema validation, no lint, no APK, no emulator or phone run, no live ChatGPT request. The migration SQL was written to match the entities by hand; `migrationFromVersion1KeepsCheckHistory` is the test that proves it.

### Next actionable step

On the Mac with the SDK: `./tools/build.sh`, then `./gradlew --project-cache-dir "$HOME/.cache/antigravity-mobile-gradle" :app:connectedDebugAndroidTest` on the emulator. Fix anything the real toolchain finds (most likely kapt/Room schema details, lint, or packaging of JGit resources). Then install over 0.1.2 on the emulator to prove the in-place migration, and only then on the phone. After that, a live ChatGPT agent task is the first real provider evidence.

The remaining product blockers are unchanged: Claude and Google subscription routes, and an Android-host build toolchain.

---

## 0.1.2 record

### Current deliverable (0.1.2)

The latest shipped version is **0.1.2-probe**, Android version code **3**, for Android 10+ and ARM64. It is a personally signed validation prototype, not the complete three-provider development app.

- Repository: https://github.com/Srimi1/antigravity--mobile
- Release: https://github.com/Srimi1/antigravity--mobile/releases/tag/v0.1.2
- Release source commit: `75ffc8c583b48513f291499f2fa879b6c9ecc5e2`
- Current Google Drive APK: https://drive.google.com/file/d/1SncofhU19PhL1FoIRxH3gCohCMc5_nvV/view
- The previous Google Drive 0.1.1 APK was deleted after replacement verification.

The release contains the APK, complete source ZIP, SHA-256 checksums, original app icon, repository cover and compatibility report. Local release copies are in `release/` and `dist/`; binary release artifacts are excluded from Git history.

APK SHA-256: `aebaab349a3fd70ab913de3b2b34e1cf1d9a7626263b7350ee4897497169b39e`.

### Implemented and tested (0.1.2)

- Kotlin/Compose diagnostic interface and original adaptive launcher icon, with round and Android 13+ themed-icon resources.
- App-private workspace read/write, checkpointing, diff capture and conflict-aware rollback.
- Packaged Android/Bionic ARM64 executable with action approval, output, exit status and cancellation.
- Room check history and interruption recovery without automatically replaying commands.
- Experimental ChatGPT OAuth with PKCE/state/nonce, signed ID-token validation, encrypted credentials, streaming inference, renewal and logout implementations.
- Complete Compose sample source generation with executable Gradle launcher.
- APK selection/installer launch and compatibility report export.
- Repository artwork, emulator screenshots, reproducible build instructions and release metadata.

**19 automated tests passed:** 11 JVM and 8 Android instrumentation tests on an Android 12 ARM64 emulator. Release lint, signing verification, installation, launch and visual launcher-icon inspection passed. All six GitHub release assets matched the local SHA-256 digests. Google Drive replacement metadata was verified.

Fixed bugs: checkpoint path traversal, lost generated launcher permissions, and stale generated-asset packaging paths. Build output now lives outside iCloud Drive.

### Unresolved product requirements (as of 0.1.2)

Google and Claude subscription integration have not been established. ChatGPT consent, real inference, renewal and revocation have not been tested against the user's live account. No Android-host compiler toolchain is bundled, and no APK has been compiled on the phone. The physical OnePlus 7T Pro's current Android version, RAM and free storage remain uninspected.

The full five-screen app, repository cloning/commits, general shell execution and common agent tool loop are not implemented in this release. See [compatibility report](compatibility-report.md) and [provider evidence](provider-evidence.md) for detailed limits.

## User direction

The user wants **a full app**, not another diagnostic-only release. The 0.2.0 source above is the first full-app implementation. Do not repeat the completed icon/release/upload work.

Retain the agreed boundaries: personal sideloading on OnePlus 7T Pro, native Android implementation, no root, no desktop runtime dependency, no cloud builds, no paid-API fallback, and Google/Claude/ChatGPT subscriptions mandatory. Do not invent subscription support, proxy unofficial client tokens or mark the product complete while those dependencies are missing.

## Local development and signing

- Workspace: `/Users/srimi/Library/Mobile Documents/com~apple~CloudDocs/Antigravity--Mobile`
- Build: `./tools/build.sh`; source packaging: `python3 tools/package_source.py`.
- Java 17, Gradle 8.13, Kotlin 2.1.21, AGP 8.10.1, Android SDK 36, NDK 27.2.12479018.
- Build output: `~/.cache/antigravity-mobile-build`; project cache: `~/.cache/antigravity-mobile-gradle`.
- Emulator used: `AntigravityMobileProbe_API31`, Android 12 ARM64. It was stopped after verification.
- Application ID: `dev.srimi.antigravitymobile.probe`. Keep it and the signing certificate to permit updates.
- The private signing key remains locally in `.signing/personal.p12`, excluded from Git and source archives. Preserve it; never publish it.
- Signing certificate SHA-256: `791980edfce3d623dfe1ba2e3209a506b01c4be7da74065805d400a5051210b5`.

The existing release ZIP is a snapshot of v0.1.2; this checkpoint is saved separately in the repository.
