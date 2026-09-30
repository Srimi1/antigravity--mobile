# Antigravity Mobile — saved checkpoint

Last updated **1 October 2026** (0.4.1 private phone-test build; signed upgrade chain and companion update validated on emulators; full app still not accepted).

**For the next agent:** start with the repository-root [AGENTS.md](../AGENTS.md). It maps the current implementation, explains the user's full-app request, and gives continuation, validation and signing guidance. This checkpoint records the completed work; AGENTS.md explains how to resume it.

## Latest: signed upgrade, companion update and phone-like simulation — 1 October 2026

See [upgrade QA](upgrade-qa-2026-10-01.md), [phone test guide](PHONE_TEST_0.4.1.md) and `assets/screenshots/upgrade-20261001/`.

- **Release emulator `AntigravityMobileProbe_API31`:** signed 0.2.0 → 0.3.0 → 0.4.0 → 0.4.1 in place; every first launch 448–591 ms with no ANR; projects and a 0.3.0 marker file preserved. Companion code 1 installed via Build tab, then **updated 1→2 via Build tab** (same signer). Release-signed website preview and a **native Compose build (231 s) → install → Count 0→1** after the upgrades. The original 10,410 ms first-upgrade ANR did not reproduce; root cause still unknown (one trace captured, not analysed).
- **0.4.1/code 7:** Build shows "Tools update needed / Update build tools" for older same-signer tools. `./tools/build.sh` passed (56 JVM, release lint, signed `dist/antigravity-mobile-0.4.1.apk`, 286,711,261 bytes, SHA-256 `d4144955ede813a745de0177f2d5a9b374a107a0030b4b4ac55845ea323b55b7`). API 36 device suite 19/20 on first run after cold boot (one 20 s UI timeout, unexplained), **20/20** after raising the test wait to 60 s.
- **OnePlus-like AVD `OnePlus7ProSim_API31`** (Android 12, 1440×3120/560 dpi, 6 GB, generic Google image — not OxygenOS/Snapdragon): public 0.1.2 → 0.4.1 through Files + Android installer (654 ms first launch); tools update 1→2 via new label; **Compose build 188 s → install → Count 0→1**; website preview OK. Min available memory 2.5 GB; daemon RSS 1.56 GB.
- **Distribution:** 0.4.1 uploaded only as a **GitHub draft release** (visible to repository owner/collaborators, not public), because the APK bundles OpenJDK/Gradle/Android SDK build components whose public redistribution/notice obligations are still unresolved. Public v0.1.2 release and Drive file unchanged.
- **UX gaps:** after granting "Install unknown apps", the user must tap Install/Update again.

**Next:** user runs [PHONE_TEST_0.4.1.md](PHONE_TEST_0.4.1.md) on the physical OnePlus 7 Pro and reports; then foreground service for agent tasks + persisted tool history, approved agent build/test tools, storage pruning, WebRTC-free preview package, redistribution/notice review before any public runtime release. Live subscriptions still gate acceptance. **Full app is not ready.**

## Earlier: static website workflow validated on emulators — 1 October 2026

See [website QA](website-qa-2026-10-01.md) and `assets/screenshots/web-20261001/`. Baseline `f3e8592d3bdc5e1123c2616e68d9705e5a2285f6` = fetched `origin/main`; the previous session's uncommitted website wiring was preserved and completed.

