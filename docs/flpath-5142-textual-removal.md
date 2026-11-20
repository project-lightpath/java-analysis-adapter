# FLPATH-5142 — textual ServiceLoader name-resolver removal

Issue: FLPATH-5142 follow-up (javac default)
Branch: `feature/flpath-5142-remove-textual`
Base: `origin/main` @ `73e85bc`
Plan: `PLAN-5142-removal.md`
Prior PRs: #27 (seam), #28 (provenance), #29 (e2e), #30 (javac default), #14 (budgets)

## Change

javac Trees is now the only authority for source-defined `ServiceLoader.load`
sites. The textual name-resolution implementation is deleted:

| Removed | Lines |
|---|---|
| `JavaImports` | 150 |
| `ServiceLoaderInheritance` | 197 |
| textual branch and shadowing helpers in `ServiceLoaderSourceCache` | ~270 |
| `ServiceLoaderCallSiteParser.parse` and import/argument machinery | ~250 |
| `ServiceLoaderCallSite.fallbackServiceType` | 1 field |
| resolver rollback flag and thread-scoped mode | ~25 |

`ServiceLoaderSourceCache` keeps only the per-scanner cache and reads the
compiler map:

```java
JavacServiceLoaderSiteResolver.sites(scanner)
    .map(byFile -> byFile.getOrDefault(file.path().toAbsolutePath().normalize(), List.of()))
    .orElse(List.of());
```

`ServiceLoaderCallSiteReader` uses `site.serviceType()` verbatim. Retained
unchanged: `ServiceLoaderSourceMasker`, `ServiceLoaderCallSiteSource`, statement
/ lambda / type-body correlation, descriptor scanning, archive enumeration,
dynamic/reflection facts, and certainty policy.

javac parser stack exhaustion is now contained at the compile boundary and
fails closed (`Optional.of(Map.of())`) instead of leaking an `Error` to callers.

## TDD trail

- `PLAN_5142` — removal scope, test-count strategy, residuals (confidence 0.84).
- `RED_1` — tests converted to compiler-bound expectations; the rollback-flag
  test fails under the textual fallback; a behaviour-neutral four-argument
  `ServiceLoaderCallSite` constructor is the documented compile stub; the
  textual baseline observation is frozen. Also fails: the 20,000-component
  import probe, because javac's stack overflow was not yet contained.
- `GREEN_1` — delete the textual implementation and flag; use compiler binary
  names verbatim; contain javac stack exhaustion.
- `REFACTOR_1` — under the green suite, replace fully qualified JDK names in
  `JavacServiceLoaderSiteResolver` with imports, repair the broken import line,
  drop the unused file list and imports, and correct the harness description.
  No behavior change.
- `EVIDENCE_1` — this record.

## Test conversion and count

Baseline gate: **338** total. Final: **342** (`341` default + `1`
large-archive). Count did not decrease.

- `ServiceLoaderNameResolutionMatrixTest`: all 56 JLS cells now run through
  javac. Pattern-switch cells use `javaRelease(21)`. Six cells changed
  disposition because javac is stricter: two invalid-Java cells
  (`dotlessImportFailsClosed`, `unselectedQualifiedSuperclassFailsClosed`) and
  two per-file-error cells (`javaLambdaParameterErrorFailsClosedForWholeFile`,
  `switchArmJavaPatternErrorFailsClosedForWholeFile`) now expect zero sites.
  The remaining four fixture fixes add the `load`/`touch` methods, the thrown
  checked exception, the initialized final field, and the `Provider` stub that
  the previous textual fixtures did not need.
- `ServiceLoaderSourceCacheTest`: all 25 cells run through javac with the same
  fixture completions.
- `ServiceLoaderCallSiteSourceTest`: 15 correlation tests construct the site
  directly; 9 parser-ownership tests became javac fixtures proving the same
  JLS outcome (non-ASCII prefixes, static member/on-demand imports, unicode
  class literal, dotted member selection, prohibited package fail-closed,
  deep-import robustness).
- `ServiceLoaderCoverageTest`: 5 parser tests became 5 masker/correlation tests.
- `JavacServiceLoaderSiteResolverTest`: the flag-off test became
  `textualRollbackFlagNoLongerDisablesJavac`; scoped-mode wrappers removed.
- `ServiceLoaderEndToEndTest`: scoped-mode wrappers removed; 4 tests pass.
- `JavaAnalysisAdapterTest`: two redundant textual parser assertions removed;
  the adapter assertions remain.
- `JavacResolverAccuracyTest` / harness: the textual baseline is frozen from the
  committed observation in
  `spike/results/javac-symbol-resolver-textual-baseline.json`. The javac half,
  thresholds, determinism, and committed-observation comparison stay live.

## Gates

- `make adapter-test`: **341** default + **1** large-archive = **342**, 0
  failures (gate >= 338).
- `make java-lint`: PASS, 0 PMD findings, 0 CPD duplications.
- `make evidence-validate`: PASS.
- `make e2e-test`: 4 PASS.

## Residuals

- **R1 (accepted):** one erroneous expression in a compilation unit drops every
  site in that file. This is fail-closed and pre-existing; renamed matrix cells
  pin it. Follow-up candidate: per-declaration error scoping or a scan-global
  diagnostic for javac errors.
- **R2:** textual re-resolution in the reader is gone. javac binary names are
  used verbatim, including `$` for nested and local types.
- **R3:** the frozen baseline's `wallMillis`/`peakHeapBytes` reflect a JSON read,
  not the removed textual resolver. The evidence view excludes them; the raw
  report must not be read as a fresh performance measurement.
- **R4:** the `Javac` class prefix remains although the resolver is now the only
  path. Renaming is deferred to avoid churn.
- **R5:** catching javac's wrapped `StackOverflowError` trades a crash for a
  whole-scan abstention. The deep-import probe pins the fail-closed behavior.
