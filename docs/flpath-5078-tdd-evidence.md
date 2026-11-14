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

## Completed groups

### 4567038418 — nested ServiceLoader type resolution

- `RED_1`: commit `b0de78f860a21bccf95f60b859f03b71f6d8b7ac`; one behavioral
  failure, no production-path changes.
- `GREEN_1`: commit `93302de24dbd200921eed90a5f181707d2947746`; the same
  selector passed with one test.
- Sidecar hashes and parent provenance are append-only in
  `docs/flpath-5078-tdd-commits.jsonl`.

### 4567038425 — SootUp ServiceLoader edge classification

- `RED_2`: commit `63a856a9538f4ed380104a5e9d5ca75112ad5ecd`; one behavioral
  failure (`128` expected, `0` ServiceLoader facts), with no production-path changes.
- `GREEN_2`: commit `4c39df9d43b0b7c5d8f2657151419b156249e86c`; the same
  selector passed with 128 exact provider facts.
