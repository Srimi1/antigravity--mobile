# Antigravity Mobile 0.7.2 (code 15)

Signed update prepared on 4 October 2026 for the owner's report: CLI/Termux not opening, Allow doing nothing, and Gemini login. Publication is explicitly authorized by the owner's request to launch the update. Release: [v0.7.2](https://github.com/Srimi1/antigravity--mobile/releases/tag/v0.7.2).

## Changes

- Includes the 0.7.1 permission recovery fix: Allow requests Android's permission dialog, or opens App info when Android has stopped asking. The same controls are now beside Google sign-in in Accounts. They remain usable while a runtime refresh is pending.
- Open Termux reports launch failures and uses an explicit Activity fallback. Interactive RUN_COMMAND dispatch brings Termux to the foreground; starting its service alone does not reliably open a terminal on Android 10+. Interactive sessions have no result PendingIntent: CLI output, OAuth codes and account credentials stay inside the official client.
- Debian terminal and CLI actions first check command access, the container and the selected executable. Failed checks never report that a terminal opened. CLI paths/PATH are explicit; Codex's Open CLI starts its documented device-auth login. No arbitrary shell tool is added to Agent chat.
- Accounts now has **Gemini with Google sign-in → Sign in with Google**. This opens Google's own Android/Bionic Antigravity CLI in Termux, without needing Debian or a desktop keyring. First launch runs Google's HTTPS installer in an interactive terminal, checks the installer-selected upstream SHA-512, and stores the executable only under `~/.agm/google-cli/bin/agy`. Existing installs are reopened without redownloading or overwriting them. Google OAuth and the browser callback are handled entirely by Google's CLI.
- Copy setup and open Termux copies the required `allow-external-apps` command. It prints **Done. Return to Antigravity Mobile.** after settings reload, replacing the previously silent success.
- Termux error bundles are rejected even when they include an exit code of 0.

The Google login action does not connect the native AI Studio-key provider or unlock the Debian CLI Agent backend. The Codex/Antigravity Agent sandbox gates remain unchanged. No API billing fallback, token extraction, root access, desktop remote control or cloud build was added.

## Validation

- Prepared pinned runtime inputs with `tools/android-runtime-lab/prepare.py`.
- Isolated Gradle build: **166 JVM tests in 37 classes, 0 failures/errors/skips**; both release lints passed (app 62 warnings, worker 15 warnings, 0 errors/fatal issues); release, debug and instrumentation APKs built, **BUILD SUCCESSFUL in 4m 18s**, 227 tasks executed.
- **19 targeted API 36 instrumentation tests passed**: TerminalLaunch 3, real Termux ready 1, RuntimeStore 4, CliRuntime 11. New checks cover foreground Activity dispatch without login-output capture, failed Debian/CLI preflight, explicit CLI paths and environment. RuntimeStore verifies v3→v4 preservation. Tests completed at 11:16–11:17 IST; the shared QA app was separately replaced at 11:19, after these runs. This is a targeted suite, not the entire connected test suite.
- Android 12 ARM64 signed **0.5.2/code 11 → 0.7.2/code 15** in-place install returned Success. Existing Spoon-Knife project, `index.html` (355 B), `README.md` (780 B), and `styles.css` (256 B) remained visible. Launch returned Status: ok. No Antigravity crash entry was observed.
- Signed UI: Allow → first denial → Allow → second denial automatically opened App info; another Allow with USER_FIXED again opened App info. Permissions → Additional permissions → Run commands in Termux environment → Allow → return automatically removed the permission controls. Copy setup and open Termux opened its Activity; long-press Paste + Enter ran the actual copied setup line; return enabled Google sign-in without Refresh.
- Signed Google button opened the real official **agy 1.2.16 Android ARM64** Google OAuth menu. Selecting Google OAuth opened Chrome at Google's sign-in page for Google Antigravity. A first native-client probe used a host-verified upstream binary; the subsequent first-install UI run downloaded and installed it in Termux through the app's actual installer command. No account credentials, OAuth codes, entitled inference or paid request were supplied.
- An earlier Chrome first-run attempt displayed a Chrome ANR while host builds and two emulators were active. Chrome was stopped; no root cause or physical-phone browser stability is claimed.

## Signed artifacts

Application ID remains `dev.srimi.antigravitymobile.probe`. Room remains v4. Release companion remains **code 2 / 0.4.0-tools**. The unrelated local build-cache/worker-code-3 work is not included in this isolated release.

- Main APK: **306,282,142 bytes**, SHA-256 `8f3d9bc3aea5ed557cda836e4022e03683c19046769fdb63dcc274156cc48796`.
- Companion: **261,989,704 bytes**, SHA-256 `da9788f32491923693ec6d7ea665b5de419238cb7067a3afb27707105368f4b1`.
- Original certificate SHA-256: `791980edfce3d623dfe1ba2e3209a506b01c4be7da74065805d400a5051210b5`. The embedded signed companion matches the standalone APK byte for byte. No replacement key was generated.
- Local artifacts and logs: `~/.cache/antigravity-cli-fix-20261004/`. Recovery snapshot preserves the initial source/index/patches and iCloud's malformed duplicate remote ref; the Git index was restored to the existing HEAD without altering user files, then origin fetch succeeded.

## Still open

Physical OnePlus upgrade and settings wording; completed Google sign-in, renewal, logout and real Gemini coding response; signed-in CLI Agent protocol and confinement; the earlier real-Termux candidate restart suite; phone-only Git/build/install/launch acceptance. Claude subscription integration remains blocked. Full-product acceptance remains open.

## Owner test

[PHONE_TEST_0.7.2.md](PHONE_TEST_0.7.2.md). Install the signed APK as an update, grant command access, finish the copied setup command in Termux, and use Accounts → Gemini with Google sign-in. Do not uninstall the existing app or send credentials/codes in a report.
