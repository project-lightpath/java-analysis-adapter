# FLPATH-5078 TDD evidence

This ledger starts from the approved baseline and records immutable
`RED -> GREEN -> EVIDENCE` commits. Implementation-first results from the
quarantine worktree are not accepted as TDD evidence.

## Baseline

- Approved baseline: `a87b6fcb20f001e0722d8d951ba66f4aafca6e75`
- TDD branch: `feature/flpath-5078-tdd`
- Pinned runtime: Temurin `21.0.13` (Eclipse Adoptium)
- Pinned runtime home: `/Users/jgil/.cache/project-lightpath/jdk/jdk-21.0.13+9/Contents/Home`
- Baseline worktree status: clean at worktree creation
- Baseline committed diff from approved baseline: empty
- Quarantine snapshot manifests: `/private/var/folders/r7/gktmmltd1zq7wqhsjjslwsm80000gn/T/opencode/flpath-5078-quarantine/`

## Baseline verification

The exact command was run with both Surefire no-test guards:

```sh
export JAVA21_HOME="/Users/jgil/.cache/project-lightpath/jdk/jdk-21.0.13+9/Contents/Home"
export JAVA_HOME="$JAVA21_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
MAVEN_BIN="$(bash spike/scripts/bootstrap-maven.sh)"
"$MAVEN_BIN" -B -ntp -Dsurefire.failIfNoTests=true \
  -Dsurefire.failIfNoSpecifiedTests=true test -f pom.xml
```

Result: exit code `0`; `69` tests passed, `0` failures, `0` errors, `0`
skipped. The complete output is in
`docs/flpath-5078-tdd/baseline-test.log`.

Evidence hashes:

- `java-version-baseline.txt`: `b8046fd5bc7f6b1d2541037719dbee012cb9a1db0a9cd765d3fa3b877d11380c`
- `baseline-test.log`: `c1d06a7c944cbf129b05d0dd3b400f95268cd674d69c9c3be012c9a7e1c194bc`

## TDD rules

- A red record must prove a behavioral assertion failure from a selected test;
  compilation-only failures are rejected.
- Production source is unchanged in every red commit and its tree must equal
  the previous evidence commit.
- A green commit has the red commit as its direct parent and records a passing
  rerun of the same selector.
- An evidence commit appends hashes and provenance without amending red or
  green commits.
- Performance guards run three times under the pinned toolchain and preserve
  complete output, test count, and `/usr/bin/time -p` data.
