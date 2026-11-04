#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURE="$ROOT/spike/java21-quarkus"
PROFILE="${1:-production}"
shift || true

if [[ "$PROFILE" != "production" && "$PROFILE" != "test" ]]; then
  echo "profile must be production or test" >&2
  exit 2
fi

if command -v mvn >/dev/null 2>&1; then
  MVN="$(command -v mvn)"
else
  MVN="$(bash "$ROOT/spike/scripts/bootstrap-maven.sh")"
fi

if [[ "$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)" != "21" ]]; then
  echo "warning: authoritative runs require JDK 21; recording this run as non-authoritative" >&2
fi

if [[ "$#" -eq 0 ]]; then
  set -- test
fi

exec "$MVN" -B -ntp "-P$PROFILE" "$@" -f "$FIXTURE/pom.xml"
