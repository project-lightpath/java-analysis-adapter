# PLAN-5142-removal — delete the textual ServiceLoader name resolver

Issue: FLPATH-5142 (javac default) follow-up removal
Branch: `feature/flpath-5142-remove-textual`
Base: `origin/main` @ `73e85bc`
Prior PRs: #27 (seam), #28 (provenance), #29 (e2e), #30 (javac default), #14 (budgets)

## Goal

Delete the textual ServiceLoader **name-resolution** implementation. javac Trees
is the only authority for which `ServiceLoader.load(...)` call sites exist and
what service type each names. Keep every non-name-resolution path unchanged:
bytecode/source correlation, statement/lambda/type-body scoping, masking,
descriptor scanning, archive enumeration, dynamic/reflection and certainty
policy.

Non-goals:
- No change to descriptor budgets, archive enumeration, or certainty policy.
- No new diagnostics. Per-file javac error scoping stays as-is (R1).
- No rename of `JavacServiceLoaderSiteResolver` (historical name; still accurate).

## Production surface to delete

| File | Action | Evidence |
|---|---|---|
| `JavaImports.java` | delete (150 lines) | only refs are `ServiceLoaderCallSiteParser`, `ServiceLoaderInheritance`, `ServiceLoaderSourceCache.singleTypeImportNamed` |
| `ServiceLoaderInheritance.java` | delete (197 lines) | only refs are `ServiceLoaderSourceCache` and itself |
| `ServiceLoaderSourceCache.java` | delete textual branch + all helpers | `create()` textual fallback lines 108-127 and helpers 130-401 |
| `ServiceLoaderCallSiteParser.java` | delete `parse`/import-ownership/argument machinery | keep `hasInterveningReplacingCall`, `insideTypeBodyDeclaredInLambda`, and their private support |
| `JavacServiceLoaderSiteResolver.java` | remove flag/scoped-mode; keep `Optional<Map>` contract | `sites()` always compiles and fails closed with `Optional.of(Map.of())`; the `Optional` remains only to avoid churn across seven resolver tests |
| `ServiceLoaderCallSite.java` | drop `fallbackServiceType` | javac supplies the binary name; reader uses it verbatim |
| `ServiceLoaderCallSiteReader.java` | stop textual re-resolution | use `site.serviceType()`; keep statement/receiver correlation |

`ServiceLoaderSourceCache` after removal retains only the per-scanner cache and:

```java
private static CachedSource create(SourceMetadataScanner scanner, SourceFile file) {
  String source = file.source();
  return new CachedSource(source,
      JavacServiceLoaderSiteResolver.sites(scanner)
          .getOrDefault(file.path().toAbsolutePath().normalize(), List.of()));
}
```

`ServiceLoaderSourceMasker`, `ServiceLoaderCallSiteSource`,
`ServiceLoaderDescriptor*`, `SourceMetadataScanner`, and dynamic/reflection
policy are untouched.

## Test strategy and count accounting

Baseline gate: **338** total (`337` default + `1` large-archive). No net change.

| Test file | Current | Action | Net |
|---|---:|---|---:|
| `ServiceLoaderNameResolutionMatrixTest` | 56 | keep all 56 cells, run them through javac | 0 |
| `ServiceLoaderSourceCacheTest` | 25 | keep all 25 cells, run them through javac | 0 |
| `ServiceLoaderCoverageTest` | 58 | replace 5 parser-manufactured tests with 5 correlation/masker tests | 0 |
| `ServiceLoaderCallSiteSourceTest` | 24 | replace `ServiceLoaderCallSiteParser.parse(source).get(0)` with a direct-site helper | 0 |
| `JavacServiceLoaderSiteResolverTest` | 7 | replace the flag-off test with a flag-is-ignored test; drop scoped-mode wrappers | 0 |
| `JavacResolverAccuracyTest` / harness | 7 | freeze the textual baseline observation; javac half still runs live | 0 |
| `ServiceLoaderEndToEndTest` | 4 | drop scoped-mode wrappers | 0 |
| `JavaAnalysisAdapterTest` | 73 | remove 2 `ServiceLoaderCallSiteParser.parse` assertions; adapter assertions stay | 0 |

Matrix/cache conversion probe (already run on this branch): with a `demo/Provider`
stub plus helper stubs, **70 of 81** cells pass unchanged under javac. The 11
deltas are dispositioned below.

### Matrix/cache deltas after javac

1. `arrowArmPatternVariableScopesToItsArm`,
   `lambdaParameterAndLocalClassScopeEndAtTheLambdaBody`,
   `scopesLocalServiceLoaderShadowingToItsEnclosingMethod`: fixtures declare a
   shadow `ServiceLoader` **without** the `load` method they then call. Add the
   method; expectation (1 attributed site) is unchanged.
2. `allowsRealServiceLoaderOutsideCatchBlock`: fixture catches a checked
   `IOException` never thrown. Add `throw new java.io.IOException();`; expectation
   unchanged.
