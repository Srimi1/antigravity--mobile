# Testing 0.6.0 (reliable approvals, keyed builds, foreground agent tasks)

Download `antigravity-mobile-0.6.0.apk` from https://github.com/Srimi1/antigravity--mobile/releases/tag/v0.6.0 and tap **Update**. Do **not** uninstall 0.5.2 first: Update keeps your projects, chats, accounts and build history. Optional check: the SHA-256 in the release notes must match the file.

No USB cable or computer is needed. If something fails, take a bug report on the phone: Settings → System → Developer options → **Take bug report** → Interactive. Share that ZIP (it contains logs, never your keys).

## A. Update kept your data (Gate 0.6.0 upgrade check)
1. Before updating, note your project names, one Agent conversation and the last Build history entry.
2. After updating, open the app. Expected: no crash; Settings → Apps → Antigravity Mobile shows **0.6.0**; the same projects, conversation and build history are there; your provider in Accounts is still connected.

## B. "Approved but reported as declined" (Gate 0.6.0 trace)
1. Agent → pick your provider → in a Compose project ask: "build the debug APK".
2. When the build prompt appears, tap **Approve** once. Expected: exactly **one** build in the Build tab; the chat reports the real result (success or a compile error with a log), **never "declined"**.
3. Ask again and tap **Decline**. Expected: chat says declined; no build runs.
4. Ask again, then press **Back** on the prompt. Expected: no build runs and the chat does not claim you declined.
5. Start a build from the Build tab yourself, then tap **Cancel**. Expected: build stops and shows CANCELLED.
6. While the agent works, switch to another app for a minute. Expected: a notification shows the agent task; on return the task has continued or shows a Paused card with a reason and **Retry this provider**.

## C. Game task (final acceptance)
1. New project → **Compose app** → name it `Game`.
2. Agent: "Make a simple tap game: a moving target, a score, and a restart button. Then build and install it."
3. Approve the build **once** and the install **once**. Expected: one build, Android's installer opens, the game launches and the score increases when you tap the target.
4. Ask: "Did the build succeed and how long did it take?" Expected: the answer comes from the recorded build result and log, not a guess.

## Not in this build
Codex and Antigravity CLI backends stay switched off until they pass checks on this phone. Their panel saying "unavailable" is expected. Claude Pro/Max and Google subscriptions remain blocked.

Send back pass/fail per step (A1–A2, B1–B6, C1–C4), screenshots of any error and the bug report ZIP if something failed. Never send keys or passwords.
