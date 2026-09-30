# Compatibility report

Status: **VALIDATION PROTOTYPE — full-product gate BLOCKED**.

Target: OnePlus 7T Pro. Its current Android version, RAM and free storage have not been inspected. No physical device was connected during initial development. Android 10+ and `arm64-v8a` are the prototype requirements.

## Gates

| Acceptance item | Current status |
| --- | --- |
| Google subscription in native app | BLOCKED: no supported integration established |
| Claude subscription in native app | BLOCKED: applicable integration/approval not established |
| ChatGPT consent, inference, renewal, logout | UNVERIFIED: implementation exists; live account validation pending |
| Workspace file edit and rollback | PASSED in JVM checks; physical OnePlus validation pending |
| Packaged Android ARM64 command and cancellation | PASSED on Android 12 ARM64 emulator; physical OnePlus validation pending |
| Native sample generation | Implemented; not an APK compilation result |
| On-phone Kotlin/Compose compilation | BLOCKED: JDK/Gradle/Android-host build tools not bundled |
| APK installer and launch on OnePlus | UNVERIFIED until performed on the target phone |
| Full repo maintenance / autonomous agent loop | Deferred by the requested gate |

The downloaded desktop Android SDK/NDK is used to cross-compile the probe, not claimed as a phone runtime. The embedded native program proves Android executable packaging only. It is neither a compiler nor a terminal toolchain.

## Development validation

Validated on 2026-09-30 using Java 17.0.20, Gradle 8.13, Kotlin 2.1.21, AGP 8.10.1, Android platform/build tools 36 and NDK 27.2.12479018:

- Signed release APK built successfully; Android 12 ARM64 emulator installation and activity launch succeeded.
- **11 JVM tests passed:** 5 workspace boundary/rollback tests and 6 signed ID-token validation tests.
- **8 Android instrumentation tests passed:** real packaged ARM64 execution and exit status, explicit process cancellation, coroutine cancellation and execution-slot reuse, interrupted-session recovery, Keystore-encrypted credential storage, source-archive packaging, actual sample generation with an executable Gradle wrapper and report output, and the adaptive launcher icon.
- Release lint passed with no errors. Remaining warnings concern pinned dependency versions, ARM64-only scope, kapt usage and Kotlin convenience extensions.
- The complete HelloPhone Compose sample compiled into a debug APK on the development Mac. This is host-build validation only.
- The packaged executable is linked for Android/Bionic using NDK's ARM64 target, API 29, with 16 KB alignment.
- The release UI was visually inspected on the emulator. No startup crash was reported.
- Build output and the build script's project cache live outside iCloud Drive after a repeated build exposed cloud-sync duplicate DEX files.

Emulator results must not be treated as OnePlus acceptance. Live subscriptions remain unverified even though the automated checks pass. The 21 MB probe APK is a diagnostic deliverable; it does not contain an Android-host compiler toolchain.

The first instrumentation run caught an interrupted-output-reader error during process cancellation. It was fixed and the final five device checks passed; the delivered APK includes that fix.

## Next required evidence

Install the probe on the OnePlus, run the checks, and export its JSON report. Establish supported Google and Claude routes. Supply and validate an Android-host JDK/Gradle/build-tool distribution before extending to project compilation. The complete five-screen agent application is gated on those results.

## Bug review and fixes — 0.1.1

The expanded test run on 2026-09-30 found and fixed:

1. **Checkpoint traversal:** `../workspace/source.kt` could resolve back inside the workspace but escape the checkpoint directory when copying the original. It could overwrite another checkpoint. Non-normalized paths are now rejected, and a regression test reproduces the old failure.
2. **Generated launcher permissions:** ZIP extraction did not preserve `gradlew` executable permissions. Generated samples now explicitly make that launcher executable; an Android test verifies it. Compilation still requires the missing Android-host toolchain.
3. **Stale packaging paths:** relocating Gradle build output left the native/asset source directories pointed at the previous location. Existing generated files could mask the defect. All generated sources now use Gradle's configured build directory; a clean build and an APK asset test validate packaging.

The task recorder's initial database write was also moved inside its `try/finally`, so a failed write cannot leave the UI permanently busy.

Final verification: 18 tests passed (11 JVM, 7 Android), release lint passed, and the 0.1.1 release signature was verified. The APK installed and launched on the Android 12 ARM64 emulator. No claim is made that the app is bug-free: live OAuth/inference/renewal/revocation, phone-specific behavior, and on-phone compiler integration remain untested or blocked.

Use `dist/antigravity-mobile-probe-0.1.1.apk` in place of the earlier 0.1.0 artifact. Machine-readable results are in `dist/test-results.json`; the verification log is `dist/bugfix-verification.log`.

Manual release UI checks also passed: file rollback, Compose source generation, and restart after force-stop. Requesting inference without a saved account produced a recoverable failed check without crashing. Live inference was not attempted.

## Icon release — 0.1.2

Version 0.1.2 (version code 3) adds the generated blue/violet adaptive icon, a separate dark background, round launcher support and a themed-icon silhouette for Android 13+. Full-size source artwork and repository cover imagery are included in `assets/branding`. No provider logos are used.

All **19 tests passed**: 11 JVM and 8 Android instrumentation checks on the Android 12 ARM64 emulator. Release lint and signature verification passed. The new personally signed release installed and launched successfully. Its certificate fingerprint remains `791980edfce3d623dfe1ba2e3209a506b01c4be7da74065805d400a5051210b5`. Android 13 themed-icon rendering and the physical OnePlus remain untested.

The APK in `release/` and the GitHub release is `antigravity-mobile-probe-0.1.2.apk`. The full-product dependency gates above are unchanged.

The new icon was visually inspected in the emulator launcher with a circular mask. Launcher and prototype screenshots are included in `assets/screenshots`.
