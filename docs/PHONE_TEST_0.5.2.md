# Testing 0.5.2 (Claude with your Anthropic API key)

Download `antigravity-mobile-0.5.2.apk` from https://github.com/Srimi1/antigravity--mobile/releases/latest and tap **Update**.

**Cost warning:** a Claude API key is billed per use to your Anthropic Console credits. This is separate from any Claude Pro/Max subscription. Set a monthly spend limit in the Console first.

1. Open console.anthropic.com → Settings → API keys → **Create key**, and copy it. Add credits under Billing if needed.
2. Accounts → **Claude (Anthropic API key)** → paste the key → **Check and save**. Expected: "Key accepted by Anthropic … N Claude model(s) available".
3. **Test request** → expect "ready".
4. Select **Use for Agent** under Claude. The default model is Claude Opus 5.5; you can pick another under **Models**. Sonnet is cheaper.
5. In Agent, ask for a small change in a project, then "build and install it".

Send back pass/fail and screenshots of any error. Never send the key itself.
