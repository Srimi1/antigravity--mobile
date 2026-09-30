# Antigravity Mobile — complete continuation prompt

Saved on 30 September 2026 at the user's request. Copy this entire document into another AI and give it the current project folder or the accompanying source archive. This is a development handoff, not a claim that the product is ready.

## Instructions to the next AI

You are continuing my existing **Antigravity Mobile** project. Continue the actual implementation, preserve existing work, and verify results. Do not start over, recreate the artwork, or mistake the published diagnostic prototype for the full app.

My original goal is:

> “I don't know how you will do it, but you have to make sure the app is ready, okay? I should be able to do all the tasks I have mentioned. It should perform all the tasks that it can perform on Linux, Windows, or Mac. Just as simple as that. Now create it!”

The tasks I explicitly asked about are **creating Android apps, creating websites, and doing coding work from my phone**. The intended experience is a complete coding agent and development workspace on Android. Do not narrow that goal to a chat screen, diagnostic app, website preview, or one successful sample build. Desktop-equivalent coverage is a requested goal; it has **not** been achieved or established as feasible in full.

My latest reported phone is **OnePlus 7 Pro, 12 GB RAM, 256 GB storage**. Older notes say OnePlus 7T Pro; use my latest statement, and inspect the physical model, installed Android version, available storage and architecture before claiming compatibility. The physical phone has not been tested. RAM/storage specifications alone do not establish compatibility or performance.

### First actions

1. Read `AGENTS.md`, `docs/PROJECT_CHECKPOINT.md`, `docs/compatibility-report.md`, `docs/provider-evidence.md`, and `README.md`. The latest sections supersede historical sections; old reports deliberately retain old outcomes.
2. Inspect `git status`, the current commit and fetched `origin/main`. Preserve all changes. The last fully validated source baseline before the website work was **`29d0d11e68b367cfad3ccaf36dc4b0e1b0545e9c`**. Do not reset the current checkout to it.
3. Read the **unfinished 0.4.0 website work** section below before editing. Some new code compiles but is not connected to the user interface or tested on Android. Do not report that websites now work.
4. Continue meaningful implementation and verification. Say clearly when a supported integration, physical device, licence acceptance or live-account consent is missing. A successful compilation or an OAuth callback is not end-to-end acceptance.
5. Update the checkpoint after substantive work. Keep source progress synchronized between this Mac folder and `Srimi1/antigravity--mobile`, preserving unrelated edits and never force-pushing.

## Scope and constraints that must remain

- A **full native Android app**, implemented with Kotlin, Compose, coroutines and Room. Screens: **Projects, Agent, Changes, Build, Accounts**.
- Personal sideloading for one user; one agent task at a time. Repositories, editing and execution live on the phone.
- **Google, Claude and ChatGPT subscription support are mandatory for full acceptance.** Do not silently drop a provider or replace subscription access with separately billed API keys.
- No root, desktop runtime dependency, remote desktop wrapper, Termux launcher as a substitute, cloud builds or paid-API fallback. The initial Antigravity APK can be assembled on the development Mac; user projects must eventually build on the phone itself.
- Use documented, supported authentication/inference routes. No internal endpoint reverse engineering, unofficial token lifting or proxying, provider impersonation or fabricated responses.
- Keep credentials out of logs, repository files, chat records, source archives and exported evidence. Do not copy private keys into the handoff.
- File tools stay within the selected workspace. Checkpoint before changes, show proposed edits/diffs, require the user's approval for commands in the app, and never automatically replay an uncertain command after process death.
- Preserve all user projects and original imported folders. Never uninstall the user's phone app merely to resolve a test signer conflict. Preserve the original app ID and personal signing key for upgrades.
- Source synchronization is authorized. Publishing a runtime APK, replacing a public release, uploading private signing material, sending approval requests to providers, or deleting public artifacts is a separate action requiring the user's authorization.

## Current answer about readiness

**No, the complete app is not ready.** A real Compose sample was built, installed and run through the integrated Antigravity emulator workflow. Basic file editing and local Git have evidence. A static-website workflow (create, edit, approve, preview, console, export) passed emulator QA on 1 October 2026. Node/backend website work, wider languages/repositories, physical-phone acceptance, complete live subscription access, runtime distribution acceptance and some durability/performance issues remain unfinished.

