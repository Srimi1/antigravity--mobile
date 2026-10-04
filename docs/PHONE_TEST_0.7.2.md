# Phone test: 0.7.2

[Download the signed APK](https://github.com/Srimi1/antigravity--mobile/releases/download/v0.7.2/antigravity-mobile-0.7.2.apk) and choose **Update**. Keep the existing app installed.

1. Open **Accounts → Gemini with Google sign-in**.
2. If **Allow** appears, tap it. Choose Allow in Android's dialog; if App info opens, use **Permissions → Additional permissions → Run commands in Termux environment → Allow**, then return. OnePlus wording may vary.
3. If **Copy setup and open Termux** appears, tap it, long-press in Termux and choose Paste, then press the keyboard's Enter key. Wait for **Done. Return to Antigravity Mobile.**, then return. The extra-key **↹** is Tab, not Enter.
4. Tap **Sign in with Google**. The first launch downloads the official Android CLI in Termux. Keep Termux open; choose **Google OAuth**, then finish sign-in in your browser. Return to Termux as directed by Google's client.
5. Send one small request in the official CLI and confirm a real Gemini response. A login page alone does not confirm subscription access.
6. Check **Open Termux** and the Debian **Open terminal/Open CLI** actions if Debian is installed. A missing Debian install or missing CLI should give a clear error.

This login stays inside Google's CLI. The native AI Studio-key account is separate, and the app's CLI Agent sandbox checks remain required. No automatic paid fallback is used.

Report pass/fail for Allow, Open Termux, first download, browser opening and the coding response. Include app version and non-secret error text. Never send passwords, keys, OAuth codes or tokens.
