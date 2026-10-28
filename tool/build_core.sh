#!/usr/bin/env bash
# Builds the shared Kotlin core (tracking-core) as an XCFramework for iOS and
# copies it where the podspec expects it. Android needs no step: it compiles
# tracking-core/src/commonMain directly (see android/build.gradle.kts).
#
# Usage: tool/build_core.sh [debug|release]   (default: debug)
set -euo pipefail

flavor="${1:-debug}"
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root/tracking-core"

case "$flavor" in
  debug)   task="assembleTrackingCoreDebugXCFramework" ;;
  release) task="assembleTrackingCoreReleaseXCFramework" ;;
  *) echo "usage: $0 [debug|release]" >&2; exit 64 ;;
esac

./gradlew --no-daemon "$task"

rm -rf "$root/ios/Frameworks/TrackingCore.xcframework"
mkdir -p "$root/ios/Frameworks"
cp -R "build/XCFrameworks/$flavor/TrackingCore.xcframework" "$root/ios/Frameworks/"
echo "TrackingCore.xcframework ($flavor) -> ios/Frameworks"
