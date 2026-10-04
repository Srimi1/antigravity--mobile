# Antigravity Mobile — saved checkpoint

Last updated **4 October 2026** (0.7.2/code 15 is GitHub Latest; 0.7.3/code 16 signed candidate built and emulator-update tested, not published; physical phone and live CLI acceptance still open).

**For the next agent:** start with the repository-root [AGENTS.md](../AGENTS.md). It maps the current implementation, explains the user's full-app request, and gives continuation, validation and signing guidance. This checkpoint records the completed work; AGENTS.md explains how to resume it.

## 0.7.3/code 16 signed candidate — 4 October 2026 (built, not published)

Contents: the five security audit fixes below + build-cache reuse (worker code 3, `0.7.3-tools`, `MIN_WORKER_VERSION` 3). Built with `./tools/build.sh` using the original `.signing/personal.p12`.

- `dist/antigravity-mobile-0.7.3.apk` — 306,303,310 bytes, SHA-256 `78306bb4e09436d3375e4a4531a01113d7438d4d9590c99c85dbce453e7c4188`, `dev.srimi.antigravitymobile.probe` code 16, APK v2 signature.
- `dist/antigravity-mobile-build-tools-0.7.3-tools.apk` — 261,997,548 bytes, SHA-256 `0adb5aeb202f6cee1bde32cecb8a3c88febf582df70f80e554addc5f9cacf32d`, worker code 3; byte-identical to the embedded `assets/build-worker.apk`.
- Signer certificate SHA-256 `791980edfce3d623dfe1ba2e3209a506b01c4be7da74065805d400a5051210b5` for both, identical to published v0.7.2, so it installs as an update.
- Passed: 183 JVM tests, `:app:lintRelease`, `:build-worker:lintRelease`, apksigner verify.
- **Signed emulator update (OnePlus7ProSim_API31, Android 12 ARM64):** installed signed 0.7.2/code 15 + worker code 2 with the Spoon-Knife project → `adb install -r` 0.7.3 succeeded (firstInstallTime kept), project and its three files intact, no crash. Build tab showed "Tools update needed / Update build tools"; tapping it opened Android's chooser (Termux also offers to open APKs — pick Package installer), "Do you want to update this app?" → Update → worker code 3 `0.7.3-tools` installed, Build tab "Tools installed". Evidence: `~/.cache/agm-073-upgrade/`. That run used a candidate differing only in one Build-tab sentence (stale "each build has a fresh cache" text, then corrected); the final APK was installed over it and relaunched with project intact. No on-device build was run.
- Not run: instrumentation, physical phone, live accounts.
- Committed and pushed to main (no GitHub release created). `dist/` is ignored by Git.

## Security audit fixes (4 October 2026, committed, unreleased)

External audit of `056a302` reported five findings; all five were still present at `cfe046a` and are now fixed in source:

1. **Medium — GitHub token sent to any Git host.** `GitService` now uses `GitHubScopedCredentials`: the token is supplied only when JGit requests credentials for `https://github.com` (port 443), checked per request on the current post-redirect URI. Clone URLs and effective fetch/push URLs (incl. `pushurl`, `insteadOf`) are pre-checked; a non-GitHub remote gets no credentials and a "Saved GitHub token not sent" progress message. Pull now uses the branch's configured remote. Accounts text states the actual scope.
2. **Snapshot collision** (`.A.kt.tmp` + `A.kt` destroyed a backup): `ChangeService.store` writes temp files to `<set>/scratch/` with unique names.
3. **Heap exhaustion:** `BuildInspector` reads at most 256 KB of each `build.gradle*`; agent write/delete previews refuse existing files over `MAX_FILE` (400 KB) before reading.
4. **Symlink to `.git`:** agent paths are also checked on the canonical target (`WorkspaceService.resolvedPath`).
5. **OAuth loopback DoS:** `LoopbackRequest.readLine` caps the request line at 8 KB and each client at a 5 s absolute deadline; a bad client is dropped without ending sign-in; accept timeout now gives the proper "Sign-in timed out" message.

