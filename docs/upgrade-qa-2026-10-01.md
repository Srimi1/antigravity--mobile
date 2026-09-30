# Companion update, signed upgrade and phone-like simulation — 1 October 2026

Emulator evidence only. **Not physical OnePlus acceptance; no live provider account used.** Evidence: `assets/screenshots/upgrade-20261001/` (raw logs and the Perfetto trace stay in `~/.cache/antigravity-mobile-runtime/evidence/upgrade-20261001/` and `oneplus-sim-20261001/`).

All main APKs below are personally signed with certificate SHA-256 `791980edfce3d623dfe1ba2e3209a506b01c4be7da74065805d400a5051210b5`; the embedded companion carries the same signer.

## 1. Signed in-place upgrade chain on the preserved release emulator

AVD `AntigravityMobileProbe_API31` (Android 12 ARM64, WebView 91, run with 3 GB). Starting state: signed **0.2.0/code 4** (first installed as 0.1.2 on 30 Sep), projects `Hello Phone` and `QAScratch`, no companion.

| Step | Result |
| --- | --- |
| 0.2.0 → signed 0.3.0 (`adb install -r`, SHA-256 `c0e4e74e…a771`) | Success. First cold launch **448 ms**; both projects present; no ANR/crash in logcat. The Perfetto config for this step failed to parse, so no trace. |
| Companion code 1 via **Build → Install build tools** | "Install unknown apps" permission → Android installer → "App installed" → "Tools installed". |
| Data created in 0.3.0 | Project `aUpgradeMarker` (stray keystroke in name) with `marker.txt` = `CREATED_IN_030_BEFORE_UPGRADE`. |
| 0.3.0 → signed 0.4.0 (SHA-256 `58e23228…8200`) | Success. First cold launch **480 ms** with a 30 s Perfetto trace (`upgrade-040.pftrace`, 5,686,098 bytes; host load average 4.4–6.1). No ANR. All 3 projects and the marker text preserved. |
| Companion update **code 1 → 2 through the Build tab** | Android offered "Do you want to update this app?" → updated to 0.4.0-tools/code 2, same signer → "Tools installed". **UX defect:** the button still said "Install build tools"; fixed in 0.4.1 (see below). |
| Release-signed website preview | Hello Web approved and previewed in the release companion: CSS, module JS, local JSON, Count button; console note "Older Android System WebView: using the page-level WebRTC guard". |
| Release-signed native Compose build after both upgrades | Build `44c20f72`, `:app:assembleDebug`, **completed in 231 s**, 1 APK transferred → Android installer → opened → **Count 0→1**. |
| 0.4.0 → signed 0.4.1 | Success. Cold launch **591 ms**; all 5 projects present; tools still connected; no ANR. |

The **original first-upgrade ANR (10,410 ms, 0.1.2→0.2.0 on 30 Sep) did not reproduce** in any of the four in-place upgrades or first launches recorded here (0.2.0→0.3.0→0.4.0→0.4.1 on the release emulator, 0.1.2→0.4.1 on the simulation below). The root cause is still not established; the one trace captured has not been analysed with trace_processor. Treat it as unresolved but not reproduced.

## 2. Fix shipped in 0.4.1/code 7

When an older same-signer companion is installed, Build now shows **"Tools update needed"** and **"Update build tools"**, and explains that projects and previous tool outputs are kept (Android package updates keep app data). Main version 0.4.1/code 7; companion unchanged at 0.4.0-tools/code 2.

Validation of 0.4.1: `./tools/build.sh` — 56 JVM tests, release lint, signed release `dist/antigravity-mobile-0.4.1.apk`, **286,711,261 bytes, SHA-256 `d4144955ede813a745de0177f2d5a9b374a107a0030b4b4ac55845ea323b55b7`**. Device suite on `AntigravityMobileQA_API36`: first run **19/20** (`actualWebViewRuns…` timed out at 20 s right after a cold emulator boot; cause not established), rerun after raising the test's UI wait to 60 s **20/20** (that test took 1.1 s). Both XML results are saved.

## 3. Phone-like simulation (OnePlus 7 Pro approximation)

New AVD `OnePlus7ProSim_API31` at `~/.cache/antigravity-mobile-qa/oneplus7pro.avd`: Android 12 (OnePlus 7 Pro's last official major Android version), ARM64, **1440×3120 at 560 dpi**, 4 cores, **6 GB RAM** (the phone's 12 GB is not possible on this 16 GB host), 16 GB data. This is a generic Google emulator image: **not OxygenOS, not the Snapdragon 855 GPU/thermal profile, not OnePlus battery/background limits.** WebView 91.

Followed the likely phone path:

1. Installed public **0.1.2** (SHA-256 `aebaab34…b39e`, matches the published release); ran its file/rollback check.
2. Pushed 0.4.1 to Downloads, opened it from the **Files** app → Android installer showed **"Do you want to update this app?"** → updated in **~4 s**. First cold launch **654 ms**, no ANR.
3. To exercise the new label, installed the old companion code 1 (extracted from signed 0.3.0; SHA-256 `f90d1fe4…adae`, equal to the recorded historical worker). Build showed **"Tools update needed" / "Update build tools"** → permission → *after returning, the button had to be tapped again* → "Update" → "Tools installed".
4. **Compose template → Review build → Approve** → build `ed978f0a` **completed in 188 s**, APK transferred, installed through Android's installer, opened, **Count 0→1**. Sampled minimum `MemAvailable` 2,506,164 kB; Gradle daemon peak sampled RSS 1,559,356 kB (RSS includes shared pages).
5. **Website template → Review preview → Approve** → Hello Web rendered with local JSON.

No crash from the apps in logcat; the only `FATAL EXCEPTION` entries were the host's overlapping `uiautomator` dump tool.

## Not established

- Physical OnePlus 7 Pro: OxygenOS version, WebView version, free space, thermal behaviour, background-kill policy, real 12 GB behaviour.
- Whether the 0.1.2 check history survived 0.1.2→0.4.1 in the simulation (not inspected; earlier 0.1.2→0.2.0 preservation passed on 30 Sep).
- ANR root cause; live ChatGPT; Claude/Google (blocked); WebRTC gap in `srcdoc` frames on old WebView.
