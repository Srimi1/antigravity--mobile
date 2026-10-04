# Gemini with a Google account (0.7.2)

1. Install the signed 0.7.2 app update. Open **Accounts → Gemini with Google sign-in**.
2. Use **Allow**. If Android stopped showing its dialog, follow App info → Permissions → Additional permissions → Run commands in Termux environment → Allow.
3. Use **Copy setup and open Termux**, paste and press Enter. After **Done**, return to the app.
4. Tap **Sign in with Google**. The official Google installer selects its Android ARM64 client in Termux and verifies the downloaded checksum. Debian is not needed for this login. Choose **Google OAuth** and complete the browser sign-in in Google's own client.
5. Run a small coding request in the CLI to confirm a real Gemini response. Login completion, subscription entitlement, renewal/logout and physical-phone stability still need the owner's results.

The app does not read CLI tokens, OAuth codes or passwords. This login does not connect the native AI Studio-key account or unlock the app's Debian CLI Agent backend. That backend still requires a passing confinement check. Keys and API billing remain separate and are never selected automatically.

[Phone checklist](PHONE_TEST_0.7.2.md) · [Google authentication documentation](https://antigravity.google/docs/cli/install/) · [Official installer](https://antigravity.google/cli/install.sh) · [Google's CLI transition](https://developers.googleblog.com/an-important-update-transitioning-gemini-cli-to-antigravity-cli/)