Do not promise that everything possible on Linux, Windows or macOS can already run on this phone.

## Versions: distinguish shipped, validated and work in progress

| Version | Actual state |
| --- | --- |
| **0.1.2-probe / Android code 3** | Publicly released diagnostic prototype. Original signer, artwork, release assets and Drive APK are preserved. Not the full app. |
| **0.2.0 / code 4** | Five-screen implementation; real host build, lint, 34 JVM + 12 emulator tests and data-preserving release upgrade passed. An initial upgrade ANR remains unresolved. Historical APK is private. |
| **0.3.0 / code 5** | Last complete validated implementation milestone. Integrated separate native build worker, Room v3, 37 JVM + 16 emulator tests, signed host release build and a real Compose build/install/launch through the app. Unpublished. |
| **0.4.0 / code 6** | Current source. Static-website workflow implemented; on 1 Oct 2026 `./tools/build.sh` (56 JVM tests, release lint, signed release), worker release lint, 20/20 device tests on API 36 emulator, website device tests on API 31 and manual emulator QA passed (`docs/website-qa-2026-10-01.md`). Unpublished; no physical-phone/live-provider acceptance; embedded companion UI update and signed upgrade unvalidated. |

Main application ID stays **`dev.srimi.antigravitymobile.probe`**. Last validated companion is **`dev.srimi.antigravitymobile.worker`**, 0.3.0-tools/code 1. Current companion source is **0.4.0-tools/code 2**. Current main client requires worker code 2 or later with a matching signature; the old worker will need an update before current-source operations can run.

Room remains **schema v3**, with `MIGRATION_1_2` and `MIGRATION_2_3`. The website groundwork adds no table. Future schema changes need a v4 migration with data-preservation evidence.

## What has been implemented

All main Kotlin files below are under `app/src/main/java/dev/srimi/antigravitymobile/`.

| Area / files | Implementation and actual limitations |
| --- | --- |
| `MainActivity.kt`, five `*Screen.kt` files, `UiParts.kt` | Projects, Agent, Changes, Build and Accounts navigation. Real native Compose UI. |
| `AntigravityApp.kt` | Process-wide container and Room/services; selected project; startup interruption recovery. Uncertain work is not replayed. |
| `ProjectsViewModel.kt`, `ProjectRepository.kt` | Create projects, Compose template, HTTPS clone, SAF import as a copy, ZIP export, rename/delete, bounded editor and file browser. Clone/pull/push networking has not received physical/live-device acceptance. |
| `WorkspaceService.kt`, `Archives` in `ProjectRepository.kt` | Relative-path bounds, reads/writes/listing, symlink-safe deletion, import/archive safeguards. |
| `GitService.kt` | JGit 5.13.5: init, clone, status, commit, log, pull/push and diffs. Android-specific runtime configuration disables auto-GC and isolates configuration. Init/commit/status/log passed on ART; device networking coverage remains incomplete. |
| `ChangeService.kt` | Durable before/after snapshots, review, accept, conflict-aware revert, commit marking and interrupted-set recovery. |
| `AgentLoop.kt`, `AgentViewModel.kt` | Provider-independent streaming/tool loop, project-bounded tools, approvals and diff previews, durable user/assistant messages/actions and Stop. No live provider coding turn has passed. The model has no shell/build/test tool. Agent tasks live in a ViewModel; background process death ends them. In-flight tool history is not fully replayed across app restarts. |
| `ChatGptProbeAdapter.kt`, `ResponsesStream.kt`, `OidcVerifier.kt`, `CredentialStore.kt` | Documented OAuth/PKCE/state/nonce, signed ID-token verification, Keystore-backed encrypted storage, Responses streaming/function tools with `store:false`, renewal/logout/model selection. Live entitlement/inference/coding/renewal/revocation are unverified. |
| `Providers.kt`, `AccountsScreen.kt` | Honest account states. Google and Claude remain BLOCKED. Git author and encrypted HTTPS token settings exist. |
| `BuildSnapshot.kt`, `BuildCoordinator.kt` | Bounded immutable source ZIP/hash, exact task review, one-shot approval claim, persistent build records, observation/cancellation/artifact transfer. No uncertain START replay. |
| `BuildWorkerClient.kt`, `runtime-contract/` | Messenger requests, same-signature and exact UID checks, copied reply Bundles and reconnection after worker death. Approved read-only ZIP descriptors cross the boundary; account files are not transferred. |
| `build-worker/` | Separate Android UID, native compiler/runtime storage, foreground Gradle execution, per-build caches, bounded output and child cancellation. Scripts can access all worker storage/internet; this is not isolation between arbitrary projects. |
| `BuildScreen.kt`, `ApkInstaller.kt` | Embedded companion installer implementation, project/task review, build status/logs, APK transfer/staging/storage guard, Android installer and installed-app launch. The companion itself was installed with ADB in QA; its embedded install/update flow is not yet validated. |
| `ProbeViewModel.kt`, `NativeExecutionService.kt`, C probe | Device diagnostics remain. Fixed childless `version`, `exit-7` and `wait` actions are a probe, not a shell/compiler. |

