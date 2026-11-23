# PLAN-26 — annotation/type-shape parity cells and gates for the #16 harness

Branch: `issue-26-annotation-type-shape-parity` (from `origin/main`).
Issue: project-lightpath/java-analysis-adapter#26 (parent #20).
Baseline context: the bounded-context brief grounded against main
(2026-11-23). Subissues #21, #22, #24 are closed; #23 (TypeMirror shapes)
and #25 (reflection shapes) are open and out of scope — this harness is
the parity gate they will migrate under.

## Scope (binding)

Test scope only, like the #16 spike: `src/test` harness files plus the
`spike/scripts/validate_spike.py` evidence gate and the committed
observation. No production source changes, so default adapter output is
unchanged by construction; a flag-off guard test records that anyway.
The textual resolvers for the new families remain live in production
(`SourceMetadataScanner.parameterType` and the annotation-regex helpers
in `SourceMetadataScannerTextLayer02`); their defects on the new cells
are pinned as evidence, not fixed here.

## What exists (verified on main)

- `JavacResolverAccuracyHarness.run` compares the frozen textual JSON
  baseline (`spike/results/javac-symbol-resolver-textual-baseline.json`)
  with the live javac path (`JavacSourceSymbolResolver.resolve`) per
  cell; `Evaluation.evaluate` produces the seven metrics.
- `JavacResolverCell.Family = {PRECEDENCE, STATIC_OWNER, DOLLAR_OWNER,
  INCOMPLETE}` — all ServiceLoader-name shaped, 71 cells.
- `JavacResolverFact` carries serviceType/owner/member/accessibility/
  certainty/promotion/diagnostics — no annotation or type-shape fields.
- `JavacResolverAccuracyTest` already pins the two known textual defects
  (`String@$Ann[]`, `(value=1)String`) and the javac answers
  (`java.lang.String[]`, `java.lang.String`) via
  `JavacSourceSymbolResolver.parameterType`.
- `spike/scripts/validate_spike.py` pins the observation cell set (71),
  per-fact owner `java.util.ServiceLoader`, and per-cell GO zeros.

## Design

### 1. Generalize the cell/fact model (no fork)

- `Family` gains `ANNOTATION_NAME`, `ANNOTATION_ARGUMENT`, `TYPE_USE`,
  `TYPE_SHAPE`, `REFLECTION_SOURCE_BOUND`.
- `JavacResolverCell.baselineAvailable: boolean` becomes
  `baselineMode: BaselineMode {FROZEN, LIVE_TEXTUAL, NONE}`. Existing
  call sites map `true -> FROZEN`, `false -> NONE`. This is a sanctioned
  mechanical edit of `JavacResolverAccuracyTest.tooNewReleaseIsBlocked…`
  (direct cell constructor) and the catalog helper.
- `JavacResolverFact` gains additive fields `annotationName` and
  `annotationValue` (default `""`, one flat observation schema). A
  compact overload keeps existing 13-arg call sites compiling.
- For the new families, `serviceType` carries the **canonical observed
  value** (qualified annotation name, canonical argument rendering,
  canonical erased type, member signature, or reflection target) because
  these cells have no ServiceLoader service type. `Evaluation` is
  unchanged: `wrongServiceType` catches canonical mismatches on both
  paths.

### 2. Two baseline modes

- ServiceLoader cells keep the frozen JSON baseline (FROZEN).
- New families run the LIVE textual path (LIVE_TEXTUAL):
  - `TYPE_USE`, `TYPE_SHAPE` parameters: `SourceMetadataScanner
    .parameterType(rawDeclaration)` — the production heuristic.
  - `ANNOTATION_NAME`: the production annotation token pattern
    `@[A-Za-z_][\w.]*` (the exact regex inside
    `SourceMetadataScannerTextLayer02.parameterType`), replicated in the
    harness with a sync comment; the textual answer is the unqualified
    token.
  - `ANNOTATION_ARGUMENT`: raw argument text between the matching
    parentheses, whitespace-collapsed — what the textual families actually
    retain.
  - `REFLECTION_SOURCE_BOUND`: NONE — the textual reflection scanner
    (`reflectionSites`) requires a full `AnalysisContext`/`SourceFile`
    pipeline; reflection parity is #25's scope. Documented residual.
