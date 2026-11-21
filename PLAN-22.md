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
| `TextLayer11.methodDescriptor` via `SourceMetadataScanner.proxyFeaturesForInterface` (file + `typeContaining` in scope at the call site) | yes | parameter shapes for proxy target descriptors |
| `SourceMetadataScannerTextLayer19` qualifier/injection extraction (has `file`) | yes | qualifier names read from the index |

**Deferred to a follow-up (block-only statics with dozens of call
sites, no owner context):** `TextLayer09.annotationOccurrences` /
`hasAnnotation` / `annotationArguments` (the `@(...)?name\b` matcher
with the same `$`/Unicode blindness, backing framework bean/injection
layers), and `TextLayer18.qualifierNames` (a name-*capturing* regex the
original inventory missed). These need the #24 member-model rework or
an explicit signature sweep; the residual list records that textual
name **matching** — not just `maskAnnotationArguments` — keeps its
`$`/Unicode gaps until then.

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
3. **Name-spelling contract per consumer**: qualifier membership checks
   (`NON_QUALIFIER_ANNOTATIONS`, `equals("Resource")`) and qualifier
   output use the annotation's **simple** name; evidence recording uses
   the canonical name. A flag-on `@Inject` injection point must emit no
   new qualifier (matrix cell) — feeding canonical names into the
   simple-name membership logic would fabricate facts.

### Correlation rule (the load-bearing decision)

The migration correlates at the points where a consumer asks a
**name-keyed** question and holds (file, owning type, member name):

- Given (file, owning type, member name) → the owning type's index
  `TypeInfo` (canonical or binary), then
  `index.members(typeName, memberName)` for every overload's
  `annotations()`, `parameterTypes()`, `returnType()`.
- **Constructor names**: the scanner's `MethodInfo` names constructors
  `<init>`; the #21 index names them after the type's simple name. The
  correlation layer translates `<init>` → the owning type's simple
  name. A pre-RED scratch probe pins the index's constructor
  `MemberInfo.name` spelling (AGENTS: probe, don't inspect).
  Initializer-block pseudo-members (`<clinit>`-shaped `MethodInfo`)
  have no index counterpart and always fall back.
- Overload disambiguation: the textual `MethodInfo` knows its parameter
  count; when exactly one index overload matches the arity, its values
  are used. Zero matches (unindexed source, error type, local class)
  or >1 same-arity matches → **keep the textual value** (safe: that is
  today's output; the #26 harness measures the fallback rate).
- The index gains **no position/offset API** and **no new type-spelling
  API** in this subissue.

### Type-spelling decision (probe-gated)

The textual path emits **source spellings with generics retained**
(`List<String>`); the index emits **canonical erased**
(`java.util.List`). Loop-1 finding: these are not interchangeable —
wholesale replacement would change every descriptor, not just
annotation-broken ones. Decision:

- The migrated `methodDescriptor` path uses index parameter types
  **only for parameters whose textual parse is annotation-broken**
  (the textual `parameterType` output still contains `@` or an
  unbalanced `(`). Clean parameters keep their textual source
  spelling.
- For a repaired parameter, the emitted spelling is the index's
  canonical erased name — an **intended, cell-asserted flag-on delta**
  (`String@$Ann[]` → `java.lang.String[]`). A pre-RED scratch probe
  records what `TypeMirror.toString()` and erasure yield per shape
  (array/nested/generic/varargs); if the probe shows a cheaper
  source-spelling render exists (it likely does not without new index
  API), the plan is amended before RED.
- A matrix cell asserts the descriptor spelling for a generic
  parameter under **both** flag states (clean parameter: identical;
  annotation-broken parameter: the recorded delta).

### Migration points (narrowed)

1. `TextLayer11.methodDescriptor` via
   `SourceMetadataScanner.proxyFeaturesForInterface`: flag-on repair of
   annotation-broken parameter shapes per the spelling decision.
2. `TextLayer19` qualifier/injection extraction (file in scope):
   flag-on annotation names from `AnnotationInfo`, with the
   simple/canonical spelling contract above.

A package-private `JavacAnnotationAccess` helper (holds the per-context
index from `JavacSourceSymbolIndex.of(context)`) wraps the lookup +
fallback + spelling rules so both consumers share one implementation.

### Flag

Reuse `flpath.java.symbolindex`. Flag off (default): byte-for-byte
today's output — pinned by a scan-equality cell in both directions.

## Test matrix (`JavacAnnotationMigrationTest`, new file; SAME_THREAD)

Derived from the issue's cells plus the JLS boundary rule (`$`,
Unicode, qualified names — the D-series lesson):

1. Flag off: output identical to the textual path (scan equality on a
   fixture with annotated members).
2. `@$Ann` on a parameter, field, method return, and type: names and
   shapes resolve (A18/A19: `java.lang.String[]`, `java.lang.String`).
3. Unicode-initial annotation name (e.g. `@Ωmega`).
4. Fully qualified annotation name `@pkg.Outer.Ann`.
5. `$`-named annotation `@Mar$k` on each member kind.
6. Annotation values: array, class literal, enum constant, nested
   annotation — read back through the migrated consumer with the #21
   renderer's spellings.
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
12. No `@[A-Za-z_]` annotation-name regex remains in the migrated call
    sites — a source-scan test asserts the literal is absent from
    `SourceMetadataScannerTextLayer19` and the migrated
    `methodDescriptor` path.
13. Cache poisoning: a flag-on scan followed by a flag-off scan on the
    same file in one JVM yields byte-for-byte textual output for the
    flag-off scan (the `SourceMetadataScannerFileExtracts` invariant).
14. Constructors: `methodDescriptor`-adjacent constructor correlation
    maps `<init>` to the type's simple name (probe-pinned); `<clinit>`
    pseudo-members always fall back.
15. Spelling delta: a clean generic parameter's descriptor is
    identical under both flags; an annotation-broken parameter's
    descriptor records the intended canonical-erasure delta
    (`String@$Ann[]` → `java.lang.String[]`).
16. `@Inject` on an injection point emits no new qualifier flag-on
    (simple-name membership contract).

## TDD sequence

- **RED_22**: the matrix above plus a behavior-neutral seam (the
  `JavacAnnotationAccess` helper returning textual values
  unconditionally), expected-red cells enumerated in the commit.
- **GREEN_22**: implement the correlation and the three migration
  points; targeted green; full gates; Makefile floor raised to the new
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

## Residuals / risks

- **Deferred consumers**: `TextLayer09.annotationOccurrences`/
  `hasAnnotation`/`annotationArguments` and
  `TextLayer18.qualifierNames` keep their `$`/Unicode/qualified-name
  blindness in both flag states until the #24 member-model rework (or
  an explicit signature-sweep follow-up); the issue's acceptance for
  those sites is met by #24, recorded here per the AGENTS
  open-cell rule.
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
