#!/bin/sh
set -e
GRADLE_VERSION="8.7"
GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
DIST_DIR="$GRADLE_HOME/wrapper/dists/gradle-${GRADLE_VERSION}-bin"
mkdir -p "$DIST_DIR"
GRADLE_BIN=$(find "$DIST_DIR" -name "gradle" -type f 2>/dev/null | head -n 1)
if [ -z "$GRADLE_BIN" ]; then
  ZIP_FILE="/tmp/gradle-${GRADLE_VERSION}-bin.zip"
  curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o "$ZIP_FILE"
  unzip -qo "$ZIP_FILE" -d "$DIST_DIR"
  GRADLE_BIN=$(find "$DIST_DIR" -name "gradle" -type f 2>/dev/null | head -n 1)
  chmod +x "$GRADLE_BIN"
fi
exec "$GRADLE_BIN" "$@"
