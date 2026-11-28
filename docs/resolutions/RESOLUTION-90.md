# RESOLUTION-90 — Normalize the remaining raw-extraction owner anchors

Issue: project-lightpath/java-analysis-adapter#90 (post-#77 residual
family, RESOLUTION-77 §Residuals; filed by the #85 triage item 4).
Branch: `issue-90-anchor-normalization` from `origin/main` @
`3e0f3d0` (PR #91 merge).
Commits: PLAN_90 (+ 1 review-round amendment), RED_90, GREEN_90,
EVIDENCE_90 (this commit), REVIEW_90_n (implementation review rounds).

## What changed

Three expressions, one per site, each routing a mirror-lookup owner
through the shared `ownerTypeFor` helper
(`typeContaining(file, declarationStart(file, method.start()))`) —
the #77 normalization — instead of the raw extraction anchor
(`typeContaining(file, method.start())`):

- `FrameworkBeanScanning.producerCandidate` — the `memberLookup`
  owner behind `javacMirrorAbstentionMember`.
- `FrameworkInjectionScanning.methodInjectionPoints` — the `owner`
  local (member/parameter mirror lookups AND
  `InjectionPoint.ownerType`).
- `FrameworkRouteScanning.routesForMethod` — the `memberAnnotations`
  owner (now agrees with the already-normalized `routesForFile`
  filter).

Divergence mechanism (probe-verified on the clean tree): METHOD's
regex consumes the previous member's closing `}`, so
`method.start()` lands inside the nested type's span for a member
declared after it; `typeContaining` then resolves the NESTED type.
`declarationStart` skips exactly those braces.

## Observables that changed (honest inventory, wider than the issue's
"identity string" phrasing — plan review round 1)

- Bean mirror seam: the `mirror_member_absent` decline with the
  `$Helper` identity disappears when the lookup now succeeds
  (M1b), and declines carry the NORMALIZED identity when it still
  declines (M1). Mirror-derived annotation values can now stand in
  where the raw anchor forced a textual fallback — the standard
  flag-on behavior every non-divergent member already gets.
- Injection seam: `InjectionPoint.ownerType` reaches the fact ID
  (`injectionFactId` :71), input name (:493), evidence symbol
  (:475), the per-owner uniqueness count gating `legacyIdAllowed`,
  the binding-row owner match (`injectionOwnerMatches` :41), the
  type-shape bijection (`JavacInjectionTypeShapes.memberFor` :108),
  the legacy-ID gate (`legacyInjectionFactId` :95), and the
  member/parameter mirror lookups. Normalization CORRECTS a
  misattributed owner and can RESTORE a binding-row match the
  misattribution blocked (M2's binding arm: callee resolves to the
  row's candidate where the `$Helper` owner left the injection
  unresolved). No fabricated target: the row is external metadata
  naming the true owner, the same evidence class that promotes
  non-divergent injections.
- Route seam: the member mirror lookup succeeds against the true
  owner, so mirror-resolved route values (folded-constant paths)
  apply (M3); the raw anchor silently forced the textual fallback
  (no route at all for a mirror-only path).

Everything the #62/#76 channel pinned is untouched: never
`scan.diagnostics()`, never blocking DEFINITE promotion, flag-off
emits no evidence, `withSilencedEvidence` handling, unique-owner
guards, attribute keys, certainty semantics, status, fact-ID schemes.

## Acceptance evidence (issue #90)

- M1 `producerMirrorAbstentionNamesTheNormalizedOwner` (Spring):
  ambiguous same-arity overload group after a nested type —
  `mirror_member_ambiguous` with `demo.AmbiguousAfterNested#make(1)`.
- M1b `producerMirrorLookupSucceedsAgainstTheNormalizedOwner`
  (Spring): single producer after a nested type — no
  `javacMirrorAbstention`, sibling seams still abstain (non-vacuity).
