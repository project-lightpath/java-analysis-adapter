# RESOLUTION-77 — Normalize the producer-type seam's raw-extraction owner anchor

Issue: project-lightpath/java-analysis-adapter#77 (follow-up from #62 /
PR #72; `docs/resolutions/RESOLUTION-62.md` Residuals — the only
behavior-changing follow-up of the three filed after #62).
Branch: `issue-77-producer-anchor-normalization` from `origin/main` @
`fb8a664` (#80 merge).
Commits: PLAN_77 (+ 4 review-round amendments), RED_77, GREEN_77,
EVIDENCE_77 (this commit), REVIEW_77_* (implementation review rounds).

## What changed

One line: `FrameworkBeanScanning.producerCandidate` derives its
correlation owner through the shared `ownerTypeFor` helper
(`typeContaining(file, declarationStart(file, method.start()))`) — the
`declarationStart` normalization every other correlation consumer
already applies — instead of the historical raw-extraction anchor
(`typeContaining(file, method.start())`). For a member declared after
a nested type, the raw anchor resolved the NESTED type, so the
`javacProducerTypeAbstentionMember` identity named
`demo.ProducerConfig$Helper#build(1)` for a member declared in
`demo.ProducerConfig`. The identity now names the true owner.

- Comment updates: the seam's inline comment (the owner keeps the
  shared normalization, #77) and
  `JavacAbstentionEvidence.producerTypeAttributes`'s javadoc (the
  producer-type and descriptor key families stay distinct because they
  describe different lookups that can both decline for one candidate —
  the old two-derivations rationale is obsolete now that both seams
  normalize).
- The producer-TYPE VALUE never changes: anchor divergence requires a
  type declared before the member in the same file, which makes
  `singleTypeFile` false (`JavacSourceSymbolIndex.typesIn(path)` is
  file-level and `visitClass` counts every `TypeElement` — nested,
  local, anonymous, enum, record, interface), so the correlation is
  blocked (`multi_type_file`) in every constructible divergence shape
  and the textual spelling survives. Verified structurally AND by the
  full-suite probe: the scratch patch on main failed exactly ONE cell
  (the sanctioned C4 characterization).
- Everything the #62/#76 channel pinned is untouched: never
  `scan.diagnostics()`, never blocking DEFINITE promotion, flag-off
  emits no evidence, `withSilencedEvidence` failed-build handling,
  unique-owner guards, attribute keys, certainty, status, fact IDs.

## Acceptance evidence (issue #77)

- The normalized identity is pinned on BOTH framework arms: C4 (Spring,
  `producerAbstentionIsVisibleOnTheCandidateEvidence`, sanctioned edit
  to the observed `demo.ProducerConfig#build(1)`) and N1 (Quarkus,
  `quarkusProducerAbstentionNamesTheNormalizedOwner`, RED cell).
- The opt-out arm emits nothing: the N1 flag-off twin sweeps
  `assertNoAbstentionAttributes` over the binding fact.
- The clean path credits the canonical type: N2
  (`correlatingProducerCarriesTheCanonicalReturnType`), asserted at the
  `beanCandidates()` level with an on-demand-import return spelling
  (`java.util.Optional` textual fallback blank), so the correlation's
  success is load-bearing.
- Gates: `make adapter-test` **757 executions** (default=643, large=1,
  sootup=113; main's floor 754 + 3 cells), zero failures; `make
  java-lint` PASS with 0 new findings on the local JDK 27 (the change
  is a one-line expression swap plus comments — no GodClass-relevant
  structural change, so the #64 helios08 cross-check is not triggered);
  `make evidence-validate` pass.

## Mutation checks (executed, red under exactly their own mutation)

1. Revert the normalization (raw anchor) ⇒ N1 fails (observed
   `$Helper#build(1)` vs `ProducerConfig#build(1)`) — the RED_77
   receipt.
2. Corrupt the identity rendering (`owner + "$Mut#"` in
   `JavacAbstentionEvidence.memberIdentity`) ⇒ N1 and C4 both fail on
   the identity (plus the shared-family cells) — the assertions are
   not vacuous.
3. `ownerTypeFor(file, method)` → `file.typeName()`: SURVIVES — both
   fixtures declare the producer in the top-level type where the two
   agree; the mutant is killed by any future fixture declaring a
   producer inside a nested type (the owner reaches the identity, so
   `ownerTypeFor` would name the nested owner). Documented residual.
4. Drop the `memberOptional()` mapping (producerType keeps the textual
   value) ⇒ N2 fails (`java.util.Optional` expected, blank observed) —
   the N2 assertion is load-bearing.

## Review record

- **Plan review**: 5 rounds, 15 findings total, all incorporated.
  Material corrections: the route seam's raw anchor CAN diverge (the
  original anchor-safe-by-construction claim was wrong — the
  `declarationStart` filter constrains only the normalized owner);
  the N2 injection-fact anchor was unconstructible with a divergent
  spelling (the binding match consults the raw `producerSignature`),
  re-anchored to `beanCandidates()`; N2's vacuous-pass guard
  (on-demand-import spelling); expected-green labeling for the
  controls. Confidence 0.82 → 0.88 (round 5: no new addressable
  findings).
- **Implementation review**: see REVIEW_77 commits below.

## Residuals

- The method-MIRROR lookup owner in the same method
  (`FrameworkBeanScanning.producerCandidate`, the
  `javac.memberLookup(typeContaining(file, method.start()))` call)
  still uses the raw anchor — `javacMirrorAbstentionMember` keeps
  naming the nested owner (`demo.ProducerConfig$Helper#build(1)` in
  C4/N1's evidence). Same over-abstention defect class, safe
  direction; candidate follow-up.
- `FrameworkInjectionScanning.methodInjectionPoints:159` — the
  injection-point seam's owner (raw anchor, same class).
- `FrameworkRouteScanning.routesForMethod:186` — the route mirror
  lookup's raw anchor can diverge from the normalized filter at
  `routesForFile:112-118` (a top-level route method declared after a
  nested type).
- Mutation 3's equivalent mutant (above).

Confidence score: **0.90** — a one-line, probe-measured change with
zero value churn, the identity change pinned red-first on both
framework arms, and every channel contract unchanged; the open
residuals are documented same-class raw anchors in other seams.
