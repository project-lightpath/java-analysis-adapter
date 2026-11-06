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

# Keep the default distribution independently authenticated. Custom versions
# remain supported when the caller supplies their trusted digest explicitly.
case "$MAVEN_VERSION" in
  3.9.11)
    EXPECTED_SHA512="bcfe4fe305c962ace56ac7b5fc7a08b87d5abd8b7e89027ab251069faebee516b0ded8961445d6d91ec1985dfe30f8153268843c89aa392733d1a3ec956c9978"
    ;;
  *)
    EXPECTED_SHA512="${MAVEN_SHA512:-}"
    ;;
esac
EXPECTED_SHA512="$(printf '%s' "$EXPECTED_SHA512" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')"
if [[ ! "$EXPECTED_SHA512" =~ ^[0-9a-f]{128}$ ]]; then
  echo "Maven $MAVEN_VERSION has no trusted SHA-512 digest; set MAVEN_SHA512 for a custom version" >&2
  exit 1
fi

archive_digest() {
  if command -v sha512sum >/dev/null 2>&1; then
    sha512sum "$1" | awk '{print $1}'
  else
    shasum -a 512 "$1" | awk '{print $1}'
  fi
}

archive_layout_valid() {
  tar -tzf "$1" | grep -F -x "apache-maven-$MAVEN_VERSION/bin/mvn" >/dev/null
}

archive_verified() {
  local archive="$1"
  [[ -f "$archive" ]] \
    && [[ "$(archive_digest "$archive")" == "$EXPECTED_SHA512" ]] \
    && archive_layout_valid "$archive"
}

mkdir -p "$CACHE_ROOT"
LOCK_DIR="$CACHE_ROOT/apache-maven-$MAVEN_VERSION.lock"
LOCK_WAIT_SECONDS="${MAVEN_LOCK_TIMEOUT_SECONDS:-120}"
if [[ ! "$LOCK_WAIT_SECONDS" =~ ^[0-9]+$ ]]; then
  echo "MAVEN_LOCK_TIMEOUT_SECONDS must be a non-negative integer: $LOCK_WAIT_SECONDS" >&2
  exit 1
fi
LOCK_ACQUIRED=false
PENDING_SIGNAL=""
PENDING_STATUS=0
handle_signal() {
  PENDING_SIGNAL="$1"
  PENDING_STATUS="$2"
  if [[ "$LOCK_ACQUIRED" == true ]]; then
    exit "$PENDING_STATUS"
  fi
}
release_lock() {
  if [[ "$LOCK_ACQUIRED" == true ]]; then
    rmdir "$LOCK_DIR" 2>/dev/null || true
    LOCK_ACQUIRED=false
  fi
}
trap release_lock EXIT
trap 'handle_signal INT 130' INT
trap 'handle_signal TERM 143' TERM

LOCK_WAITED=0
while ! mkdir "$LOCK_DIR" 2>/dev/null; do
  if [[ -n "$PENDING_SIGNAL" ]]; then
    exit "$PENDING_STATUS"
  fi
  if (( LOCK_WAITED >= LOCK_WAIT_SECONDS )); then
    echo "timed out waiting for Maven cache lock: $LOCK_DIR" >&2
    exit 1
  fi
  sleep 1
  LOCK_WAITED=$((LOCK_WAITED + 1))
done
LOCK_ACQUIRED=true
if [[ -n "$PENDING_SIGNAL" ]]; then
  exit "$PENDING_STATUS"
fi

if [[ -x "$INSTALL_ROOT/bin/mvn" ]] && archive_verified "$ARCHIVE"; then
  printf '%s\n' "$INSTALL_ROOT/bin/mvn"
  exit 0
fi
if [[ -x "$INSTALL_ROOT/bin/mvn" ]]; then
  rm -rf "$INSTALL_ROOT"
fi
if [[ -f "$ARCHIVE" ]] && ! archive_verified "$ARCHIVE"; then
  rm -f "$ARCHIVE"
fi
if [[ ! -f "$ARCHIVE" ]]; then
  downloaded=false
  for url in "${ARCHIVE_URLS[@]}"; do
    temporary="$ARCHIVE.part.$$"
    if curl --fail --location --silent --show-error \
        --proto '=https' --proto-redir '=https' \
        --retry 4 --retry-all-errors --retry-delay 2 \
        --connect-timeout 20 --max-time 300 \
        "$url" --output "$temporary" \
        && archive_verified "$temporary"; then
      mv "$temporary" "$ARCHIVE"
      downloaded=true
      break
    fi
    rm -f "$temporary"
  done
  if [[ "$downloaded" != true ]]; then
    echo "unable to download and authenticate Maven $MAVEN_VERSION from configured mirrors" >&2
    exit 1
  fi
fi
if ! archive_verified "$ARCHIVE"; then
  rm -f "$ARCHIVE"
  echo "cached Maven $MAVEN_VERSION archive failed integrity or layout validation" >&2
  exit 1
fi
rm -rf "$INSTALL_ROOT"
tar -xzf "$ARCHIVE" -C "$CACHE_ROOT"
if [[ ! -x "$INSTALL_ROOT/bin/mvn" ]]; then
  rm -rf "$INSTALL_ROOT"
  echo "authenticated Maven $MAVEN_VERSION archive has no executable bin/mvn" >&2
  exit 1
fi

printf '%s\n' "$INSTALL_ROOT/bin/mvn"