- **Implemented (0.4.0/code 6, worker 0.4.0-tools/code 2, Room v3 unchanged):** Projects **Website** template; editor **Preview saved HTML** (blocked while unsaved); **Website** tab with folder/entry selection (e.g. `dist` + `index.html`); `WebsiteService` durable one-use approvals (hash, file list, claim once, decline, startup `INTERRUPTED` without replay); preview in the worker UID with Reload (approved copy only), Console and Close (extracted copy deleted, ID cannot reopen); **Export website ZIP** (chosen folder at ZIP root) via the document picker.
- **Defects found/fixed:** preview refused to start on WebView 91 (no `DOCUMENT_START_SCRIPT`) → `WebGuard` injected into served HTML after doctype (plus document-start when supported); console line numbers shifted → one-line guard. Preview activity now survives rotation (`configChanges`).
- **Tests:** `./tools/build.sh` passed — **56 JVM tests**, main release lint, signed release (`dist/antigravity-mobile-0.4.0.apk`, 286,709,857 bytes, SHA-256 `58e23228d32e30085f1c9dfc99e29e0c458e804b2753f7066d31b8dd735c8200`, original certificate `791980ed…10b5`, private/unpublished). Worker `lintRelease` passed. **20/20 device tests** on `AntigravityMobileQA_API36` (Android 16, WebView 133); `WebsiteDeviceTest` **4/4** on `AntigravityMobileQA_API31` (Android 12, WebView 91).
- **Manual emulator QA (API 36):** create Hello Web → edit/save → approve → preview (local JSON, CSS, Count 0→1, local navigation, reload) → `dist/index.html` created in app → folder `dist` preview → console shows warning and uncaught `ReferenceError` → exported ZIP pulled, one root `index.html`, SHA-256 equal to approved copy.
- **Honest limits:** HTTP/file/content/POST/service-worker denial is enforced; WebRTC removal is JavaScript defense in depth only — on WebView 91 a `srcdoc` frame still exposed `RTCPeerConnection` and the worker UID has `INTERNET`. Proper fix: preview package without `INTERNET`. Static sites only (no Node/npm, backend, external APIs, deployment). Preview/approval marker records are not pruned.
- Embedded companion **fresh** install through the Build tab was captured by the previous session on API 36 (`prior-api36-*`); the UI **update** path (code 1→2) and personally signed release upgrade/data preservation remain unvalidated. The QA AVDs were stopped after testing; `AntigravityMobileProbe_API31` (release emulator) was not touched. No physical phone, live account, publish or deletion of user/public artifacts. One stale ddmlib upload copy (`/data/local/tmp/app-debug.apk`, SHA-256 identical to the host build output) was removed from the disposable QA AVD to free space.

**Next:** (1) validate embedded companion update 1→2 and signed 0.3.0→0.4.0 in-place upgrade with data preservation on `AntigravityMobileProbe_API31`, tracing the first-upgrade ANR; (2) move preview into a no-`INTERNET` package or otherwise close the WebRTC gap; (3) foreground service for agent tasks + persisted tool history; (4) approved agent build/test tools; (5) storage/cache pruning with preservation. Physical OnePlus 7 Pro and live subscriptions (ChatGPT unverified; Claude/Google blocked) still gate full acceptance. **Full app is not ready.**

## Earlier: user-requested complete AI handoff — 30 September 2026

Read [CONTINUE_WITH_ANY_AI.md](../CONTINUE_WITH_ANY_AI.md) for the complete copyable goal, constraints, verified progress, architecture, evidence, artifacts, remaining work and continuation instructions. The user asked to save the full context and current work in this folder so another AI can continue.

- Before website edits, clean `main` matched fetched `origin/main` at **`29d0d11e68b367cfad3ccaf36dc4b0e1b0545e9c`**. Local recovery ref: `refs/checkpoints/before-web-workflow-20260930`.
- **Current source is 0.4.0/code 6; companion source 0.4.0-tools/code 2, unfinished.** Added `samples/HelloWeb`, `hello-web.zip` packaging, shared `WebFiles`, worker `WebPreviewStore`/`WebPreviewActivity`, signed Messenger preview-copy operation and a client requiring matching worker code 2. Room remains v3. Website files are saved; **no Projects/Editor creation, approval, preview-launch or export UI has been wired**.
- The new preview code intends bounded copied-site HTTPS loading, JavaScript/CSS/local JSON, console output, network/file/content denial and no native JS bridge. These behaviors and safeguards have **not** received website tests or emulator validation. Node/frontend package builds, backend servers, external API access and deployment are not implemented.
- Real-toolchain **main/worker debug Kotlin compilation + existing 37 JVM tests passed, zero failures/errors/skips**; Room kapt ran. No new website-specific tests, 0.4.0 release lint/main APK/device tests, installation or live account/phone validation were performed. Build evidence: [handoff-validation-2026-09-30.txt](handoff-validation-2026-09-30.txt). The 16 passing device tests below belong to validated 0.3.0, not 0.4.0.
- Source-only handoff archive: `dist/antigravity-mobile-handoff-2026-09-30.zip` and checksum; current generic source ZIP also refreshed. Prior 0.3.0 source ZIP preserved separately. Archives exclude keys, credentials, APKs, build caches and generated native inputs; another machine needs the pinned preparation script and original signer obtained privately if an upgrade is intended.
- Read-only device check: `emulator-5556` connected, worker 0.3.0-tools/code 1 and generated fixtures installed; **main package not listed**. No install/uninstall was performed during this handoff. Historical 0.3.0 private APK/evidence preserved. No physical phone attached.

