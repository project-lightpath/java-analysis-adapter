# RESOLUTION-16 — javac-backed source-symbol resolver accuracy and GO/NO-GO

Issue: project-lightpath/java-analysis-adapter#16
Branch: `spike/javac-symbol-resolver` (baseline `origin/main` at `0ced20a`)
Harness: `src/test/java/com/projectlightpath/analysis/java/JavacResolverAccuracyTest`
Observation: `spike/results/javac-symbol-resolver-observation.json`

## Recommendation

**GO.**

The javac/Trees prototype produced **zero wrong DEFINITEs**, **zero lost
facts**, **zero downgraded facts**, and **zero added facts** across the 68-cell
catalogue. The textual baseline produced 17 wrong DEFINITE proxies, 16 lost
facts, 10 added facts, and 6 missing abstentions on the same cells. The
prototype is deterministic across fresh JVM runs and stays inside the
`-Xmx256m` child.

This is an accuracy GO, not a production-adoption GO: the prototype is
test-scope only. Migration is a separate follow-up (issue filed after this
report).

## Baseline vs prototype (all 68 cells)

| Metric | Textual baseline (`0ced20a`) | javac prototype |
|---|---:|---:|
| wrong DEFINITE | 17 | **0** |
| wrong service type | 0 | **0** |
| lost facts | 16 | **0** |
| downgraded facts | 0 | **0** |
| added facts | 10 | **0** |
| unexpected facts | 10 | **0** |
| abstention mismatches | 6 | **0** |
| wall time (68 cells) | 190 ms | 1,622 ms |
| peak sampled heap | 189.0 MB | 189.0 MB |

Baseline wrong-DEFINITE cells: `N20 N21 N22 N23 A9 A10 A11 A13 A14 A15 A16b
A17 A20 I1 I2 I3 I5`.
Baseline lost-fact cells: `N20 N21 N22 N23 N28 N32 A1 A4 A7 D1 D3 D4 D6 D7 D8
D9`.
Baseline added-fact cells: `N20 N21 N22 N23 A10 A13 A14 A16b A17 A20`.

The baseline number is a **site proxy**, not production DEFINITE: it drives
`ServiceLoaderSourceCache.source(scanner, caller)` and canonicalizes each site
with `resolveReferenceType`. It does not claim
`ServiceLoaderDescriptorScanner.isUnique` or a descriptor-backed promotion.
The comparison is therefore conservative for the baseline and still shows the
gap.

## Coverage

- **JLS precedence/scope (N-series, 34 cells):** on-demand import, same-package
  type, single-type import, same-CU top-level/nested/local, field/parameter,
  lambda, catch, instanceof, try-with-resources, for-each, inherited member,
  fully qualified escape, lambda × local class, catch × nested type, switch
  pattern, lambda × switch, same-CU × inherited, static single-member import,
  a non-class-literal `load` negative control, and the three
  `extends`-resolution branches with public/package-private/protected/private
  members.
- **PR #12 A-series (20 cells):** A1-A17, A16b, A20, A21. A18/A19 are covered
  as parameter-type probes. A2/A5/A10/A13/A14/A16b/A17/A20 are ambiguous and
  correctly produce no fact; A3 is a clean static single-member import shadow;
  A8/A9/A11/A15/I5 are incomplete and produce POSSIBLE with a diagnostic.
- **`$` owners (9 cells):** top-level `$` names, nested vs top-level
  `Outer$Inner`, leading `$`, adjacent `$$`, single-character `$`, package
  `$`, anonymous/local `$1`, and prefix collisions with decoy files. Each
  D-cell is also scored against the textual `sourceFile` baseline. C10-C12
  helper parity (`declaredType`/`methodSource`/`sourceLineContains`) remains a
  documented residual rather than a claimed check.
- **Incomplete inputs (5 cells):** missing class literal, missing import,
  missing superclass, duplicate binary name across explicit units, and an
  error-typed inherited member. All are POSSIBLE/abstained with diagnostics;
  none is DEFINITE.

## Evidence recorded per fact

Every javac fact carries relative path, line, column, offset, enclosing
top-level type, canonical service type, owner `java.util.ServiceLoader`,
member `load`, accessibility `PUBLIC_STATIC`, certainty, and any downgrade
diagnostics (`Diagnostic.getCode()` + `getMessage(Locale.ROOT)`, relative
paths only). Abstentions carry the same position plus
`SHADOWED`/`AMBIGUOUS`/`MISSING_INPUT`/`ERROR_TYPE` and diagnostics.

## Determinism

`JavacResolverAccuracyTest.freshRunsProduceIdenticalFacts` spawns two fresh
JVMs with distinct work/report paths and compares the full sorted fact list
per cell. They are identical. Fact order is sorted by offset and service type.

## TDD and gates

- RED_16: cell catalogue, harness, metrics controls, accuracy gate, and the
  documented behavior-neutral resolver stub. Targeted accuracy test fails;
  the other 291 tests stay green.
- GREEN_16: compiler-backed resolver; targeted tests pass.
- EVIDENCE_16: observation, this report, validator coverage, gate receipts.

Gate receipts on the GREEN/EVIDENCE tree:

- `make adapter-test` — **301 tests**, 0 failures, 0 errors (baseline 291,
  +10).
- `make java-lint` — PASS, 0 PMD/CPD findings. The project's
  `<includeTests>false</includeTests>` means this gate does not lint the
  test-scope spike code; review covers that gap explicitly.
- `make evidence-validate` — PASS, including the new
  `javac-symbol-resolver-observation.json` schema/GO-evidence/host-path check.

## What did not change

- No `src/main` edits. Default adapter output is unchanged.
- Textual resolvers, dynamic/reflection logic, descriptor logic, dependency
  resolution, and certainty semantics are untouched.
- No production diagnostics were added, so the `ServiceLoaderScanner.isUnique`
  scan-global blast radius is unchanged.

## Residuals / follow-ups

- The prototype is test-scope. A production seam and flag-gated wiring are the
  migration issue, not this spike.
- Task-global ERROR downgrade is deliberately conservative: any error in a
  cell's compiler task downgrades every fact in it. It may over-abstain on
  unrelated errors; the harness records this and the migration must scope the
  downgrade to the relevant attribution unit.
- Duplicate binary names are detected as diagnostics; the prototype does not
  choose a winner.
- The harness covers ServiceLoader source facts only. Framework annotations,
  reflection, descriptor provenance, and the other name-resolution consumers
  are not migrated.
- Peak heap is sampled by `MemoryMXBean` at 5 ms in a fresh `-Xmx256m` JVM,
  not JFR; it is indicative, not an allocation profile.
- A18/A19 textual values are pinned as known-bad baseline losses
  (`String@$Ann[]`, `(value=1)String`); the javac side returns
  `java.lang.String[]` and `java.lang.String`.
- Switch-pattern cells require JDK >= 21; a blocked required cell fails the
  gate rather than being skipped.