`samples/HelloPhone/` is the complete Kotlin/Compose project template. `assets/branding/` contains completed original icon/cover/prompts. Preserve it.

## Real validation already completed

### 0.2.0 and migration

Real AGP/kapt compilation, Room schema generation, release lint and signed release assembly passed. **34 JVM + 12 Android instrumentation tests passed**. A personally signed release upgraded 0.1.2 in place and retained the saved check record.

The first upgraded release launch produced an ANR with a **10,410 ms focus timeout**. Choosing Wait recovered it. Three subsequent controlled cold launches took 796/749/705 ms and did not repeat it. The root cause was not established; do not mark it fixed. See `docs/emulator-qa-2026-09-30.md` and `assets/screenshots/qa-20260930/`.

### Android-native runtime and Compose lab

A separate account-free lab compiled/executed Java, launched a child JVM, started Gradle, built/signed/verified a small Android Java APK and tested cancellation. The generated APK installed and launched. An early full Compose attempt suffered an unexpected emulator guest reboot; later attempts exposed cache/SDK metadata errors, preserved their evidence, and used fresh per-task caches.

After fixes, a **complete Compose sample built on Android in 3m 37s, 35 tasks executed**, installed, launched and responded to its Count button. Six lab regression checks passed in the later investigation. Host tools prepared the runtime APK and controlled QA; the sample compilation itself ran inside Android. Read `docs/native-runtime-qa-2026-09-30.md` and `docs/native-compose-qa-2026-09-30.md` for the exact historical sequence.

### 0.3.0 integrated worker

`./tools/build.sh` passed the real host toolchain, **37 JVM tests**, main release lint, signed release assembly and signer verification. Worker release lint also passed with warnings. A final full emulator run passed **16 Android instrumentation tests, zero failures/errors/skips**.

Tests include migrations v1/v2→v3, one-shot/declined approval, immutable approved copy after an original source edit, separate worker UID and inability to read a main-private test marker, foreground cancellation, worker death/reconnection, interruption and refusal of an old ID. A recycled Android Message/Bundle race was found and fixed before the final run.

Through visible Antigravity controls: **create Compose project → review/approve → native Gradle build → transfer APK → Android install → open → Count 0→1**. The QA scratch app ID was changed through ADB to `dev.srimi.integrationphone` to preserve an older fixture; do not describe that ID edit as a UI feature.

- Build ID: `b94e9172-9f34-4518-ae9d-cfba78fc7512`.
- Approved source SHA-256: `d7c2f04a876952e255a2e06720220f94bfdcc485d8b4cea2e1f0f785c943d272`.
- Matching native begin/end attempt ID: `732fc3bc-074f-4f6c-9c90-6c6b44a830f5`.
- Exit 0; elapsed **232,740 ms / 3m 52s**, all 35 tasks executed.
- Main was force-stopped/reopened during compilation; the same worker job was recovered without a second START.
- Generated APK: 23,669,282 bytes, SHA-256 `e0bd612b2252950fd96c041a3a67ef8c9bf93a10c0f73eb6518fe47141951d2f`. Its development signer is the worker's private generated key, not the Antigravity app signer.

An Android installer low-storage failure was reproduced: 252,907,520 bytes free versus 624,066,560 requested. An older **generated lab cache** was archived and fully verified before clearing that cache; project source, APKs and logs were preserved. A truncated compressed backup was not used as justification for deletion. A later staging/storage guard was reproduced with a temporary QA reservation, the reservation removed, and installation succeeded. The guard is conservative, not a promise for every OEM installer.