- LIVE_TEXTUAL baseline facts are emitted at the javac facts' offsets in
  ordinal order, so `Evaluation` pairs them with the same expected
  sites. The harness therefore measures javac before baseline for
  LIVE_TEXTUAL cells (FROZEN order is irrelevant).
- The textual path's wrong answers appear as pinned non-zero baseline
  metrics in the committed observation — the evidence #23 consumes.

### 3. Javac path — same compiler entry point

`JavacSourceSymbolResolver.resolve` stays the single entry point. The
`Facts` TreePathScanner gains family-keyed emission (site file only,
same task-global ERROR downgrade to POSSIBLE):

- `TYPE_USE` / `TYPE_SHAPE` (parameters): one fact per parameter of
  each method declared in the cell's probe class (the `owner` field),
  value = canonical erased type via the existing `canonicalType`
  (recursive array erasure; type annotations erased, JLS 4.4/9.7.4).
- `TYPE_SHAPE` (member selection): one fact per method invocation and
  `new` expression in the site file, value =
  `owner.<name>(erasedParamTypes)` or `owner.<init>(…)`; one return-type
  fact per probe-class method with a non-void return, at the method tree
  position. Constructors contribute no parameter facts and `super()` /
  `this()` invocations are skipped, so javac's synthesized default
  constructors contribute nothing.
- `ANNOTATION_NAME`: one fact per annotation tree on probe-class
  members and their parameters (declaration and type-use), value =
  qualified annotation name. Unresolved annotation types are emitted
  from the syntax tree with POSSIBLE + diagnostics — compiler
  independent, per the #24 cross-compiler lesson (some compilers drop
  error mirrors, others keep them).
- `ANNOTATION_ARGUMENT`: one fact per annotation tree, value = the
  canonical rendering of `AnnotationTree.getArguments()` in tree order
  (deterministic): literals normalized, class literals as qualified
  names, enum constants as qualified `Type.CONSTANT`, arrays comma
  joined, nested annotations as `@qualified(…)`.
- `REFLECTION_SOURCE_BOUND`: one fact per `Class.forName("literal")`
  invocation in the site file; source-bound (declared by a parsed unit)
  -> DEFINITE with the binary name; resolvable but not in the parsed
  units (runtime-only) -> POSSIBLE; unresolvable -> POSSIBLE +
  MISSING_INPUT diagnostic.

### 4. Cell matrix (equivalence classes from issue #26's list)

| Cell | Shape | Expected |
|---|---|---|
| AN1 | simple `@Route` on a member | DEFINITE `demo.Route` |
| AN2 | `$` annotation name `@$Mark` | DEFINITE `demo.$Mark` |
| AN3 | Unicode annotation name | DEFINITE `demo.Über` |
| AN4 | qualified `@demo.Outer.Inner` | DEFINITE `demo.Outer.Inner` |
| AN5 | unresolved annotation type | MISSING_INPUT (POSSIBLE + diagnostic) |
| AA1 | class-literal array argument | DEFINITE canonical rendering |
| AA2 | enum constant argument | DEFINITE canonical rendering |
| AA3 | nested annotation argument | DEFINITE canonical rendering |
| AA4 | explicit `value =` / string argument | DEFINITE canonical rendering |
| TU1 | type-use annotation before type | DEFINITE `java.lang.String` |
| TU2 | type-use annotation after type (dims) | DEFINITE `java.lang.String[]` |
| TU3 | `String @$Ann... args` | DEFINITE `java.lang.String[]` |
| TU4 | `@Ann(value = 1) String p` | DEFINITE `java.lang.String` |
| TS1 | generic `List<String>` | DEFINITE `java.util.List` |
| TS2 | wildcard `List<?>` | DEFINITE `java.util.List` |
| TS3 | array `String[]` | DEFINITE `java.lang.String[]` |
| TS4 | multidimensional `String[][]` | DEFINITE `java.lang.String[][]` |
| TS5 | primitive `int` | DEFINITE `int` |
| TS6 | nested `demo.Outer.Inner` | DEFINITE `demo.Outer.Inner` |
| TS7 | overload selection `f("x")` / `f(1)` | DEFINITE `demo.Probe.f(java.lang.String)` etc. |
| TS8 | constructor selection `new Outer(1)` | DEFINITE `demo.Outer.<init>(int)` etc. |
| TS9 | return type `List<String> audit()` | DEFINITE `java.util.List` |
| R1 | `Class.forName("demo.Present")`, source present | DEFINITE `demo.Present` |
| R2 | `Class.forName("java.lang.String")`, runtime-only | POSSIBLE `java.lang.String` |
| R3 | task error (missing import) + source-bound forName | MISSING_INPUT (POSSIBLE + diagnostic) |
| R4 | `Class.forName("no.such.Type")`, unresolvable | POSSIBLE `no.such.Type` |

