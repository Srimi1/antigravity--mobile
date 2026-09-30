#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
[[ -d "$ANDROID_HOME/ndk/27.2.12479018" ]] || {
  echo 'Install NDK 27.2.12479018 with sdkmanager before building.' >&2
  exit 1
}
mkdir -p .signing
chmod 700 .signing
if [[ ! -f .signing/personal.p12 ]]; then
  "${JAVA_HOME:+$JAVA_HOME/bin/}keytool" -genkeypair -keystore .signing/personal.p12 \
    -storetype PKCS12 -storepass local-probe -keypass local-probe -alias personal-probe \
    -keyalg RSA -keysize 3072 -validity 3650 -dname 'CN=Personal Mobile Probe' -noprompt
fi
chmod 600 .signing/personal.p12
./gradlew --project-cache-dir "$HOME/.cache/antigravity-mobile-gradle" :app:testDebugUnitTest :app:lintRelease :app:assembleRelease --console=plain
VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)"
APK="dist/antigravity-mobile-$VERSION.apk"
mkdir -p dist
cp "$HOME/.cache/antigravity-mobile-build/app/outputs/apk/release/app-release.apk" "$APK"
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs "$APK"
shasum -a 256 "$APK" > dist/SHA256SUMS
