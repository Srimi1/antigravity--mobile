# Antigravity Mobile — continuation prompt for any AI

**Current handoff — 4 October 2026:** 0.7.2/code 15 signed CLI/Termux, permission and Google-login update, published as GitHub Latest. Read `docs/PROJECT_CHECKPOINT.md` and `docs/RELEASE_0.7.2.md` for the current validation/artifacts. Accounts opens Google's official Android CLI in Termux; completed sign-in/inference are unverified and the in-app CLI Agent sandbox gates remain closed. Worker code 2, Room v4, original signing key. 166 JVM tests, both release lints and 19 targeted Android checks passed; signed 0.5.2→0.7.2 retained repository files. Separate local save/cache work remains preserved and excluded. The older saved narrative below is historical; its version, foreground-service and blanket Google-route statements are superseded by the checkpoint. Full native phone-only app acceptance remains open.


Saved **1 October 2026** at the user's request. Give this file and the project folder (or a clone of `https://github.com/Srimi1/antigravity--mobile`, branch `main`) to the next AI. It summarises everything done so far and how to continue. It is a development handoff, not a claim that the product is finished.

## 1. Instructions to the next AI

You are continuing the owner's existing **Antigravity Mobile** project: a native Android coding workspace with an AI agent. Do not start over, recreate the artwork, or replace the signing key.

1. Read, in order: this file → `AGENTS.md` (rules and file map) → `docs/PROJECT_CHECKPOINT.md` (newest section first) → `docs/provider-evidence.md` → `README.md`.
2. Run `git status`, `git fetch origin`, and compare `HEAD` with `origin/main`. Preserve any uncommitted work. Never force-push.
3. Talk to the owner directly and honestly. Report what was actually tested, what was only built, and what needs their phone or account. They test on their phone and report back; ship fixes as new GitHub releases.
4. After substantive work: update `docs/PROJECT_CHECKPOINT.md`, commit, push, and (when asked, or for a fix they need on the phone) publish a new GitHub release with the signed APK. Verify the download hash.

## 2. Owner and goal

- Owner: GitHub `Srimi1`. Phone: **OnePlus 7 Pro**, 12 GB RAM, 256 GB storage (exact Android/OxygenOS version not yet reported; app needs Android 10+ ARM64).
- Original goal: "make sure the app is ready… create Android apps, create websites, and do coding work from my phone… everything it can do on Linux, Windows or Mac." Desktop-equivalent coverage is a goal, not achieved.
- The owner installs from **GitHub Releases** (asked for public releases of every version, Latest = newest main app).

## 3. Where things stand (0.5.2, code 11, commit `c4212d1`)

**Works on the owner's phone (owner reports):** app installs/updates in place; ChatGPT sign-in and requests work after 0.4.2 ("the codes are working"); app usable for coding.

**Implemented and emulator-tested, awaiting the owner's phone report:**

| Area | What it does |
| --- | --- |
| Projects | Create, Compose template, Hello Web template, clone, SAF import, ZIP export, file browser/editor, Git panel. |
| GitHub (0.5.0) | Personal access token sign-in (Accounts → GitHub, pre-scoped token link). **From GitHub** repository picker (own repositories, public search). Branch create/switch (including remote-only branches), push with tracking, **Pull request**, **Publish to GitHub**. Live search and clone tested on an emulator; signed-in push/PR not yet tested. |
| Agent | Provider-neutral tool loop: list/read/search/write/delete files (writes need approval with diff), git_status. **0.5.1:** `build_project` (on-phone Gradle build of an approved snapshot, log tail returned so the model can fix errors) and `install_apk` (Android installer). Stop cancels. No general shell. |
| Providers | **ChatGPT** (Sign in with ChatGPT, Responses streaming; all catalog models listed with names). **Gemini** via the owner's Google AI Studio key (0.4.2). **Claude** via the owner's Anthropic API key (0.5.2, official Java SDK, paid per use). The owner picks which one the Agent uses (Accounts → "Use for Agent"). |
| Changes | Change sets with diffs, accept, conflict-aware revert, commit accepted files. |
| Build | Embedded build-tools companion (separate app/UID: Java 17, Gradle 8.13, Android SDK 36). Builds Android projects on the phone, transfers the APK, opens the installer. Tools install/update from the Build tab. |
| Websites (0.4.0) | Static sites: edit, approve a one-use copy, preview in the companion with console, export ZIP. |

