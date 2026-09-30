# JVM compile harness

Some cloud sandboxes block Google Maven (`dl.google.com`), so AGP, AndroidX and Room cannot be downloaded there. This harness is a fallback that still catches most mistakes:

- compiles all of `app/src/main` (including the Compose UI) as Kotlin/JVM against an API 36 `android.jar`, JetBrains Compose Desktop 1.8.0 (same APIs as the Compose BOM 2025.04.01) and small signature stubs in `stubs/` for Room, Activity, Lifecycle and FileProvider;
- runs every JVM unit test in `app/src/test` with real JGit, org.json and coroutines;
- compiles (does not run) `app/src/androidTest` with stubs in `devstubs/`.

It is **not** the product build. It does not run kapt/Room code generation, resource processing, lint, dexing or packaging, and stubs can drift from real signatures. Always run `./tools/build.sh` and the instrumentation tests on a machine with the Android SDK before releasing.

```bash
# android.jar: any API 36 platform jar. Default: ~/android-sdk/platforms/android-36/android.jar
export ANDROID_JAR=/path/to/android.jar
gradle -p tools/jvm-harness compileDeviceTestKotlin test
```

Gradle 8.14 and JDK 21 were used. When new AndroidX APIs are used in app code, add matching signatures to the stubs.
