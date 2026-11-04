#!/usr/bin/env bash
set -euo pipefail

VERSION="${JDTLS_VERSION:-1.63.0}"
RELEASE="${JDTLS_RELEASE:-202610191322}"
CACHE_ROOT="${JDTLS_CACHE_DIR:-${SPIKE_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/project-lightpath}}"
INSTALL_ROOT="$CACHE_ROOT/jdtls/$VERSION"
ARCHIVE="$CACHE_ROOT/jdt-language-server-$VERSION-$RELEASE.tar.gz"
BASE_URL="https://download.eclipse.org/jdtls/milestones/$VERSION"

if [[ -d "$INSTALL_ROOT/plugins" ]]; then
  printf '%s\n' "$INSTALL_ROOT"
  exit 0
fi

mkdir -p "$CACHE_ROOT"
if [[ ! -f "$ARCHIVE" ]]; then
  curl --fail --location --silent --show-error \
    "$BASE_URL/jdt-language-server-$VERSION-$RELEASE.tar.gz" \
    --output "$ARCHIVE"
fi

EXPECTED="$(curl --fail --location --silent --show-error \
  "$BASE_URL/jdt-language-server-$VERSION-$RELEASE.tar.gz.sha256" | awk '{print $1}')"
if command -v shasum >/dev/null 2>&1; then
  ACTUAL="$(shasum -a 256 "$ARCHIVE" | awk '{print $1}')"
else
  ACTUAL="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
fi
[[ "$EXPECTED" == "$ACTUAL" ]] || { echo "JDT LS checksum mismatch" >&2; exit 1; }

rm -rf "$INSTALL_ROOT"
mkdir -p "$INSTALL_ROOT"
tar -xzf "$ARCHIVE" -C "$INSTALL_ROOT"
printf '%s\n' "$INSTALL_ROOT"
