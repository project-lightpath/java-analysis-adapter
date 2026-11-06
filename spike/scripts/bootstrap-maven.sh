#!/usr/bin/env bash
set -euo pipefail

MAVEN_VERSION="${MAVEN_VERSION:-3.9.11}"
CACHE_ROOT="${SPIKE_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/project-lightpath}"
INSTALL_ROOT="$CACHE_ROOT/apache-maven-$MAVEN_VERSION"
ARCHIVE="$CACHE_ROOT/apache-maven-$MAVEN_VERSION-bin.tar.gz"
BASE_URL="https://archive.apache.org/dist/maven/maven-3/$MAVEN_VERSION/binaries"
CENTRAL_URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$MAVEN_VERSION"
ARCHIVE_URLS=(
  "$CENTRAL_URL/apache-maven-$MAVEN_VERSION-bin.tar.gz"
  "$BASE_URL/apache-maven-$MAVEN_VERSION-bin.tar.gz"
)

if [[ ! -x "$INSTALL_ROOT/bin/mvn" ]]; then
  mkdir -p "$CACHE_ROOT"
  if [[ -f "$ARCHIVE" ]] && ! tar -tzf "$ARCHIVE" >/dev/null 2>&1; then
    rm -f "$ARCHIVE"
  fi
  if [[ ! -f "$ARCHIVE" ]]; then
    downloaded=false
    for url in "${ARCHIVE_URLS[@]}"; do
      temporary="$ARCHIVE.part.$$"
      if curl --fail --location --silent --show-error \
          --retry 4 --retry-all-errors --retry-delay 2 \
          --connect-timeout 20 --max-time 300 \
          "$url" --output "$temporary"; then
        mv "$temporary" "$ARCHIVE"
        downloaded=true
        break
      fi
      rm -f "$temporary"
    done
    if [[ "$downloaded" != true ]]; then
      echo "unable to download Maven $MAVEN_VERSION from configured mirrors" >&2
      exit 1
    fi
  fi
  rm -rf "$CACHE_ROOT/apache-maven-$MAVEN_VERSION"
  tar -xzf "$ARCHIVE" -C "$CACHE_ROOT"
fi

printf '%s\n' "$INSTALL_ROOT/bin/mvn"
