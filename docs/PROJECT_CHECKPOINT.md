# Antigravity Mobile — saved checkpoint

Saved on **30 September 2026** at the user's request.

**For the next agent:** start with the repository-root [AGENTS.md](../AGENTS.md). It maps the current implementation, explains the user's full-app request, and gives continuation, validation and signing guidance. This checkpoint records the completed work; AGENTS.md explains how to resume it.

## Current deliverable

The latest shipped version is **0.1.2-probe**, Android version code **3**, for Android 10+ and ARM64. It is a personally signed validation prototype, not the complete three-provider development app.

- Repository: https://github.com/Srimi1/antigravity--mobile
- Release: https://github.com/Srimi1/antigravity--mobile/releases/tag/v0.1.2
- Release source commit: `75ffc8c583b48513f291499f2fa879b6c9ecc5e2`
- Current Google Drive APK: https://drive.google.com/file/d/1SncofhU19PhL1FoIRxH3gCohCMc5_nvV/view
- The previous Google Drive 0.1.1 APK was deleted after replacement verification.

The release contains the APK, complete source ZIP, SHA-256 checksums, original app icon, repository cover and compatibility report. Local release copies are in `release/` and `dist/`; binary release artifacts are excluded from Git history.

APK SHA-256: `aebaab349a3fd70ab913de3b2b34e1cf1d9a7626263b7350ee4897497169b39e`.

## Implemented and tested

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

## Unresolved product requirements

Google and Claude subscription integration have not been established. ChatGPT consent, real inference, renewal and revocation have not been tested against the user's live account. No Android-host compiler toolchain is bundled, and no APK has been compiled on the phone. The physical OnePlus 7T Pro's current Android version, RAM and free storage remain uninspected.

The full five-screen app, repository cloning/commits, general shell execution and common agent tool loop are not implemented in this release. See [compatibility report](compatibility-report.md) and [provider evidence](provider-evidence.md) for detailed limits.

## Latest user direction and next work

The user now explicitly wants **a full app**, rather than another diagnostic-only release. Resume by building Projects, Agent chat, Changes, Build and Accounts screens with real local repository workflows and durable task/action recovery. Do not repeat the completed icon/release/upload work.

The full-app request was followed by this save request before any new full-app code was written. Investigation covered local repository services, Git integration and Android build-tool distribution; those choices are not implemented or validated yet.

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
