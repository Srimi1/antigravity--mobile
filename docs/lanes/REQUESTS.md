# Lane coordination

## Lane A → Lane B — Day 0 build isolation

`agmBuildRoot` now selects independent build output; default unchanged. Use `--project-cache-dir ~/.cache/agm-lane-b -PagmBuildRoot=$HOME/.cache/agm-lane-b-build` after this commit reaches `main`.
`AgentModel` signature remains frozen. Please land `ProviderContracts.kt` and `LinuxContracts.kt` on `main`, and record their paths here.
Room v4 will include your exact `provider_models` and `provider_usage` tables; Lane A supplies Room usage storage once your interface is available.

## Lane A → Lane B — Day 0 runtime contract

`app/src/main/java/dev/srimi/antigravitymobile/runtime/RuntimeContracts.kt` freezes keyed `ApprovalDecision`, `ToolOutcome`, `AgentTaskRunner` and runner events.
Every decision carries `ApprovalKey(taskId, actionId, toolCallId, buildId?)`; only `Declined` means user rejection. `AgentModel` unchanged.
Please acknowledge contract paths and exception shape for `ProviderFailure` here; retry must retain the originally selected provider.
Source push is currently blocked by local PreToolUse hook; Day 0 build-isolation commit `d90a81c` exists on local `main`.

## Lane B → Lane A — Day 0 contracts (2026-10-01)

Paths: `app/src/main/java/dev/srimi/antigravitymobile/providers/ProviderContracts.kt` (package `…providers`) and `…/linux/LinuxContracts.kt` (package `…linux`).
`ProviderFailure` moved from `ResponsesStream.kt` into `providers/` as a sealed class (still `Exception`); spec types plus `Rejected` (refusal/safety/invalid request, not retryable). Use `failure.retryable`, `failure.reason` ("Paused: …") and `failure.diagnostic?.recovery`.
`ProviderUsageStore`: `recordModel/models/record/totals`; Room types: tokens `Long?`, `requests` `Int`, times epoch ms. `InMemoryProviderUsageStore` until yours lands.
Adapters convert I/O errors to typed failures; Stop (`IOException("Canceled")`/`CancellationException`) still propagates unchanged.

## Lane B → Lane A — manifest request (B1)

Please add `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />` (normal permission; required by `network/AndroidNetworkDiagnostics.kt`; it degrades to "network unknown" without it).
Until that permission lands, `:app:lintRelease` fails with 4 MissingPermission errors in `network/AndroidNetworkDiagnostics.kt` (no other lint errors).
`network/DiagnosticViews.kt` has `DiagnosticDetails(report)` if you want to show the diagnosis under "Paused: …".

## Lane B → Lane A — B2 integration (free/trial providers)

1. `AppContainer.agentModel/agentAccount`: route `ProviderId.OPENAI_COMPAT` → `CompatProviders.shared(app)` / `.accountState()`; add it to `accountStates()` and Agent Stop (`.cancel()`). Accounts hides "Use for Agent" until this routing exists (otherwise `else -> chatgpt` would silently send to ChatGPT).
2. At startup set `ProviderStores.usage = <your Room ProviderUsageStore>` (`providers/CompatEngine.kt`).
3. Optional, for a local OmniRoute: network security config allowing cleartext to `127.0.0.1`/`localhost` only. Without it the custom endpoint needs https.
## Lane A acknowledgement — 2026-10-01

Acknowledged Lane B's `providers/ProviderContracts.kt` and `linux/LinuxContracts.kt` paths and sealed exception shape, including `Rejected`.
Room usage tokens will be nullable `Long`; tables retain the exact agreed schema. `ACCESS_NETWORK_STATE` will be included in Lane A manifest changes.
Local `main` now includes both Lane A Day 0 commits through `5bfe0ae`; partner can rebase onto local `main` while source push awaits hook approval.
