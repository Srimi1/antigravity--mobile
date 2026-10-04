# Release 0.7.3 (code 16) — 4 October 2026

Published [v0.7.3](https://github.com/Srimi1/antigravity--mobile/releases/tag/v0.7.3) at `2026-10-04T07:21:48Z`: public, not draft, not prerelease, GitHub Latest. Tag and build source: `63e8a3eb18bf27655d774c0d17cd9fdcd6015db4`.

## Contents

- Five fixes from an external source audit of `056a302` (GitHub token bound to `https://github.com`; change-snapshot temp-name collision; bounded build-script and edit-preview reads; `.git` symlink refusal; bounded sign-in loopback reader). Details: [PROJECT_CHECKPOINT.md](PROJECT_CHECKPOINT.md#security-audit-fixes-4-october-2026-committed-unreleased).
- Per-project Gradle dependency cache in the build tools app (worker code 3, `0.7.3-tools`, `MIN_WORKER_VERSION` 3).

## Assets

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| antigravity-mobile-0.7.3.apk | 306,303,310 | `78306bb4e09436d3375e4a4531a01113d7438d4d9590c99c85dbce453e7c4188` |
| antigravity-build-tools-code3.apk | 261,997,548 | `0adb5aeb202f6cee1bde32cecb8a3c88febf582df70f80e554addc5f9cacf32d` |
| antigravity-mobile-0.7.3-source.zip | 18,598,854 | `87c6cad37894619f76012f9624d2e08651252afd24a5ab43e5a439add20ebfdf` |
| artifacts.json | 1,471 | `2c617910503cdb41a25a1ab0601bc6f33b74c2235e73f761003c496c7a8fddc4` |
| SHA256SUMS | 378 | `ac33ca53c82909b97b02e090af80a94486186374a4789d5f0a8113d2c035f924` |

GitHub's reported sizes and SHA-256 digests match the local files; public main APK download returned HTTP 200 with the expected length. Both APKs are signed with certificate SHA-256 `791980edfce3d623dfe1ba2e3209a506b01c4be7da74065805d400a5051210b5` (same as v0.7.2). The embedded `assets/build-worker.apk` is byte-identical to the standalone build tools APK. The source ZIP was packaged from a clean worktree at the tag (560 files, ZIP integrity OK, no signing keys, APKs, build output or local notes).

## Validation

- 183 JVM tests, `:app:lintRelease`, `:build-worker:lintRelease`, `apksigner verify`.
- Signed update on `OnePlus7ProSim_API31` (Android 12 ARM64): 0.7.2/code 15 with Spoon-Knife project → 0.7.3 installed as update, project files intact, no crash. Build tab showed **Update build tools**; Android's chooser offered Termux and Package installer (choose Package installer); update moved worker code 2 → 3 and the tab showed **Tools installed**. This run used a candidate differing only in one corrected Build-tab sentence; the final APK was then installed over it and relaunched with the project intact.
- Not run: instrumentation, physical OnePlus, on-phone build with the new cache, live GitHub clone/push, live account sign-in.

## Phone steps

1. Download `antigravity-mobile-0.7.3.apk`, open it, choose **Update** (keep the existing app).
2. Open **Build** → **Update build tools** → choose **Package installer** → **Update**.
3. Build a Gradle project twice; the second build should report "dependency cache reused" and be much faster.
4. Optional: with a GitHub token saved, clone a GitHub repository (should authenticate) and note that a non-GitHub HTTPS remote shows "Saved GitHub token not sent".

Full-product acceptance remains open.