**Next:** complete and validate website UI/approval/export and matching worker update; then continue the remaining full-app gates in the handoff. Published 0.1.2, original signer, artwork and Drive artifact unchanged. **Full app is not ready; overall objective remains unachieved.** The validated 0.3.0 milestone and its actual evidence follow below.

## Latest: integrated foreground build worker — 30 September 2026

See [integration evidence](build-worker-qa-2026-09-30.md) and `assets/screenshots/integration-20260930/`. Baseline `8c2888a6ccd6e2bdf66014388c1bf60ae4ababdf`, fetched/equal before edits; recovery ref `refs/checkpoints/before-worker-integration-20260930`.

- Source now **0.3.0/code 5**, unpublished. Main embeds matching debug/release companion `dev.srimi.antigravitymobile.worker`, code 1, same signer/different UID. Packaged toolchain runs in its foreground service; main account storage is not mounted or transferred. Gradle scripts can access all worker storage/internet, so there is no isolation between build projects.
- New `BuildSnapshot`, `BuildCoordinator`, `BuildWorkerClient`, `build-worker/` and `runtime-contract/`. Immutable bounded source copy/hash, exact-task approval, one-shot Room claim, per-build caches, bounded logs, cancellation, dead-worker reconnection and APK transfer. Main death reconnects to a live worker; worker death interrupts commands without replay.
- Room **v3** with `MIGRATION_2_3`, retaining v1→v2 migration. **37 JVM + 16 device tests passed**, real AGP/kapt, main/worker release lint and signed release assembly. Device checks preserved old project/chat/message and covered declined/consumed approval, source-copy isolation, private-file denial, cancellation and worker force-stop/refused old ID. Fixed recycled Messenger reply race (two initial test failures) and explicit reconnection.
- Through visible controls: create Compose project → approve → Android-native Gradle build → transfer → install → open → Count 0→1. Build ID `b94e9172-9f34-4518-ae9d-cfba78fc7512`, all 35 tasks executed in **3m 52s**, exit 0/matching attempt IDs. Main force-stop/restart during compilation retained the same job. Scratch application ID changed through ADB to preserve the old lab fixture; companion was installed with ADB for QA (embedded companion installer itself unvalidated).
- Actual installer **low-storage failure reproduced**. Archived and verified the older generated lab cache before clearing it; original source/APKs/logs preserved. Added APK staging cleanup and conditional disk guard; controlled 348 MiB reservation displayed 728 MB free/63 MB needed. Reservation removed, install succeeded. Original failure and recovered UI evidence retained.
- One 86,055,588-byte trace parsed, no nonzero error/data-loss stats. Sampled minimum available memory **21,544,960 bytes** on 3 GB emulator; daemon peak RSS **1,652,932,608 bytes**. Generated-app gfxinfo counters conflict (0/48 of 50), so no smoothness claim. Not physical OnePlus evidence.
- Private main release `dist/antigravity-mobile-0.3.0.apk`, 286,454,547 bytes, SHA-256 `c0e4e74efe40c2915a3220796c2dc76d3621d4616ba0107e92f7c6778807a771`. Worker release 261,800,807 bytes, SHA-256 `f90d1fe4e7cfd06590c169d039edf97ede12541a22b17eadf0ea84f99150adae`. Original certificate preserved. Android-built sample `~/.cache/antigravity-mobile-runtime/integrated-compose.apk`, SHA-256 `e0bd612b2252950fd96c041a3a67ef8c9bf93a10c0f73eb6518fe47141951d2f`.
- All runtime APKs remain private/unpublished. Existing 0.1.2 release/Drive/artwork/key preserved. Raw evidence and verified cache archive under `~/.cache/antigravity-mobile-runtime/evidence/integration-20260930/`. QA AVD `emulator-5556` retained; no command left running. Generated cache/storage management remains unfinished.

**Next:** validate companion install/update and release in-place migration on the preserved release emulator; physical OnePlus 7 Pro (reported 12 GB/256 GB, Android/free space unverified), broader repositories/languages/websites and agent durability. Complete native runtime health/security/licensing acceptance before publishing. ChatGPT live coding is unverified and requires user consent; approved Google/Claude subscription routes remain blocked. The earlier ANR remains unresolved. Full app acceptance is still **not achieved**.

## Earlier: Android-native Compose build succeeded — 30 September 2026

See [Compose evidence](native-compose-qa-2026-09-30.md) and `assets/screenshots/compose-20260930/`. Baseline `b5dfa65f3af74f8d5c6ca2ec97d19769ef016a26`; recovery ref `refs/checkpoints/before-compose-trace-20260930`.

