# Subscription evidence — updated 4 October 2026

## Google Android CLI login — 4 October 2026 (0.7.2)

The owner's request to fix Gemini login adds Accounts → **Gemini with Google sign-in**. It opens Google's official Android/Bionic Antigravity CLI inside Termux; Google handles OAuth, browser callback and credential storage. No credentials are imported into this app. The official [installer](https://antigravity.google/cli/install.sh) now selects `android_arm64` in Termux and checks its public manifest's SHA-512. The current client used in QA was 1.2.16, verified and executed on Android 12 ARM64. The app's real first-install action downloaded it in Termux and displayed Google OAuth; choosing it opened Google's sign-in page for Google Antigravity.

**Completed account sign-in, entitlement and inference are still unverified.** Debian's CLI Agent backend remains sandbox-gated; this launcher does not enable it or transfer its account session. Native Gemini AI Studio keys remain separate, user-selected API access. [Official authentication](https://antigravity.google/docs/cli/install/) and [Google's consumer CLI transition](https://developers.googleblog.com/an-important-update-transitioning-gemini-cli-to-antigravity-cli/) describe the first-party route. The earlier direct-native Google restriction remains; it must not be read as denying the existence of Google's own CLI.

## Google account route — 2 October 2026

Consumer Google login should use the **official Antigravity CLI (`agy`)**, with the owner signing in inside that client. [Google's transition announcement](https://developers.googleblog.com/an-important-update-transitioning-gemini-cli-to-antigravity-cli/) says consumer Gemini CLI requests stopped on 18 June 2026; enterprise and API-key access are separate. [Official authentication](https://antigravity.google/docs/cli/install/) and [headless/model selection](https://antigravity.google/docs/cli/headless/) document the client route. See [GEMINI_LOGIN.md](GEMINI_LOGIN.md).

This corrects any older blanket claim that no Google account route exists. A supported direct native subscription adapter has not been established. **The app's Antigravity CLI backend remains disabled:** no documented headless sandbox self-test has been established and passed on the physical phone. No owner sign-in, real model response or signed-in protocol trace was collected. Gemini AI Studio keys remain a separate user-selected API provider.

## Claude via Anthropic API key — 1 October 2026 (0.5.2)

Added at the user's explicit request after being told it is billed per use and is not their Claude Pro/Max subscription. Implementation uses the official Anthropic Java SDK. Verified only against a local mock server and with a fake key (real HTTP 401 from Anthropic on an Android emulator). **No real key or paid request has been run.** Claude Pro/Max subscription access remains BLOCKED.

## Physical phone report — 1 October 2026 (0.4.2)

User reported the app working on the phone and was ready to start coding. They asked for more ChatGPT models; per-provider request outcomes were not itemised. Request to bypass Google's subscription restrictions was declined (terms of service, account-suspension risk).

## Physical phone report — 1 October 2026 (0.4.1, user's OnePlus 7 Pro; outcomes only)

- **ChatGPT sign-in:** consent granted in the browser; after "Continue" the callback page kept loading until the user returned to the app, then the app showed connected and listed models. Likely cause: OxygenOS/Android paused the backgrounded app, so its loopback callback server could not answer. 0.4.2 keeps a foreground service running during sign-in and adds a "Return to Antigravity Mobile" link.
- **ChatGPT inference:** every Agent message and "Test request" failed with "Expected streaming response": OpenAI answered HTTP 2xx without an event-stream content type. 0.4.1 discarded the body, so the real reason is still unknown. 0.4.2 parses event-stream bodies regardless of label, accepts a completed non-streamed Response, and otherwise shows status, content type and OpenAI's error code/message (secrets redacted). **ChatGPT remains UNVERIFIED** until a request completes on the phone.
- **Google:** user requested Google access. Google's terms do not allow third-party apps to reuse Google AI Pro/Ultra logins; Google suspended accounts doing so in 2026 and removed consumer "Login with Google" for Gemini CLI on 18 June 2026 ([summary](https://syntackle.com/blog/google-gemini-ai-subscription-with-opencode/), [Gemini API OAuth docs](https://ai.google.dev/gemini-api/docs/oauth)). **At the user's explicit request (1 Oct 2026), 0.4.2 adds Gemini through the user's own Google AI Studio API key** — a documented, key-based route, labelled as not the subscription. Free-tier data-use and billing are disclosed in the UI. Live key error path verified on an emulator (real Google HTTP 400 `INVALID_ARGUMENT` for a fake key); **a real key and agent turn are untested.**
- Claude: unchanged, BLOCKED.


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
