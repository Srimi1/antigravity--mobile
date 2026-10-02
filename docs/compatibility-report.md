# Compatibility report

Status: **CURRENT SOURCE AND LATEST PUBLISHED APK 0.7.0/code 13; full-product gate BLOCKED**. Current evidence is below and in the [release record](RELEASE_0.7.0.md). Earlier [candidate notes](RELEASE_CANDIDATE_0.7.0.md) and older milestone sections are historical. Physical-phone acceptance and live subscriptions remain unverified.

Latest user-reported target: OnePlus 7 Pro, 12 GB RAM, 256 GB storage (earlier records said 7T Pro). Physical model, current Android and free storage remain uninspected. No physical device was used in QA. Android 10+ and `arm64-v8a` remain the app requirements.

## 0.7.0 validation and publication — 2 October 2026

Fresh signed publication build: 35 JVM classes/155 tests, 0 failures/errors/skips; app and worker release lint with 0 errors/fatal issues; `BUILD SUCCESSFUL in 2m 1s`, 143 tasks. Python bridge: `Ran 31 tests in 28.850s`, `OK`. Linux installer/helper: `passed=22 failed=0`. Both APKs use the original signer, and the embedded signed worker equals the standalone companion. All five GitHub release assets match local sizes and SHA-256 digests; the public APK download returned HTTP 200. Regression coverage includes real Python/Kotlin native-request delivery and upload retry, upload commit crash recovery without replacing files, failed/interrupted install status, and endpoint verification reset/serialization. [Release record](RELEASE_0.7.0.md) contains commands, output and artifacts; [candidate notes](RELEASE_CANDIDATE_0.7.0.md) preserve earlier debug/androidTest/unsigned build evidence.

Room remains v4 and worker remains code 2. The signed 0.6.0→0.7.0 update on emulator-5554 returned `Success`, retained the existing Hello Phone project and launched with `Status: ok`; current app PID had no crash entry. This was **v4→v4 with no schema migration**. No physical phone was connected; no physical migration, live subscription response or game acceptance is claimed. Antigravity CLI stays disabled without a passing documented headless sandbox check; Codex stays disabled when its sandbox probe fails. The owner authorized publishing without physical acceptance; Gate 0.7.0 remains open and 0.8.0 is not bumped.

26 emulator checks completed before the combined run was stopped. The real-Termux restart case failed due to mismatched **temporary QA** launcher/client roots. Production paths match. QA correction/rerun awaits owner approval after repeated failures; the full candidate device suite is **not passed**. Actual Codex probe: `aarch64`, `codex-cli 0.159.3`, sandbox `unavailable`. See candidate notes for real output and setup details.

## 0.6.0 validation — 2 October 2026

JVM 150/150, release lint, Python bridge 29/29. Emulator-5554: CliRuntime 11, NativeRuntime 5, CliRealTermux 4 OK; release 0.5.2 → 0.6.0 in-place update kept a Room v3 project and opened all tabs without a crash. Real `codex-cli 0.159.3` and `agy 1.2.14` execute on ARM64 Debian 12 under proot on the emulator; Codex sandbox check fails (exit 182), so both CLI backends stay disabled. **Physical phone: untested.**

## Current 0.3.0 validation — 30 September 2026

Real AGP/kapt, **37 JVM tests**, main/worker release lint, signed release build and **16 device tests** passed. Through Antigravity's UI, a Compose project was approved, compiled in the separate Android foreground worker, transferred back, installed and launched. Main restart retained a live build; worker death interrupted/refused replay. One-shot approval, immutable source copy, private-account-storage isolation and Room v1/v2→v3 migrations passed. Companion installation was done with ADB for QA; its embedded installer still needs validation.

Low-storage installation failure was reproduced and recovered after verified backup/clear of a generated lab cache. A conditional staging-space guard was validated. One parsed trace shows memory pressure on the 3 GB emulator; physical OnePlus performance is unknown. Native health/MTE/security/licensing, storage management, wider projects/languages/websites, foreground agent durability, live subscriptions and the earlier ANR remain open. The full app is not ready for acceptance.

