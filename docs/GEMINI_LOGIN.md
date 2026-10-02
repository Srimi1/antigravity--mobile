# Gemini models with a Google login

Use Google's official **Antigravity CLI (`agy`)** for personal Google account access. Consumer Gemini CLI login moved to that product; enterprise and API-key routes differ. [Google announcement](https://developers.googleblog.com/an-important-update-transitioning-gemini-cli-to-antigravity-cli/).

The app has an adapter for `agy`, but it is **disabled** until its phone capability gate passes. Its account panel does not establish live inference. No direct native consumer subscription adapter or credential extraction is provided.

1. In **Build → Linux setup**, install Debian and the base packages in Termux. 0.7.0 includes the required `python3` package.
2. In **Accounts → CLI accounts**, install Antigravity CLI and open the Debian terminal.
3. Launch `agy`; complete Google's sign-in yourself inside its documented browser/keyring flow. Never paste passwords, OAuth codes or tokens into the app chat or a bug report. Android/proot browser and keyring compatibility remain unverified. [Official authentication guide](https://antigravity.google/docs/cli/install/).
4. Run `agy models` and choose an available Gemini model using `/model` or a documented `--model` slug. Model availability depends on your account. [Official model/headless guide](https://antigravity.google/docs/cli/headless/).
5. A real successful response is required to confirm access. App integration additionally needs a documented sandbox check that refuses outside-workspace writes on the phone. `--sandbox` alone is not evidence that confinement works.

Until those checks pass, the app's native **Gemini (AI Studio key)** provider is the existing, separately selected API route. It uses an API key and its API allowance/billing, not a Google AI Pro/Ultra login. No automatic fallback is used.
