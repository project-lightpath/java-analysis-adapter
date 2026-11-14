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

### 4567038430 — ServiceLoader expression ownership

- `RED_3`: commit `75e3884029b0375ecf21662815f3f624ab72b057`; valid and
  unrelated `record()` calls were both classified as ServiceLoader evidence.
- `GREEN_3`: commit `4acf48a7b905d5016f823690f68f58007e9672b7`; only the
  provider callback remains a ServiceLoader fact.

### 4567038440 — block-lambda ServiceLoader ownership

- `RED_4`: commit `02abf9cb77612df9a75a0770516bca15935b5861`; the callback
  was missed after an inner block statement.
- `GREEN_4`: commit `d1217b940ac97f0af4cce850b41cb068ac0822be`; nested delimiter
  depth preserves the callback and rejects the outer statement.

### 4567750966 — nested custom-loader ServiceLoader evidence

- `RED_5`: commit `56ade83cab157c9df61861989f1e8ee70687a50e`; the inner
  custom-loader fact incorrectly borrowed the sibling descriptor.
- `GREEN_5`: commit `1357f0d1ec1055c59dd25ff41e3a2b3b24f7442c`; site selection
  keeps the fact possible and removes unrelated descriptor evidence.

### 4567750984 — same-line ServiceLoader statement boundaries

- `RED_6`: commit `88a956fe133797155692cf159b4926988fb832dc`; an unrelated
  invocation after a closing lambda brace was classified as ServiceLoader.
- `GREEN_6`: commit `abcac6eae9307d02da19ec83ae2eff49413676de`; lexical site
  matching accepts a closing-brace suffix and rejects a second same-line statement.

### 4567750975 — ServiceLoader source-model caching

- `RED_7`: commit `ee27b9dc34975f4d0c619a649747f461018dc70c`; repeated-edge
  cache reuse was absent.
- `GREEN_7`: commit `887cc149bd239e65a1ac489c2700a9e08386f434`; source text and
  parsed ServiceLoader sites are reused per scanner and source file.

### 4567750992 — skipped WAR ServiceLoader metadata

- `RED_8`: commit `d77869f6c72275ed5e34d809f6def347a298e042`; a WAR root descriptor
  was not observed and nested metadata had no blocking diagnostic.
- `GREEN_8`: commit `efa7e3f6c2b8cfd9c886f7034f66973d9b9838da`; root descriptors are
  scanned while `WEB-INF/classes` and nested `WEB-INF/lib` metadata emits a diagnostic
  that prevents a definite provider resolution.

### 4567750996 — archive decompression budget diagnostics

- `RED_9`: commit `6a902a9b4b966a40f24eaad5fd43808d9332b27e`; a consumed archive
  budget skipped `module-info.class` without `archive_decompression_limit` evidence.
- `GREEN_9`: commit `160ba51963b744905b9e97d09262b4b10ad17352`; archive entries are
  streamed against a cumulative decompression budget and skipped module entries are diagnosed.

### 4567751004 — module provider visibility

- `RED_10`: commit `e4ae8c1ac7ec88806d0aa3728236321d1b9d01`; module-info provider
  metadata was not observed.
- `GREEN_10`: commit `c12fa3b4e4a34a24af8dc9fe5ad7a2b297738c2f`; module provides
  declarations are retained as `POSSIBLE` with `moduleVisibility=not_proven`.

### 5896913850 — explicit-loader wildcard dependency evidence

- `RED_11`: commit `512b6f461f81fd27409a0aa3bf83f7aee49f3121`; wildcard dependency
  metadata was not scanned, so clean explicit-loader evidence was absent.
- `GREEN_11`: commit `c1b06e807038d65d54b20c202d864c1e044b7507`; descriptor scanning
  uses the same resolved wildcard dependency entries as SootUp and retains explicit-loader
  results as `POSSIBLE`.

### 4567751011 — BOM-prefixed provider metadata

- `RED_12`: commit `d1018fc08e9456d3129428af02858f2667920a43`; the BOM entry was
  malformed but the possible-resolution limitation did not explain that metadata failure.
- `GREEN_12`: commit `fcde3c541a7444d20e002cf9b24ab80e87bca93c`; BOM-prefixed provider
  names remain malformed and the resolution limitation reports malformed metadata.

### 4567751015 — ordered provider normalization