Evidence: `docs/build-worker-qa-2026-09-30.md` and `assets/screenshots/integration-20260930/`. Actual screenshots, UI XML, successful/failed logs, migration/test output, signatures, run records and performance metadata are saved there.

## Performance and compatibility limits

QA used **Android 12 API 31 ARM64, 3 GB RAM**, AVD `AntigravityMobileQA_API31`, serial `emulator-5556`. It did not emulate or inspect the user's physical OnePlus hardware.

A focused integrated build trace was 86,055,588 bytes and parsed successfully; the health query returned no nonzero error/data-loss statistics. Sampled minimum available memory was **21,544,960 bytes / 20.5 MiB**. The Gradle daemon's individual peak sampled RSS was **1,652,932,608 bytes**. RSS includes shared mappings; do not add individual peaks or present them as true peak PSS. The emulator experienced strong memory pressure.

Generated-app gfxinfo counters conflicted (0 versus 48 janky out of 50 frames); no smoothness conclusion is supported. Main cold launches of 708/897 ms occurred under different conditions and are not a controlled comparison. The final crash buffer was empty; that does not resolve the earlier ANR.

The native runtime is experimental: Android/Bionic Java 17.0.18, Gradle 8.13, native resource tools 35.0.2 with SDK/Build Tools data 36, disabled Gradle native integration/file watching/instrumentation agent, single worker and in-process Kotlin. Its Android port uses legacy heap-tagging/path compatibility measures. Native health/MTE, current security maintenance, broader compatibility and complete redistribution/corresponding-source/third-party notice obligations remain open. A missing platform-tools licence warning was preserved; no licence acceptance was fabricated.

## Provider evidence and exact blockers

| Provider | Current evidence |
| --- | --- |
| Google | First-party desktop subscription access exists in the recorded documentation, but no supported direct native third-party consumer-subscription route has been established for this app. BLOCKED. |
| Claude | The recorded official SDK documentation requires prior approval for third-party claude.ai login/limit sharing. Applicable approval/integration for this Android app has not been established. BLOCKED. |
| ChatGPT | Documented Sign in with ChatGPT/eligible local-app Responses route is implemented. Android eligibility, user entitlement and a real coding turn remain UNVERIFIED. |

No live account credentials were used in the development/QA run. No provider approval request was submitted. Live ChatGPT testing requires the user's consent first. Record only outcomes/date/version/non-secret error categories in `docs/provider-evidence.md`; never tokens or prompts. Re-check official documentation before claiming routes are currently available.

Primary sources recorded in the project include Google's Antigravity downloads/pricing/SDK pages, OpenAI's `developers.openai.com/siwc/token-sharing-open-source` documentation and Claude's `code.claude.com/docs/en/agent-sdk/overview`. A login screen, model catalog, OAuth callback, unit test or mock model is not subscription inference evidence.

## 0.4.0 website work

**Update 1 October 2026:** the items listed as missing below were implemented and emulator-validated; see `docs/website-qa-2026-10-01.md` and the checkpoint. Remaining website gaps: WebRTC only JavaScript-guarded (bypass via `srcdoc` on WebView 91; worker holds `INTERNET`), no Node/package builds, backend, external APIs or deployment, no pruning of preview records, no physical-phone run. The text below is the original 30 September handoff, kept for history.


The user requested this handoff while the website workflow was being implemented. The following source is saved; it is **not a completed website feature**:

