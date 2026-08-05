#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

echo "AETHER ENGINE M18 VERIFICATION"
echo "=============================="

python3 tools/static_audit.py
python3 tools/m18_report.py

if [[ "${1:-}" == "--source-only" ]]; then
  echo "M18 SOURCE VERIFICATION PASS"
  exit 0
fi

test -x ./gradlew
./gradlew --no-daemon --version
./gradlew --no-daemon --stacktrace clean \
  :app:processDebugMainManifest :app:processReleaseMainManifest \
  :app:mergeDebugResources :app:mergeReleaseResources
./gradlew --no-daemon --stacktrace \
  :engine-core:compileKotlin :engine-core:compileTestKotlin \
  :engine-platform-android:compileDebugKotlin :engine-platform-android:compileReleaseKotlin \
  :app:compileDebugKotlin :app:compileReleaseKotlin
./gradlew --no-daemon --stacktrace :engine-core:test :engine-core:check
./gradlew --no-daemon --stacktrace \
  :engine-platform-android:lintDebug :engine-platform-android:lintRelease \
  :app:lintDebug :app:lintRelease
./gradlew --no-daemon --stacktrace :app:assembleDebug :app:assembleRelease

test -s app/build/outputs/apk/debug/app-debug.apk
test -s app/build/outputs/apk/release/app-release-unsigned.apk

echo "M18 ANDROID VERIFICATION PASS"
