# Release 0.7.5 (code 18) — 4 October 2026

Published [v0.7.5](https://github.com/Srimi1/antigravity--mobile/releases/tag/v0.7.5) at `2026-10-04T08:47:33Z`: public, not draft, not prerelease, GitHub Latest. Tag: `c6c0ea6496b9ad79361e41819200e7ae9338d057`. The APK was built from the working tree that this commit records (application source identical); the APK's version-control-info names the previous HEAD `410cab19`.

## Contents

- Termux setup fixes from the owner's phone screenshots: detection of "Termux installed after this app" (Android then never registers the RUN_COMMAND permission, so Settings lacks the entry), and a quote-free allow-external-apps command so a cut-off paste cannot leave Termux at a `>` prompt.
- The audit lower-priority fixes (commit `410cab1`): reviewed-content commits, pageable diffs and gated Keep, no auto-routing on Gemini key save, per-task model pinning, PID start-time ownership in agm-linux.sh, CLI evidence tied to environment changes, conditional diagnostics, shared redaction, URI-parsed custom endpoints, file list refresh after save.

## Assets

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| antigravity-mobile-0.7.5.apk | 306,333,930 | `7d00b8a3b067254d5e9383c3c8e4b6a828a2b0bb214cc47bbad8963fcce60f07` |
| antigravity-build-tools-code3.apk | 261,997,548 | `c162cc44d6a3993a5b65b1550417909b2ec1ecc11231ee624f331a76018b03c5` |
| antigravity-mobile-0.7.5-source.zip | 18,616,786 | `96b4776d118fbcac7696bdbfedcbdd1b8800825ccfd998f3342a9426c06f1689` |
| artifacts.json | 1,600 | `962eb88f67b4e0009c72bab48a05f25a3aa949472817f9e5a69dc7d1026e799b` |
| SHA256SUMS | 378 | `c4299ed011e2a0c8c0b6267e45207e001898b132c705fc1e6cc2ad4d925d2fbc` |

GitHub's sizes and digests match the local files; the public APK download returned HTTP 200 with the expected length. Signer `791980edfce3d623dfe1ba2e3209a506b01c4be7da74065805d400a5051210b5` (unchanged). Build tools still code 3 (embedded copy extracted byte for byte). Source ZIP from a clean worktree at the tag: 566 files, integrity OK, no keys, APKs, build output or local notes (secret-pattern hits are only fake test fixtures).

## Validation

- 198 JVM tests (1 Linux-only skip on macOS), both release lints, `apksigner verify`; PID ownership functions run under mksh/toybox on the API 36 emulator.
- Signed 0.7.4 → 0.7.5 update on `OnePlus7ProSim_API31` (Android 12 ARM64): project kept, RUN_COMMAND stayed granted, Build tab "Tools installed", Termux steps "Granted"/"Allowed", no crash.
- Not run: physical phone (owner runs LegionOS, AOSP-based), the "Termux installed after the app" case on a device, live accounts.

## Phone steps (LegionOS / AOSP)

1. In Termux, if the prompt shows `>`, tap **CTRL** then **c**.
2. Install `antigravity-mobile-0.7.5.apk` as an **Update**.
3. Antigravity Mobile → **Build → Linux on this phone** → **Allow**. If no dialog appears: Settings → Apps → See all apps → Antigravity Mobile → Permissions → Additional permissions → Run commands in Termux environment → Allow (labels can differ slightly on custom ROMs).
4. Copy the setup command from step 3 of that screen into Termux and press Enter; it prints "Done. Return to Antigravity Mobile."
5. Fallback from a computer with USB debugging: `adb shell pm grant dev.srimi.antigravitymobile.probe com.termux.permission.RUN_COMMAND`. An error such as "has not requested permission" means Android has not registered it yet; install the APK again as an update first.

Full-product acceptance remains open.