## Earlier 0.2.0 validation — 30 September 2026

Real AGP build, Room kapt, 34 JVM tests, release lint and signed APK passed on the Mac. All 12 instrumentation tests passed on a separate Android 12 ARM64 emulator, including the four previously unrun full-app tests. Release upgraded 0.1.2 and retained its exact check record. Manual project creation, save/restart, Git status, five tabs and Compose-template generation passed after recovery.

The first release launch after upgrade produced an ANR with a 10,410 ms focus timeout. Wait recovered it; three controlled cold launches did not repeat it. Root cause unresolved. Screenshots, UI state, logs and performance limitations are in the QA report. Physical OnePlus, live subscriptions and phone-only compilation remain unverified or blocked.

## Separate Android-native toolchain lab — 30 September 2026

Five lab instrumentation tests passed on Android 12 ARM64: real Java compilation/execution, child JVM, Gradle startup, Android Java APK build/sign/verify and cancellation. The generated fixture installed and launched. A later full Kotlin/Compose build, APK installation, launch and interaction passed in the separate lab; see [Compose evidence](native-compose-qa-2026-09-30.md). This runtime is experimental and not integrated into Antigravity. See [native runtime evidence](native-runtime-qa-2026-09-30.md) for hashes, compatibility compromises and limits. No physical-phone or live-provider evidence was added.

## Gates

| Acceptance item | Current status |
| --- | --- |
| Google subscription in native app | BLOCKED: no supported integration established |
| Claude subscription in native app | BLOCKED: applicable integration/approval not established |
| ChatGPT consent, inference, renewal, logout | UNVERIFIED: implementation exists; live account validation pending |
| Workspace file edit and rollback | PASSED in JVM checks; physical OnePlus validation pending |
| Packaged Android ARM64 command and cancellation | PASSED on Android 12 ARM64 emulator; physical OnePlus validation pending |
| Native sample generation | PASSED through emulator UI; generated project compiled/installed/launched |
| Local Kotlin/Compose compilation | PASSED for the integrated emulator fixture; physical phone/wider projects unverified |
| APK installer and launch on OnePlus | UNVERIFIED until performed on the target phone |
| Full repo maintenance / autonomous agent loop | Local Git passed on ART; full existing-repo workflow and live agent acceptance unverified |

The development SDK/NDK cross-compiles the initial app and native libraries. The old childless diagnostic probe remains a probe. The companion executes a separate Android-native Java/Gradle/resource toolchain; architecture support alone is not broader compatibility proof.

## Historical 0.1.2 development validation

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

## Historical next required evidence (0.1.2)

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

## Initial full-app source: 0.2.0 (historical, superseded by current QA above)

The five-screen app, local projects, JGit, the agent tool loop, durable change review and account states are implemented in source. Validation so far is limited to a JVM harness in a cloud container that could not reach Google Maven:

| Item | Status |
| --- | --- |
| All app sources, including Compose UI, compile | PASSED in `tools/jvm-harness` (JetBrains Compose 1.8.0 + AndroidX signature stubs), not with AGP |
| 34 JVM tests | PASSED in the harness |
| JGit HTTPS clone and cancellation | PASSED once on the JVM (manual, network) |
| AGP build, kapt/Room schema, lint, signed APK | NOT RUN |
| 12 instrumentation tests (4 new: migration, JGit on ART, ledger, template) | COMPILE ONLY; NOT RUN |
| Live ChatGPT agent task with function tools | UNVERIFIED |
| Claude, Google subscription | BLOCKED (unchanged) |
| On-phone Kotlin/Compose compilation | BLOCKED (unchanged) |

Known risks to check first on the real toolchain: hand-written `MIGRATION_1_2` SQL versus Room's expected schema, JGit resource packaging, and lint `InvalidPackage` (downgraded to a warning; JGit's `java.lang.management` use is avoided by disabling auto-gc).
