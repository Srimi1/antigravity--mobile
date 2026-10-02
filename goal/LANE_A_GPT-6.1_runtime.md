# Goal — Lane A: Task runtime, reliability, and CLI bridge

**Assigned model:** GPT 6.1 SOL max (max effort)
**Partner:** Lane B (Opus 5.5 medium) works simultaneously on providers, network diagnostics, and Linux setup. Read `goal/LANE_B_OPUS-5.5_providers-linux.md` once so you know its scope, but never do its work.
**Starting point:** Antigravity Mobile 0.5.2 (version code 11), branch `main`.

## Your goal

Make agent tasks reliable and durable on the phone: approvals that can never be misreported, builds whose real outcome is reported, tasks that survive backgrounding and process death, network failures that pause instead of losing work. Then connect phone-local CLI agents (Codex, Antigravity CLI) to Agent chat through an authenticated bridge.

Done means: approving a build once produces exactly one build; only an explicit Decline is ever described as "user declined"; build reports and follow-up answers come from recorded worker outcomes and logs; a network failure shows "Paused: …" with **Retry this provider** and never repeats completed writes, installs, or uncertain builds.

## Read first

1. `AGENTS.md` (all boundaries apply to you)
2. `CONTINUE_WITH_ANY_AI.md`, `docs/PROJECT_CHECKPOINT.md`, `docs/compatibility-report.md`
3. `goal/LANE_B_OPUS-5.5_providers-linux.md` (partner scope only)

## Rules for working alongside Lane B

1. **Isolated workspace.** Work in a git worktree outside iCloud: `~/dev/agm-lane-a`, branch `lane-a/runtime`. Use `--project-cache-dir ~/.cache/agm-lane-a`.
2. **File ownership is absolute.** Edit only files you own (list below). If you need a change in a Lane B file, add a request of five lines or fewer to `docs/lanes/REQUESTS.md`; Lane B applies it. Apply Lane B's requests against your files promptly.
3. **Contracts are frozen after Day 0.** Changing a shared interface requires a note in `REQUESTS.md` and Lane B's acknowledgement there.
4. **Devices.** Your emulator is `emulator-5554` only. The OnePlus 7 Pro is shared: take `~/.cache/agm-phone.lock` (write your lane name and time) before using it, release it after. Never uninstall the user's phone app.
5. **Git.** Rebase onto `main` before merging; fast-forward merges only; never force-push. Only the gate owner bumps `versionCode`/`versionName`.
6. **Notes.** Record progress, tests, and blockers in `docs/lanes/lane-a.md`. Only at a gate you own do you fold notes into `docs/PROJECT_CHECKPOINT.md` and `docs/compatibility-report.md`.
7. **Security.** Never print, upload, or commit `.signing/personal.p12`, tokens, or keys. Signed releases and GitHub publishing need the owner's approval each time. No token lifting, no automatic paid fallback, no invented results.

## Files you own

`AgentLoop.kt`, `AgentViewModel.kt`, `AgentScreen.kt`, `AgentBuildTools.kt`, `BuildCoordinator.kt`, `BuildWorkerClient.kt`, `BuildSnapshot.kt`, `ChangeService.kt`, `ChangesScreen.kt`, `SessionStore.kt`, `Contracts.kt`, `AntigravityApp.kt`, `MainActivity.kt`, `BuildScreen.kt`, `WorkspaceService.kt`, `AndroidManifest.xml`, root and app `build.gradle.kts`, `build-worker/`, `runtime-contract/`, and new packages `runtime/` and `bridge/`, plus their tests.

Lane B owns everything about providers, credentials, Accounts, network diagnostics, and Linux/Termux setup. Do not edit those files.

## Day 0 — do this first and push to `main`

1. **Build directory isolation.** `build.gradle.kts:10` hard-codes `~/.cache/antigravity-mobile-build/<project>`; two worktrees would collide. Make it honour an optional `-PagmBuildRoot=<dir>` (default unchanged). Push immediately; Lane B depends on it.
2. **`RuntimeContracts.kt`** (new file):
   - `ApprovalDecision { Approved, Declined, Cancelled, Interrupted }`, always bound to `taskId`, `actionId`, `toolCallId`, `buildId?`.
   - `sealed ToolOutcome`: `Success(data)`, `Failed(reason, logRef)`, `Cancelled`, `Interrupted`, `RuntimeUnavailable(reason)`.
   - `interface AgentTaskRunner { start; observe(): Flow<RunnerEvent>; answerApproval(ApprovalDecision); cancel }`.
3. **Promise:** the existing `AgentModel` signature in `Contracts.kt` stays unchanged. Lane B's adapters implement it. Any change goes through `REQUESTS.md`.

### Contracts you consume from Lane B (Lane B pushes these on Day 0)

- `ProviderContracts.kt`: `sealed ProviderFailure` (`Dns`, `NoNetwork`, `Timeout`, `StreamInterrupted`, `RateLimited(retryAfter?)`, `QuotaExhausted`, `TrialExpired`, `PricingChanged`, `AuthInvalid`, `Unknown`), `NetworkDiagnostics.diagnose(host): DiagnosticReport` (no credentials), `ProviderUsageStore` interface, `ProviderDescriptor`.
- `LinuxContracts.kt`: `TermuxGateway` (`status()`, `run(cmd): TermuxResult`), `LinuxRuntime` (`status`, `ensureInstalled`, `start`, `stop`, `storageUsage`, `cleanup`), `CliInstall` (binary path and version).

