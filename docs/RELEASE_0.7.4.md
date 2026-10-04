# Release 0.7.4 (code 17) — 4 October 2026

Published [v0.7.4](https://github.com/Srimi1/antigravity--mobile/releases/tag/v0.7.4) at `2026-10-04T07:38:58Z`: public, not draft, not prerelease, GitHub Latest. Tag: `0e6607d7068ae4ecfe1048a9126e36f4dbb06c03`.

The APK was built from `65423451` plus the version bump that `0e6607d` commits (the APK's `META-INF/version-control-info.textproto` records `65423451`). Application code is identical; `0e6607d` only adds checkpoint text.

## Contents

v0.7.3 plus the audit correctness fixes: project-bound editor/file list/Git results, atomic editor save, project-bound atomic revert with retry, durable pending-write recovery in the change ledger, and Linux cleanup refused during active CLI tasks. Details: [PROJECT_CHECKPOINT.md](PROJECT_CHECKPOINT.md).

## Assets

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| antigravity-mobile-0.7.4.apk | 306,314,926 | `9f2a2cb80a99c378f228330065223d0e58d80a1525048a6b9ce24a8cd65eecfd` |
| antigravity-build-tools-code3.apk | 261,997,548 | `6d6ea7f6beb685af9dd4b5fe3b53a38ca88c759708525cfa140c9a1fb942cb3f` |
| antigravity-mobile-0.7.4-source.zip | 18,605,842 | `e43019e7b029030af0631f7645117297343373a28e44ece36b97e38c0d6c0bb9` |
| artifacts.json | 1,553 | `a986704059c1bd7c23da4d62f96ec2c9cb0b91566cf2a4b36c1c3ca92cad3b93` |
| SHA256SUMS | 378 | `7d020667f8ca2533416badd1ea5c88eb07a6037eb26d9fec4b664f71776ae6d8` |

GitHub's sizes and digests match the local files; the public main APK download returned HTTP 200 with the expected length. Signer certificate SHA-256 `791980edfce3d623dfe1ba2e3209a506b01c4be7da74065805d400a5051210b5` (unchanged). The build-tools asset is the embedded copy extracted byte for byte; it is still worker code 3 and differs from the v0.7.3 asset only in `META-INF/version-control-info.textproto`. Source ZIP from a clean worktree at the tag: 562 files, integrity OK, no keys, APKs, build output or local notes.

## Validation

- 191 JVM tests, both release lints, `apksigner verify`.
- `FullAppDeviceTest` 5/5 on the API 36 QA emulator (debug build of `65423451`).
- Signed 0.7.3 → 0.7.4 update on `OnePlus7ProSim_API31` (Android 12 ARM64): project kept, no crash, editor save verified by reopening the file, Build tab "Tools installed" with no prompt.
- Not run: physical OnePlus, live accounts, concurrent-edit timing on device.

Known minor issue: after an editor save the file list shows the old size until refreshed.

## Phone steps

1. Download `antigravity-mobile-0.7.4.apk`, open it, choose **Update**.
2. If the Build tab asks, **Update build tools** → **Package installer** → **Update** (not needed if done for 0.7.3).

Full-product acceptance remains open.
