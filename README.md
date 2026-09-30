# Antigravity Mobile — personal validation prototype

For agents continuing this project, read [AGENTS.md](AGENTS.md) and the [saved checkpoint](docs/PROJECT_CHECKPOINT.md) first.

![Antigravity Mobile artwork](assets/branding/repository-cover.png)

<img src="assets/branding/app-icon.png" width="128" alt="Antigravity Mobile app icon" />

[Download the latest signed APK](https://github.com/Srimi1/antigravity--mobile/releases/latest) · [Release details](release/README.md)

This is **stage 1 of the requested plan**, not the completed multi-provider agent app.
The plan requires subscription access and an on-phone Android build toolchain to pass before building the full interface.
Those gates are currently blocked. See [compatibility report](docs/compatibility-report.md) and [provider evidence](docs/provider-evidence.md).

Version **0.1.2** includes the original adaptive launcher icon, round launcher support and an Android 13+ themed-icon silhouette. The repository artwork and icon sources are in [assets/branding](assets/branding/README.md).

<img src="assets/screenshots/launcher.png" width="240" alt="New icon on the Android emulator launcher" /> <img src="assets/screenshots/prototype.png" width="240" alt="Validation prototype running on the Android emulator" />

## Implemented

- A native Kotlin/Compose app for Android 10+ and ARM64, privately sideloaded.
- Local file read/write, change checkpoints, diff capture and conflict-aware rollback.
- A real packaged Android/Bionic executable: output, exit status, explicit command approval and cancellation.
- Persistent Room check history; unfinished checks become interrupted after process death and are never replayed.
- Experimental ChatGPT-plan OAuth with fresh state/nonce/PKCE, loopback callback, signed ID-token validation, encrypted credentials, streaming inference, renewal and logout.
- A complete minimal Kotlin/Compose sample project generated into app-private storage.
- APK selection and Android installer launch, plus device metrics and JSON report export.

## Not implemented or verified

Google/Claude subscription adapters, the full agent loop, repository cloning/commits, a general-purpose terminal, and a bundled JDK/Gradle/Android build toolchain. Generating the sample source does **not** mean it can be compiled on the phone. No Google or Claude credentials are imported. No API-key billing fallback exists.

## Build the probe

Requires Java 17, Android SDK platform 36, build tools 36.0.0 and NDK 27.2.12479018. Gradle 8.13 is pinned by the wrapper. Kotlin 2.1.21 and AGP 8.10.1 are pinned. The initial probe APK is built on a development machine; this is distinct from proving future on-phone project builds.

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  "platforms;android-36" "build-tools;36.0.0" "ndk;27.2.12479018"
./tools/build.sh
```

The output is `dist/antigravity-mobile-probe-0.1.2.apk`. The script generates a local signing key once in `.signing/personal.p12`. Keep that file privately to install future updates; deleting it generates a different signer and requires uninstalling the previous app. The fixed keystore password protects this disposable development container only; filesystem permissions protect the private key. Use a separately managed key before promoting beyond the probe.

Temporary app build output lives in `~/.cache/antigravity-mobile-build`; the script keeps Gradle's project cache in `~/.cache/antigravity-mobile-gradle`. This avoids iCloud creating conflicting copies of generated compiler files. Source and delivered artifacts stay in this project.

Builds also work from `sdk.dir` in an ignored `local.properties` file. Set `ANDROID_HOME` when using the build script. No SDK paths or private signing keys are included in the source delivery.

## Run checks on the phone

1. Install the probe APK using Android's installer. Android 10 or later and ARM64 are required.
2. Run the file/rollback check, approve the executable and nonzero-exit checks, then run cancellation.
3. Generate the sample project. Compilation remains blocked until an Android-host toolchain is integrated.
4. If eligible for OpenAI's documented locally hosted app flow, use **Continue with ChatGPT**, explicitly grant plan usage, return to the app, and run coding inference, renewal and disconnect. Do not enter an API key. These actions consume your account's authorized allowance when successful.
5. Export the JSON compatibility report. It contains device metrics and check results, not credentials, account email, prompts or generated code.

Network loss and access denial leave a failed check. Reconnect explicitly. If logout cannot confirm remote revocation, the history says so; disconnect the app in ChatGPT Settings. The app can request eligible Responses API access, but Android eligibility and account entitlement are not proven by source code or a compiled APK.

## Development checks

```bash
./gradlew :app:testDebugUnitTest :app:lintRelease
./gradlew :app:connectedDebugAndroidTest
./gradlew -p samples/HelloPhone :app:assembleDebug
```

Device tests use a separate test credential file and never overwrite a saved ChatGPT account. They verify native execution/cancellation, recovery records and credential encryption. JVM tests cover workspace traversal/symlink boundaries, rollback conflicts and signed-token validation. No unit test is represented as a live subscription test.

The command probe launches only its fixed native test program, which creates no child processes. That is not proof of safe cancellation of arbitrary shell process trees. File path checks are not a shell sandbox. Checkpoint persistence currently saves file copies; the probe's review metadata is in-memory, so durable agent-edit recovery remains future work.