3. `allowsRealServiceLoaderWithGenericTypeArgument`: `final List<ServiceLoader>
   loaders;` is uninitialized. Initialize to `null`; expectation unchanged.
4. `explicitImportWinsOverSamePackageTypeInAnotherFile`: fixture omits
   `demo/Provider.java`. Add it; expectation unchanged.
5. `javaLambdaParameterDropsOnlyInsideTheLambda` and
   `switchArmJavaPatternDropsOnlyThatArm`: the inner `java.util.ServiceLoader`
   select is a **compile error** (a value is named `java`). javac's per-file
   error scoping drops the whole file, including the valid outer call. Rename to
   `...ErrorFailsClosedForWholeFile` and expect `0`; record the false abstention
   as R1.
6. `dotlessImportDoesNotAbortScan` (`import ServiceLoader;`) and
   `unselectedQualifiedSuperclassNameStaysAttributed` (`extends external.Base`
   with no selected source): both are invalid/incomplete Java. Rename to
   `...FailsClosed` and expect `0`; this is the safe direction.
7. Pattern-switch cells run with `javaRelease(21)` in their fixture helper
   (pattern matching for switch is not accepted by `--release 17`). Production
   still caps `--release` at the running JDK.

`JavacResolverAccuracyTest` needs a frozen baseline because the textual code
that produced it is being deleted:
- Extract every cell's `baseline` object from the committed
  `spike/results/javac-symbol-resolver-observation.json` into
  `spike/results/javac-symbol-resolver-textual-baseline.json`.
- `JavacResolverAccuracyHarness` reads that frozen map instead of calling
  `ServiceLoaderSourceCache` with a textual mode. `wallMillis`/`peakHeapBytes`
  become `0`; the evidence view already excludes them.
- Javac comparison, thresholds, determinism, and `committedObservationMatchesAFreshRun`
  stay live. The baseline test now proves the **frozen historical** baseline,
  which cannot change after removal.

### Coverage replacement for the 5 parser tests

Keep the source-correlation coverage, not the parser:
- `parserRejectsMalformedCallsAndHandlesNestedArguments` → masker handles
  malformed load shapes without throwing.
- `parserIgnoresCommentsStringsAndTextBlocks` → `sameStatement`/`invokeIsLexicallyBound`
  ignore comments, strings, and text blocks for a directly constructed site.
- `sourceOwnershipHandlesInvalidTargetsAndStatementBoundaries`,
  `sameStatementIgnoresTrailingCommentDelimiters`,
  `sourceOwnershipIgnoresCommentsStringsAndTextBlocks` → construct
  `ServiceLoaderCallSite` directly with the known offset/line; assertions
  unchanged.

## TDD trail (RED -> GREEN -> EVIDENCE)

One removal change, recorded as a triple. The RED commit contains all test
conversions plus a behaviour-neutral four-argument `ServiceLoaderCallSite`
constructor (documented compile stub) and the frozen textual baseline. It fails
on `textualRollbackFlagNoLongerDisablesJavac` and the deep-import stack probe.
GREEN deletes the textual implementation, the flag, and the fallback field, and
contains javac stack exhaustion. EVIDENCE adds this plan and the evidence
record below.

`JavacServiceLoaderSiteResolver` keeps its `Optional<Map>` return contract to
avoid a compile-only churn across seven resolver tests; it is always present.

## Gates

- `make adapter-test` — total must stay >= 338; final 342.
- `make java-lint` — zero PMD/CPD findings.
- `make evidence-validate`.
- `make e2e-test` — 4 tests.

## Residuals / risks

- **R1 (accepted, documented):** one erroneous expression anywhere in a
  compilation unit drops every site in that file (per-file error scoping). This
  is fail-closed and pre-existing; the matrix keeps renamed cells proving it.
  Follow-up candidate: per-declaration error scoping or a scan-global diagnostic.
- **R2:** `ServiceLoaderCallSiteReader` stops textual re-resolution. Javac binary
  names are used verbatim; nested/local names keep `$` separators. Run the full
  suite to catch any caller that depended on textual normalization.
- **R3:** `ServiceLoaderCallSite` loses `fallbackServiceType`; external/test
  constructors must use the 4-argument form. Only `ServiceLoaderCoverageTest`
  constructs one in tests.
- **R4:** frozen baseline timings are zero; evidence view ignores them. A reader
  of the raw JSON must not treat `0` as a measurement.
- **R5:** the `Javac` prefix remains although the resolver is now the only path;
  renaming is deferred to avoid churn.

## Review checklist

- JLS 6.5 precedence cells still asserted end-to-end (matrix 56).
- No production reference to `JavaImports`, `ServiceLoaderInheritance`, parser
  `parse`, `PROPERTY`, `setScopedMode`, or `clearScopedMode`.
- No silent skip introduced; descriptor/dynamic paths untouched.
- Test count still 338.
