# Integrated native build QA — 30 September 2026

**0.3.0/code 5 is an unreleased implementation milestone.** A Compose project was created through Antigravity, approved, compiled by its separate Android foreground worker, transferred back, installed through Android's installer and launched from Antigravity. Full-product acceptance remains blocked by subscriptions, physical-phone validation, wider coding/build coverage and the unresolved earlier ANR.

Baseline: `8c2888a6ccd6e2bdf66014388c1bf60ae4ababdf`, equal to fetched `origin/main` before edits. Recovery ref: `refs/checkpoints/before-worker-integration-20260930`. Existing release, artwork, personal signer and Drive file preserved.

## Implementation and toolchain

- Main package `dev.srimi.antigravitymobile.probe`, version 0.3.0/code 5. Worker package `dev.srimi.antigravitymobile.worker`, version 0.3.0-tools/code 1, same certificate but separate UID.
- Matching debug/release worker APK is embedded as a main asset. Android's installer handles companion installation in the implementation; QA installed the companion with ADB, so that embedded companion install/update flow is not yet validated. Native binaries/data are generated outside iCloud; no downloaded Linux executable, desktop compiler service, Termux, cloud build or root is used to compile user projects.
- Read-only source ZIP, exact tasks, SHA-256 and fresh ID cross the boundary. Source links are refused, Git/SDK/cache/output files excluded and tree/content consistency checked. Original files are not mounted in the worker. Project scripts can access all worker storage and the internet; isolation is between the main account app and build UID, not between arbitrary build projects.
- Room v3 adds build history with v1→v2→v3 and v2→v3 migrations. Approval is claimed atomically once. START timeouts are observed, never replayed. Worker process death interrupts old commands and stops owned children; main process death reconnects to a still-live foreground build. APK transfer can be retried without rerunning Gradle.
- Per-build Gradle caches avoid reusing an interrupted cache. Process output is bounded (4 MiB disk, 1 MiB memory; 8 KiB per line). Main and worker validate exact caller/reply UIDs and signing certificates.
- Native runtime remains experimental: Bionic Java 17.0.18, Gradle 8.13, native resource tools 35.0.2 with platform/Build Tools data 36, and legacy heap-tagging/path compatibility settings. Native health/MTE, security maintenance and complete redistribution/source obligations remain open. The missing platform-tools licence warning was retained; no licence was forged or accepted on the user's behalf.

## Automated validation

`./tools/build.sh` passed real AGP, kapt, **37 JVM tests**, main release lint, signed release assembly and certificate verification. Worker full release lint also passed (warnings retained). Final `:app:connectedDebugAndroidTest` passed **16 tests, zero failures/errors/skips** on Android 12 API 31 ARM64, `AntigravityMobileQA_API31`, serial `emulator-5556`, 3 GB RAM.

New device checks verify v2→v3 preservation of a project/conversation/message, one-shot approval, refusal of a declined command, immutable snapshot use after an original edit, different worker UID and inability to read a main-private test marker, foreground cancellation, worker force-stop recovery and refusal to run an old build ID again. Existing native, credential encryption, Git, change ledger and template checks also passed. Credentials and providers were not tested with a live account.

The first 15-test run had two failures: `Message` instances were recycled after the reply handler returned. Copying the reply `Bundle` fixed the race. The two affected tests passed; after adding worker-death coverage and explicit reconnection, all three worker tests passed, followed by the final complete 16-test run. All intermediate failure evidence is retained.

## Visible build/install/recovery flow

1. **Compose app → Create** generated Hello Phone. The QA scratch project's application ID was changed through ADB to `dev.srimi.integrationphone` before approval, preserving the earlier lab fixture; namespace/source stayed unchanged. Creation, approval and build were driven through visible app controls.
2. **Review build → Approve and build** ran `:app:assembleDebug`, snapshot SHA-256 `d7c2f04a876952e255a2e06720220f94bfdcc485d8b4cea2e1f0f785c943d272`, build ID `b94e9172-9f34-4518-ae9d-cfba78fc7512`.
3. The main app was force-stopped while the worker/CLI/daemon remained live. Reopening it recovered the same build ID, with no second command. Native begin/end attempt ID matched `732fc3bc-074f-4f6c-9c90-6c6b44a830f5`; exit 0, timeout false, elapsed **232,740 ms**. Gradle reported **BUILD SUCCESSFUL in 3m 52s, 35 tasks executed**.
4. The worker transferred a valid 23,669,282-byte APK to the main app. Native output APK SHA-256: `e0bd612b2252950fd96c041a3a67ef8c9bf93a10c0f73eb6518fe47141951d2f`. V2 signature verified; its generated development signer belongs to the worker's private build home, not the Antigravity personal signer.
5. Android's first install attempt failed with low disk space. `installd` reported 252,907,520 free bytes versus 624,066,560 requested. The older lab cache (7,418 files, 918,349,357 content bytes) was archived to the Mac and fully verified before clearing that generated cache; lab source/APKs/logs were retained. A compressed backup attempt was truncated, so it was not used for deletion. Verified raw archive SHA-256: `2a779703762d80ff82fa67120d2e7515792eb2739a618e7b8b2fc42edb4473dc`.
6. Added installer staging cleanup and a conditional space check. A controlled 348 MiB QA reservation reproduced the guard: **728 MB free; free at least 63 MB**, displayed inside Antigravity. The reservation was removed. The guard leaves APK size plus 768 MiB available for staging/system reserve; it is a conservative check, not a guarantee for every Android/OEM installer.
7. Retrying through **Install artifact-0.apk** succeeded; Android showed **App installed**. **Open installed app** launched it, and tapping **Count** changed 0→1. No desktop compiler built this APK. The older failed installer activity was dismissed from the back stack after success; installed-package and live UI checks confirmed the successful result.

