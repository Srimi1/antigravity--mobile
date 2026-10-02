# Testing 0.7.1 (Termux permission fix)

Download `antigravity-mobile-0.7.1.apk` from https://github.com/Srimi1/antigravity--mobile/releases/tag/v0.7.1 and tap **Update**. Do not uninstall first.

Open **Build → Linux on this phone (Termux)**.

## Step 2 — permission
1. Tap **Allow**. Either Android's dialog appears (choose **Allow**), or the **App info** page opens.
2. On App info (OnePlus wording may differ slightly): **Permissions → Additional permissions → Run commands in Termux environment → Allow**. Then go back to Antigravity Mobile.
3. Expected: step 2 shows **Granted** by itself (no Refresh needed).

## Step 3 — Termux accepts commands
1. Tap **Copy**, then **Open Termux**.
2. In Termux, long-press the black screen → **Paste** → press Enter on the keyboard. **Nothing is printed — that means it worked.** Do not tap the **⇆** key (that is Tab; it causes "Display all … possibilities?" — if you see that, press `n`).
3. Go back to Antigravity Mobile. Expected: step 3 shows **Allowed**, and Debian 12 shows **Install**.

## Then
4. Tap **Install** (needs Wi-Fi, several minutes; keep Termux installed and the phone awake).
5. Accounts → run the Antigravity CLI / Codex check again. Expected: no "not allowed to run commands in Termux" error. (CLIs still need you to sign in inside Termux; they stay switched off until their checks pass.)

Send pass/fail for steps 2.1–5 and a screenshot of anything unexpected. Never send passwords or keys.
