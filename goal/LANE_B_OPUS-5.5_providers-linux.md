# Goal — Lane B: Providers, network diagnostics, and phone-local Linux setup

**Assigned model:** Opus 5.5 (medium effort)
**Partner:** Lane A (GPT 6.1 SOL max) works simultaneously on the task runtime, approvals, Room migration, foreground service, and CLI bridge. Read `goal/LANE_A_GPT-6.1_runtime.md` once so you know its scope, but never do its work.
**Starting point:** Antigravity Mobile 0.5.2 (version code 11), branch `main`.

## Your goal

Give the user verifiable free and trial AI providers they select manually, honest quota/allowance display, credential-free network diagnostics that explain failures, and a guided phone-local Linux environment (Termux + Debian 12 ARM64 PRoot + XFCE + Termux:X11) with the official CLIs installed.

Done means: every provider failure surfaces as a typed `ProviderFailure` with a diagnosis; only verified tool-calling models are enabled for coding; unknown quota shows as **unknown**; nothing routes automatically or falls back to paid access; Linux setup installs, starts, stops, reports storage, and cleans up without harming an existing Termux install.

## Read first

1. `AGENTS.md` (all boundaries apply to you)
2. `CONTINUE_WITH_ANY_AI.md`, `docs/PROJECT_CHECKPOINT.md`, `docs/provider-evidence.md`, `docs/compatibility-report.md`
3. `goal/LANE_A_GPT-6.1_runtime.md` (partner scope only)

## Rules for working alongside Lane A

1. **Isolated workspace.** Work in a git worktree outside iCloud: `~/dev/agm-lane-b`, branch `lane-b/providers-linux`. Use `--project-cache-dir ~/.cache/agm-lane-b` and `-PagmBuildRoot=$HOME/.cache/agm-lane-b-build` (Lane A adds that property on Day 0; do not run Gradle builds until it is on `main`).
2. **File ownership is absolute.** Edit only files you own (list below). If you need a change in a Lane A file (manifest entries, Gradle dependencies, AppContainer wiring, screen hooks), add a request of five lines or fewer to `docs/lanes/REQUESTS.md`; Lane A applies it. Apply Lane A's requests against your files promptly.
3. **Contracts are frozen after Day 0.** Changing a shared interface requires a note in `REQUESTS.md` and Lane A's acknowledgement there.
4. **Devices.** Your emulator is `emulator-5556` only. The OnePlus 7 Pro is shared: take `~/.cache/agm-phone.lock` (write your lane name and time) before using it, release it after. Never uninstall the user's phone app or an existing Termux.
5. **Git.** Rebase onto `main` before merging; fast-forward merges only; never force-push. Only the gate owner bumps `versionCode`/`versionName`.
6. **Notes.** Record progress, tests, and blockers in `docs/lanes/lane-b.md`. Only at a gate you own do you fold notes into `docs/PROJECT_CHECKPOINT.md`, `docs/provider-evidence.md`, and `docs/compatibility-report.md`.
7. **Security.** API keys only in `CredentialStore` (Keystore-backed), never in logs, Room, exports, or diagnostics. Never print or commit `.signing/personal.p12`. Signed releases and GitHub publishing need the owner's approval each time. No token lifting, no proxying subscription credentials, no automatic paid fallback, no invented provider results.

## Files you own

`Providers.kt`, `GeminiAdapter.kt`, `ClaudeAdapter.kt`, `ChatGptProbeAdapter.kt`, `ResponsesStream.kt`, `CredentialStore.kt`, `OidcVerifier.kt`, `AccountsScreen.kt`, the Accounts portion of `ReviewViewModels.kt`, new packages `providers/`, `network/`, `linux/`, the new UI file `LinuxSetupPanel.kt`, and Linux/Termux setup scripts under `tools/linux-runtime/`, plus their tests.

Lane A owns the agent loop, approvals, builds, Changes, Room (`SessionStore.kt`), `Contracts.kt`, `AntigravityApp.kt`, `MainActivity.kt`, `AgentScreen.kt`, `BuildScreen.kt`, the manifest, and Gradle files. Do not edit those.

## Day 0 — do this first and push to `main`

1. **`ProviderContracts.kt`** (new file):
   - `ProviderDescriptor`: id, display name, baseUrl, auth type, allowance class (`Free`, `Trial`, `AccountDependent`, `Paid`), allowance units, reset/expiry, billing requirement, eligibility, source URL, `verifiedAt`.
   - `sealed ProviderFailure`: `Dns`, `NoNetwork`, `Timeout`, `StreamInterrupted`, `RateLimited(retryAfter?)`, `QuotaExhausted`, `TrialExpired`, `PricingChanged`, `AuthInvalid`, `Unknown`. Each carries an optional `DiagnosticReport`.
   - `NetworkDiagnostics.diagnose(host): DiagnosticReport` — credential-free.
   - `interface ProviderUsageStore` plus an in-memory implementation (Lane A supplies the Room one).
2. **`LinuxContracts.kt`** (new file):
   - `TermuxGateway`: `status()` → installed / RUN_COMMAND permission granted / external apps allowed; `run(cmd): TermuxResult`.
   - `LinuxRuntime`: `status`, `ensureInstalled`, `start`, `stop`, `storageUsage`, `cleanup(selection)`.
   - `CliInstall`: binary path and version for codex, antigravity, gemini, claude-code, custom.

### Contracts you consume from Lane A

