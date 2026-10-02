# Physical checks for 0.7.0

0.7.0/code 13 is [published with the original signer](https://github.com/Srimi1/antigravity--mobile/releases/tag/v0.7.0). Download [antigravity-mobile-0.7.0.apk](https://github.com/Srimi1/antigravity--mobile/releases/download/v0.7.0/antigravity-mobile-0.7.0.apk) and install it as an update. Both the main app and embedded Build Tools match the previous release certificate. Physical acceptance is still unverified. Never uninstall Antigravity Mobile or Termux; an unsigned or debug-signed APK cannot safely update the owner's release installation.

## Preserve the update path

Record the installed version and back up important projects before updating. Install the matching signed APK as an update (`adb install -r` when USB is available). Check the same projects, conversations, accounts and build history still exist and all five tabs open.

From 0.6.0 the database path is **v4→v4**, with no schema migration. An owner still on 0.5.2 exercises **v3→v4**. Record which path actually ran; neither phone path has been verified here.

## Approved build outcome

Ask the Agent for a small build and approve `build_project` once. Record one approval key/build ID, one claimed build, the worker's actual Success/Failed outcome, and the returned log. A decline is valid only after pressing Decline. Ask a follow-up about that same build; its answer must match the recorded result and log.

## Linux and custom endpoint fixes

- Complete Linux base setup; verify `python3 --version` inside `agm-debian`. A failed or interrupted setup must stay failed until repaired, even if its root filesystem already exists.
- After verifying a custom endpoint/model, change the endpoint URL. Its key, selected model, plan confirmation, model list and active tool verification must clear. Add the new endpoint's own key and repeat its tool-calling check.
- A CLI task interrupted before Start may resume preparation without replacing its private workspace. A changed workspace or uncertain started task must never be overwritten or replayed.

## CLI and final acceptance

Use the app's probe through Termux RUN_COMMAND; do not substitute `adb run-as com.termux`. Record architecture, `codex --version`, `agy --version` and the actual sandbox result. Failing Codex sandbox means disabled. Antigravity CLI remains disabled unless a documented headless sandbox check passes.

Sign in yourself inside each enabled official client; never share credentials or raw account files. See [Gemini login guide](GEMINI_LOGIN.md). One real task per enabled backend, denied permission, disconnected bridge, malformed events, cancellation and conflicting imports remain phone checks. Signed-in `agy` events must confirm the adapter's init/step/tool/soft-denial shapes.

Finally ask for a small game, approve one build, build its debug APK on the phone in the worker, install/launch/play it and capture screenshots. Follow up in the same chat using the recorded build evidence. Anything not observed stays **unverified**. Review bug-report/log files for private information before sharing.
