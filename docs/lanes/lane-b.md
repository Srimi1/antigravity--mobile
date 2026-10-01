# Lane B — providers, network diagnostics, phone-local Linux

## Day 0 — 2026-10-01

- Baseline `fea895f`; worktree `~/dev/agm-lane-b`, branch `lane-b/providers-linux`. Lane A's `agmBuildRoot` commit cherry-picked unchanged for isolated builds.
- **Host toolchain was missing** (JDK, Android SDK, adb, `~/.gradle`, `~/.android`, all `~/.cache/antigravity-mobile-*` incl. AVDs and evidence). Reinstalled: Homebrew `openjdk@17`, `kotlin`, `android-commandlinetools`; SDK at `~/Library/Android/sdk` with platform 36, build-tools 36.0.0, platform-tools, NDK 27.2.12479018, emulator, system image android-36 google_apis arm64-v8a. No AVDs exist yet.
- `tools/build.sh` generates a new key when `.signing/personal.p12` is missing. Worktrees do not have it: signed release builds must run in the main checkout only.
- Contracts: `providers/ProviderContracts.kt`, `linux/LinuxContracts.kt`.
- B1 started: `providers/FailureClassifier.kt` (HTTP + I/O → `ProviderFailure`, flow/call wrappers that attach a diagnosis), `network/DiagnosisRules.kt` (pure rules, recovery actions), `network/AndroidNetworkDiagnostics.kt` (ConnectivityManager callbacks, Private DNS, VPN, captive portal, DNS/TCP/TLS probes). ChatGPT, Gemini key and Claude key adapters now throw typed failures.
- Accounts → **Network check**: OpenAI/Google/Anthropic hosts or any hostname/URL (only the hostname is used); provider failures with a diagnosis show it there too.
- Tests: `:app:testDebugUnitTest` 94 passed, 0 failed (78 previous + 16 new). `:app:lintRelease`: only the 4 MissingPermission errors above.
- Pushing is blocked for agents by the owner's git hook; the owner pushes.
- Not yet verified on a device: AndroidNetworkDiagnostics (needs the manifest permission), the owner's DNS failure (no phone attached, `adb devices` empty).