26 cells + R5 (non-literal forName abstention pin) + R6 (unattributed
forName abstention pin) = 28; 71 + 28 = 99 in the observation.

### 5. Gates

- Javac path on the new cells: zero wrongDefinite, wrongServiceType,
  lost, downgraded, added, unexpectedFact, abstentionMismatch.
- Textual baseline defects pinned as exact cell sets (like
  BASELINE_WRONG today): the known-defective textual answers
  (`String@$Ann[]`, `(value=1)String`, unqualified names, `$`/Unicode
  regex misses, raw argument text) must stay visible and non-zero.
- Determinism across fresh JVM runs; committed observation matches a
  fresh run; no absolute paths in diagnostics.
- Flag-off default-output guard: the harness run leaves
  `flpath.java.resolver` untouched and the production default selection
  is unchanged before/after a harness run (test-scope-only change).
- `spike/scripts/validate_spike.py` becomes family-aware: 96 cells,
  new-family cell ids required, per-family fact invariants (owner is the
  probe class for shape families, `java.lang.Class.forName` member for
  reflection), textual baseline metrics explicitly NOT required zero
  for LIVE_TEXTUAL cells (they are the evidence).

## TDD sequence

- **RED_26_1**: fact-model generalization — BaselineMode, new families,
  additive fact fields, harness accepts new cell kinds; failing tests in
  `JavacResolverAccuracyTest` for the new-family GO gates and pinned
  textual-defect sets; behavior-neutral stubs documented (new-family
  emission returns empty results). Sanctioned edits:
  `tooNewReleaseIsBlocked…` constructor call, validator cell-count pin.
- **GREEN_26_1**: catalog + emitters + live textual baseline so the new
  gates pass.
- **EVIDENCE_26_1**: regenerated observation, validator update, gate
  receipts.
- Items 2–4 of the bounded context (live-textual baseline, javac
  queries, observation+docs) fold into these triples where the harness
  work is inseparable; the commit messages state which item each triple
  delivers.

Gates after every item: `make adapter-test` (floor 613 executions, must
not decrease), `make java-lint` (zero new findings; note PMD
`includeTests=false` keeps test scope style-checked by review),
`make evidence-validate`. Heap: the harness subprocess keeps its
`-Xmx256m` ceiling; the in-JVM accuracy-test runs add ~25 small
compilations — watch the 5-minute subprocess deadline and the measured
suite heap at `ADAPTER_TEST_PARALLELISM=4`.

## Residuals / risks

- The textual baselines for annotation names replicate the production
  annotation-name pattern (capture site:
  `JavacFrameworkQualifierFamilies.TEXTUAL_ANNOTATION_NAME`, strip site:
  `SourceMetadataScannerTextLayer02.parameterType`) and argument text now
  comes from the production parser itself
  (`SourceMetadataScanner.parseAnnotation`); a drift between the replicated
  pattern and the production capture site is a documented sync risk
  (cross-referenced at both sites).
- Textual probes anchor to javac facts by ordinal; a probe count exceeding
  the javac fact count fails loudly (IllegalStateException), but ordinal
  misalignment inside the list would surface as javac `lost` (a dropped
  fact fails its expected site) — the gate cannot false-pass on a dropped
  emitter site. TS9 deliberately anchors a PARAMETER_TYPE probe to the
  return-type fact (the same heuristic measures both declarations).
- Reflection textual parity is deferred to #25 (AnalysisContext
  pipeline); R cells gate the javac side only. Uncovered `Class.forName`
  shapes (3-arg overload, computed names) abstain visibly
  (`NON_LITERAL_FORNAME`); `ClassLoader.loadClass` and explicit
  `super(...)` constructor-chain invocations remain silent skips outside
  the family contract — documented, not claimed as covered.
- Argument canonical rendering is a harness-local convention (tree
  order, qualified names); #23 owns the production TypeMirror contract.
- Member-shape emission covers direct invocations and `new` expressions
  in the site file; method references and implicit constructor chains
  are out of scope.