Until Lane B's real implementations land, build against fakes.

### Room tables you must create for Lane B in migration v4 (exact schema)

- `provider_models(providerId TEXT, modelId TEXT, toolCallingVerified INTEGER, verifiedAt INTEGER, PRIMARY KEY(providerId, modelId))`
- `provider_usage(id INTEGER PK AUTOINCREMENT, providerId TEXT, modelId TEXT, taskId TEXT NULL, inputTokens INTEGER NULL, outputTokens INTEGER NULL, requests INTEGER, recordedAt INTEGER)`

Provide a Room-backed implementation of Lane B's `ProviderUsageStore`.

## Phase A1 — Reliability (Gate 0.6.0, version code 12; you own this gate)

Verified defects to fix: approval uses an unkeyed Boolean response; Stop completes it as `false`; the agent describes every `false` as "the user declined"; successful tool execution receives a generic completed status even when the build outcome is failure. The exact cause of the user's approved-but-declined build still needs a phone trace; capture one.

1. **Durable, identifiable approvals.** Replace the Boolean with persisted `ApprovalDecision`. Consume atomically; ignore duplicate taps and stale responses. Show an approval receipt and the subsequent worker state. Only explicit Decline yields "user declined".
2. **Separate approval categories.** "Approve all edits" covers file edits only. Builds and APK installation keep their own prompts. Stop cancels pending work and reports cancellation.
3. **Real results.** Typed `ToolOutcome` everywhere. Persist APK references; installation may select a verified successful build from the same project across messages. Build reports and follow-up answers read recorded worker outcomes and logs.
4. **Keep tasks alive.** Move task ownership into a foreground service; screens only observe. Room migration **3 → 4** (including Lane B's tables), keeping `MIGRATION_1_2` and `MIGRATION_2_3` working; device-check both upgrade paths.
5. **Pause and resume.** Persist completed model turns, tool calls/results, change sets, build references, and task checkpoints. On any `ProviderFailure`, show "Paused: <reason>" plus Lane B's diagnostic recovery action and **Retry this provider**. Retry only the unfinished model request after checking recorded actions; never repeat completed writes, installs, or uncertain builds.
6. **Regression tests.** Approve once → exactly one build. Duplicate taps, stale prompts, Stop, backgrounding, rotation, process death, and worker death all give accurate states without replay. DNS failure, disconnection, network switching, timeout, and interrupted streaming preserve edits and history; retry continues safely.
7. **Gate.** Requires Lane B's Phase B1 (diagnostics) merged. Bump to 0.6.0 / code 12, keep worker version sync and the original signer, update checkpoint and compatibility evidence, and ask the owner before signing or publishing.

## Phase A2 — CLI bridge (Gate 0.8.0, version code 14; you own this gate)

Starts in parallel with fakes; integrates after Lane B's Phase B3 (Linux setup) and Gate 0.7.0 are merged.

1. **Bridge.** Paired, authenticated loopback helper running inside Debian, launched through Lane B's `TermuxGateway`. Supervises CLI processes, streams events, answers supported approvals, cancels tasks. Reject unpaired clients; treat all events as untrusted input.
2. **Runners.** `AgentTaskRunner` implementations for native providers (wrapping `AgentLoop`) and CLI agents. Each CLI owns its agent loop. One active task across both backends.
3. **Workspace.** Export a bounded project snapshot into a private runtime workspace. Return changes as a diff/archive and import through Changes with conflict checks. Exclude account storage and native Git internals.
4. **Codex.** Integrate the documented app-server protocol (chat, sessions, approvals, cancellation). Enable capabilities only after ARM64 execution and sandbox checks pass on the phone. <https://learn.chatgpt.com/docs/app-server>
5. **Antigravity CLI.** Connect documented headless streaming output to Agent chat. Expose the app's build/install operations as narrowly scoped MCP tools that keep native approval. Headless mode cannot accept interactive approvals; shell actions needing review open the terminal. Permission denial appears as `RuntimeUnavailable`, never as a user decline. <https://www.antigravity.google/docs/cli/headless/>
6. **Integrate Lane B's panel.** Insert `LinuxSetupPanel()` into `BuildScreen` and the backend selector into `AgentScreen`.
7. **Runtime tests.** Denied Termux permission, disconnected bridge, malformed events, CLI cancellation, conflicting imports.
8. **Gate.** Bump to 0.8.0 / code 14 with owner approval for signing/publishing.

## Final physical acceptance (shared, take the phone lock)

Reproduce the user's game-development task on the OnePlus 7 Pro: approve its build once, build the debug APK on the phone, install, launch, verify gameplay. Follow-up questions must retrieve the actual build result and log. Keep anything unverified explicitly marked unverified.

## Report back

At each gate: what changed, tests run (with real output), what is still unverified on the physical phone, exact commit IDs on local and GitHub, and the next step.