- Reduced CLI heap/workers, added atomic attempt IDs/refusal to replay uncertain actions, per-task caches and a reproducible trace capture. Six lab regressions passed. No production foreground service or Room migration yet.
- New runs 01/02 failed with durable records (corrupted cache, then SDK metadata/Build Tools 35 mismatch). Original cache preserved; no guest reboot. Fixed omitted `build.prop` and pinned HelloPhone Build Tools 36. Lab assembly/test APK/lint passed.
- **Full Kotlin/Compose run 03 PASSED on Android 12 ARM64**: 35 Gradle tasks executed in 3m 37s; instrumentation `OK (1 test)`; matching begin/end IDs, exit 0. APK compiled/signed inside Android, installed/launched by the ADB harness, and Count changed 0→1. No desktop/cloud compiler built that project APK.
- Private APK `~/.cache/antigravity-mobile-runtime/compose-android-built.apk`, 23,669,266 bytes, SHA-256 `3d1b2cd085d2b31a7c5f040b6339fd41de47d76797fcdc0501a221bcb7531a0a`. Full 54.6 MB trace remains outside Git; curated screenshots/XML/logs/trace metrics saved.
- Tight 3 GB emulator memory: sampled minimum available 27,557,888 bytes; daemon peak RSS 1,610,240,000 bytes. Not a phone/larger-repository resource guarantee. No root, fake licence acceptance or live account used.
- Main 0.2.0/code 4 APK/Build UI unchanged; sample metadata source changed and main APK must be rebuilt before distribution. Published 0.1.2/key/artwork/Drive unchanged. Experimental heap-tagging/runtime/SDK-mix and licensing gates remain. Physical OnePlus, mandatory subscriptions and original ANR unverified/unresolved.
- QA emulator `emulator-5556` left running with lab and Compose fixture. No build in flight. Raw logs under `~/.cache/antigravity-mobile-runtime/evidence/compose-trace-20260930-03/`.

**Next:** integrate the native pipeline into approved, credential-isolated, durable foreground build execution and phone installer UI; validate wider repositories/languages, physical phone and live subscriptions. This milestone is not full-app acceptance.

## Earlier: separate Android-native build foundation — 30 September 2026

See [native runtime evidence](native-runtime-qa-2026-09-30.md), [lab source](../tools/android-runtime-lab/README.md) and `assets/screenshots/runtime-20260930/`.

- Baseline `f9c37fc7efc3a3e212da94b681b887e66b612354`, equal to fetched `origin/main`; recovery ref `refs/checkpoints/before-native-runtime-20260930`.
- Added a separate credential-free validation package, `dev.srimi.antigravityruntime.lab`. Main source remains 0.2.0/code 4; its Build screen still reports BLOCKED. This is not another published probe release or full-app acceptance.
- Packaged genuine Android/Bionic ARM64 OpenJDK, Java-only compiler/tool classes, Gradle and Android-native resource tools. Inputs are hash-pinned; preparation/build output remains under `~/.cache/antigravity-mobile-runtime/`.
- Real lab assembly, instrumentation APK and debug lint passed. **Five device tests passed**: Java compilation/execution, child JVM, Gradle 8.13 startup, Java Android APK build/sign/verify, parent/child cancellation. These are additional lab checks, not main-app/provider/physical-phone acceptance.
- Android-generated fixture APK installed and launched, displaying “Built entirely on Android”. SHA-256 `731d24343bc309331976217313eebf5aeb888f932f3ced021dcce03bea15d2ee`; local file `~/.cache/antigravity-mobile-runtime/phone-built-proof.apk`. Screenshot, XML, logs and limited performance evidence retained.
- Full Gradle Compose attempt **INTERRUPTED by a guest emulator reboot**. Partial configuration log, no end record or generated APK. Cause unknown; no automatic replay. This has not proven Kotlin/Compose compilation.
- Experimental compatibility shim/legacy heap-tagging setting needed; MTE/native health, security maintenance, complete redistribution notices, physical Android compatibility and peak build resource use unresolved. Native resource tools 35.0.2/SDK data 36 are a mixed unaccepted profile.
- No main-app integration, foreground execution or new Room migration yet. No live account or physical OnePlus testing. Google/Claude remain blocked, ChatGPT unverified. Published 0.1.2/key/artwork/Drive unchanged.
- QA AVD `AntigravityMobileQA_API31` retains lab scratch projects and the installed proof APK. Native runtime raw evidence is local under `~/.cache/antigravity-mobile-runtime/evidence/`.

