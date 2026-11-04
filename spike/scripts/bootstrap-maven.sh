#!/usr/bin/env bash
set -euo pipefail

MAVEN_VERSION="${MAVEN_VERSION:-3.9.11}"
CACHE_ROOT="${SPIKE_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/project-lightpath}"
INSTALL_ROOT="$CACHE_ROOT/apache-maven-$MAVEN_VERSION"
ARCHIVE="$CACHE_ROOT/apache-maven-$MAVEN_VERSION-bin.tar.gz"
BASE_URL="https://archive.apache.org/dist/maven/maven-3/$MAVEN_VERSION/binaries"

if [[ ! -x "$INSTALL_ROOT/bin/mvn" ]]; then
  mkdir -p "$CACHE_ROOT"
  if [[ ! -f "$ARCHIVE" ]]; then
    curl --fail --location --silent --show-error \
      "$BASE_URL/apache-maven-$MAVEN_VERSION-bin.tar.gz" \
      --output "$ARCHIVE"
  fi
  rm -rf "$CACHE_ROOT/apache-maven-$MAVEN_VERSION"
  tar -xzf "$ARCHIVE" -C "$CACHE_ROOT"
fi

printf '%s\n' "$INSTALL_ROOT/bin/mvn"
