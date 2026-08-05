#!/usr/bin/env sh
set -eu
GRADLE_VERSION=8.14.5
BASE_DIR=${GRADLE_USER_HOME:-"$HOME/.gradle"}/aether-bootstrap
DIST_DIR="$BASE_DIR/gradle-$GRADLE_VERSION"
ZIP_FILE="$BASE_DIR/gradle-$GRADLE_VERSION-bin.zip"
SHA_FILE="$ZIP_FILE.sha256"
URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"

if [ ! -x "$DIST_DIR/bin/gradle" ]; then
  mkdir -p "$BASE_DIR"
  echo "Downloading Gradle $GRADLE_VERSION..."
  if command -v curl >/dev/null 2>&1; then
    curl -L --fail --retry 3 -o "$ZIP_FILE" "$URL"
    curl -L --fail --retry 3 -o "$SHA_FILE" "$URL.sha256"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP_FILE" "$URL"
    wget -O "$SHA_FILE" "$URL.sha256"
  else
    echo "curl or wget is required" >&2
    exit 1
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    EXPECTED=$(tr -d '[:space:]' < "$SHA_FILE")
    ACTUAL=$(sha256sum "$ZIP_FILE" | awk '{print $1}')
    [ "$EXPECTED" = "$ACTUAL" ] || { echo "Gradle checksum mismatch" >&2; rm -f "$ZIP_FILE"; exit 1; }
  fi
  command -v unzip >/dev/null 2>&1 || { echo "unzip is required" >&2; exit 1; }
  rm -rf "$DIST_DIR"
  unzip -q "$ZIP_FILE" -d "$BASE_DIR"
fi
exec "$DIST_DIR/bin/gradle" "$@"
