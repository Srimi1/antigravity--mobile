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

## B2 — providers (2026-10-01)

- Official pages checked 1 Oct 2026 (WebFetch; quoted in commit/registry): Groq rate limits, OpenRouter limits, Cloudflare Workers AI pricing + OpenAI compatibility, Hugging Face pricing, Z.AI pricing + error codes, Kilo gateway models, Cohere rate limits + compatibility API, OpenCode Zen, Cerebras rate limits, GitHub Models (retired 30 Jul 2026 → excluded), Mistral usage limits, NVIDIA NIM FAQ.
- Figures seen only on third-party pages are **not** used: Mistral phone/card/training terms and numeric limits, NVIDIA 40 RPM and credit counts, Z.AI Flash concurrency. They show as unknown.
- Findings that differ from the plan: Cerebras is now a card-required $5 / 30-day trial (Trial, not AccountDependent). Cloudflare and Hugging Face only stop instead of billing on certain plans → AccountDependent; free mode needs the user's plan confirmation. OpenCode Zen free models are time-limited promotions.
- Code: `providers/ProviderRegistry.kt`, `OpenAiCompat.kt` (Chat Completions wire + SSE/tool-call parser), `CompatEngine.kt` (JVM-testable core), `FreeModePolicy.kt`, `CompatProviders.kt` (keys per provider in CredentialStore, bound to their base URL), `CompatAccountsPanel.kt` (Accounts UI), `ProviderId.OPENAI_COMPAT`.
- Free mode (default on): Free class; Trial with documented hard stop; AccountDependent only after user confirmation; Paid/custom blocked. Price-based rules re-read the catalog (≤30 min old) and raise PricingChanged before sending.
- Coding requires a passed tool-calling probe per model (stored per provider/model; recorded through ProviderUsageStore).
- Tests: 106 JVM tests pass (12 new for B2, including MockWebServer streaming/tool calls/429/quota/pricing change/trial/paid block/truncation/no key in errors). Lint: only the 4 known MissingPermission errors.
- **No real provider key has been used. Zero models are verified for tool calling.** Verification needs the owner's keys.

## Verification on emulator-5556 (AgmLaneB_API36, Android 16 / API 36) — 2026-10-01

