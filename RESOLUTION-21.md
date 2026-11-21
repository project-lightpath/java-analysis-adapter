# RESOLUTION-21 — javac source-symbol index and flag seam

Issue: project-lightpath/java-analysis-adapter#21 (parent #20)
Branch: `feature/flpath-5142-javac-index-seam` (from `origin/main` @ `5d4635f`)
Plan: `PLAN-21.md` (19 adversarial review loops before implementation)
Test: `src/test/java/com/projectlightpath/analysis/java/JavacSourceSymbolIndexTest.java`
Commits: `PLAN_21` (6f2b0cc), `RED_21` (2b32f69), `GREEN_21` (878e83a),
`REFACTOR_21` (2c43c89).

## Summary

`JavacSourceSymbolIndex` (src/main, package-private) parses the source
roots recorded in one `AnalysisContext` exactly once — cached in a
`WeakHashMap` keyed by context identity, failed builds cached like
successes, no strong context reference retained — and exposes immutable
records for type/member/annotation lookups, canonical and binary names,
error-type state, and build diagnostics. The seam is gated by
`-Dflpath.java.symbolindex` (default **off**); no production consumer is
wired, so default fact output is byte-for-byte unchanged, pinned both
directions by the scan-equality cell.

## Acceptance criteria

| Criterion | Receipt |
|---|---|
| One parse per context, reused across consumers; repeated lookup does not rerun javac | `secondLookupDoesNotStartAnotherCompiler`: two `of(context)` calls ⇒ one compilation delta; a fresh context ⇒ one more. Consumers hold the `AnalysisContext` (verified: `BoundedFrameworkModel`, `ProviderFactNormalizer`, `SootUpBaseGraphProvider` all construct scanners from the context). |
| Default output unchanged with the flag off | `flagOffYieldsNoIndexAndNoCompilation` (no index, no compilation) and `flagDoesNotChangeScanOutput` (scan + resolution record-equal with the flag off and on; the flag-on arm builds the index first). |
| Error-type and missing-input lookups return POSSIBLE/unresolved plus diagnostics | `errorTypeMemberIsFlaggedWithADiagnostic`, `missingSourceRootIsDiagnosedAndShortCircuits`, `outOfScopeRootsEmitMissingSource`, `missingRequiredClasspathIsDiagnosed`, `duplicateBinaryNamesAreFlaggedInBothFiles`; consumers must abstain via the `TypeShape`/`MemberInfo` flags (no certainty change in this subissue, per non-goals). |
| Deterministic results; peak heap and wall time recorded | `repeatedBuildsAreDeterministicAndRecordCost`: two fresh contexts over identical sources produce equal type/member/diagnostic sets, `wallMillis() >= 0`, `peakHeapBytes() > 0`. |
| `make adapter-test`, `make java-lint`, `make evidence-validate` pass | Gate receipts below. |
| Regression test: second lookup does not start another compiler | Cell above; counter incremented only by the index build path after a fully successful build. |

## Measured cost (index build, 40 compilation units, test JVM)

Three fresh runs of a 40-unit fixture (annotated classes, one method and
one field each), measured in the surefire JVM (includes first-use javac
classloading):

| Run | wallMillis | peakHeapBytes |
|---|---:|---:|
| 1 | 7,441 | 14,285,888 |
| 2 | 6,247 | 13,461,288 |
| 3 | 11,702 | 13,179,432 |

Peak heap is a before/after `MemoryMXBean` sample (indicative, not JFR —
same caveat as PLAN-16). Wall time is dominated by javac startup inside
a shared test JVM; the one-parse-per-context amortization is the point,
not single-build latency.

## Coverage (18 cells)

Flag-off seam; one-parse regression; `$`-name D-series boundary cells
including the D3 binary collision double-assertion (top-level
`demo.Outer$Inner` vs nested `demo.Outer.Inner`, both flagged, decoy
never returned for the nested name) and leading `$`, adjacent `$$`,
single-character `$`, `$`-in-package; overlapping-root dedup (generated
root nested in a source root, non-normalized `typesIn` spellings);
release-clamp boundary (feature and feature+1); local/anonymous classes
binary-only (bare-simple-name qualified spelling probed and excluded
from the canonical map); declaration/package annotations with rendered
values (strings, ints, class-literal arrays, enum constants, nested
annotations, package-info, miss shape, non-normalized spellings);
canonical erased member shapes (varargs array, type-use annotated,
generic, nested dotted, record component, enum constant, overloads);
error-type member; missing/absent/empty/out-of-scope source roots;
required-empty and absent classpath (in-repo and `<external>` entry
rendering); duplicate binary names across files; determinism + cost;
flag wiring invariant; absolute-path-free diagnostics (including the
absolute-`declaredPath` fallback); `javac_unavailable` via an injected
null-supplier with failure caching.

