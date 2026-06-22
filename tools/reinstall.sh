#!/usr/bin/env bash
# tools/reinstall.sh — build, (re)install and launch NLPhotos on a connected device.
#
# Usage:
#   ./tools/reinstall.sh            # build + install (keeps app data/permissions)
#   ./tools/reinstall.sh --clean    # uninstall first, then fresh install
#   ./tools/reinstall.sh --shot     # also grab a screenshot to /tmp/nlphotos.png
#   ./tools/reinstall.sh --offline  # use Gradle's offline cache (faster, needs warm cache)
#   flags can be combined, e.g. ./tools/reinstall.sh --clean --shot
set -euo pipefail

PKG="com.nlphotos"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

CLEAN=0
SHOT=0
OFFLINE=""
for arg in "$@"; do
  case "$arg" in
    --clean)   CLEAN=1 ;;
    --shot)    SHOT=1 ;;
    --offline) OFFLINE="--offline" ;;
    *) echo "Unknown flag: $arg" >&2; exit 1 ;;
  esac
done

# Move to the project root (parent of this script's dir) regardless of where it's run from.
cd "$(dirname "$0")/.."

echo "==> Checking for a connected device…"
if ! adb get-state >/dev/null 2>&1; then
  echo "No device/emulator found. Plug in the phone (USB debugging on) and retry." >&2
  adb devices
  exit 1
fi
adb devices

if [ "$CLEAN" -eq 1 ]; then
  echo "==> Uninstalling existing $PKG (clean install)…"
  adb uninstall "$PKG" || true
fi

echo "==> Building + installing debug APK…"
./gradlew :app:installDebug $OFFLINE --no-daemon

echo "==> Launching $PKG…"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null

if [ "$SHOT" -eq 1 ]; then
  OUT="/tmp/nlphotos.png"
  echo "==> Waiting for UI, then capturing screenshot → $OUT"
  sleep 3
  adb exec-out screencap -p > "$OUT"
  echo "Screenshot saved to $OUT"
fi

echo "==> Done."
