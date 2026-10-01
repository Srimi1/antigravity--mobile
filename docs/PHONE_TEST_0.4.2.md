# Testing 0.4.2 on your OnePlus 7 Pro

Download `antigravity-mobile-0.4.2.apk` from https://github.com/Srimi1/antigravity--mobile/releases/latest and open it. It updates 0.4.1 in place and keeps your projects.

## 1. ChatGPT (fixes from your report)

1. Accounts → **Reconnect** (or Continue with ChatGPT). A notification "Waiting for ChatGPT sign-in" should appear.
2. Sign in, tap **Continue**. The page should now say **"Signed in"** quickly. Tap **Return to Antigravity Mobile**.
3. Tap **Test request**.
   - If it says **"A subscription response completed"** → ChatGPT works. Send "hi" in Agent.
   - If it fails, the message now explains why (for example `OpenAI replied HTTP 200 (application/json): <code> — <message>`). **Send me a screenshot of that exact message.** It contains no password or token.

## 2. Gemini with a Google AI Studio key

1. Accounts → Gemini → **Get a key in AI Studio** → sign in with your Google account → **Create API key** → copy it.
2. Paste it into **Gemini API key** → **Check and save**. Expected: "Key accepted… N Gemini model(s) available".
3. **Test request** → expect "ready".
4. Select **Use for Agent** under Gemini (or keep ChatGPT if it works).
5. Agent → open a project → "list the files and explain this project". It may ask approval before any file change.

Note: this uses the Gemini API with your key, not your Google AI Pro subscription (Google forbids that for third-party apps). On the free tier Google may use what you send to improve its products. Never paste the key anywhere else or send it to me.

## What to send back

Pass/fail for ChatGPT steps 1–3 and Gemini steps 2–5, with screenshots of any error message.