**Blocked or not done — keep these labelled honestly:**

- **Claude Pro/Max and Google AI Pro/Ultra subscriptions:** third-party apps may not use those logins (Anthropic needs prior approval; Google prohibits it and suspended accounts that tried in 2026). The owner asked for a "bypass" — **declined; do not implement token lifting, impersonation or proxying.** API-key providers are the approved alternatives.
- No authenticated GitHub push/PR, real Gemini key, or real Claude key tested yet (needs the owner's credentials on the phone).
- Agent tasks run in a ViewModel: a background kill ends them (no foreground service yet). Tool history is not persisted across app restarts.
- Websites are static only (no Node/npm, backend, deployment). Preview WebRTC is only JavaScript-guarded (bypass via `srcdoc` on old WebView).
- Emulator builds were slow in the last run (two Compose builds took 32 minutes in total vs. 3–4 minutes earlier); not investigated (per-build fresh dependency caches are suspected).
- The first-upgrade ANR from 30 Sept (10.4 s) never reproduced since; root cause unknown.
- Redistribution: APKs bundle OpenJDK, Gradle and Android SDK components; `THIRD_PARTY_NOTICES.md` and a GPL source offer exist. The owner chose public releases knowing Google's SDK licence restricts redistribution.

## 4. Version history (all are public GitHub releases with signed APK, SHA-256 and source tag)

| Version | Code | Highlights |
| --- | --- | --- |
| 0.1.0–0.1.2-probe | 1–3 | Diagnostic prototype, original artwork. |
| 0.2.0 | 4 | Five-screen app (Projects, Agent, Changes, Build, Accounts). |
| 0.3.0 | 5 | Embedded on-phone build companion; Compose app built/installed on an emulator. |
| 0.4.0 | 6 | Static website workflow. |
| 0.4.1 | 7 | "Update build tools" label; signed upgrade chain validated. |
| 0.4.2 | 8 | Owner's phone report fixes: sign-in keep-alive service + return link; non-stream ChatGPT replies parsed or explained; Gemini AI Studio key provider. |
| 0.5.0 | 9 | GitHub support; full ChatGPT model catalog. |
| 0.5.1 | 10 | Agent build/install tools. |
| **0.5.2** | **11** | Claude via Anthropic API key (Latest). APK 305,254,448 bytes, SHA-256 `3c80020f653478c062dd46867b44d29963623814c2e69f259c06c8b67c2ca7db`. |

All APKs are signed with the owner's personal certificate, SHA-256 `791980edfce3d623dfe1ba2e3209a506b01c4be7da74065805d400a5051210b5`. The private key is `.signing/personal.p12` on the owner's Mac only. It is git-ignored; never print, upload or commit it. A different key cannot update the installed app.

## 5. Decisions the owner made (do not reverse without asking)

1. Public GitHub releases of every version; newest main app marked **Latest** (despite the licence risk explained to them).
2. Gemini via AI Studio key and Claude via Anthropic API key are approved exceptions to "subscriptions only". Both are user-selected, never automatic fallbacks. Claude is labelled paid per use.
3. No Google/Claude subscription bypass.
4. GitHub access uses a personal access token (no OAuth app registered).

## 6. Key code (all under `app/src/main/java/dev/srimi/antigravitymobile/`)

- `AgentLoop.kt` (orchestrator, `ToolHost`, `WorkspaceTools`), `AgentBuildTools.kt` (`build_project`, `install_apk`, `BuildRunner`/`PhoneBuildRunner`), `AgentViewModel.kt`.
- Providers: `ChatGptProbeAdapter.kt` + `ResponsesStream.kt` (`ResponsesWire.unlabelled/describe/catalog`), `GeminiAdapter.kt` (`GeminiWire`, `GeminiStreamParser`), `ClaudeAdapter.kt` (`ClaudeEngine`, `ClaudeWire`; SDK `com.anthropic:anthropic-java:2.34.0`), `Providers.kt`, `SignInKeepAlive.kt`.
- GitHub/Git: `GitHubService.kt`, `GitHubUi.kt`, `GitService.kt` (JGit).
- Build: `BuildCoordinator.kt`, `BuildWorkerClient.kt`, `BuildSnapshot.kt`, `build-worker/` module, `runtime-contract/`.
- Websites: `WebsiteService.kt`, `WebsitePanel.kt`, `runtime-contract/.../WebFiles.kt`, `WebGuard.kt`, worker `WebPreviewActivity.kt`.
- Accounts UI: `AccountsScreen.kt`, `ReviewViewModels.kt` (`AccountsViewModel`). Storage: `CredentialStore.kt` (Keystore), Room `SessionStore.kt` (schema v3).

## 7. Build, test and release (owner's Mac)

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/Users/srimi/Library/Android/sdk
cd "/Users/srimi/Library/Mobile Documents/com~apple~CloudDocs/Antigravity--Mobile"
./tools/build.sh   # JVM tests, release lint, signed dist/antigravity-mobile-<version>.apk + dist/SHA256SUMS
```

- Bump `versionCode`/`versionName` in `app/build.gradle.kts` for each release (never reuse a code). The companion stays 0.4.0-tools/code 2 unless worker code changes.
- Device tests: boot `AntigravityMobileQA_API36` (disposable), then `./gradlew --project-cache-dir "$HOME/.cache/antigravity-mobile-gradle" :build-worker:installDebug :app:connectedDebugAndroidTest`. Last full run: **21/21** (0.5.1; includes a real on-device Compose build through the agent runner, which takes a long time).
- Owner-like emulator: AVD `OnePlus7ProSim_API31` (Android 12, 1440×3120, 6 GB). UI helper scripts are in `~/.cache/antigravity-mobile-runtime/evidence/*/qa.py`.
- Release: copy the APK out of iCloud first (uploads from iCloud timed out once), then `gh release create vX.Y.Z -R Srimi1/antigravity--mobile --target <sha> --latest <apk> --notes-file …`, and verify `releases/latest/download/<apk>` hashes.
- Latest JVM count: **78 tests, 0 failures** (0.5.2).
- The hook in this environment blocks commands containing `git commit` together with `-n`-like flags (for example `grep -n` in the same command). Run secret scans separately.

## 8. Environment notes

- Between 03:15 and 14:24 IST on 1 Oct, something outside these sessions deleted `~/.android` and `~/.gradle` parts. The release-test AVD `AntigravityMobileProbe_API31`, `AntigravityMobileQA_API31`, the debug keystore and adb keys were lost. The personal signing key, SDK and `~/.cache` AVDs survived; AVD pointer files were recreated. Advise the owner to exclude `~/.android` and `~/.gradle` from cleaners.
- Build outputs live in `~/.cache/antigravity-mobile-build` (outside iCloud). Raw evidence lives in `~/.cache/antigravity-mobile-runtime/evidence/`; curated evidence is in `assets/screenshots/*-2026100*`.
- Google Drive upload of 300 MB APKs is not possible from this machine (no Drive client; the connector only takes inline data).

## 9. Suggested next steps (in order)

1. Collect the owner's phone results for 0.5.0–0.5.2 (`docs/PHONE_TEST_0.5.*.md`): GitHub push/PR, agent build/install, Gemini and Claude keys. Fix what fails and release.
2. Keep agent tasks alive in a foreground service during long builds; persist tool history across restarts.
3. Speed up on-phone builds (shared dependency cache between builds, with preservation rules).
4. Storage management for build caches, APKs and preview/approval records.
5. A WebRTC-free preview (separate package without INTERNET); Node/frontend build support later.
6. Re-check provider policies periodically. Implement a subscription route only if an official, approved one appears.

Full acceptance still requires real coding tasks on the phone across the owner's providers, plus the phone alone editing/testing/committing/pushing a real repository and building/installing apps, with recoverable failures.