- `samples/HelloWeb/`: HTML entry/about page, responsive CSS, JavaScript module with a real Count button/local JSON fetch, JSON data and README.
- `app/build.gradle.kts`: main version 0.4.0/code 6 and a `hello-web.zip` template packaging task. **No ViewModel/template creation button has been added.**
- `runtime-contract/.../BuildProtocol.kt`: preview operation/activity and minimum worker code 2.
- `runtime-contract/.../WebFiles.kt`: website path/symlink bounds, ZIP copy/hash/source consistency checks and bounded extraction (64 MiB/10,000 entries). Hidden files and node_modules excluded/refused. New safeguards need their own tests/review.
- `build-worker/.../WebPreviewStore.kt`: receives only a read-only approved copy/hash, creates a fresh preview ID, extracts into worker storage and stores a ready marker. No Main data path is accepted. Failure keeps an ID claim; no automatic rerun.
- `build-worker/.../WebPreviewActivity.kt`: native Android Views + WebViewAssetLoader intended to serve local HTTPS content, real JS/CSS/JSON, reload/close/console, external network/file/content denial, no JavaScript bridge, denied device permissions/service workers and no automatic restoration after interruption. **These are implementation intentions, not emulator-proven security or functional claims.**
- Worker manifest registers the signature-protected activity; worker dependency adds AndroidX WebKit 1.15.0; worker source version is 0.4.0-tools/code 2.
- `BuildWorkerService.kt`: asynchronous PREVIEW request preparation through the existing signed/exact-Main-UID Messenger boundary.
- `BuildWorkerClient.kt`: preview-copy request and minimum matching worker version 2.

**Still missing:** Projects/Editor/Website-tab UI, template creation wiring, approval dialog and one-shot user flow, entry/root selection, preview launch, website ZIP export controls, new filesystem/security tests, complete APK/lint validation, matching-worker update validation and manual emulator QA with screenshots/UI/logs/performance evidence. The current main app has no button to invoke the new preview groundwork. Do not assume a website task is usable because these files exist.

Debug Kotlin compilation for main/worker and the existing 37 JVM tests passed on the real toolchain. Room kapt ran. No new website-specific tests or Android instrumentation tests were run, no 0.4.0 main APK was assembled/installed, and no 0.4.0 release lint/phone/provider acceptance occurred. Evidence is `docs/handoff-validation-2026-09-30.txt`.

Planned next implementation: let a user create Hello Web, edit/save files, choose an HTML entry (including a built frontend output such as `dist/index.html`), explicitly approve an immutable website-root copy, open preview in the account-free worker UID, inspect console errors, and export a deployment ZIP. Reload should use the approved copy; saved changes require a new copy. Do not silently execute unsaved editor text or replay preview/build actions after uncertain failure.

Node/package-manager/frontend build pipelines, backend servers, external APIs and deployment remain separate unfinished requirements. Static preview does not satisfy all website or desktop coding needs. Review the WebView network/storage/service-worker policy with meaningful tests before accepting it.

## Remaining acceptance work

1. ~~Finish and test the static website workflow~~ (done on emulators, 1 Oct 2026). Close the WebRTC gap (preview package without `INTERNET`) and add Node/frontend build support later without overclaiming.
2. Validate the embedded companion install/update and personally signed release upgrade/data preservation on the preserved test release emulator. Never substitute an uninstall on the physical phone.
3. Investigate the initial upgrade ANR, capturing a trace before launch and controlling host contention.
4. Keep long agent tasks alive with an appropriate foreground service; persist tool history and recover interruption honestly.
5. Add approved agent command/build/test tools and broader language/repository workflows, preserving credential isolation, command approvals, cancellation and no replay. Current agent has only file-oriented tools.
6. Improve generated build cache/APK/storage management with preservation before deletion.
7. Resolve runtime native health/MTE/security maintenance and redistribution requirements before publishing bundled runtime APKs.
8. With explicit user consent, validate a real entitled ChatGPT coding turn plus renewal/logout. Establish supported approved Google/Claude subscription routes; do not fake them or add a paid API fallback.
9. Validate on the actual OnePlus: install/update, open existing repo, edit/review/test/commit, create/build/install/launch a Compose app, website workflow, cancellation, process death, storage failure and resource/thermal behavior.

Full acceptance requires all mandatory subscriptions performing real coding tasks and the phone alone completing the requested development workflows. Do not mark the overall goal complete while those gates remain open.

## Development environment and commands

Local project:

`/Users/srimi/Library/Mobile Documents/com~apple~CloudDocs/Antigravity--Mobile`

Repository: `https://github.com/Srimi1/antigravity--mobile`, branch `main`. Build caches are deliberately outside iCloud.

Host Java 17.0.20 at `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`; Android SDK `/Users/srimi/Library/Android/sdk`; platform/Build Tools 36, NDK 27.2.12479018; Gradle wrapper 8.13, Kotlin 2.1.21, AGP 8.10.1. Paths are machine-specific; inspect them on another machine.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/Users/srimi/Library/Android/sdk

