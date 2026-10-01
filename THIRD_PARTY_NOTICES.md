# Third-party components in Antigravity Mobile APKs

Versions **0.3.0, 0.4.0 and 0.4.1** embed a companion "Antigravity Build Tools" APK that contains a development toolchain. Versions 0.1.x and 0.2.0 do not. This list was prepared by the project author; it has not been reviewed by a lawyer, and completeness is not guaranteed. Open an issue if something is missing.

| Component | Version | Licence | Source |
| --- | --- | --- | --- |
| OpenJDK runtime for Android (MojoLauncher build) | 17.0.18 | GPL-2.0 with Classpath Exception | https://github.com/MojoLauncher/android-openjdk-build-multiarch (runtime from the `rolling` release `jre17-pojav.zip`); upstream https://github.com/openjdk/jdk17u |
| Eclipse Temurin JDK class modules (Java-only compiler/tool classes) | 17.0.18+8 | GPL-2.0 with Classpath Exception | https://github.com/adoptium/temurin17-binaries/releases/tag/jdk-17.0.18%2B8 ; source https://github.com/adoptium/jdk17u |
| Gradle distribution | 8.13 | Apache-2.0 (bundled libraries under their own licences, included in the distribution) | https://github.com/gradle/gradle/tree/v8.13.0 |
| Android SDK build tools for ARM64 (lzhiyong build: aapt2, zipalign and others) | 35.0.2 | Apache-2.0 (AOSP sources) | https://github.com/lzhiyong/android-sdk-tools/releases/tag/35.0.2 ; AOSP https://android.googlesource.com |
| Android SDK platform and build-tools Java data (android.jar, d8/R8 and related jars) | 36 | Android Software Development Kit License Agreement and the Apache-2.0 notices of their AOSP sources | https://developer.android.com/studio/terms ; https://android.googlesource.com |
| Kotlin compiler and standard library | 2.1.21 | Apache-2.0 | https://github.com/JetBrains/kotlin |
| AndroidX, Jetpack Compose, Room, WebKit | per `gradle/libs` / build files | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support |
| Eclipse JGit | 5.13.5 | Eclipse Distribution License 1.0 (BSD-3-Clause) | https://github.com/eclipse-jgit/jgit |

Upstream licence files shipped with the runtime are kept inside the companion APK's runtime archive.

**GPL source offer.** For three years from each release date, the corresponding source for the GPL-licensed OpenJDK components distributed in these APKs can be requested by opening an issue at https://github.com/Srimi1/antigravity--mobile/issues. It is also available from the upstream projects listed above. The exact download URLs and SHA-256 values used are in `tools/android-runtime-lab/prepare.py`.

**Android SDK.** Some bundled Java data comes from Google's Android SDK. Their redistribution is governed by the Android SDK License Agreement. The project author has chosen to publish these builds for personal sideload testing. If a rights holder objects, the affected assets will be withdrawn.

Antigravity Mobile's own source is in this repository.
