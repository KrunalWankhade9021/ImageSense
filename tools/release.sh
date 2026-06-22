#!/usr/bin/env bash
# tools/release.sh — cut a GitHub release with the APK, from your machine.
#
# Run locally (not in CI) because it needs the ~230MB ONNX models, which live
# only here. It verifies, builds the APK, then creates the GitHub release.
#
# Usage:
#   ./tools/release.sh v0.2.0                    # build debug APK + release
#   ./tools/release.sh v0.2.0 --release          # build signed release APK instead
#   ./tools/release.sh v0.2.0 --notes "text…"    # custom release notes
#   ./tools/release.sh v0.2.0 --draft            # create as a draft
#
# Requires: gh CLI authenticated (gh auth status), and the models present in
# app/src/main/assets/models/ (generate with tools/export_model.py + quantize).
set -euo pipefail

TAG="${1:-}"
if [[ -z "$TAG" || "$TAG" == --* ]]; then
  echo "Usage: ./tools/release.sh <vX.Y.Z> [--release] [--draft] [--notes \"…\"]" >&2
  exit 1
fi
shift

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
cd "$(dirname "$0")/.."

VARIANT="debug"
DRAFT=""
NOTES=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --release) VARIANT="release" ;;
    --draft)   DRAFT="--draft" ;;
    --notes)   NOTES="$2"; shift ;;
    *) echo "Unknown flag: $1" >&2; exit 1 ;;
  esac
  shift
done

# Sanity checks -------------------------------------------------------------
command -v gh >/dev/null || { echo "gh CLI not found. Install it and run 'gh auth login'." >&2; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "gh not authenticated. Run 'gh auth login'." >&2; exit 1; }
if ! ls app/src/main/assets/models/*/*.onnx >/dev/null 2>&1; then
  echo "No .onnx models found. Generate them first (see SETUP.md) before releasing." >&2
  exit 1
fi

# Verify --------------------------------------------------------------------
echo "==> Running unit tests…"
./gradlew :app:testDebugUnitTest --no-daemon

# Build ---------------------------------------------------------------------
if [[ "$VARIANT" == "release" ]]; then
  echo "==> Building release APK…"
  ./gradlew :app:assembleRelease --no-daemon
  APK=$(ls -t app/build/outputs/apk/release/*.apk | head -1)
else
  echo "==> Building debug APK…"
  ./gradlew :app:assembleDebug --no-daemon
  APK=$(ls -t app/build/outputs/apk/debug/*.apk | head -1)
fi
echo "==> APK: $APK"

# Release -------------------------------------------------------------------
echo "==> Creating GitHub release $TAG…"
ARGS=(release create "$TAG" "$APK" --title "ImageSense $TAG")
[[ -n "$DRAFT" ]] && ARGS+=("$DRAFT")
if [[ -n "$NOTES" ]]; then
  ARGS+=(--notes "$NOTES")
else
  ARGS+=(--generate-notes)
fi
gh "${ARGS[@]}"

echo "==> Done."