# Inspect before editing; preserve changes and reconcile newer remote commits.
git status --short
git fetch origin
git rev-parse HEAD origin/main

# If prepared native inputs are absent, read the runtime README first.
python3 tools/android-runtime-lab/prepare.py \
  --sdk "$ANDROID_HOME" --gradle /path/to/extracted/gradle-8.13 \
  --host-jdk "$JAVA_HOME"

# Full host verification and a private signed main APK.
./tools/build.sh

# Separate worker lint, matching debug worker, then device tests.
# Choose a disposable emulator with the correct signer and enough free space.
./gradlew --project-cache-dir "$HOME/.cache/antigravity-mobile-gradle" \
  :build-worker:lintRelease :build-worker:installDebug \
  :app:connectedDebugAndroidTest --console=plain

# Source-only shareable package (no key/APKs/build caches).
python3 tools/package_source.py
```

Do not run the placeholder preparation command literally until its Gradle path is resolved. `tools/jvm-harness/` is only a fallback when Google Maven/SDK downloads are blocked; it does not replace real AGP/kapt/lint/device evidence.

## Saved files, evidence and private artifacts

- Main historical private APK: `dist/antigravity-mobile-0.3.0.apk`, 286,454,547 bytes, SHA-256 **`c0e4e74efe40c2915a3220796c2dc76d3621d4616ba0107e92f7c6778807a771`**.
- Historical worker release SHA-256: **`f90d1fe4e7cfd06590c169d039edf97ede12541a22b17eadf0ea84f99150adae`**, 261,800,807 bytes. Gradle output paths can be overwritten by future builds; verify before reusing.
- Personal signing certificate SHA-256: **`791980edfce3d623dfe1ba2e3209a506b01c4be7da74065805d400a5051210b5`**. Private key stays in `.signing/personal.p12`, locally only. Another computer/source ZIP does not contain it; a newly generated key cannot update the original signed app.
- Current source bundle: `dist/antigravity-mobile-source.zip`; dated handoff copy: `dist/antigravity-mobile-handoff-2026-09-30.zip`, with its own checksum file. This includes unfinished source and this prompt, not a working 0.4.0 release.
- Curated evidence is under `assets/screenshots/qa-20260930/`, `runtime-20260930/`, `compose-20260930/` and `integration-20260930/`, linked by the QA reports.
- Large raw evidence, runtime inputs and generated sample APKs stay outside Git under `~/.cache/antigravity-mobile-runtime/`; host build outputs under `~/.cache/antigravity-mobile-build`; Gradle project cache under `~/.cache/antigravity-mobile-gradle`.
- Native prepared assets/JNI are under `~/.cache/antigravity-mobile-runtime/lab-generated/`. Source bundles exclude these large generated inputs; use the pinned preparation script on another machine.
- Recovery ref before website edits: `refs/checkpoints/before-web-workflow-20260930` at the validated baseline. This local Git ref is not part of a source ZIP.

Public release remains [v0.1.2](https://github.com/Srimi1/antigravity--mobile/releases/tag/v0.1.2). Recorded Drive APK: `https://drive.google.com/file/d/1SncofhU19PhL1FoIRxH3gCohCMc5_nvV/view`. These are historical saved links; re-check access/current metadata before acting. Published APK SHA-256: `aebaab349a3fd70ab913de3b2b34e1cf1d9a7626263b7350ee4897497169b39e`. Source progress does not change those public artifacts.

At this handoff's read-only device check, `emulator-5556` was connected. Worker 0.3.0-tools/code 1, the runtime lab and generated fixture apps were installed; the main package was **not listed**. Do not assume an installed main app or current app-private project database exists. No installation/uninstallation was performed during this handoff session. Historical QA evidence and private APKs remain saved. No physical phone was attached.

## How to continue communicating with me

Give me direct, honest status: what works, what was actually tested, what is unfinished and what needs my participation. Make progress once the direction is clear. Do not repeatedly ask for permission for ordinary reversible work already authorized, but do not access live subscription credentials, publish or delete artifacts without the relevant authorization. Never represent mocks, emulator measurements or source compilation as physical-phone/live-provider acceptance.

Save substantive progress and exact test/artifact evidence in the project checkpoint so another AI can continue again later. The immediate task is to continue from the saved source, not redo completed artwork/releases or pretend the whole product is finished.
