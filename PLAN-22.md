# PLAN-22 — annotation names and arguments from javac, not regexes

Issue: project-lightpath/java-analysis-adapter#22 (parent #20).
Branch: `feature/flpath-5142-javac-annotation-names` from `origin/main` @ `1077c09`
(after merges of #33, #35, #36).
Prior art: `PLAN-21.md` / `RESOLUTION-21.md`, `JavacSourceSymbolIndex`
(the #21 seam: one parse per `AnalysisContext` behind
`flpath.java.symbolindex`, default off), PLAN-16/RESOLUTION-16 (the
accuracy GO that motivates the whole tree).

## Goal

Replace the annotation-name and annotation-argument **text** reading with
javac answers from the #21 index, behind the existing
`flpath.java.symbolindex` flag, keeping the textual path as fallback and
default output byte-for-byte unchanged while the flag is off.

## Verified consumer inventory (Engram-searched, `@[A-Za-z_]` family)

In scope — **narrowed by review loop 1 to consumers whose call sites
actually hold the correlation inputs** (file, owning type, member name):

| Site | Correlation inputs available | What changes flag-on |
|---|---|---|
| `TextLayer11.methodDescriptor` via `SourceMetadataScanner.proxyFeaturesForInterface` (interface file + resolved interface name in scope at the call site) | yes | parameter shapes for proxy target descriptors |
| `JavacAnnotationAccess` read layer (helper-level; correlation consumed by migration point 1; the name/value surface has test-only consumers in #22 — production consumers arrive with #24/#37) | n/a | annotation names, values, member correlation |

**Deferred to #24/#37 (block-only statics with dozens of call
sites, no owner context):** `TextLayer09.annotationOccurrences` /
`hasAnnotation` / `annotationArguments` (the `@(...)?name\b` matcher
with the same `$`/Unicode blindness, backing framework bean/injection
layers), `TextLayer18.qualifierNames` (a name-*capturing* regex the
original inventory missed), and **`TextLayer19.parameters`' own
`@[A-Za-z_]` strip copy** (the issue names TextLayer19; its live copy
sits inside the per-parameter qualifier path handed to #24). These
need the #24 member-model rework or an explicit signature sweep; the
residual list records that textual name **matching** — not just
`maskAnnotationArguments` — keeps its `$`/Unicode gaps until then.

Out of scope (boundary, loop-1-confirmed):

- `SourceMetadataScannerTextLayer07.maskAnnotationArguments` — widening
  its name regex would change textual-mode output and break the
  flag-off guarantee. Flag-on scans still execute it for retained
  textual consumers, so its gaps are a flag-on residual too.
- Declaration-*discovery* regexes that tolerate annotations
  (`State.METHOD`, `State.INJECT_FIELD`, `TextLayer11` constructor
  pattern, `TextLayer15.INITIALIZER_FIELD`, `ReflectionLayer02/03`,
  `FrameworkInjectionScanning`).
- `TextLayer12.returnType` and producer-signature shapes: its javadoc
  says generics are **deliberately retained for producer matching**;
  the index's erased types would change `producerSignatureMatches`
  outcomes — framework semantics, #24 scope. The return-shape repair
  moves to #24 with the producer-matching decision.
- Framework route/bean/injection semantics (#24), reflection runtime
  strings (#25), parity harness (#26).

## Design

### Invariants fixed by review loop 1

1. **The static text helpers stay textual and unconditionally
   correct-as-textual.** `SourceMetadataScanner.parameterType`,
   `returnType`, `hasAnnotation` & co. keep their signatures and
   behavior in both flag states; `JavacResolverAccuracyTest`'s pinned
   known-bad values (lines 187–188) remain green unchanged. The flag
   gate lives only at instance-level consumers that can reach
   `JavacSourceSymbolIndex.of(context)`.
2. **`SourceMetadataScannerFileExtracts` stays purely textual.** The
   static per-path memoization of `MethodInfo` (computed at cache-fill
   time, context-free) is never written with index-derived values: a
   flag-on scan followed by a flag-off scan in one JVM must not poison
   the cache (test cell). Flag-on consumers correlate **after** reading
   textual `MethodInfo` from the cache.
3. **Name-spelling contract**: `JavacAnnotationAccess` offers both the
   annotation's **simple** name and its **canonical** name; consumers
   pick per their membership logic (qualifier membership by simple
   name, evidence by canonical). The flag-on leak-guard cell for
   qualifier emission moved to #24 with the qualifier consumers (its
   design is in the #24 comment); #22 pins only the accessor-level
   dual-spelling rule.

### Correlation rule (the load-bearing decision)

The migration correlates at the points where a consumer asks a
**name-keyed** question and holds (file, owning type, member name):

- Given (file, owning type, member name) → the owning type's index
  `TypeInfo` (canonical or binary), then
  `index.members(typeName, memberName)` for every overload's
  `annotations()`, `parameterTypes()`, `returnType()`.
- **Constructor names**: dropped in loop 2 — the only migrated
  `methodDescriptor` call site maps interface **methods**, never
  constructors; the `<init>` translation was dead code in scope.
  Constructor/`<clinit>` correlation lands with #24's member-model
  rework. Initializer-block pseudo-members always fall back.
- Overload disambiguation: the textual `MethodInfo` knows its parameter
  count; when exactly one index overload matches the arity, its values
  are used. Zero matches (unindexed source, error type, local class)
  or >1 same-arity matches → **keep the textual value** (safe: that is
  today's output; the #26 harness measures the fallback rate).
- The index gains **no position/offset API** and **no new type-spelling
  API** in this subissue.

### Type-spelling and repair-trigger decision (probe-gated)

The textual path emits **source spellings with generics retained**
(`List<String>`); the index emits **canonical erased**
(`java.util.List`). Loop-1 finding: these are not interchangeable —
wholesale replacement would change every descriptor, not just
annotation-broken ones. Decision:

- **Repair trigger (loop-2/3/4 fix)**: a parameter is annotation-broken
  when its raw text contains an annotation the textual stripper cannot
  fully consume: `@` not followed by `[A-Za-z_]` (Unicode/`$` names),
  the character at the end of the **maximal
  `@[A-Za-z_][\w.]*` match** being `$` or non-ASCII (the `@Ma` + `Ωga`
  and `@pkg.` + `Ωmega` partial-consumption tails), or a post-strip
  output that still contains `@` or `(`. Plain ASCII annotations
  (`@Deprecated`, `@Ann String`) and type-use annotations inside
  generics (`List<@Ann String>`) strip cleanly and stay textual — a
  cell pins `@Deprecated String p` identical under both flags. The
  trigger is implemented as a character-class scan
  (`Character.isJavaIdentifierPart`-based), so the helper carries no
  `@[A-Za-z_]` regex literal (cell 12 compatibility). (Loop-2's
  justification erred on one shape: `(value=1)String` does contain
  `(`; the provable miss is the `$k` tail only.) A pre-RED scratch
  probe pins the predicate against every issue cell shape (plain,
  `final`, annotated-plain, annotated-with-args, qualified,
  `$`-named, Unicode-initial, mixed ASCII/non-ASCII (`@MaΩga`,
  `@pkg.Ωmega`), varargs, array, generic with type-use annotation)
  before RED.
- The migrated `methodDescriptor` path uses index parameter types
  **only for triggered parameters**; clean parameters keep their
  textual source spelling.
- For a repaired parameter, the emitted spelling is the index's
  canonical erased name — an **intended, cell-asserted flag-on delta**
  (`String@$Ann[]` → `java.lang.String[]`, `@Ann(value = 1) String p`
  → `java.lang.String`). The probe also records `TypeMirror.toString()`
  vs erasure per shape; if a cheaper source-spelling render exists, the
  plan is amended before RED.
- A matrix cell asserts the descriptor spelling for a generic
  parameter under **both** flag states (clean parameter: identical;
  triggered parameter: the recorded delta).

### Migration points (narrowed; loop-5 scope triage)

1. `TextLayer11.methodDescriptor` via
   `SourceMetadataScanner.proxyFeaturesForInterface`: flag-on repair of
   triggered parameter shapes. Correlation owner is the **resolved
   interface name** (the descriptor's `method` belongs to the interface
   file, not the caller); when the interface file is absent the
   descriptor is `""` and the path is always-textual.
2. ~~Field-injection / bean qualifier consumer wiring~~ — **handed to
   #24** (loop-5 triage): reproducing the T18+T19
   `injectionQualifiers` pipeline (the six-family output-shape table,
   the per-site canonical-Spring guard split, the
   `Named:`/`ResourceDefault:` enrichment contracts, and the
   call-site-local gate placement) is framework-scanning semantics,
   not annotation-name reading. The loop-5 findings that specified it
   are preserved as a design comment on issue #24 so it can be tackled
   directly. What stays in #22 is the **read layer**:
   `JavacAnnotationAccess` answers annotation names (simple and
   canonical spellings), rendered values, and member correlation for
   any consumer — asserted helper-level and exercised end-to-end by
   migration point 1.

A package-private `JavacAnnotationAccess` helper (holds the per-context
index from `JavacSourceSymbolIndex.of(context)`; the scanner's
package-private `context` field reaches it from every layer) wraps the
lookup + fallback + spelling rules. The flag gate is always
call-site-local, never inside a shared static helper that deferred
consumers also call.

### Flag and test isolation (loop-2/3 fix)

Reuse `flpath.java.symbolindex`. This subissue adds the **first
production reader** of the flag, so in-JVM flag toggling in tests races
concurrently running scan-driving classes (`JavaAnalysisAdapterTest`,
`SootUpBaseGraphProviderIntegrationTest`,
`ServiceLoaderEndToEndTest`, `SootUpDependencyBoundaryIntegrationTest`
— none can see a `@ResourceLock` they do not declare). Fix: every
flag-**writing** test class (`JavacAnnotationMigrationTest` and the
existing `JavacSourceSymbolIndexTest`) is annotated
`org.junit.jupiter.api.parallel.Isolated`, which JUnit guarantees runs
exclusively of all other classes — no existing-test semantics change
beyond the annotation, and no other class needs editing. Flag-on
**end-to-end adapter** cells (if any) run in a forked JVM (the
`JavacResolverAccuracyTest` harness pattern). The existing
`JavacSourceSymbolIndexTest` wiring-invariant cell's flag write is
covered by the same `@Isolated`; its scan equality remains green by
fixture content (no annotations/proxies/beans in its fixture) —
recorded as a residual.

## Test matrix (`JavacAnnotationMigrationTest`, new file; SAME_THREAD)

Derived from the issue's cells plus the JLS boundary rule (`$`,
Unicode, qualified names — the D-series lesson):

1. Flag off: output identical to the textual path (scan equality on a
   fixture with annotated members).
2. `@$Ann` on a parameter and on a **field** (helper-level through
   `JavacAnnotationAccess` — no migrated field consumer post-triage;
   the field arm asserts the canonical name, red at RED): names and
   shapes resolve (A18/A19: `java.lang.String[]`, `java.lang.String`
   flag-on).
   Plain `@Deprecated String p` is **identical under both flags**
   (clean-strip pin). Method-return and type-annotation **shape** questions have no
   migrated consumer in this scope; their cells exercise
   `JavacAnnotationAccess` directly and are marked helper-level.
2b. ~~Qualifier shape equality~~ — handed to #24 with migration
    point 2 (loop-5 triage).
3. Unicode-initial annotation name (e.g. `@Ωmega`).
4. Fully qualified annotation name `@pkg.Outer.Ann`: helper-level
   flag-on assertion is the **canonical name** `pkg.Outer.Ann` (the
   textual path already emits the simple name; the index value-add is
   canonical resolution) — red at RED.
5. `$`-named annotation `@Mar$k` on each member kind.
6. Annotation values: array, class literal, enum constant, nested
   annotation — asserted at **helper level** through
   `JavacAnnotationAccess`; the contract is the #21 renderer spellings
   **minus the string quoting**, per the shape rules.
7. Varargs and type-use annotations before and after the type.
8. Annotation-like text inside a string or comment is not an
   annotation (index path: it never was; the cell pins that the
   migration does not regress the masking the textual path keeps).
9. Overloads: two same-name methods with equal parameter counts are
   not silently conflated — flag-on falls back to textual values
   rather than guessing (the ambiguity rule), and the parity choice is
   asserted.
10. Error types / unindexed sources: silent textual fallback, no
    diagnostics added (non-goal: scan-global diagnostics).
11. Determinism: two fresh contexts produce equal migrated output.
12. No annotation-name regex remains reachable from the migrated call
    sites: a source-scan test asserts the name-capturing regex family
    (`@(...)?name\b`, `qualifierNames`) is absent from
    `JavacAnnotationAccess` and that the gated sites route through it;
    the trigger's character-class scan carries no `@[A-Za-z_]`
    literal by construction (finding 9: literal-absence on a moved
    helper changes nothing; the assertion names the symbols).
13. Cache poisoning: a flag-on scan followed by a flag-off scan on the
    same file in one JVM yields byte-for-byte textual output for the
    flag-off scan (the `SourceMetadataScannerFileExtracts` invariant).
14. ~~Positive qualifier delta~~ — handed to #24 with migration
    point 2; the accessor's non-vacuity is proven helper-level by
    cells 3–6 and end-to-end by cell 15.
14b. Mixed-name trigger shapes: `@MaΩga` and `@pkg.Ωmega` parameters
    are triggered and repaired flag-on (the maximal-match boundary
    shapes from the probe).
15. Spelling delta: a clean generic parameter's descriptor is
    identical under both flags; a triggered parameter's descriptor
    records the intended canonical-erasure delta
    (`String@$Ann[]` → `java.lang.String[]`, `@Ann(value = 1) String p`
    → `java.lang.String`).
16. ~~`@Inject` qualifier leak-guard~~ — handed to #24 with migration
    point 2 (the simple/canonical name-spelling contract it tested is
    part of the qualifier pipeline).

## TDD sequence

- **RED_22**: the matrix above plus a behavior-neutral seam (the
  `JavacAnnotationAccess` helper returning textual values
  unconditionally), expected-red cells enumerated in the commit.
  Expected-red at RED (loop-5/6 per-cell re-derivation from the stub's
  actual behavior — the stub delegates to the textual helpers, so any
  cell whose flag-on assertion is satisfied by textual output passes at
  RED): cells 2 (both arms: triggered parameter repair and the
  helper-level field canonical name), 3, 4 (helper-level canonical-name
  arm), 5, 6, 14b, 15 (any cell requiring a flag-on index answer or a
  delta). Expected-green at RED: cells 1, 7–13 —
  equality/fallback/determinism cells the textual stub already
  satisfies; cell 12's literal-absence arm is
  green (the delegating stub carries no regex literal) while its
  routing arm is red at RED (the gated site is not wired until GREEN)
  and green at GREEN.
- **GREEN_22**: implement the correlation and migration point 1 (the
  read layer plus the `proxyFeaturesForInterface` repair); targeted
  green; full gates; Makefile floor raised to the new
  strict total.
- **REFACTOR_22**: under green.
- **EVIDENCE_22**: `RESOLUTION-22.md` — parity deltas vs the pinned
  baseline losses (`String@$Ann[]`, `(value=1)String` now correct with
  the flag on), fallback-rate note, wall/heap receipts, residuals.

## Gates

`make adapter-test` (count floor raised in GREEN), `make java-lint`
(zero findings; no new suppressions without inventoried justification),
`make evidence-validate`. Full green adapter-test first applies at
GREEN_22 (RED intentionally contains failing tests, per AGENTS).

## Adversarial plan review (pre-implementation)

Loop 1 findings folded back (all verified by the reviewer against
source):

1. **The index's canonical erased shapes are not the textual source
   spellings** — wholesale descriptor replacement would change facts
   for every member and producer matching deliberately retains
   generics. Fixed: repair only annotation-broken parameters, return
   shapes deferred to #24, spelling delta cell-asserted (finding 1).
2. **`MethodInfo` carries no file or owner**; block-only static
   annotation readers have no correlation inputs. Fixed: scope
   narrowed to the two call sites that hold (file, owner, member);
   block-only readers deferred with residuals (finding 2).
3. **`SourceMetadataScannerFileExtracts` memoizes textually and
   statically** — index values must never enter it. Fixed as invariant
   2 with a poisoning cell (finding 3).
4. **Inventory missed `TextLayer18.qualifierNames` (name-capturing)
   and `TextLayer09.annotationOccurrences`** — added with explicit
   deferral decisions (finding 4).
5. **Simple vs canonical name contract at qualifier consumers** —
   fixed as invariant 3 with the `@Inject` cell (finding 5).
6. **Constructor `<init>` vs index naming** — correlation translation
   plus a pre-RED probe; `<clinit>` always falls back (finding 6).
7. **`JavacResolverAccuracyTest` pins the static helpers** — statics
   stay textual in both flag states; corrections asserted only in the
   new test (finding 7).
8. `maskAnnotationArguments` caveat (finding 8) and cell-12 decision
   (finding 9) folded into the boundary note and matrix.

Loop 2 findings folded back:

9. **The repair trigger provably missed both headline shapes**
   (`(value=1)String` leaves no residue; `@Mar$k` leaves balanced
   `$k`) — trigger redefined on the raw parameter text, probe-pinned
   before RED (findings 1–2).
10. **Migration point 2 named no implementable call site** — replaced
    with the field-injection and bean-type sites that hold the
    correlation triple; the T18 chain is duplicated-and-owned; per-
    parameter qualifiers stay textual (#24) (finding 3).
11. Constructor cell and `<init>` translation dropped as dead code in
    scope (finding 4).
12. **Cross-class flag race**: first production flag reader — shared
    `@ResourceLock` on all flag-touching test classes; forked JVM for
    full-scan flag-on cells (finding 5; mechanism superseded by loop-3
    finding 14's `@Isolated`).
13. Cells 2/6 marked helper-level where no migrated consumer reads
    them (finding 6); positive qualifier-delta cell added (finding 7);
    `BoundedFrameworkModelCandidateMetadata.methodDescriptor`
    dispositioned in the inventory (finding 8, #24 scope); cell 12
    asserts symbol reachability, not literal absence (finding 9); the
    issue-acceptance interpretation is recorded for RESOLUTION-22:
    #22's acceptance is met at the consumer level, with the retained
    textual helpers deleted only after #26 parity (finding 10);
    absent-interface proxy fallback is always-textual (finding 11).

Loop 3 findings folded back:

14. **Isolation was one-sided** — every scan-driving class races the
    first production flag reader; fixed with `@Isolated` on the
    flag-writing classes (exclusive execution, no existing-test edits)
    and forked-JVM for any full-adapter flag-on cell (finding 1).
15. **The raw-text trigger over-fired** on cleanly-stripped shapes
    (`@Deprecated`, `List<@Ann String>`); refined to
    unconsumable-annotation detection, plain-annotation identity cell
    added, and loop-2's `(value=1)String` justification corrected
    (finding 2).
16. **Cell 14 was unconstructible at the field site** (Unicode
    qualifiers undiscoverable by the ASCII-gated `INJECT_FIELD`
    discovery; `$` names emit a wrong-but-present qualifier flag-off) —
    pinned to the bean type-level Spring site with correction-delta
    field cells (finding 3).
17. **The owned chain must include the T19 `injectionQualifiers`
    enrichment** (`Named:`/`ResourceDefault:` derivations) with an
    output-shape table and a shape-equality cell, or `@Resource`
    fields lose derived qualifiers flag-on (finding 4).
18. Spring-only arm, Quarkus 1-arg and producer-method qualifier
    deferrals named (finding 5); stale migration-point count fixed
    (finding 6); the wiring-invariant cell's fixture-content greenness
    recorded (finding 7); expected-red partition enumerated in-plan
    (finding 8).

Loop 4 findings folded back:

19. **The shape table missed the canonical-Spring guard family and its
    per-site split** — without it, a user-defined `@Named`/`@Service`
    at the bean site fabricates a qualifier flag-on. Added as the
    first family (bean Spring site only, canonical-name comparison;
    field arm unguarded), with the both-states-empty cell (finding 1).
20. **Expected-red partition re-derived from the stub's actual
    behavior** — six cells were mis-classified red; the delegating
    stub makes them green at RED (finding 2).
21. **Quoted-value contract conflict** between cell 6 and the shape
    table — quoted families emit unquoted content, non-literal values
    fall back to the rendered expression verbatim (finding 3).
22. Trigger clause 2 pinned at the maximal-match end with
    `@MaΩga`/`@pkg.Ωmega` probe shapes and cells; the trigger is a
    character-class scan so cell 12's literal-absence arm holds
    (findings 4–5); loop-2's superseded `@ResourceLock` text annotated
    (finding 6); the second ASCII gate (`hasInjectionAnnotation`)
    enumerated in the field-qualifier residual (finding 7).

Loop 5 findings folded back (scope triage per the user's rule: valid
but out-of-#22-scope items move to issues, not into this plan):

23. **The qualifier-fidelity machinery is #24's charter, not #22's**
    — reproducing the T18+T19 `injectionQualifiers` pipeline (shape
    table with exact code ordering: guard → `Resource` → bean-name →
    `NON_QUALIFIER` → `Qualifier`/`Named` → simple, with
    `ResourceDefault`/`Named:` enrichment appended after the
    per-annotation list; the per-site guard split; the simple-name
    enrichment-trigger semantics; the call-site-local gate placement
    because `TextLayer19.parameters` and
    `FrameworkBeanScanning.annotationValues` share
    `qualifierNames(file, block)`) is framework-scanning semantics.
    Migration point 2 and qualifier cells 2b/14/16 are handed to #24
    with the findings preserved as a design comment on that issue;
    #22 keeps the read layer (findings 1, 2, 4).
24. Cells 4 and 8 reclassified per-cell: cell 4's flag-on assertion
    is the helper-level canonical name (red at RED); cell 8 is
    expected-green (finding 3). The stale inventory row is fixed
    (finding 6). The per-parameter annotation index API becomes its
    own issue (finding 4's dependency).

Loop 6 findings folded back (all wording-level; the design verified
sound):

25. GREEN_22's stale "two migration points" fixed; the duplicated
    `JavacAnnotationAccess` paragraph deleted; cell 2's field arm
    re-marked helper-level with an explicit partition entry
    (findings 1, 3, 4).
26. The issue-closure claim reworded to a **re-chartering
    statement** (#22 delivers the read layer plus the single
    parameter-shape repair; TextLayer12/19/header copies re-chartered
    on #24 and #37), `TextLayer19.parameters`' own strip copy added to
    the deferred inventory, the read-layer inventory row honest about
    its test-only consumers, invariant 3's leak-guard owner re-pointed
    to #24, and the owner/annotation-evidence scope bullet
    dispositioned to #24 (findings 2, 5, 6, 7).

## Residuals / risks

- **Deferred consumers**: `TextLayer09.annotationOccurrences`/
  `hasAnnotation`/`annotationArguments`, `TextLayer18.qualifierNames`,
  and `TextLayer19.parameters`' strip copy keep their `$`/Unicode/
  qualified-name blindness in both flag states until the #24
  member-model rework (or an explicit signature-sweep follow-up).
  RESOLUTION-22 states the interpretation explicitly: **#22 delivers
  the javac annotation read layer plus the single parameter-shape
  repair**; the issue's named `TextLayer12`/`TextLayer19`/
  header-stripping copies are **re-chartered on #24** (design comment)
  and **#37** — a re-chartering statement, not a consumer-level
  acceptance claim. The static helpers stay until #26 parity green
  permits deletion.
- Per-parameter qualifiers on constructor/method injection stay textual
  (no per-parameter annotation API in the index; #24 scope).
- `BoundedFrameworkModelCandidateMetadata.methodDescriptor` (JVM
  descriptor assembly from textual parameters) is #24 framework
  semantics; its annotation-broken inputs improve only via the #26
  parity path.
- `TextLayer12.returnType` and producer-signature generics are #24
  scope (deliberate generics retention for producer matching).
- Name+arity correlation cannot distinguish same-arity overloads —
  textual fallback until the index grows positions; measured in #26.
- The textual resolvers are kept (per #20); `maskAnnotationArguments`'s
  gaps are a flag-on residual too (retained textual consumers still
  execute it).
- Local/anonymous-class members are binary-keyed in the index; member
  correlation for them likely falls back silently; measured in #26.
- Repaired parameters emit canonical erased spellings (intended
  flag-on delta) until a source-spelling renderer is worth its cost;
  the #26 parity harness owns that decision.
- Unicode-named qualifiers on **field injection points** are
  undiscoverable in both flag states through **two** ASCII gates: the
  `INJECT_FIELD` discovery regex and
  `hasInjectionAnnotation`/`hasAnnotation` upstream of it — **#24**
  scope with the qualifier-consumer migration (design findings
  preserved on issue #24).
- The QUARKUS arm of `FrameworkBeanScanning.qualifiers` (1-arg
  `qualifierNames`), producer-method qualifiers, and the whole
  qualifier-fidelity machinery (six-family shape table, per-site
  canonical-Spring guard, `Named:`/`ResourceDefault:` enrichment
  contracts, call-site-local gate placement) — **#24** scope, findings
  preserved on issue #24.
- The index has **no per-parameter annotation API**; constructor and
  method injection qualifiers need one — tracked as its own issue so it
  can be tackled directly (index-API growth, consumed by #24).
- The issue's "record owner/annotation evidence for each parsed
  annotation" scope bullet is **#24** scope: adapter-level evidence
  recording is inseparable from the qualifier/bean consumers; the
  index already carries implicit ownership (`TypeInfo`/`MemberInfo`
  own their `annotations`).
- `JavacSourceSymbolIndexTest`'s wiring-invariant cell stays green by
  fixture content (no annotations/proxies/beans); its flag write is
  owned by the `@Isolated` fix.