Validation: 183 JVM tests pass (8 new regression tests in AgentLoopTest, ChangeServiceTest, GitServiceTest, new InputBoundsTest) and `:app:lintRelease` passed. Not run: device instrumentation, live GitHub clone/push, real sign-in. JGit may still carry an already-negotiated Authorization header across a cross-host redirect inside one GitHub session; not observed, not tested. Remaining audit correctness items (project-switch races, non-atomic save/revert, interrupted-write diff loss, Linux cleanup vs active tasks) are open. Source is now 0.7.3/code 16 (see candidate above).

## Build cache reuse (4 October 2026, committed, unreleased)

Worker used `build-caches/<buildId>` for `--gradle-user-home`: every build re-downloaded and re-transformed all dependencies, and the directories were never deleted. Now `runtime-contract/.../BuildCache.kt` gives each **project** one reusable Gradle user home (`build-caches/projects/<sha256(projectId)[0..32]>`), sent by the client as optional `cacheKey` in `START`. Caches are never shared between projects (script poisoning), at most 3 are kept (LRU), a cache whose last build did not end with a Gradle exit code (cancel, timeout, worker death — `.agm-building` marker) is wiped instead of reused, legacy per-build directories are deleted, and the build record/detail says "dependency cache reused". Worker code 2→3 (`0.7.3-tools`), `MIN_WORKER_VERSION` 3, so existing users get the Build-tab "Update build tools" prompt. `BuildCacheTest` (8 JVM tests) passes. **Emulator measurement (API 36 ARM64, 4 CPUs, 2.5 GB, debug build, `BuildCacheDeviceTest` OK, 262 s):** same Compose project, `:app:assembleDebug`, first build 230.8 s (cache fresh) → second build with changed source 25.8 s (cache reused), ~9× faster; an earlier identical run gave 226.7 s → 24.0 s. First build with worker code 3 also deleted 1.9 GB of legacy per-build caches (build-caches went from 1.9 GB to ~1.0 GB). Not a physical-phone figure; the full JVM suite and lint were not rerun after this change. Project work dirs (`projects/<id>`, with `build/` outputs) are still never pruned and `.gradle`/`build` are not reused (incremental compile) — next steps, plus a storage screen with "Clear build cache". Another agent had uncommitted 0.7.2 Linux/Termux edits in the same tree (app versionCode 15/0.7.2); those were not touched.

## Current update: 0.7.2/code 15 — 4 October 2026

The owner requested CLI/Termux opening fixes, a working Allow button, Gemini login and a launched update. [Release record](RELEASE_0.7.2.md) records the signed APK, hashes, validation and limitations; [phone checklist](PHONE_TEST_0.7.2.md) gives the exact next steps.

- Includes the unpublished 0.7.1 permission fix; foreground terminal Activity launch and preflight errors; CLI sign-in actions; a prominent Google sign-in action using the official Android/Bionic `agy` inside Termux without Debian. This is an explicit official-client launch, not a native subscription adapter or credential transfer. Agent sandbox gates remain closed.
- Original signer, application ID, worker code 2 and Room v4 retained. Signed 0.5.2→0.7.2 emulator update retained the Spoon-Knife repository and its files. 166 JVM tests, both release lints and 19 targeted Android checks passed. Actual signed UI reproduced and fixed USER_FIXED permission recovery and opened the Google OAuth menu; live account completion and inference remain unverified.
- Built in an isolated managed worktree to preserve concurrent build-cache/worker-code-3 edits and local save documents. Those edits are not part of this APK. Recovery: `~/.cache/antigravity-cli-fix-20261004/recovery/`; build/signing verification and UI evidence under its parent. Private signing key stayed local and excluded.
- Main APK SHA-256 `8f3d9bc3aea5ed557cda836e4022e03683c19046769fdb63dcc274156cc48796` (306,282,142 bytes). Companion SHA-256 `da9788f32491923693ec6d7ea665b5de419238cb7067a3afb27707105368f4b1` (261,989,704 bytes); embedded copy matches.
- Full product remains unaccepted: phone results, actual Google/Claude/ChatGPT subscription acceptance, confinement, and phone-only repository/build/install/launch tasks remain open. No credentials or paid requests were used.

