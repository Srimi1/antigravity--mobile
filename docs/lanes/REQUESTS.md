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
