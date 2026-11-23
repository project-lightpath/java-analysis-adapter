# RESOLUTION-26 — annotation/type-shape parity cells and gates

Issue: project-lightpath/java-analysis-adapter#26 (parent #20).
Branch: `issue-26-annotation-type-shape-parity`.
Commits: RED_26_1 `39c065c`, GREEN_26_1 `1d87320`, EVIDENCE_26_1 (this commit).

## What changed

The #16 accuracy harness now covers the annotation/type-shape parity
families that gate #23 (TypeMirror shapes) and #25 (reflection shapes),
on the same cell/fact model — no fork:

- `JavacResolverCell`: five new families (ANNOTATION_NAME,
  ANNOTATION_ARGUMENT, TYPE_USE, TYPE_SHAPE, REFLECTION_SOURCE_BOUND) and
  `BaselineMode {FROZEN, LIVE_TEXTUAL, NONE}` replacing the
  baselineAvailable boolean. 26 new cells (AN1-5, AA1-4, TU1-4, TS1-9,
  R1-4); 71 + 26 = 97 in the observation.
- `JavacResolverFact`: additive `annotationName`/`annotationValue`
  observation fields; `serviceType` carries the canonical observed value
  for the new families.
- ServiceLoader cells keep the frozen JSON baseline. The new families run
  the LIVE production textual heuristics
  (`SourceMetadataScanner.parameterType`, the TextLayer02 annotation-token
  regex, raw argument text) anchored to the javac facts' offsets, so the
  textual path's wrong answers are pinned numbers, not prose.
- The javac path stays the single `JavacSourceSymbolResolver.resolve`
  entry point; emitters are family-keyed tree scanners with the same
  task-global ERROR downgrade.
- `spike/scripts/validate_spike.py` is family-aware (97 cells, new-family
  ids and invariants, textual baseline metrics explicitly not zeroed for
  the new families — they are the #23 evidence).

## GO evidence (committed observation, deterministic, JDK 27 local)

- javacTotals: wrongDefinite 0, wrongServiceType 0, lost 0, downgraded 0,
  added 0, unexpectedFact 0, abstentionMismatch 0 — over all 97 cells.
- Pinned live-textual defects on the new cells (baseline metrics,
  non-zero by design):
  - wrongServiceType: AN1-3 (unqualified or regex-blind `$`/Unicode
    annotation names), AA1-4 (raw/unqualified argument text vs canonical
    rendering), TU1-4 (`String`, `String[]`, `String@$Ann[]`,
    `(value=1)String` vs qualified erased types), TS1-4/TS6/TS7/TS9
    (generics retained, unqualified names).
  - wrongDefinite: AN5 (textual path claims a DEFINITE name for an
    unresolved annotation type).
  - lost: TS7 (the textual path cannot select overloads, so the invocation
    sites have no textual fact).
- Correct-baseline guards: AN4 (qualified annotation name), TS5 (`int`),
  AA4's explicit `value=1` rendering.
- Gates: `make adapter-test` 599 executions (recorded floor 534), zero
  failures; `make java-lint` 0 new findings, CPD 0 (test scope is
  PMD-excluded by `includeTests=false`, so style is review-enforced);
  `make evidence-validate` pass; harness subprocess still fits
  `-Xmx256m` with the 5-minute deadline.

## Behavior notes discovered at GREEN

- javac normalizes implicit single-element annotation values to
  `value=...` in `AnnotationTree.getArguments()`; the canonical rendering
  (and the catalog expectations) pin that normalized form.
- javac materializes synthesized default constructors (and their
  `super()` calls) in the parsed tree; the emitters skip constructors for
  parameter emission and `super()`/`this()` for invocation emission, or
  every class would grow a `java.lang.Object.<init>()` fact at the class
  declaration offset.
- `trees.getPath(Element)` distinguishes source-bound from runtime-only
  reflection targets within one task; runtime-only and unresolvable
  `Class.forName` targets abstain at POSSIBLE (safe direction).

## Sanctioned edits to existing tests (PLAN-26)

- `tooNewReleaseIsBlockedInsteadOfFailingTheGate` and
  `JavacResolverMetricsTest.cell`: constructor calls updated for
  BaselineMode (mechanical).
- `everyJavacFactCarriesSymbolEvidenceAndIncompleteInputsArePossible`:
  ServiceLoader owner/member/accessibility assertions scoped to the four
  ServiceLoader families.
- `javacPathMeetsGoThresholdsAndTextualBaselineStaysVisible`: baseline
  totals and baseline cell-set comparisons scoped to the ServiceLoader
  families (the new families' baseline metrics are asserted by the new
  pinned-defect test instead).

## Residuals

- The ANNOTATION_NAME textual baseline replicates the production
  annotation-token regex; a drift between the copy and
  `SourceMetadataScannerTextLayer02.parameterType` is a documented sync
  risk (cross-referenced at both sites).
- Reflection textual parity is deferred to #25 (the textual
  `reflectionSites` scanner needs the full AnalysisContext pipeline);
  R cells gate the javac side only, with BaselineMode.NONE.
- TS8 (constructor selection) has no textual probes: the textual path
  cannot represent constructor selection, so it is BaselineMode.NONE
  rather than a contrived lost-metrics cell.
- The argument canonical rendering is a harness-local convention (tree
  order, qualified names, `value=` normalization); #23 owns the
  production TypeMirror contract.
- Member-shape emission covers direct invocations and `new` expressions
  in the site file; method references and implicit constructor chains
  are out of scope.