- `RED_13`: commit `912141ae1d536a22fbacd6a2fe2c4653d7f4f0cc`; 100,000 valid provider
  names exceeded the five-second normalization bound.
- `GREEN_13`: commit `bb1a249f386195a9efed0014dd9d7ec60feb34d0`; ordered-set normalization
  preserves encounter order, removes duplicates, and completes within the bound.

### lexical-ownership-regression — lambda receiver ownership

- `RED_14`: commit `7373d8d7fd3ee4eaf995514658ffbbc13907949a`; a provider lambda was
  rejected when an unrelated trailing lambda invocation shared the statement.
- `GREEN_14`: commit `8de0ee244f03b55d64f04c8fe261bf949340f917`; target source lines and
  lambda receiver ownership distinguish the provider call from the unrelated sibling.

### nested-load-ownership-regression — nested source-line ownership

- `RED_15`: commit `4026fbf92c128507f87a97f850e62e706328a654`; the outer nested load
  borrowed the inner target line and prevented the inner custom-loader fact from being
  classified as `service_loader`.
- `GREEN_15`: commit `651122813529a1bcf9aef62dcc12eb0a14dc4eb2`; source-line matching now
  uses the first target occurrence for each load, preserving nested-load ownership.

### coverage-gate-mapping — JaCoCo executable-line classification

- `RED_16`: commit `125a19075bb5320a83b611a447081647ea3b1d93`; the coverage gate
  had no classification for changed source lines without JaCoCo instructions.
- `GREEN_16`: commit `73064e7dfc28d2c6172e8803f590606bb54bbec2`; unmapped and zero-counter
  source lines are recorded as non-executable while executable lines remain subject to
  the unchanged 90% threshold.

### coverage-runtime-identity — pinned Java vendor probe

- `RED_17`: commit `a0532381eb5d73a64f4f8a54145567b32350c0e5`; the validator rejected
  the pinned Temurin runtime because vendor identity was read from the wrong Java output mode.
- `GREEN_17`: commit `46f8e669b0078bbcb91c39d5a2abf101b3beabac`; runtime validation now reads
  `-XshowSettings:properties` and accepts the pinned `java.version` property.

## Final gate evidence

- The authoritative JaCoCo run at corrected production `HEAD f0ace3e21ab6a5f2e4ac68f5b04a8e87fd1f8f85`
  used Temurin 21.0.13 (Eclipse Adoptium), JaCoCo 0.8.13, and the pinned no-zero-test
  Surefire flags; all `98` full-suite tests passed.
- The changed-production threshold intentionally uses the complete adapter suite,
  including its SootUp integration tests. Full-suite changed-production coverage passed
  at `91.44%` (`844/923` executable changed lines), above the required `90%` threshold.
  The canonical report SHA-256 is
  `65e3c9ad330d22610940beef14e8fd36cf82cea1eb466bd6d79a7875c0b1ff93`; the raw report
  SHA-256 is `54c1d7c446dc8e978ae7f626589925e300e8c0b61c5165ae19ed815cb8b9c30f`.
- Additional unit-only evidence passed `86/86` tests at `90.68%` changed-production
  coverage (`837/923`). Its canonical report SHA-256 is
  `8f5a852433b34be4b6c2f656e1bd6071e4e4390f78d2fac27086aa676ab9e398`; the raw report
  SHA-256 is `f2b9192bafa02a515bb95ff7ff80428fd8945521c660a0d52bfec29fb9f37e9f`.
  Complete machine-readable evidence is in `docs/flpath-5078-tdd/changed-coverage.json`
  and `docs/flpath-5078-tdd/unit-coverage.json`.

- Raw Maven/JDK output sidecars retain their exact bytes for immutable evidence hashes.
  `.gitattributes` limits the trailing-space exemption to those raw `.log` and `.txt`
  sidecars; source, tests, JSON, and Markdown remain covered by `git diff --check`.

### directory-descriptor-budget — bounded directory metadata

- `RED_18`: commit `53d952fe2044f25edce6a05762a9d1a3367f3ab3`; a large directory
  descriptor was read and fingerprinted without the shared decompression budget.
- `GREEN_18`: commit `4c2350832931fc4f6d95a78e4ba139392a439dd2`; directory descriptors
  now stream through the bounded reader and emit the existing budget diagnostic.

### jacoco-report-digest — stable report provenance

