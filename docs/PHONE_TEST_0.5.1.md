# Testing 0.5.1 (Agent can build and install) on your OnePlus 7 Pro

Download `antigravity-mobile-0.5.1.apk` from https://github.com/Srimi1/antigravity--mobile/releases/latest and tap **Update**.

1. **Build** tab: if it says **Install build tools** or **Update build tools**, tap it. Allow installs from Antigravity if asked, go back, tap again, then **Install/Update**. Wait for **Tools installed**.
2. Projects → **Compose app** → Create (or open your own Android project from GitHub).
3. **Agent** → ask: *"Change the greeting text to 'Built by the agent', then build the app and install it."*
4. Approve the file edit. The agent then asks to **Build on phone** and shows the Gradle task and snapshot. Tap **Approve**. Keep Antigravity open; the build takes 3–10 minutes.
5. If the build fails, the agent reads the error log and should fix the code and ask to build again.
6. When it asks to **Install**, approve. Android's installer opens; tap **Install**, then open the app.

Send back: did the agent build it (and how long), did install work, and screenshots of any error.