Published [v0.7.2](https://github.com/Srimi1/antigravity--mobile/releases/tag/v0.7.2) at `2026-10-04T06:01:27Z`, public/non-draft/non-prerelease and GitHub Latest. Release tag/source is `c2f7a516677418e5a2f3343157c8abc0603906e0`. All five uploaded asset sizes and GitHub SHA-256 digests match the isolated local release files. Public main APK HEAD returned HTTP 200 and Content-Length 306282142. Source ZIP: 18,586,554 bytes, SHA-256 `ab6bdcaa1b04f9675dfc7d857ad07d2d858a950f70e8a9111906bfb71bf8d4d0`; its 556 files passed ZIP integrity and signing-key/local-config/build-binary exclusion checks. The immutable source archive reflects the preparation commit; later publication documentation does not change the APK. Earlier releases remain intact.

Next: the owner installs the APK as an update and follows PHONE_TEST_0.7.2.md. Do not replay commands, regenerate the key, overwrite separate cache work, or call the full app complete.

## Earlier published: 0.7.0/code 13 — 2 October 2026

The owner requested fixes for the reviewed bugs and a new app version, authorizing the provider/Linux fixes along with bridge work. They then pushed the source and explicitly requested the new APK and release on GitHub. [v0.7.0](https://github.com/Srimi1/antigravity--mobile/releases/tag/v0.7.0) is now published as Latest. Release evidence: [RELEASE_0.7.0.md](RELEASE_0.7.0.md); earlier unsigned preparation: [RELEASE_CANDIDATE_0.7.0.md](RELEASE_CANDIDATE_0.7.0.md). Gate 0.7.0 and full-product acceptance are not complete.

Implementation commit `cd2222407b5703164e161ad6a51d558c7e6d44c4`; release tag, local main and GitHub main at publication all matched `8684db8535a5062d134001e5a8f11cd7775906f3`. Backup before publication: `~/dev/agm-before-0.7.0-publication-20261002.bundle`, verified. Git dataless-file check was 0; free disk stayed above 57 GiB. Later documentation commits do not change this APK's source commit.

- Fixed `native_request` delivery, repeated upload preparation after a lost checkpoint, helper crash after workspace rename, missing base `python3`, misleading failed/interrupted Linux status, inherited custom-endpoint tool verification (including a concurrent probe), and stale model catalogs after endpoint changes.
- Worker stays code 2; Room stays v4. Both release APKs verified against the original signing certificate `791980edfce3d623dfe1ba2e3209a506b01c4be7da74065805d400a5051210b5`; the embedded signed worker matches the standalone companion byte for byte. The private key remains local and ignored. Unsigned-release mode remains available for future preparation.
- Fresh publication validation: 35 JVM classes/155 tests/0 failures, errors or skips; both release lints, signed release build `BUILD SUCCESSFUL in 2m 1s` (143 tasks); Python bridge `Ran 31 tests in 28.850s`, `OK`; Linux helper `passed=22 failed=0`. Lint has 0 errors/fatal issues, existing warnings app 61/worker 15. Existing Python BufferedReader ResourceWarning remains.
- Emulator-5554 release update: `adb install -r` 0.6.0→0.7.0 returned `Success`, the existing Hello Phone project stayed visible, launch returned `Status: ok`, and the current app PID had no crash entry. Database path was **v4→v4, no schema migration**. This is separate from the pending isolated QA rerun below.
- Main APK: 306,239,446 bytes, SHA-256 `c21c6d1561aaec663421f02ca67897cd4b68934b684c63e037053d93fab9564e`. Worker: 261,978,324 bytes, SHA-256 `d9397d543af07e5789bac5209e07d588c47b655a9eea0c8f6406cc9c428a3b64`. Both APKs, source ZIP, checksums and `artifacts.json` are public release assets; GitHub sizes/digests match local files. Public APK HEAD returned HTTP 200 and Content-Length 306239446. Local signed artifacts and logs: `~/dev/agm-0.7.0-release/`.
- Emulator QA: 26 checks completed across CliRuntime/NativeRuntime/TermuxBridge/RuntimeStore/FullApp. The combined suite did not pass: real-Termux startup tests encountered a temporary QA launcher/client root mismatch. Production roots match. Correcting QA copies and rerunning awaits owner approval under the repeated-failure stop rule. The actual Codex probe reports `aarch64`, `codex-cli 0.159.3`, sandbox `unavailable`.
- Google login guidance now points to the official `agy` client ([guide](GEMINI_LOGIN.md)). CLI capability gates remain closed unless a device sandbox probe passes. No credentials or live inference were used.
- **Unverified:** physical 0.6.0→0.7.0 upgrade with real data, approval bug trace, signed-in CLI/protocol behavior, game build/install/play and phone-only Git acceptance. No phone is listed by ADB. The owner's publication request authorized original-key signing and publishing without those physical checks; it is not acceptance evidence.

**Next:** install the [signed 0.7.0 APK](https://github.com/Srimi1/antigravity--mobile/releases/download/v0.7.0/antigravity-mobile-0.7.0.apk) as an update and collect [phone results](PHONE_TEST_0.7.0.md). Obtain the pending QA approval before correcting temporary setup and finishing the isolated real-Termux rerun. Physical gates remain open; do not advance to 0.8.0 on local tests alone.

## Earlier published: 0.6.0 — reliable approvals, keyed builds, CLI bridge groundwork — 2 October 2026

- **0.6.0/code 12** from `lane-a/runtime` (Lanes A1/A2 plus Lane B's integrated providers and Linux setup already on `main`): persisted keyed approvals (one approval → one build), separate build/install prompts, foreground `AgentTaskService`, Paused card with reason and **Retry this provider**, recorded build outcomes/logs/APK hashes, Room v4 (`MIGRATION_3_4`), durable CLI runner/event journal and Termux pairing. Codex and Antigravity CLI backends stay **disabled** by the per-device capability gate.
- **Tests:** JVM 34 classes/150 tests, 0 failures; release lint; Python bridge 29 OK. Release APK signed with the original key (SHA-256 cert `791980ed…10b5`, same as 0.5.2), bundled worker code 2 same signer. `antigravity-mobile-0.6.0.apk` 306,233,310 bytes, SHA-256 `912dbce38349b5fd8f892b4f0120ac5e96df1de61e30a6f9cfc2b7403ac06b08`.
- **Upgrade on emulator-5554:** published release 0.5.2 installed, a project created (Room v3), then `adb install -r` 0.6.0 → versionCode 12, project kept, all five tabs open, crash buffer empty.
- **Not verified:** physical OnePlus 7 Pro (owner tests without ADB using [PHONE_TEST_0.6.0.md](PHONE_TEST_0.6.0.md)); the original "approved but reported as declined" trace on the phone; CLI sign-in/inference; game acceptance. Owner authorized building and publishing this APK on 2 Oct 2026 without the phone gate.

The pending phone checks continue in [PHONE_TEST_0.7.0.md](PHONE_TEST_0.7.0.md). The candidate now includes `python3`; its physical gate and Gate 0.8.0 remain open.

## Handoff saved — 1 October 2026

At the owner's request the full context was saved for another AI in [CONTINUE_WITH_ANY_AI.md](../CONTINUE_WITH_ANY_AI.md): state, version history, owner decisions, code map, build/release procedure, environment incident and next steps. `AGENTS.md` and `HANDOFF_STATE.json` were refreshed to 0.5.2. No code changed.

## Earlier: 0.5.2 — Claude via the user's Anthropic API key — 1 October 2026

User asked why there was no Claude API key option. Explained that an Anthropic API key is billed per use and is separate from a Claude Pro/Max subscription; the user chose to add it.

- **0.5.2/code 11:** `ClaudeAdapter` (Keystore-encrypted key, model choice, test request, account state) over `ClaudeEngine`, built on the **official `com.anthropic:anthropic-java:2.34.0` SDK** (beta Messages streaming via `BetaMessageAccumulator`; client tools from our JSON schemas; assistant turns replayed verbatim with `BetaContentBlock.toParam()` so thinking signatures survive; server-side refusal fallback `fallbacks: "default"` with `server-side-fallback-2026-07-01`; effort `medium`; default model `claude-opus-5-5`; refusal and `max_tokens` stops reported; errors reduced to Anthropic's message with keys redacted). Accounts shows a red per-use cost warning; Agent can select Claude. The Claude Pro/Max subscription card stays BLOCKED. AGENTS.md boundary updated for the approved exception.
- **Tests:** `./tools/build.sh` passed — **78 JVM tests** (4 in `ClaudeEngineTest` with OkHttp MockWebServer speaking the Messages SSE format: text deltas, tool call, thinking + signature replayed unchanged with tool_result in the next request, request headers/body including fallback beta and effort, refusal, 401 without key leakage, model ordering), release lint, signed `dist/antigravity-mobile-0.5.2.apk`, 305,254,448 bytes, SHA-256 `3c80020f653478c062dd46867b44d29963623814c2e69f259c06c8b67c2ca7db` (SDK adds ~18 MB). On the OnePlus-like emulator, 0.5.1→0.5.2 installed, the Claude card rendered, and a fake key reached Anthropic through the SDK on Android and showed `HTTP 401 — API key is invalid` (before the message clean-up). The device instrumentation suite was not rerun for 0.5.2 (no device-side code paths changed beyond the new provider). **No real key or paid request was used.**
- First test-run attempt was interrupted when the host app quit; the SDK's model auto-pager also hung against the mock server, so listing now uses one page with `limit=1000`.

**Next:** user runs [PHONE_TEST_0.5.2.md](PHONE_TEST_0.5.2.md) with their own key.

## Earlier: 0.5.1 — Agent can build and install on the phone — 1 October 2026

User reported that the Agent said it could not build or install the APK because "the environment has no build or device tools" (it only had file tools and its instructions said so).

- **0.5.1/code 10:** `AgentBuildTools` adds two approval-gated tools on top of the file tools. `build_project` prepares an immutable snapshot during the approval preview (tasks + SHA-256 shown); the user's approval is the one-shot claim; it runs in the companion worker, waits for the final state (cancelling the agent cancels the build), and returns status, duration, APK names and the last 6,000 characters of the log so the model can fix errors. Declining releases the prepared build. `install_apk` opens Android's installer for an APK from the latest successful build in the task. Agent instructions updated: no general shell, never claim unexecuted results. `ToolHost.declined` hook added. `BuildRunner` interface/`PhoneBuildRunner` keep it testable.
- **Tests:** `./tools/build.sh` passed — **74 JVM tests** (4 in `AgentBuildToolsTest` through the real orchestrator: approve→build→install, decline releases and never runs, failed build returns the log and blocks install, missing tools/invalid tasks refused before approval), release lint, signed `dist/antigravity-mobile-0.5.1.apk`, SHA-256 `e97ffd0d565ba259c98e4df47d01869bd6c9c2f1add32bfaf38904d194b18bdf`. **21/21 device tests on API 36**, including new `AgentBuildDeviceTest`: the real `PhoneBuildRunner` built the Compose template (COMPLETED, APK, "BUILD SUCCESSFUL" in the returned log), then a deliberately broken source build returned FAILED with the `notDefinedAnywhere` compiler error and no APK. Those two builds took 1,941 s together on this emulator, much slower than earlier 188–231 s runs; not investigated (fresh dependency caches per build are a likely factor).
- No live model drove these tools yet (needs the user's account on the phone).

**Next:** user runs [PHONE_TEST_0.5.1.md](PHONE_TEST_0.5.1.md); investigate build time and dependency caching; keep agent tasks alive in a foreground service during long builds.

## Earlier: 0.5.0 — GitHub support and full ChatGPT model catalog — 1 October 2026

User's phone report on 0.4.2: "the codes are working", ready to code; asked for GitHub support, for all ChatGPT models (named "Soul 6.16", "Luna 6") and for a bypass to use the Google AI subscription. **The bypass was declined:** it would breach Google's terms and risk suspension of the user's Google account. The Gemini AI Studio key route remains.

- **GitHub (0.5.0/code 9):** `GitHubService`/`GitHubWire` (REST API with the user's personal access token, Keystore-encrypted in the existing Git credential store, verified via `/user`); Accounts GitHub card with a pre-scoped token link (`repo`, `workflow`); Projects **From GitHub** picker (own repositories, or anonymous public search) → one-tap clone; Git tab **Branch** dialog (switch, create, check out remote-only branches with tracking), push of the current branch with upstream tracking, **Pull request** (pushes, opens a PR into the default branch, link to it), **Publish to GitHub** (creates a repository and pushes), **Open on GitHub**. `GitService.branches/checkout/setRemote` added; push now targets the current branch explicitly.
- **ChatGPT models:** Accounts lists every model OpenAI's catalog returns, with display names; ones not marked `visibility: list` are flagged "may be refused" and can be selected. Models OpenAI does not return cannot be added.
- **Tests:** `./tools/build.sh` passed — **70 JVM tests** (5 new in `GitHubSupportTest`: remote parsing, token redaction, catalog, real JGit branch/push/tracking/remote-branch checkout against a bare repository), release lint, signed `dist/antigravity-mobile-0.5.0.apk`, SHA-256 `ac991edd5a86f7259452c9701727a841ac7227e5f3d0fc2532891865ff89cefd`. API 36 device suite **20/20**.
- **Live emulator checks (OnePlus-like AVD, real network):** 0.4.2→0.5.0 kept data; anonymous GitHub search found `octocat/Spoon-Knife`; **real HTTPS clone on Android** succeeded (first device clone evidence); Git tab showed the GitHub repository, remote branches and created/switched to `phone-edit`; pull ran; PR without sign-in showed "Sign in to GitHub in Accounts first"; a fake token got GitHub's real `HTTP 401: Bad credentials`. **No authenticated push/PR/publish was run** (that needs the user's own token; not used by the developer).

**Next:** user follows [PHONE_TEST_0.5.0.md](PHONE_TEST_0.5.0.md) with their token on the phone and reports push/PR results.

## Earlier: 0.4.2 — phone-report fixes and Gemini key provider — 1 October 2026

User's **physical OnePlus 7 Pro** report on 0.4.1 (outcomes in [provider-evidence.md](provider-evidence.md)): ChatGPT sign-in page hung until returning to the app; every ChatGPT request failed "Expected streaming response"; Google unavailable.

- **0.4.2/code 8:** sign-in foreground service `SignInKeepAlive` + HTML callback page with a `dev.srimi.antigravitymobile://signed-in` return link (MainActivity now `singleTask`); `ResponsesWire.unlabelled/describe` parse or explain non-event-stream replies (secrets redacted); new **Gemini provider (Google AI Studio API key, user-approved change to the earlier no-API-key rule)** — `GeminiAdapter`/`GeminiWire`/`GeminiStreamParser`, native `streamGenerateContent?alt=sse`, function calling with `parametersJsonSchema`, thought-signature replay via Opaque items; Accounts lets the user pick which provider the Agent uses; Google subscription card explains Google's prohibition.
- **Tests:** `./tools/build.sh` passed — **65 JVM tests** (9 new in `ProviderReplyTest`), release lint, signed `dist/antigravity-mobile-0.4.2.apk` **286,770,505 bytes, SHA-256 `db8ce8083c978e3004f6148d8a9392b8876bfa15b237e89eddb8a53c3d5d242c`**. API 36 device suite: 19/20 on the first run after reinstall (same intermittent website-preview timeout as before, now even at 60 s), then **20/20 twice**, including a fresh-install rerun; the test now reports the visible screen when it times out.
- **Emulator checks (OnePlus-like AVD):** 0.4.1→0.4.2 kept data; keep-alive service ran during sign-in; with the app backgrounded 30 s the loopback server answered instantly (process at perceptible priority); Stop ended the service; the return link reopened the existing task; a fake Gemini key reached Google and showed `HTTP 400 INVALID_ARGUMENT — API key not valid`. The emulator does not reproduce OxygenOS's freezing, and no real ChatGPT or Gemini request has completed.
- **Environment incident:** between ~03:15 and 14:24 IST something outside this session deleted `~/.android` and `~/.gradle` (~14 GB). Lost: AVDs `AntigravityMobileProbe_API31` (preserved release-upgrade data) and `AntigravityMobileQA_API31`, the Android debug keystore and adb keys. `.signing/personal.p12`, the SDK, `~/.cache` AVDs and evidence survived. AVD pointer files for `OnePlus7ProSim_API31` and `AntigravityMobileQA_API36` were recreated (non-destructive); debug test packages on the disposable API 36 AVD were reinstalled with the new debug key.

**Next:** user runs [PHONE_TEST_0.4.2.md](PHONE_TEST_0.4.2.md) and sends the exact ChatGPT error text if it still fails, plus Gemini results.

## Earlier: all versions published on GitHub — 1 October 2026

At the user's explicit request (after being told about the licence risk of redistributing the bundled OpenJDK/Android SDK build components), every built version was published as a **public GitHub release** with its signed APK, SHA-256 and source tag: v0.1.0-probe, v0.1.1-probe (nearest source tag 75ffc8c; their exact build source was not committed), v0.1.2 (existing, unchanged), v0.2.0 (0f43adf), v0.3.0 (29d0d11), v0.4.0 (cda8bcc) and **v0.4.1 marked Latest** (f61f7d1). Added [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) with licences, upstream sources and a GPL source offer. All APKs are signed with certificate `791980ed…10b5`. Releases state plainly that the full app is not ready. Google Drive upload was not possible from this machine.

## Earlier: signed upgrade, companion update and phone-like simulation — 1 October 2026

See [upgrade QA](upgrade-qa-2026-10-01.md), [phone test guide](PHONE_TEST_0.4.1.md) and `assets/screenshots/upgrade-20261001/`.

- **Release emulator `AntigravityMobileProbe_API31`:** signed 0.2.0 → 0.3.0 → 0.4.0 → 0.4.1 in place; every first launch 448–591 ms with no ANR; projects and a 0.3.0 marker file preserved. Companion code 1 installed via Build tab, then **updated 1→2 via Build tab** (same signer). Release-signed website preview and a **native Compose build (231 s) → install → Count 0→1** after the upgrades. The original 10,410 ms first-upgrade ANR did not reproduce; root cause still unknown (one trace captured, not analysed).
- **0.4.1/code 7:** Build shows "Tools update needed / Update build tools" for older same-signer tools. `./tools/build.sh` passed (56 JVM, release lint, signed `dist/antigravity-mobile-0.4.1.apk`, 286,711,261 bytes, SHA-256 `d4144955ede813a745de0177f2d5a9b374a107a0030b4b4ac55845ea323b55b7`). API 36 device suite 19/20 on first run after cold boot (one 20 s UI timeout, unexplained), **20/20** after raising the test wait to 60 s.
- **OnePlus-like AVD `OnePlus7ProSim_API31`** (Android 12, 1440×3120/560 dpi, 6 GB, generic Google image — not OxygenOS/Snapdragon): public 0.1.2 → 0.4.1 through Files + Android installer (654 ms first launch); tools update 1→2 via new label; **Compose build 188 s → install → Count 0→1**; website preview OK. Min available memory 2.5 GB; daemon RSS 1.56 GB.
- **Distribution (superseded same day):** 0.4.1 first uploaded only as **GitHub draft release `v0.4.1`** (user chose private draft over public on 1 Oct 2026; Drive upload not possible from this machine — no Drive sync client/CLI, connector cannot carry 287 MB) (visible to repository owner/collaborators, not public), because the APK bundles OpenJDK/Gradle/Android SDK build components whose public redistribution/notice obligations are still unresolved. Public v0.1.2 release and Drive file unchanged.
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