- `RED_19`: commit `bcebe5e59fc8e573a3c52ba85b9d8374324d35db`; volatile JaCoCo session
  metadata made equivalent reports hash differently.
- `GREEN_19`: commit `69317d2386d9ad1468e441f81446191dcc4a3095`; report evidence now stores
  a canonical hash with session metadata removed and preserves the raw report hash separately.

### metadata-boundary-and-enumeration — authorized bounded descriptor inputs

- `RED_20`: commit `7c040cfba9a513a9038eef45283de1d4c058aa07`; application output metadata was
  not scanned, an observed descriptor outside selected inputs was accepted, and archive/directory
  enumeration had no bound.
- `GREEN_20`: commit `11c774b2fc8ad492169f8afb5d285ddfd62d1802`; application output metadata is
  scanned, external paths are diagnosed and skipped, and archive/directory enumeration plus
  descriptor reads share explicit bounds.

### lexical-source-masking — comment and literal exclusion

- `RED_21`: commit `454b03e7b463d319a3908bb43c37ee4f99c2c222`; raw comments, string literals,
  and text blocks produced false ServiceLoader call sites.
- `GREEN_21`: commit `943af6bc43dfc89a922ec8818973cbba5121a773`; the parser masks non-code text
  while preserving source offsets and line numbers before matching calls.

### post-evidence-quality-and-review-fixes

- `e8b0d55200127ea7a5d2a7bf981fe7fcbcf495dd` extracts the lexical masker and archive
  enumeration helpers without changing the `GREEN_20`/`GREEN_21` behavior. Focused
  ServiceLoader tests pass `7/7`; the direct Java lint gate reports `0` new PMD findings
  and `0` CPD duplications.
- `f52ea0514148957ade9ff2da4ebae13d2d3c6fa9` records the independent-review fixes in
  `EVIDENCE_22`; focused ServiceLoader tests pass `9/9` and the full suite passes `92/92`.

### independent-review-safety-gaps

- `RED_22`: commit `7365409871dcf606ca56c20a013afa7f9960c050`; independent review regressions
  reproduced method-reference false attribution, symlink traversal, invalid declared-path failure,
  and unbounded wildcard dependency expansion.
- `GREEN_22`: commit `f52ea0514148957ade9ff2da4ebae13d2d3c6fa9`; unsupported method references now
  abstain, descriptor authorization uses real-path containment, malformed paths are diagnosed and
  skipped, and cached wildcard dependency expansion is bounded with a diagnostic.

### independent-review-follow-ups

- `RED_23`: commit `2a420a19f2b58fc719d72603e50bbd6163afb987`; regressions covered raw target text,
  duplicate output/resource descriptors, archive-order dependence, and symlinked dependencies.
- `GREEN_23`: commit `73e303905280098d47b18a15096b0d4b2c483e16`; ownership matching masks comments
  and literals, identical descriptors are deduplicated for uniqueness, bounded subsets retain the
  deterministic smallest entries, and dependency/SootUp and descriptor scanning share a no-follow
  symlink policy with cached wildcard-limit diagnostics and fail-fast propagation.
- Final follow-up evidence at this production HEAD passes `95/95` full-suite tests and `83/83`
  unit-only tests; changed-production coverage was remeasured at `91.82%` (`819/892`) full-suite
  and `91.03%` (`812/892`) unit-only.

### independent-review-budget-and-nested-lambda

- `RED_24`: commit `c6443fa125e591d5c45a7d76f8c45555a7904c18`; a compiled nested lambda
  invocation was misattributed as `service_loader`, enumeration crossed the 1,024-entry budget,
  and a large WAR emitted one diagnostic per skipped nested metadata entry.
- `GREEN_24`: commit `8be6a11f19f039ec9b7ffb8ccc33fb48014d989a`; ownership uses the outermost
  containing lambda, directory/archive/wildcard traversal stops at the shared budget, and WAR
  diagnostics are capped with a suppression summary. Focused regressions pass `5/5`; the
  matching class-selector run passes `75/75`.
- Final follow-up coverage at this production HEAD passes `97/97` full-suite tests and `85/85`
  unit-only tests; changed-production coverage is `91.36%` (`825/903`) full-suite and `90.59%`
  (`818/903`) unit-only.

### independent-review-qualified-receiver-and-order-independence