- M2 `injectionPointAfterANestedTypeNamesTheTrueOwnerAndSelectsItsRow`
  (Spring): the fact names `demo.Consumer#setThing` and the
  binding row naming the true owner selects `demo.Thing#<init>`
  (today: `demo.Consumer$Helper` spellings, unresolved).
- M3 `routeAfterANestedTypeResolvesItsFoldedPathThroughTheMirror`
  (Quarkus): `/items/greeting` flag-on; no route flag-off (the
  folded constant is mirror-only — the opt-out golden keeps the
  cell non-vacuous).
- M4 `producerInsideANestedTypeNamesTheNestedOwner` (Spring,
  expected-green control): a producer declared INSIDE a nested type
  names `demo.Config$Helper#make(1)` — kills RESOLUTION-77
  mutation 3's equivalent mutant (`ownerTypeFor` vs
  `file.typeName()`).
- Gates: `make adapter-test` **806 executions** (default=692,
  large=1, sootup=113; main's floor 801 + 5 cells), zero failures;
  Makefile :24 floor literal raised 801 → 806. `make java-lint`
  PASS, zero findings (three one-expression swaps plus comments —
  no GodClass-relevant structural change, the #64 helios08
  cross-check is not triggered). `make evidence-validate` pass.
- Zero edits to existing tests (rg: no test pinned
  `javacMirrorAbstentionMember`, a divergent injection owner, or a
  divergent folded route path; the nested-owner binding precedent
  `resolvedBindingMatchesNestedInjectionOwner` pins a field INSIDE
  the nested type, where raw and normalized anchors agree —
  unaffected, verified green in the full suite).

## Mutation checks (executed, red under exactly their own mutation)

1. Revert the bean anchor (raw `typeContaining(file,
   method.start())`) ⇒ M1 and M1b red (observed
   `mirror_member_absent` with `$Helper#make(1)` / `$Helper#build(1)`).
2. Revert the injection anchor ⇒ M2 red on the identity assertion
   (`spring:injection:demo.Consumer$Helper#setThing(Thing)` observed;
   the binding arm never fires under the mutant — the row cannot
   match the nested owner).
3. Revert the route anchor ⇒ M3 red (no route: the mirror lookup
   fails under the nested owner and the folded constant cannot
   resolve).
4. `ownerTypeFor(file, method)` → `file.typeName()` at the
   producer-type seam ⇒ M4 red (`demo.Config#make(1)` observed vs
   `demo.Config$Helper#make(1)`) — RESOLUTION-77 mutation 3's
   equivalent mutant is now killed.
5. Corrupt `JavacAbstentionEvidence.memberIdentity` (`$Mut#`) ⇒ 13
   cells red across the shared identity families (M1, M1b, M4 plus
   the #62/#77 cells) — the assertions are not vacuous.

## Review record

- **Plan review**: 2 rounds (round 2 closed with no new addressable
  findings). Round 1 finding (MAJOR, incorporated): the plan's "no
  new DEFINITE becomes reachable" was wrong — the injection owner
  feeds `injectionOwnerMatches` and the type-shape bijection, so
  normalization can RESTORE a blocked binding-row match; §1
  rewritten and M2 strengthened with the binding-row arm. Also
  verified: the constructor/field injection anchors are
  non-divergent for the after-nested-type shape (the constructor
  regex cannot start on `}`; INJECT_FIELD requires a leading `@`).
- **DEVIATION (recorded)**: the harness blocks nested subagents at
  depth 1 (`experimental.subagent_depth`), so both the plan review
  and the implementation review were conducted by the implementing
  agent as fresh adversarial passes grounded in
  engram-zvec-grep/rg and source reads, not by independent
  subagents. The methodology's intent (independent adversarial
  pressure) is only partially met; the PR-stage CodeRabbit full
  review is the independent backstop.
- **Implementation review**: round 1 (self-conducted, fresh pass over
  the full `3e0f3d0..HEAD` diff): the production diff is exactly the
  three sanctioned one-expression swaps plus seam comments and the
  Makefile floor bump; the test diff is pure additions (251
  insertions, 0 deletions — zero edits to existing tests, as
  planned); no #84-adjacent surface touched; the double
  `ownerTypeFor` call per producer (correlation + mirror lookup) is
  covered by the per-offset `typeContaining` memoization. One NIT
  (comment wording: "true (top-level) type" → "true declaring type",
  the owner is the innermost declaring type, not necessarily
  top-level) fixed in REVIEW_90_1. Round 2: diff re-read after the
  NIT fix — no new addressable findings.
- **Implementation review round 2 (REVIEW_90_2, independent)**: a
  fresh subagent (this session; the depth-1 block did not apply)
  reviewed the full `3e0f3d0..HEAD` diff adversarially with
  engram-zvec-grep/rg. Verdict: loop closes, no MAJOR findings.
  Production diff confinement, swap semantics, hidden consumers
  (injectionFactId, injectionOwnerMatches, memberFor — all move in
  the abstention-safe direction), residual non-divergence for
  constructor/field anchors, test purity/non-vacuity, and the
  Makefile floor all verified clean. Three doc-precision findings,
  all addressed in REVIEW_90_2: (1) MINOR — the residual section
  misclassified three reflection-layer uses as site-offset semantics
  when `ReflectionLayer02` :69, `TextLayer18` :63, `TextLayer19` :66
  are raw `method.start()` member anchors in #90's divergence family
  (mostly abstention-safe; `hasVisibleMethodNamed` has one
  over-approximation edge) — Residuals rewritten, follow-up
  candidate; (2) NIT — PLAN-90's floor prediction (805) undercounted
  its own five-cell list — amendment note added; (3) QUESTION — the
  route seam comment's "shares the normalization" could be read as
  "uses the same helper" (the filter resolves through
  `innermostType`, the mirror owner through `typeContaining`) —
  comment tightened.

## Residuals

- `FrameworkInjectionScanning.constructorInjectionPoints` :62 and
  `fieldInjectionPoints` :115 keep raw anchors — verified
  non-divergent for the after-nested-type shape (regex starts sit
  after the nested type's end); a nested-type-INTERNAL member
  resolves the same owner both ways. No known constructible
  divergence; noted for completeness.
- Reflection-layer `typeContaining` uses split into two families
  (implementation-review round 2 correction): the site-offset uses
  (`SourceMetadataScannerReflectionLayer00` :152, `TextLayer03` :49)
  are inside-method-body semantics, a different family, out of #90's
  scope. But `TextLayer02` :62 (the `siteType` argument), and the
  member-declaration anchors `ReflectionLayer02` :69
  (`typeContaining(candidate, method.start())` in
  `hasVisibleMethodNamed`), `TextLayer18` :63, and `TextLayer19` :66
  (`isAccessibleMethod`) ARE raw `method.start()` member anchors —
  the exact divergence family #90 fixes (the METHOD regex can consume
  the preceding nested type's `}`). Divergence direction is mostly
  abstention-safe there (misattribution to the nested type makes the
  method invisible → decline), with one over-approximation edge:
  `hasVisibleMethodNamed` with a nested-type `siteType` could count an
  outer member whose raw anchor lands in the nested span. Follow-up
  candidate, not addressable on this PR without scope creep.
- The injection fact ID/input-name churn for divergent shapes
  (misattributed today) is a breaking observable for downstream
  consumers keyed on those strings — the corrected identity is the
  intended outcome, same class as #77's identity corrections.

Confidence score: **0.90** — three probe-measured one-expression
swaps in #77's exact shape, five cells pinning each seam's
normalized identity (plus the mutation-3 kill), all five mutation
receipts red under exactly their own mutation, and the full suite
(806) green with zero edits to existing tests; the open residuals
are documented same-family anchors with no constructible
divergence.
