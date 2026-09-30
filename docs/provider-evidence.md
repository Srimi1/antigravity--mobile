# Subscription evidence — 2026-09-30

Personal use is the intended scope. Provider documentation and a real entitled request must establish the route; a login screen, model catalog or successful build is insufficient.

| Provider | Interface and authentication | Subscription entitlement | Android status | Outcome |
| --- | --- | --- | --- | --- |
| Google | Antigravity first-party desktop login; SDK documents Gemini API key or Google Cloud credentials | Google Pro/Ultra supported in Google's app; no supported direct native third-party subscription integration established | No Android Antigravity package listed; no mobile subscription request executed | BLOCKED |
| ChatGPT | Documented Sign in with ChatGPT dynamic registration, loopback callback, PKCE, signed ID token; eligible Responses API requests | Requires explicit `chatgpt.tokens.use.direct` grant and completed inference; client eligibility must be established | Native probe implemented; no actual account sign-in or inference executed here | UNVERIFIED |
| Claude | Agent SDK or official Claude Code; documentation requires prior approval to offer claude.ai login or limits through third-party products | Applicable approved integration for this private Android implementation has not been established | No Android subscription request executed | BLOCKED |

## Primary evidence

- [Google desktop downloads](https://antigravity.google/download/) list macOS, Windows and Linux.
- [Google plans](https://antigravity.google/pricing) cover first-party Antigravity account access. Selecting Claude through Google is not linking an independent Claude subscription.
- [Antigravity SDK overview](https://antigravity.google/docs/sdk/overview) documents API-key setup and a Google Cloud route; neither proves consumer subscription sharing. The page was readable in the planning session; subsequent fetches were inconsistent, so no new consumer-auth capability is inferred.
- [OpenAI overview](https://developers.openai.com/siwc/token-sharing-open-source) documents an eligible open-source/local-app subscription route; private/native Android eligibility still needs live confirmation.
- [OpenAI sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in) specifies issued client registration, stable host ID, state, nonce, PKCE and loopback callbacks.
- [OpenAI inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference) requires `store:false`, `stream:true` and `response.completed`; the probe uses that public endpoint, not private ChatGPT endpoints.
- [OpenAI account lifecycle](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions) documents renewal and remote revocation. The discovery endpoint currently advertises RS256 ID-token signatures; the verifier rejects other algorithms.
- [Claude SDK overview](https://code.claude.com/docs/en/agent-sdk/overview) states the prior-approval requirement. This is a documented restriction, not a finding that every conceivable private integration is technically impossible.

## Required live evidence

For each provider: authorization/consent completed, entitlement confirmed, coding turn completed, renewal tested, logout tested, and no separate API billing used. Do not paste tokens into this document. Record only outcome, date, app version and non-secret error categories. Until all three pass, the full product gate remains blocked.

No provider was contacted with user credentials during this development run. No approval request was submitted to Anthropic or Google.
