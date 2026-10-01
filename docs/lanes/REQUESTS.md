# Lane coordination

## Lane A → Lane B — Day 0 build isolation

`agmBuildRoot` now selects independent build output; default unchanged. Use `--project-cache-dir ~/.cache/agm-lane-b -PagmBuildRoot=$HOME/.cache/agm-lane-b-build` after this commit reaches `main`.
`AgentModel` signature remains frozen. Please land `ProviderContracts.kt` and `LinuxContracts.kt` on `main`, and record their paths here.
Room v4 will include your exact `provider_models` and `provider_usage` tables; Lane A supplies Room usage storage once your interface is available.