JaCoCo 0.8.13 focused unit run at `2e7a925` (JDK 27): **371/412 = 90.05%
line**, **148/188 = 78.72% branch**. Branch coverage is below 80%;
the weakest area remains `IndexRenderer`'s defensive rendering branches.
Line coverage is the recorded gate.

## Review round 1 (PR #33)

Three findings, all addressed:

1. **MEDIUM — demotion named the wrong file.** `firstUnmappableName` took the
   first ERROR with any non-null source, so a unit-file error ahead of a
   classpath error could be named. It now filters to unmappable sources, with a
   direct two-diagnostic regression cell.
2. **MEDIUM — absolute path in `index_build_error`.** The `Files.walk` catch
   interpolated `root.declaredPath()`; it now uses
   `IndexRenderer.declaredRootPath(root)`, matching the path-safety invariant.
3. **LOW — no coverage receipt.** Recorded above.

## Pre-RED scratch probe results

Recorded in `PLAN-21.md`: the D3 collision leaves both entries usable
(top-level flagged ERROR by javac); a local class's qualified name is
its bare simple name (not empty — excluded from the canonical map);
nested member types render `demo.Outer.Inner` in both `toString()` and
erasure.

## Gate receipts

| Commit | adapter-test | java-lint | evidence-validate |
|---|---|---|---|
| RED_21 | 16/17 new cells fail as predicted (cell 1 only green); rest of suite 349 green | 0 findings (stub) | pass |
| GREEN_21 | 366 = 349 + 17 (Makefile floor raised 338 → 366) | 0 findings (after splitting Extractor into ElementReader + AnnotationRenderer to clear two GodClass findings) | pass |
| REFACTOR_21 | 366 | 0 findings | pass |

## Residuals

- No consumer reads the index yet (by scope); the annotation,
  type-shape, framework, and reflection subissues migrate consumers
  behind the flag.
- With the flag on, one context pays two javac parses (the
  ServiceLoader site resolver compiles the scanner's content snapshot;
  the index compiles from disk). A shared parse is a follow-up; the
  seams are keyed differently on purpose.
- The index reads sources from disk at `of()` time while the site
  resolver compiles the scanner's in-memory snapshot; a file modified
  between the two sees different views. Consumers must build the index
  after source materialization.
- `module-info.java` is excluded (module resolution against an empty
  sourcepath manufactures error diagnostics); `package-info.java` is
  included. This diverges from `scanner.files()`, which excludes both.
- `packageAnnotations` cannot distinguish a miss from an indexed
  `package-info` with zero annotations (both return empty).
- `javac_global_error` and the compile-triggered `index_build_error`
  path have no end-to-end cells: the source-less-error trigger needs a
  slow pathological unit and the build-error trigger is not reliably
  constructible across filesystems. The jar-entry demotion *selection*
  (`firstUnmappableName`) has a direct two-diagnostic cell. Defensive
  rows, code reviewed.
- Sub-floor `javaRelease` values surface as `index_build_error` (same
  behavior as the existing seam); no cell.
- `typesIn`/`packageAnnotations` normalization is lexical; a different
  symlink spelling of the same root can miss (both seams share this).
- Constructors render as the enclosing type's simple name (javac's
  `<init>` is mapped); fields and enum constants carry `""` return
  types by contract.
- The 8-line repository-relative-or-file-name path rendering is
  re-implemented context-side rather than extracting
  `ServiceLoaderDescriptorScanner.diagnosticPath` (out of #21 scope);
  a later cleanup may unify them, as with a shared task-construction
  helper if CPD ever fires between the two seams (it does not today).
- Duplicate-binary-name attribution of the second unit may be
  incomplete (javac duplicate-class error); both entries are flagged so
  consumers abstain — observed in the D3 probe that the colliding
  entries remain present with usable names.
