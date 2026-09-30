# Website workflow QA — 1 October 2026

Scope: the 0.4.0 static-website workflow (main 0.4.0/code 6, companion 0.4.0-tools/code 2). Emulator and host evidence only. **No physical OnePlus, no live provider account, no public release.**

Baseline before this session: `f3e8592d3bdc5e1123c2616e68d9705e5a2285f6` (equal to fetched `origin/main`), with uncommitted website wiring from the previous session preserved and continued (Projects Website button/tab, `WebsiteService`, `WebsitePanel`, JVM and device tests).

## What the workflow does

1. **Projects → Website** creates a project from the packaged `samples/HelloWeb` template (HTML, CSS, JS module, local JSON, about page) and initialises Git.
2. Files are edited and saved in the normal editor. **Preview saved HTML** is disabled while the editor has unsaved text.
3. The **Website** tab selects a website folder (empty = project root, or e.g. `dist`) and an HTML entry inside it.
4. **Review preview** makes an immutable, bounded ZIP copy (hidden files and `node_modules` excluded, links refused, 64 MiB / 10,000 files) and shows its SHA-256 and file list. **Approve and open** claims that approval exactly once, sends the copy to the companion over the signed/exact-UID Messenger boundary, and opens `WebPreviewActivity` in the account-free worker UID.
5. Preview: Reload re-reads the approved copy only; Console shows `console.*` output and uncaught errors; Close deletes the extracted copy. The same preview ID cannot be opened again; interrupted/pending approvals become `INTERRUPTED` at next start and are never replayed.
6. **Export website ZIP** writes the chosen folder at the ZIP root through Android's document picker.

Still static only: no Node/npm/frontend build, backend server, external API access or deployment.

## Results

| Check | Result |
| --- | --- |
| JVM tests (`./tools/build.sh`) | **56 passed**, 0 failures/errors/skips (37 existing + 19 website: `WebFilesTest` 8, `WebsiteServiceTest` 7, `WebGuardTest` 4) |
| Main release lint + signed release | Passed. `dist/antigravity-mobile-0.4.0.apk`, 286,709,857 bytes, SHA-256 `58e23228d32e30085f1c9dfc99e29e0c458e804b2753f7066d31b8dd735c8200`, signer `791980edfce3d623dfe1ba2e3209a506b01c4be7da74065805d400a5051210b5` (original personal certificate). Private, unpublished. |
| Worker `lintRelease` + release assembly | Passed. `build-worker-release.apk` 261,989,776 bytes, SHA-256 `04a0cf4a4a526352520edbd84b996acc8ae0ea53a18f3218d68eb1ab65adbf78` (Gradle output path; may be overwritten). |
| `:build-worker:installDebug :app:connectedDebugAndroidTest`, AVD `AntigravityMobileQA_API36` (Android 16, ARM64, 4 GB, WebView 133.0.6943.137) | **20 passed**, 0 failures/errors/skips (16 existing + 4 `WebsiteDeviceTest`). XML: `assets/screenshots/web-20261001/api36-connected-final.xml`. |
| `WebsiteDeviceTest` on AVD `AntigravityMobileQA_API31` (Android 12, ARM64, 3 GB, **WebView 91.0.4472.114**) | **4 passed** with the final worker (`api31-website-final.txt`). The full 20-test suite was not rerun there: that AVD lacked free space for a new 560 MB main debug APK; the installed main debug (built 00:17, same main-app code) was used. |

### Defect found and fixed: preview refused on older WebView

The first API 31 run failed 3 website tests. Screenshot `api31-webview91-refused-before-fix.png`: the preview refused to start because WebView 91 lacks `DOCUMENT_START_SCRIPT`. Older phones without WebView updates would have had no website preview. Fix: `WebGuard` (now in `runtime-contract`) is injected into every served HTML response, right after any BOM/doctype, as a single line; document-start injection is still added when supported. A second defect found during manual QA (console line numbers shifted by the multi-line guard: `line 16` for line 1, `19-dist-console.png`) was fixed by making the injected guard one line (`24-console-lines.png`, `line 1`).

