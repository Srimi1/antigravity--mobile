# Testing 0.4.1 on your OnePlus 7 Pro

This is a private test build, not a public release. It has passed on emulators only (see [upgrade QA](upgrade-qa-2026-10-01.md)).

- File: `antigravity-mobile-0.4.1.apk`, 286,711,261 bytes
- SHA-256: `d4144955ede813a745de0177f2d5a9b374a107a0030b4b4ac55845ea323b55b7`
- Signer certificate SHA-256: `791980edfce3d623dfe1ba2e3209a506b01c4be7da74065805d400a5051210b5` (same key as the public 0.1.2, so it updates it in place)

## Before you start

1. **Settings → About phone:** note the Android version. The app needs **Android 10 or later** (OnePlus 7 Pro shipped with Android 9 and received updates up to Android 12).
2. **Settings → Storage:** have at least **3 GB free**. The app is 287 MB and installs a 262 MB build-tools app. A Compose build needs about 1.5 GB more.
3. Keep your existing Antigravity app. Do **not** uninstall it; this build updates it and keeps its data.
4. Wi-Fi on. The first Compose build downloads Gradle dependencies.

## Install

1. Download the APK on the phone (link sent separately) and open it from **Files / Downloads**.
2. If asked, allow installs from that app, go back, open the APK again, tap **Update** (or **Install**).
3. If Android says "App not installed" or "package conflicts", stop there and report the exact message. Do not uninstall anything.

## Tests (report pass/fail for each)

1. **Launch:** open Antigravity. Does it open within a few seconds? Any "isn't responding" dialog?
2. **Build tools:** Build tab → **Install build tools** (or **Update build tools**). Allow installs from Antigravity if asked, **go back and tap the button again**, then tap Install/Update. Does it end with **"Tools installed"**?
3. **Website:** Projects → **Website** → Create → **Website** tab → **Review preview** → **Approve and open**. Do you see "Hello Web", "Local JSON loaded", and does **Count** increase? Tap **Console**: what line appears?
4. **Edit:** close the preview, open `index.html`, add a word, **Save**, tap **Preview saved HTML** → approve. Is your word visible?
5. **Export:** Website tab → **Export website ZIP** → Save. Did it save?
6. **Android app build (takes 3–10 minutes):** Projects → All projects → **Compose app** → Create → Build tab → **Review build** → **Approve and build**. Keep the screen on and the app open. Report **completed/failed and "Elapsed"**. Then **Install artifact-0.apk** → **Open installed app** → tap **Count**. Does it show "Button presses: 1"?
7. **Heat and speed:** did the phone get very hot or lag badly during step 6?

## What to send back

- Android version and OxygenOS version (Settings → About phone).
- Pass/fail for 1–7, with the Elapsed time from step 6.
- Screenshots of any error. Build output text (Build → **Show output**) if step 6 fails.
- Do **not** send account passwords, tokens or private keys. ChatGPT sign-in is not part of this test.

Known limits: Claude and Google are blocked; ChatGPT is unverified; websites are static only; the agent cannot run commands.
