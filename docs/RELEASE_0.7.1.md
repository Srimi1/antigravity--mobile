# Antigravity Mobile 0.7.1 (code 14)

Signed bug-fix update for the owner's phone report of 2 October 2026 (Build → Linux on this phone).

Install `antigravity-mobile-0.7.1.apk` over your existing app. Do not uninstall first. Same signing certificate as 0.5.2–0.7.0; worker code 2 and Room v4 are unchanged.

## Owner report

1. Step 2 "Permission to run commands in Termux": tapping **Allow** did nothing.
2. Step 3: pasting the `allow-external-apps` command into Termux and pressing Enter "did nothing"; once Termux asked "Display all 415 possibilities?".
3. Accounts → Antigravity CLI check: "Antigravity Mobile is not allowed to run commands in Termux (RUN_COMMAND permission)".

## Root causes (reproduced on emulators)

- **Allow did nothing.** Installing Antigravity before Termux is *not* the cause: on fresh Android 12 (API 31) and Android 16 (API 36) ARM64 emulators with 0.7.0 installed first and Termux 0.118.3 second, `com.termux.permission.RUN_COMMAND` is registered as a runtime permission and the first Allow shows Android's dialog. After two "Don't allow" taps (or a dismissed dialog) Android marks it `USER_SET|USER_FIXED`; every further `requestPermissions` returns at once without UI. 0.7.0 had no other path, so Allow silently did nothing. Reproduced on API 31: third tap left `MainActivity` resumed with no dialog.
- **Step 3 "nothing happens"** is the command succeeding: it prints nothing. Verified on API 31 by the real path (app **Copy** → **Open Termux** → long-press **Paste** → Enter): new empty prompt, no output. "Display all … possibilities?" is bash completion from the **⇆ (Tab)** key on Termux's extra-keys row, not the pasted text (the pasted line contained no Tab).
- Step 3 could also never update while step 2 was missing, because `allow-external-apps` is only observable through a RUN_COMMAND result, and the panel kept a stale "Unknown" after the first command had already reported "off".
- The CLI error is the same missing permission.

## Fixes

- New `TermuxPermissionStep` rule: Allow shows Android's dialog when Android still can; otherwise it opens Android's App info page with the exact path *Permissions → Additional permissions → Run commands in Termux environment → Allow*. A denial without rationale after the dialog also opens it.
- **Open settings** button and that path shown under step 2; the permission error text names it too.
- The panel re-checks on return to the app (from Settings or Termux), and re-reads step 3 after the first command so "Off in Termux settings"/"Allowed" appear at once.
- Step 3 explains that no output means success and what the Tab prompt means.

## Verification actually run

- Regression `TermuxPermissionStepTest` (6 tests) failed first (unresolved rule), then passed. JVM: 36 classes, 161 tests, 0 failures; release lint passed.
- Android 12 emulator (`emulator-5562`, fresh AVD, debug build): Allow → dialog → Don't allow → Allow → dialog → Don't allow → **App info opened automatically**; Back → Allow (USER_FIXED) → **App info opened** (was: nothing); Permissions → Additional permissions → Run commands in Termux environment → Allow → back to the app → step 2 **Granted** without Refresh; first command → step 3 **Off in Termux settings**; Copy → Open Termux → Paste → Enter → back → step 3 **Allowed**, Debian 12 shows **Install**.
- Signed release 0.7.1: signer `791980edfce3d623dfe1ba2e3209a506b01c4be7da74065805d400a5051210b5`, `versionCode='14' versionName='0.7.1'`. APK 306,250,918 bytes, SHA-256 `eb7b388778dcf14952e21266bf2cc36922364f63bf7991d239824a7a4aa47c90`.
- Android 12 emulator: published 0.7.0 → `adb install -r` 0.7.1 → `Success`, versionCode 14, launch `Status: ok`, five tabs opened, crash buffer 0 lines.

## Still unverified

The OnePlus 7 Pro itself (OxygenOS settings wording may differ slightly), Debian install on the phone, CLI sign-in and inference, and everything listed as pending in `RELEASE_0.7.0.md`.