### Browser boundary evidence (both WebViews)

Asserted in `browserDeniesExternalFileContentPostWorkersAndPeerConnections`: external HTTPS, `file://`, `content://`, hidden `/.env`, POST, service-worker registration all denied; top-level `RTCPeerConnection`/`WebTransport` removed and locked.

Recorded but **not asserted** (JavaScript API removal is defense in depth, not the network boundary):

| Probe | API 36 / WebView 133 | API 31 / WebView 91 |
| --- | --- | --- |
| `about:blank` iframe via `contentWindow` / `window[0]` | undefined | undefined |
| `srcdoc` iframe's own `RTCPeerConnection` | undefined | **function (bypass)** |

**Known limitation:** on older WebView, a page can reach WebRTC from a `srcdoc` frame. The companion UID holds `INTERNET` (Gradle needs it), so HTTP interception is the real boundary and WebRTC is not fully denied. The UI and approval dialog claim only "HTTP network requests, file access and device permissions are blocked". A proper fix is hosting preview in a separate package without `INTERNET`. The previewed code is the user's own saved site; the preview has no file/content access, no JS bridge, fresh storage and no account data.

## Manual QA through visible controls (API 36)

Screenshots/UI XML in `assets/screenshots/web-20261001/`:

- `01-projects` → `02-website-dialog` → `03-created`: Website template created (6 files).
- `05-edited`: `index.html` edited; the dirty marker disables preview ("Save your edits before previewing."). `06-saved`.
- `07-approval`: approval shows root, entry, 6 files, SHA-256 `0fddc404…0192`, one-use notice.
- `08-preview`: saved edit `EDITED_ON_EMULATOR` rendered; module JS loaded local JSON ("Local JSON loaded"). `09-counted`: Count 0→1. `10-about`: local navigation. `11-console`: Reload + Console ("No console messages").
- Created `dist/index.html` via **New file**, containing a console warning and an undefined function. `17-website-tab` → folder `dist` → `18-dist-approval` (1 file, SHA-256 `8be51967…07a3`) → `19-dist-console` (warning + `Uncaught ReferenceError`, before the line-number fix) → `24-console-lines` (after fix, `line 1`).
- `21-saf` → `22-exported`: exported `Hello Web-website.zip` to emulator Downloads. Pulled copy `exported-dist.zip`: one entry `index.html` at the root; SHA-256 `8be519670d122ab85a5290158b01588124b47c3b3dce4b02a39d3d2b20f107a3`, identical to the approved preview copy (deterministic snapshot).
- After closing, the worker keeps only `opened`/`ready.json` markers per preview ID (extracted site deleted); main keeps only `approval.properties` (archives deleted). These tiny records are never pruned yet.
- A worker debug reinstall while main was open did not crash main; the next preview used the new worker. Main cold launch on API 36: 1,089 ms (`main-cold-launch.txt`), single uncontrolled sample.

Previous-session evidence copied as `prior-api36-*` (30 Sep 22:35): a **fresh** install of the embedded companion through Antigravity's Build tab and Android's installer ("Install unknown apps" → "Antigravity Build Tools" → "App installed" → "Tools installed"). This covers a first install only; the embedded **update** path code 1 → 2 through the UI and the personally signed release upgrade remain unvalidated (worker updates in this session used ADB with debug signing).

## Not done

- No physical OnePlus 7 Pro test; no live ChatGPT/Claude/Google account use.
- Embedded companion update via UI and signed-release in-place upgrade/data preservation on `AntigravityMobileProbe_API31`; first-upgrade ANR trace.
- WebRTC denial inside `srcdoc` frames on older WebView (see above).
- Node/package-manager builds, backend servers, external APIs, deployment.
- Pruning of per-preview marker/approval records.

Raw logs (outside Git): `~/.cache/antigravity-mobile-runtime/evidence/web-20261001/`.
