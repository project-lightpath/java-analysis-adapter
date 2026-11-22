# RESOLUTION-22 — annotation names and arguments from javac

Issue: project-lightpath/java-analysis-adapter#22 (parent #20)
Branch: `feature/flpath-5142-javac-annotation-names` (from `origin/main` @ `1077c09`)
Plan: `docs/plans/PLAN-22.md` (7 adversarial review loops, 26 folded findings, one
user-directed scope triage handing the qualifier-consumer migration to
#24 and the per-parameter annotation index API to #37)
Commits: PLAN_22 (029d035 + revisions), `RED_22` (bb01a6b), `GREEN_22`
(936ea37), `REFACTOR_22` (0da9887).

## Summary

`JavacAnnotationAccess` (src/main, package-private) is the javac
annotation read layer over the #21 index: correlation by owner type +
member name + arity with silent textual fallback, the probe-pinned
unconsumable-annotation repair trigger, and canonical-erasure repair
for triggered parameters only. Migration point 1 wires
`proxyFeaturesForInterface`'s descriptors through the accessor —
flag-off output is byte-for-byte unchanged; flag-on, a triggered
parameter records the canonical delta.

## What #22 delivers vs what was re-chartered

Per the loop-5 scope triage and the re-chartering statement:

- **Delivered here**: the read layer (names in canonical + simple
  spellings, values minus string quoting, member correlation) and the
  single parameter-shape repair (proxy descriptors).
- **Re-chartered on #24** (design comment
  https://github.com/project-lightpath/java-analysis-adapter/issues/24#issuecomment-7064962274):
  the qualifier-consumer migration — `TextLayer09`/`TextLayer18`/
  `TextLayer19.parameters`' block-only readers, the T18+T19
  `injectionQualifiers` pipeline (six-family shape table, per-site
  canonical-Spring guard, `Named:`/`ResourceDefault:` enrichment,
  call-site-local gate placement), field-injection and bean
  type-level sites, `TextLayer12.returnType` producer generics,
  `BoundedFrameworkModelCandidateMetadata.methodDescriptor`, and the
  owner/annotation-evidence recording.
- **Re-chartered on #37** (new issue): the per-parameter annotation
  index API that constructor/method injection qualifiers need.

The static textual helpers stay, unconditionally correct-as-textual;
`JavacResolverAccuracyTest`'s pinned known-bad values remain green
unchanged.

## Acceptance receipts

| Issue criterion | Receipt |
|---|---|
| `@$Ann`, Unicode-initial, qualified names resolve | `dollarAndUnicodeAnnotationNamesResolve` (`demo.Mar$k`, `demo.Ωmega` canonical + simple), `qualifiedAnnotationNameResolvesCanonically` (`pkg.Ann`) |
| `@Ann(value = 1) String p` yields `String` | `typeUseAndVarargsAnnotationsRepairParameterShapes` and the end-to-end proxy cell: flag-on `(java.lang.String)`, flag-off keeps `((value=1)String)` |
| `String @$Ann... args` yields `String[]` | same cells: `java.lang.String[]` flag-on |
| No `@[A-Za-z_]` copies in migrated call sites | the proxy descriptor path routes through the accessor (trigger is a character-class scan); `accessorCarriesNoNameCaptureRegex` pins the read layer; the remaining copies are re-chartered above |
| Parity cells | 14 cells: names, values, repair shapes, decoys, overload ambiguity, unindexed fallback, determinism, cache poisoning, mixed-name triggers, clean-generic identity, end-to-end |
| Default output unchanged flag-off | `flagOffOutputIsByteForByteTextual` + `flagOnScanDoesNotPoisonTheTextualCache` (FileExtracts invariant) |

## Pre-RED scratch probe

Recorded in `docs/plans/PLAN-22.md`: 16 parameter shapes pinned the trigger.
Key find: `$` passes `Character.isJavaIdentifierPart`, so the
unconsumed-tail check is `isJavaIdentifierPart && !isAsciiWord` — a
naive check misses `@Mar$k`. Triggered: annotation-with-args (ASCII
and qualified), `$`-named, Unicode-initial, mixed `@MaΩga`,
`@pkg.Ωmega`. Clean: plain, `final`, plain-ASCII annotations,
qualified-without-args, type-use in generics, varargs with ASCII
annotations, arrays, primitives.

## Fixture constraints discovered (textual-scanner residuals)

- `PROXY_INTERFACES` requires the generic `new Class<?>[]{...}` form —
  the raw `new Class[]{...}` spelling is not discovered.
- A file's `typeName` is its first declared type — annotation and
  interface must live in separate files for interface correlation.
Both recorded for #24/#26.

## Gate receipts

| Commit | adapter-test | java-lint | evidence-validate |
|---|---|---|---|
| RED_22 | 3 of 13 cells fail as predicted (the accessor-dependent repair cells; the #21 index already answers the name/value cells); rest of suite 371 green | 0 findings (stub) | pass |
| GREEN_22 | 385 = 371 + 14 (floor raised 371 → 385) | 0 findings | pass |
| REFACTOR_22 | 385 (one transient red run re-run green; the `@Isolated` migration class serializes against the heavy ServiceLoaderSafetyLifetimeTest lane) | 0 findings | pass |

## Residuals

See `docs/plans/PLAN-22.md`'s residual list; headline items: same-arity overload
ambiguity falls back to textual (positions are the follow-up),
repaired parameters emit canonical spellings until a source-spelling
renderer is worth its cost (#26 owns that decision), and all
qualifier-fidelity work is on #24/#37 as re-chartered above.
