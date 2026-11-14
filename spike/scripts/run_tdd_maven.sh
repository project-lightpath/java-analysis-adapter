#!/usr/bin/env bash
set -euo pipefail

selector="${1:?usage: run_tdd_maven.sh CLASS#METHOD}"
: "${JAVA21_HOME:?Set JAVA21_HOME to the pinned Temurin 21.0.13 home}"

export JAVA_HOME="$JAVA21_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

evidence_dir="${TDD_EVIDENCE_DIR:-docs/flpath-5078-tdd}"
mkdir -p "$evidence_dir"
version_file="${TDD_JAVA_VERSION_FILE:-$evidence_dir/java-version.txt}"
java -XshowSettings:properties -version 2>"$version_file"
grep -q 'java.version = 21\.0\.13' "$version_file"
grep -q 'java.vendor = Eclipse Adoptium' "$version_file"

MAVEN_BIN="$(bash spike/scripts/bootstrap-maven.sh)"
exec "$MAVEN_BIN" -B -ntp \
  -Dsurefire.failIfNoSpecifiedTests=true \
  -Dsurefire.failIfNoTests=true \
  -Dtest="$selector" test -f pom.xml
