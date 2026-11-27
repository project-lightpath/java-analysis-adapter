# RESOLUTION-61 — Injection-contract type matching via javac index shapes

Issue: project-lightpath/java-analysis-adapter#61 (part of #20; the
last major TEXTUAL consumer of the javac symbol-index seam, enumerated
out of #23's scope in PLAN-23 Non-goals).
Branch: `issue-61-injection-javac-shapes` from `origin/main` @ `fb8a664`.
Commits: PLAN_61 `21c5499` (+ rounds 1-5 amendments through `5d45148`),
RED_61 `3dfd7c8`, GREEN_61 `084d2d0` (+ `4e76808`), EVIDENCE_61
`f288fe1`.

## What changed

The injection-contract type matching (binding-candidate metadata vs
source candidates) resolves and compares type spellings through javac
index shapes behind `flpath.java.symbolindex` (default ON), with the
textual ladder preserved as both the flag-off path and the
decline-fallback:

- **`JavacInjectionTypeShapes`** (new seam, bare Optionals — NO
  evidence channel; the opt-out gate lives inside): `requestedShape`
  (the injection parameter's canonical erased shape through the
  `JavacFrameworkMemberLookup` unique-member seam, with the
  extractor's phantom-constructor guard recomputed through the SAME
  `constructorInfos` extraction now exported from
  `FrameworkInjectionScanning`), `candidateShape` (the producer
  correlation's return, or the unique usable index type named in the
  candidate's file — the MirrorLookup named-filter rule), and
  `contractShapes` (the declared type's DIRECT supertypes).
- **Index extensions** (additive): FIELD and ENUM_CONSTANT members
  carry their declared type in `MemberTypes.returnType` /
  `returnBinaryType` (was `""`; complete reader audit in PLAN-61 §3.2,
  the `JavacReflectionTargets.selectMember` kind filter holds — the
  migration class's field cells are the churn golden); `TypeInfo`
  gains `supertypes` (direct superclass omitting `java.lang.Object` +
  directly implemented interfaces, canonical erased, ERROR entries
  omitted).
- **The ladders** (PLAN-61 §3.4, five adversarial plan rounds):
  repair-only substitution — `erasedRaw = canonicalType(erased)
  .or(shape)` in C1/C2 (both sides), C3's erased branch (source side),
  so a non-null textual answer is NEVER overridden (the
  same-CU-vs-import and java.lang-vs-nested divergences are
  characterized residuals, pinned by cells); C4 (contract disjunct)
  and C5 (metadata-row disjunct) are `!hasGenericArguments`-gated and
  independent of the `requested.isEmpty()` early returns; C6
  (`metadataTypeMatches`) and `proxyTargetTypeMatches` are untouched
  (documented dispositions). Threading: `InjectionScanning` computes
  `requestedShape` once per parameter; signature changes confined to
  the `BoundedFrameworkModel` family.
- **Vacuum-fill promotions are the sanctioned behavior change**
  (flag-on only): null→TRUE (javac-proven raw equality), null→FALSE
  (the contract path runs under the helper-proven raw difference), and
  the C3 fallback suppression — each with its no-fabrication argument
  recorded in PLAN-61 §3.5. Zero golden churn beyond the two
  sanctioned index-shape pins (§7 below): the full suite shows the 27
  new cells and NOTHING else moved.

## Acceptance evidence (issue #61)

- Injection-contract matching uses canonical index shapes flag-on
  (A1-A13, B1-B5, D cells); the flag-off textual goldens are pinned in
  every cell (count-form where fact identities are involved).
- Same-spelling declarations keep ONE identity across files regardless
  of import style (A4: explicit vs on-demand import of the same type
  both match the same producer flag-on; flag-off only the explicit
  one).
- No fabricated DEFINITE: the shape arms repair only textual vacuums;
  the generics gates hold (B4/B5 red under exactly their gate-removal
  mutations); the phantom-constructor guard holds (A10 red under its
  removal); the qualifier ladder is never bypassed (A5's end-to-end
  twin).
- Gates: `make adapter-test` **782 executions** (floor raised
  754 → 782: the 28 new cells, zero other churn), zero failures;
  `make java-lint` PASS with 0 new findings on BOTH local JDK 27 and
  helios08 Temurin 17 (scratch checkout, identical measurements — the
  #64 discipline); `make evidence-validate` pass.

## Probe receipts (RED commit, scratch Probe61Test not committed)

- P2: `resolveReferenceType("List")` is empty under
  `import java.util.*` — the requested-side defect class is
  JDK/classpath on-demand imports (source-type on-demand resolves).
- P1: nested types never surface as bean candidates (top-level only);
  the index canonical for nested types is dotted
  (`demo.Outer.Inner`), binary `$`.
- P4/P6: injection offsets fall INSIDE the `constructors(file)`
  MethodInfo spans; the lookup seam's name-scoped owner guard makes
  the correlation bijection unnecessary here (probe-confirmed: the
  bijection adds no repair coverage beyond the unique-member lookup —
  unresolvable sibling vectors fail the group either way).
- P7: `@Ann(1 > 0)` strips cleanly; `@Mar$k` corrupts the type
  spelling AND pollutes qualifiers — A5 splits into a seam-level
  repair pin and an end-to-end no-bypass pin.
- P5a: the extractor normalizes varargs `...` to `[]` (parity cell);
  P5b: the erased bound is pinned by the existing #23 corpus.

## Mutation checks (executed, all red under exactly their own mutation)

1. Remove the requested-shape arm in C1/C2 ⇒ 7 requested-side cells.
2. Remove the candidate-shape arm ⇒ A6.
3. Remove field type shapes ⇒ A2.
4. Remove the C4 contract disjunct ⇒ B1.
5. Remove the C4 generics gate ⇒ B4 (the fabricated match appears).
6. Remove the C5 disjunct ⇒ the C5-positive downgrade pin.
7. Remove the C5 generics gate ⇒ B5 — the FIRST run was VACUOUS (no
   scanned match in the fixture, so no observable could move); the
   cell was reworked to a producer-match fixture where the erased row
   would otherwise downgrade DEFINITE to POSSIBLE, and the re-run is
   red. Recorded so the rule is strengthened: a gate pin needs a
   fixture where the UNGATED arm moves an observable.
8. Remove the phantom-constructor guard ⇒ A10 (the phantom site steals
   the real constructor's shape — a wrong-attribution fabrication).

## Sanctioned test edits (the complete list)

- `JavacSourceSymbolIndexTest.declarationAnnotationsExposeCanonical
  NamesAndValues:254` — field `returnType` "" → `java.lang.String`.
- `JavacSourceSymbolIndexTest.memberShapesUseCanonicalErasedNames:
  313-321` — record component `x` "" → `int`; enum constant `RED`
  "" → `demo.Shapes.Color`.
No other existing test was edited.

## Review record

- **Plan review**: 5 adversarial rounds (Engram-grounded subagents):
  12 → 7 → 6 → 3 → 1 findings. Material design changes forced by
  review: the repair-only substitution policy and the
  `!hasGenericArguments` gates (round 1 — the original shape-first
  design fabricated matches and, via `typeMatchCertain`, DEFINITE);
  the vacuum-fill promotion enumeration and the C2 uniformity (rounds
  2-3, including the round-3 reversal of the round-2 C2 disposition
  when the textual-C2 short-circuit was shown to flip a
  previously-TRUE match); the uniformity claim scoped to the
  raw-mismatch FALSE (round 4 — C1's generics-tail exit); the C4
  reach corrected to the code's real control flow (round 5). Loop
  closed at confidence **0.90**.
- **Implementation review**: recorded in REVIEW_61_n commits (see the
  git history and the round summaries below).

## Behavior facts discovered at GREEN

- A producer metadata row must carry `kind`/`producerMethod` to match
  a producer candidate at all (`candidateMetadataMatches` line 48) —
  A1's first binding row was silently inert.
- A multi-type file whose owner name resolves uniquely repairs
  LEGITIMATELY (the lookup's owner guard is name-scoped, unlike the
  correlation seam's file-scoped `singleTypeFile`); the honest
  multi-owner abstention shape is the duplicate binary name across
  roots (A8 re-scoped).
- A source-only consumer in a ServiceLoader fixture reports
  `javac_source_error` and blocks the scan's DEFINITE promotion
  scan-globally (the #26 incomplete-scan contract) — the C3 fixture
  compiles self-contained stub Spring annotations.
- The C4 disjunct fires through the CANDIDATE side's vacuum even when
  the requested side resolved textually — the disjunct's requested
  input is the repair-only helper result, not the shape alone.

## Residuals

- The metadata-side spellings (binding rows, proxy rows) stay textual
  (external spellings; C6's case-insensitive simple-name
  over-approximation is deliberately kept — tightening would lose
  true answers).
- `proxyTargetTypeMatches` (MetadataMatching:95) stays textual — its
  own follow-up (the proxy descriptors already correlate through #23).
- Generic binding rows (C3's bothGeneric branch) and the producer
  generic tail stay textual (the index is erased).
- The same-CU × import and java.lang × same-CU JLS-wrong textual
  answers are kept by repair-only (characterized by D6a/D8p/D10).
- Same-arity overload pairs never repair (fail-closed, A7).

Confidence score: **0.88** — the ladder design survived five
adversarial plan rounds and eight mutation checks; the flag-off path
is pinned in every cell; the residual surface (metadata spellings,
generic rows, proxy rows) is enumerated with reasons.