The build flow began with debug artifact `e84eae43721040bd48d9f593b9cf34cf4e6a82093e60d37fb14a5e52baeab90b`; the final artifact below additionally fixes labels/template-cache exclusion, the disk guard and dead-worker reconnection. The worker binary/build pipeline was unchanged. Final install/launch/guard and all 16 tests used the final main artifact. No additional Compose compilation was needed for those main UI/IPC changes.

## Performance evidence and limits

One focused build/restart/initial-installer flow produced an **86,055,588-byte Perfetto trace**, parsed by the official local trace processor. Nonzero error/data-loss stats: none. Trace timestamps span about 437 seconds; source/config/query/metadata and 5-second process/memory observations were saved. No desktop Gradle build ran during this native build trace.

| Evidence | Value |
| --- | --- |
| MemAvailable samples / minimum | 435 / **21,544,960 bytes (20.5 MiB)** |
| Gradle daemon sampled peak RSS | **1,652,932,608 bytes** |
| CLI JVM sampled peak RSS | 94,552,064 bytes |
| Worker service sampled peak RSS | 105,091,072 bytes |
| Main app sampled peak RSS | 191,373,312 bytes before controlled stop; 184,000,512 after restart |
| aapt2 sampled peak RSS | 41,644,032 bytes |
| Daemon scheduled CPU in captured interval | 181,671 ms (scheduler evidence, not function sampling) |
| Generated app idle PSS / RSS / swap | 57,134 / 155,756 / 0 KB |
| Main / worker later idle PSS | 60,930 / 17,949 KB |

RSS includes shared mappings; peaks are individual samples, not additive PSS or true instantaneous maxima. The emulator came close to its 3 GB memory limit. Generated-app gfxinfo recorded 50 frames, with conflicting counters (0 janky versus 48 legacy janky); this does not establish smoothness or a root cause. Cold main launches observed 708 ms and 897 ms under different QA conditions, not a controlled speed comparison. The crash buffer was empty at final interaction; the earlier 0.2.0 upgrade ANR remains unresolved. No OnePlus performance claim follows from these measurements.

## Artifacts and remaining work

Curated unmodified screenshots, XML UI state, build/failure/test logs, runtime records, lint, signature, hash and performance reports: [`assets/screenshots/integration-20260930/`](../assets/screenshots/integration-20260930/). Raw trace, sampling, verified old cache and larger artifacts remain under `~/.cache/antigravity-mobile-runtime/evidence/integration-20260930/`; trace hash/size is in the curated metadata.

Final main release: `dist/antigravity-mobile-0.3.0.apk`, **286,454,547 bytes**, SHA-256 `c0e4e74efe40c2915a3220796c2dc76d3621d4616ba0107e92f7c6778807a771`. Worker release: **261,800,807 bytes**, SHA-256 `f90d1fe4e7cfd06590c169d039edf97ede12541a22b17eadf0ea84f99150adae`. Both personal certificate digests are `791980edfce3d623dfe1ba2e3209a506b01c4be7da74065805d400a5051210b5`. Final debug hashes/sizes are in `final-artifacts.json`. These runtime APKs remain private/unpublished; GitHub source synchronization does not publish a release.

Still open: physical OnePlus 7 Pro (reported 12 GB/256 GB; actual model/Android/free storage unverified), live ChatGPT coding, supported Claude/Google subscription routes, wider repositories/languages/websites, foreground agent durability, build-storage management, native runtime acceptance/licensing and the prior ANR. This does not provide every Linux/Windows/macOS capability. QA emulator is retained for follow-up; no compiler command was left running.
