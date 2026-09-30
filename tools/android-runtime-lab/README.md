# Android runtime validation

This dedicated validation APK proves parts of the toolchain needed for Antigravity's local build workflow. Its package is `dev.srimi.antigravityruntime.lab`, with no account credentials. Generated data, binaries, build output and evidence live in `~/.cache/antigravity-mobile-runtime/`.

The [Compose investigation](../../docs/native-compose-qa-2026-09-30.md) records cache/SDK fixes, six regressions and a successful full Compose build/install/launch with interaction. The [evidence report](../../docs/native-runtime-qa-2026-09-30.md) records actual outcomes. The main Antigravity APK remains at 0.2.0; this runtime has not been integrated into its Build screen.

## Inputs and assembly

- Android/Bionic ARM64 OpenJDK 17.0.18 from MojoLauncher. Its release excludes compiler tools.
- Java-only compiler/tool classes from matching Temurin 17.0.18 Linux ARM64 JMODs. Desktop executables and native libraries are excluded.
- A consistent module image assembled with the development JDK's `jimage` and `jlink`. Vendor-specific cross-module hashes are removed from input descriptors and the system-module metadata is regenerated for the combined distribution. Source downloads have pinned SHA-256 values; the complete image/data archive has its own digest in the APK manifest.
- Android-native resource tools from lzhiyong/android-sdk-tools 35.0.2, alongside platform/build-tool Java data from the installed SDK 36.
- Official Gradle 8.13 distribution data. Gradle native integration, file watching and its instrumentation agent are disabled; compiler execution uses the in-process strategy.
- JNI libraries and executables installed through PackageManager. JDK-layout symlinks point to those real APK libraries. A worker-only loader shim translates `libjvm` metadata to its existing logical alias so HotSpot finds its boot image before parsing Java options.

The current Android port truncates tagged native pointers. The lab uses Android's legacy heap-tagging compatibility setting and public `mallopt` API. This does not prove MTE compatibility or suitability for shipping on arbitrary Android versions. Only Android 12 ARM64 has been exercised. All upstream runtime/compiler licence files are retained. Review complete corresponding-source and third-party notice requirements before redistributing a runtime APK.

## Run

Use Java 17, Android SDK 36 and NDK 27.2.12479018. Replace the development paths below with actual installed locations. `prepare.py` downloads pinned public source artifacts and checks their hashes; it does not run their installers.

```bash
python3 tools/android-runtime-lab/prepare.py \
  --sdk "$ANDROID_HOME" \
  --gradle /path/to/extracted/gradle-8.13 \
  --host-jdk "$JAVA_HOME"
./gradlew -p tools/android-runtime-lab \
  --project-cache-dir "$HOME/.cache/antigravity-mobile-runtime/gradle-cache" \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Install the two generated APKs from `~/.cache/antigravity-mobile-runtime/build/app/outputs/apk/` on a test emulator. The lab's UID and signing are separate from the personally signed Antigravity app. The tests create only lab scratch projects and a disposable signing fixture; never export the fixture keystore.

```bash
adb -s emulator-5556 shell am instrument -w -r \
  -e runtimeTaskId compose-your-fresh-run-id \
  -e class dev.srimi.antigravityruntime.AndroidRuntimeTest \
  dev.srimi.antigravityruntime.lab.test/androidx.test.runner.AndroidJUnitRunner
```

The Compose method requires `-e runtimeTaskId compose-<fresh-id>` and a new project/cache. Use `capture.py --adb /path/to/adb --serial emulator-5556 --task-id compose-<fresh-id> --output /path/outside/repo/new-directory` for a traced run. Existing output directories and incomplete action records are refused.

Individual methods can be selected with `-e class 'dev.srimi.antigravityruntime.AndroidRuntimeTest#methodName'`. Read the instrumentation result: `adb`'s exit code alone does not prove a test passed.

## Required next work

The full Compose scratch build/install/launch now passed; the earlier guest reboot remains unexplained. Integrate the verified toolchain into the Kotlin app with command approval, foreground execution, durable interruption records, bounded project operations and verified APK installation. Physical OnePlus testing, current-runtime security maintenance, supported subscription routes and live agent acceptance remain required.