**Next:** diagnose the guest reboot and start an explicitly new traced Compose scratch build. After a real build/install/launch, integrate approved durable execution into the main app with credential isolation. The first-upgrade ANR and all full-product acceptance gates remain open.

## Earlier: real Android build and emulator QA — 30 September 2026

See [the evidence report](emulator-qa-2026-09-30.md) and `assets/screenshots/qa-20260930/`. This supersedes the initial 0.2.0 validation limits below.

- Tested baseline `0f43adfb9e4dfb7452a1112e506ce2a5ff74b4bb`, after fetch and clean local/remote equality. Recovery ref: `refs/checkpoints/before-emulator-qa-20260930`.
- `./tools/build.sh` passed AGP, Room kapt, **34 JVM tests**, release lint and signed release assembly. A stale Gradle transform reference initially failed; restarting its daemon recovered the build. No app source changes were needed.
- **12 instrumentation tests passed** on a separate Android 12 ARM64 emulator, including v1→v2 migration, JGit on ART, ledger and template. Debug signing did not disturb the release emulator.
- Release upgraded 0.1.2 without uninstall/data clear; exact old check retained. Project creation, editor save/restart, Git status, five tabs and Compose-template creation checked manually.
- **Unresolved ANR:** first launch after upgrade waited 10,410 ms for focus and showed “isn't responding.” Wait recovered it; three controlled cold starts (796/749/705 ms) did not repeat it. Screenshot, UI XML, logs and ANR stacks saved. Root cause unknown; build/test concurrency and emulator memory are confounders, not proven causes.
- Performance: 62 frames with conflicting jank counters; 54,028 KB PSS; two raw Perfetto traces captured but not analyzed.
- Local unpublished APK: `dist/antigravity-mobile-0.2.0.apk`, SHA-256 `d0c488f7b597f27b649b678bead6d457ef7a1775355cd37c3009ed9d54688a9a`. Signer unchanged. Local evidence ZIP: `dist/antigravity-mobile-emulator-qa-20260930.zip`; raw artifacts: `~/.cache/antigravity-mobile-qa/20260930-first-full-app/`.
- Latest user-reported target: **OnePlus 7 Pro, 12 GB RAM, 256 GB storage**, superseding earlier 7T Pro wording. Exact physical model/current Android/free storage unverified.
- No live account/provider request or phone testing. Published 0.1.2, Drive file, key and release archives preserved. Full-product blockers unchanged.
- Both QA emulators were stopped after capture. The upgraded release AVD retains the scratch projects and migrated check for later review.

**Next:** trace before launch and diagnose repeated focus/first-render delays, comparing a lone emulator against controlled host load; then data-preserving physical-phone validation. Minor observations: stale file-size display until refresh after save, two empty `.kotlin/` template directories. Live ChatGPT testing still needs consent; Claude/Google and Android-host tools remain unresolved.

## Repository and local folder synchronization — 30 September 2026

The user requested that the Mac folder and GitHub reflect the same development progress. The local `main` was clean at `61a638e52d48cae7f0e7366423ae3527188f1fb7`. GitHub `main` was two commits ahead, including the five-screen implementation and merged PR #1. The folder was fast-forwarded to the imported source baseline `cad5bf2cbc09da5f36472bcf8cf9e31c0f22e1ba` (54 changed files).

- All 102 tracked files matched GitHub `main`; there were no local-only commits or uncommitted changes after import. The remote branch `ccr-ab1e41fd-3zdy6l` was fully included in `main`, and there were no open pull requests.
- All 2,768 existing ignored files retained their file metadata, including the original signing key, local configuration, build outputs and release copies. Incoming paths did not overlap them. These intentionally local files remain excluded from GitHub.
- A local recovery ref, `refs/checkpoints/before-sync-20260930T100057Z`, preserves the pre-sync commit. The preservation inventory is local Git metadata under `.git/sync-checkpoints/` and is not published.
- Validation here covered repository history, tracked-file equality and local-file preservation. No app build or tests were rerun; the 0.2.0 validation limits below remain unchanged. The published APK and source archive remain the immutable 0.1.2 release.

Before future work, fetch GitHub and reconcile its progress with this folder. Complete user-authorized synchronization by committing and pushing the intended changes, then verify a clean working tree and matching local/remote commits. Preserve unrelated work and report any unresolved divergence.

## Initial 0.2.0 full-app source record (historical; see latest QA above)

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