- `RuntimeContracts.kt`: `ApprovalDecision`, `ToolOutcome`, `AgentTaskRunner`.
- `AgentModel` in `Contracts.kt` is frozen; your adapters implement it unchanged.
- Room v4 tables Lane A creates for you: `provider_models(providerId, modelId, toolCallingVerified, verifiedAt)` and `provider_usage(id, providerId, modelId, taskId?, inputTokens?, outputTokens?, requests, recordedAt)`. Code only against `ProviderUsageStore`.

## Phase B1 — Network diagnostics (deliver first; Lane A's Gate 0.6.0 depends on it)

1. Using Android's documented `ConnectivityManager` network callbacks, report the active network, the provider hostname's DNS resolution, the failed connection stage (DNS / TCP / TLS / HTTP / stream), network changes, Private DNS mode, VPN, and captive-portal state. <https://developer.android.com/develop/connectivity/network-ops/reading-network-state>
2. Each report includes a specific recovery action when the cause is outside the app (e.g. "Private DNS hostname unreachable — switch Private DNS to Automatic").
3. Map every existing adapter's exceptions (ChatGPT, Gemini key, Claude key) to `ProviderFailure`. Fix any demonstrated client-side network handling defect.
4. Help diagnose the user's game-development DNS failure on Wi-Fi and mobile data (phone lock required).
5. Merge B1 to `main` as early as possible and note it in `REQUESTS.md` for Lane A.

## Phase B2 — Providers (Gate 0.7.0, version code 13; you own this gate, after Lane A's 0.6.0)

OmniRoute's headline figure aggregates estimated allowances across many accounts and shared pools; it does not grant one account 1.2 billion tokens. Use its catalog only to find candidates, then verify each provider against its current official docs and the user's account. <https://github.com/diegosouzapw/OmniRoute/blob/release/v3.8.52/docs/reference/FREE_TIERS.md>

1. **Registry and adapters.** `ProviderDescriptor` registry; one shared OpenAI-compatible streaming + tool-calling adapter; provider-specific adapters only where required.
2. **Targets.**
   - Free tier / zero-price: existing Gemini AI Studio, Groq, Mistral, OpenRouter free models, Cloudflare Workers AI, Hugging Face, Z.AI Flash, Kilo's explicitly selected free models.
   - Evaluation / promotion / trial: NVIDIA NIM, Cohere evaluation, OpenCode Zen free promotions.
   - Account-dependent: Cerebras — allowance stays **unverified** until current account limits are confirmed.
   - Excluded: retired services including GitHub Models. <https://docs.github.com/en/github-models>
3. **Model discovery.** List current models; probe tool calling before enabling a model for coding; record results through `ProviderUsageStore` / `provider_models`.
4. **Accounts UI.** Show allowance units, reset/expiry, billing requirement, account eligibility, source, verification date, and actual usage when available. Unknown remaining quota stays **unknown**.
5. **Free mode.** Admits only verified zero-cost routes or accounts with an established free hard stop. HTTP 429, expired trials, and pricing changes raise the matching `ProviderFailure` (Lane A pauses the task). Paid access requires explicit user selection.
6. **Custom endpoint.** Manually configured OpenAI-compatible endpoint (OmniRoute or another server). Credentials scoped to that endpoint; automatic routing disabled.
7. **Tests.** Streaming, tool calls, quota exhaustion, expired trial, pricing change, manual switching, credential redaction in logs/diagnostics/exports.
8. **Gate.** Rebase on 0.6.0, bump to 0.7.0 / code 13, keep worker version sync and the original signer, update checkpoint, provider evidence, and compatibility evidence; ask the owner before signing or publishing.

## Phase B3 — Phone-local Linux and CLIs (Lane A integrates at Gate 0.8.0)

1. **TermuxGateway.** Detect and preserve an existing Termux. Guided setup for the `com.termux.permission.RUN_COMMAND` permission and `allow-external-apps`. Denied permission is reported as a runtime limitation. Request manifest `<uses-permission>`/`<queries>` entries from Lane A via `REQUESTS.md`. <https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent>
2. **LinuxRuntime.** Debian 12 ARM64 via proot-distro; XFCE, D-Bus, Termux:X11 for desktop. Linux userland on the Android kernel — no root. Start, stop, storage usage, selective cleanup. <https://github.com/termux/proot-distro>, <https://github.com/termux/termux-x11>
3. **UI.** `LinuxSetupPanel()` composable (Lane A inserts it into the Build tab). CLI account status section in Accounts.
4. **CLI installs.** Codex, official Antigravity CLI, Gemini, Claude Code, and custom executable terminal profiles. Each uses its own official sign-in; subscription credentials stay inside the official client. Consumer Gemini CLI access has moved to Antigravity CLI. <https://developers.googleblog.com/an-important-update-transitioning-gemini-cli-to-antigravity-cli/>, <https://www.antigravity.google/docs/faq/>
5. **Google subscription quota.** Use the official Antigravity CLI/GUI account flow; mark it available only after real inference with the user's account. Keep native Gemini API-key access separately labelled.
6. **Desktop.** Install the official Linux ARM64 Antigravity desktop. Mark it **unverified** until launch, sign-in, editing, and a real agent task pass on the phone.
7. **Tests.** Denied permission, missing Termux, failed install, desktop startup, cleanup not touching user data.
8. Merge B3 to `main` and notify Lane A in `REQUESTS.md` so Phase A2 can integrate.

## Final physical acceptance (shared, take the phone lock)

Support Lane A in reproducing the user's game task end-to-end on the OnePlus 7 Pro with a provider you shipped. Verify real subscription inference through the official CLIs. Keep anything unverified explicitly marked unverified.

## Report back

At each milestone: what changed, tests run (with real output), which providers and models were verified (and on what date), what remains unverified on the physical phone, exact commit IDs on local and GitHub, and the next step.
