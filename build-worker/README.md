# Android build worker

Experimental companion package `dev.srimi.antigravitymobile.worker`, version code 1 (`0.3.0-tools`). It shares the main app's signing certificate but has its own Android UID and private storage. The main APK embeds its matching variant as `build-worker.apk`; Build installs it through Android's installer. Development installation uses `:build-worker:installDebug` before main instrumentation tests.

Prepare pinned inputs with `tools/android-runtime-lab/prepare.py` (root README), then build normally. Assets/native binaries come from `~/.cache/antigravity-mobile-runtime/lab-generated`, and all generated Gradle output stays outside iCloud. No runtime binaries or signing keys belong in Git. Release uses the existing personal signer; debug variants share the development signer.

The Messenger protocol requires both the signature permission and exact main-app UID. Replies are checked against the worker UID. Only a read-only approved source descriptor, tasks, digest and new ID cross the boundary. Project build scripts execute inside worker storage, with internet access, and can access other worker files; this is not a sandbox between build projects. Main account storage is never mounted or transferred.

A foreground service persists an atomic ledger before launching, permits one build, gives each build a fresh Gradle cache, captures bounded output and transfers read-only APK descriptors after success. Stop terminates worker-owned children. A new worker process stops orphaned children and marks unfinished work interrupted. The main Room v3 ledger consumes approval once and observes uncertain outcomes; it never repeats START after a timeout or process death. Completed artifact transfer can be retried without recompiling.

The current profile is ARM64 Android/Bionic Java 17.0.18, Java tool modules, Gradle 8.13, Android-native resource tools 35.0.2 with SDK/platform/Build Tools data 36. It requires the documented legacy heap-tagging compatibility setting and public allocator/path shim. See the runtime/Compose/integration evidence for validation and compromises. No Linux native tools, Termux, desktop compiler service, root or cloud build is used for project execution.

Physical OnePlus, other Android versions, wider projects/languages, native-memory/MTE behavior, security maintenance and complete redistribution notices/corresponding-source obligations remain unaccepted. This module is not a release authorization or full-product acceptance.