- `RED_25`: commit `8ef9dbe1c73857a3824a506fc0651170bd8d6772`; a qualified `this.provider`
  receiver was misattributed as `service_loader`, and overflowing directory/archive/wildcard
  scans returned order-dependent prefixes.
- `GREEN_25`: commit `f0ace3e21ab6a5f2e4ac68f5b04a8e87fd1f8f85`; ownership now requires a bare
  lambda-parameter receiver, while overflow is fail-closed for directory/archive descriptors and
  wildcard dependency entries. Focused regressions pass `3/3`.
- Final follow-up coverage at this production HEAD passes `98/98` full-suite tests and `86/86`
  unit-only tests; changed-production coverage is `91.44%` (`844/923`) full-suite and `90.68%`
  (`837/923`) unit-only.

### independent-review-api-authorization-and-io

- `RED_26`: commit `465250a51db134ab67911d395c7faea9f4322f92`; a custom imported
  `ServiceLoader` lookalike was parsed as the Java API, repository-root metadata scanned an
  unselected descriptor, and unreadable descriptor directory/file inputs aborted analysis.
- `GREEN_26`: commit `cd76de68e09780efa93ca79b0be712d7674fd323`; unqualified calls now require
  `java.util.ServiceLoader` import/package ownership, repository-root-wide authorization is
  removed while selected metadata locations remain supported, and descriptor I/O failures emit
  diagnostics. Focused regressions pass `3/3`.

### lint-regression-after-authorization-fix

- `RED_27`: commit `c7e9de1f27afd3e9eb49c0db31e85381e248f2b1`; the production lint gate
  reported one new `GodClass` finding after the descriptor authorization and I/O changes.
- `GREEN_27`: commit `5e199cccceb329451474bd7ea34f401d0e0afccb`; descriptor path authorization
  moved into a focused helper, restoring the reviewed lint baseline. The production gate reports
  zero new PMD findings and zero CPD duplications.

### final-authoritative-gates-at-e820583

- Final full-suite validation at measured HEAD `e820583ea2f1942dbf7d48837fdbbe57f8a11b43`
  passes `101/101`; unit-only validation passes `89/89`.
- Changed-production JaCoCo coverage is `874/956 = 91.42%` full-suite and
  `867/956 = 90.69%` unit-only, both above the `90%` threshold, using Eclipse Adoptium
  Temurin `21.0.13` and JaCoCo `0.8.13`.
- `make evidence-validate` passes all `10` criteria; the TDD ledger validator passes `27/27`.
  Java lint-plugin tests pass `16/16`; production lint reports `0` new PMD findings and
  `0` new CPD duplications. Engram diagnostics report no warning-or-higher findings in
  the three changed production files.
- Machine-readable details and artifact hashes are in
  `docs/flpath-5078-tdd/final-validation.json`.

### independent-review-wildcard-service-loader-shadowing

- `RED_28`: commit `c8097f59a837f1546c0f237f6c56386710308b43`; a compiled same-package
  custom `ServiceLoader` shadowed `java.util.*`, but the parser and adapter emitted a
  Java ServiceLoader relationship.
- `GREEN_28`: commit `681546857347707c4a93a553600eb923f3df8fd0`; wildcard imports are no
  longer treated as ownership proof, so ambiguous same-package calls abstain. The focused
  regression passes `1/1`.

### final-authoritative-gates-after-wildcard-shadowing-fix

- Final full-suite validation at measured HEAD `fb92474b2781d7b3aac2c8ee8204697864945d85`
  passes `102/102`; unit-only validation passes `90/90`.
- Changed-production JaCoCo coverage is `903/984 = 91.77%` full-suite and
  `894/984 = 90.85%` unit-only, both above the `90%` threshold, using Eclipse Adoptium
  Temurin `21.0.13` and JaCoCo `0.8.13`.
- `make evidence-validate` passes all `10` criteria; the TDD ledger validator passes `28/28`.
  Java lint-plugin tests pass `16/16`; production lint reports `0` new PMD findings and
  `0` new CPD duplications. Engram diagnostics report no warning-or-higher findings in
  the three changed production files.
- The wildcard-import shadowing regression is covered by `RED_28`/`GREEN_28`; ambiguous
  same-package calls now abstain instead of emitting false Java ServiceLoader facts.
- Machine-readable details and artifact hashes are in
  `docs/flpath-5078-tdd/final-validation.json`.
