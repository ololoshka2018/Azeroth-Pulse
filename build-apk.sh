#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to an Android SDK with platform 35 and Build Tools 35.0.0}"
if [ -n "${GRADLE_BIN:-}" ]; then
  "$GRADLE_BIN" :app:assembleRelease "$@"
else
  bash ./gradlew :app:assembleRelease "$@"
fi
version=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' app/src/main/AndroidManifest.xml)
apk="build/Azeroth-Pulse-$version.apk"
mkdir -p build
python3 scripts/sign_release.py app/build/outputs/apk/release/app-release-unsigned.apk "$apk"