- `AndroidNetworkDiagnosticsTest` (new instrumentation, live network): 4/4 passed twice — api.openai.com passes DNS/TCP/TLS; `.invalid` fails at DNS with a recovery action; closed port fails at TCP; wrong.host.badssl.com fails at TLS. Run with a **temporary, uncommitted** ACCESS_NETWORK_STATE line (reverted; manifest diff 0) because Lane A owns the manifest.
- Fixed a defect found there: without the permission, diagnostics reported "no network". Now probes run on the default network and the type shows "unknown". Verified in the UI as shipped (no permission).
- DNS failures now resolve a control name (www.google.com, lookup only) to separate "this name is blocked/misspelled" from "DNS is broken"; strict Private DNS that answers other names is reported as blocking.
- Accounts → Free & trial providers renders all 11 providers with allowances/unknowns. **Live provider test (no user credentials):** Kilo Gateway anonymous catalog (398 models, 19 zero-priced; 379 hidden in free mode); tool-calling probe on `cohere/north-mini-code:free` passed through the app on the emulator; usage recorded 1 request, 48 input / 75 output tokens; verification stored; no secret in preferences. Prompt was only the fixed probe text.
- Still unverified: every other provider (needs the owner's keys), Agent routing (needs Lane A request B2-1), physical phone.

## B3 — phone-local Linux (2026-10-01)

- Sources checked: Termux RUN_COMMAND wiki + `TermuxConstants.java` (exact extras/result keys), proot-distro README (now OCI-based: `install debian:bookworm --name agm-debian`, runtime under `$PREFIX/var/lib/proot-distro/containers/`), Termux:X11 README, Claude Code setup (signed apt repo, key fingerprint 31DD…CACE), Codex README (npm `@openai/codex`, linux arm64), Antigravity CLI install doc + the real installer script (`agy`, checksum-verified download, only `--dir`/`--help`), Google's Gemini CLI transition post (Google sign-in stopped 18 Jun 2026; paid API keys continue).
- Code: `linux/TermuxGateway.kt` (RUN_COMMAND via documented `startForegroundService`, one-shot package-bound PendingIntent result), `linux/TermuxLinuxRuntime.kt` (helper copied into Termux each call; detached jobs polled), `linux/LinuxSetupPanel.kt` (`LinuxSetupPanel()` for the Build tab, `CliAccountsSection()` in Accounts), helper `tools/linux-runtime/agm-linux.sh` (bundled copy kept identical by a unit test) and its shell test suite.
- Safety: one container `agm-debian` + `~/.agm`; `safe_rm` refuses paths outside them; an old proot-distro is reported, never auto-upgraded; stop kills only recorded PIDs; cleanup never touches other containers, the OCI cache or Termux `$HOME` files.
- Shell tests (fake proot-distro/pkg, macOS bash 3.2): 19/19, stable over 12 consecutive runs after fixing a state-file race and a read-then-check race.
- Device (emulator-5556, Android 16, Termux 0.118.3 from the official GitHub release, SHA-256 verified), with a temporary uncommitted manifest containing the requested lines:
  - denied permission → `PermissionDenied`, Linux state Failed: pass. allow-external-apps off → real Termux refusal mapped to `ExternalAppsDisabled`: pass. Ready (cold Termux): exit code 3, separate stdout/stderr, stdin: pass.
  - Real install through the runtime: proot-distro, **Debian 12.15** (`debian:bookworm`), base packages; start shell; storage > 50 MB; package-cache cleanup; idempotent reinstall: pass. Sentinel file in Termux `$HOME` intact.
  - Real CLI installs on ARM64: Codex `codex-cli 0.159.3` (Node 24 checksum-verified), Claude Code `2.1.285` (signed apt), Antigravity `agy 1.2.14`, Gemini CLI `0.62.0`. **No CLI sign-in or inference was attempted** (needs the owner's accounts).
  - Defects found and fixed on device: detached jobs died with the RUN_COMMAND call (now `setsid`); plain `startService` refused when Termux is stopped (now `startForegroundService`); `--shared-tmp` broke apt signature checks (dropped; desktop uses `--shared-x11`); `agy` installer has no `--skip-aliases`; CLI PATH profile added.
  - Gemini CLI: npm skipped `@github/keytar`'s install script; its credential storage is unverified.
  - **Desktop: NOT working yet.** XFCE installs (Termux:X11 nightly app + `termux-x11-nightly` + xfce4); Start launches the X server and XFCE processes and opens Termux:X11, but the screen stays black: GTK clients (xfce4-panel, Thunar, xfsettingsd) fail with "Cannot open display" while xfwm4 connects. Tried: separate server/session → `termux-x11 :1 -xstartup …` (documented form), TCP display. Not resolved; `adb run-as` experiments are not representative (different SELinux domain, no DNS). Next: reproduce through RUN_COMMAND with a plain GTK app, then test on the physical phone. Treat the desktop as **failing/unverified**.
  - Stop/cleanup hardening found on device: a lost PID file orphaned the session and blocked removal; stop now also ends processes whose command line names `agm-debian` (shell test: orphan stopped, a user's `debian` container process untouched). Then: stop → 0 agm processes; `remove` → container gone; sentinel `~/user-file.txt`, OCI cache, proot-distro and Termux intact.
- Shell tests: 21/21 (3 consecutive runs). JVM: 111/111. Release lint: only the 4 ACCESS_NETWORK_STATE errors (0 errors with the requested manifest lines applied temporarily).

## Environment incident — 2026-10-01 ~22:00 IST

Disk reached 96% (8.7 GiB free). macOS evicted ~3,000 files of the iCloud checkout, including 245 loose objects in the shared `.git`; a fast-forward of local `main` failed mid-way (`fatal: mmap failed: Operation timed out`). Restored the main checkout to clean `cc2240d` (partial files were byte-identical copies or 0-byte). Deleted only Lane B's emulator user data (7.7 GB) → 17 GiB free. `brctl download` did not restore anything (iCloud daemon stuck); 161 files in `.git` remain cloud-only. All Lane A + Lane B commits since `fea895f` read and verified OK into `~/dev/agm-lane-b-backup.bundle`.
