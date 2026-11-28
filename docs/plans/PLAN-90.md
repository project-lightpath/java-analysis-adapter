# PLAN-90 — Normalize the remaining raw-extraction owner anchors (producer mirror, injection-point, route)

Issue: project-lightpath/java-analysis-adapter#90 (post-#77 residual
family, RESOLUTION-77 §Residuals; filed by the #85 triage item 4).
Branch: `issue-90-anchor-normalization` from `origin/main` @ `3e0f3d0`
(PR #91 merge).

## 1. Preflight (verified, probe-measured)

The three production sites derive a mirror-lookup owner through the RAW
extraction anchor (`typeContaining(file, method.start())`) instead of
the shared `ownerTypeFor` helper
(`SourceMetadataScannerReflectionLayer00.ownerTypeFor` =
`typeContaining(file, declarationStart(file, owner.start()))`):

- `FrameworkBeanScanning.producerCandidate` :171-173 — the
  `javac.memberLookup(scanner.typeContaining(file, method.start()), …)`
  call whose decline surfaces as `javacMirrorAbstention` /
  `javacMirrorAbstentionMember` on the bean-candidate evidence
  (`BoundedFrameworkModel.candidateEvidence` :540-543).
- `FrameworkInjectionScanning.methodInjectionPoints` :146 — the `owner`
  local, feeding BOTH the member/parameter mirror lookups
  (`InjectionSite`) AND the `InjectionPoint.ownerType` field.
- `FrameworkRouteScanning.routesForMethod` :185-187 — the
  `javac.memberAnnotations(scanner.typeContaining(file, method.start()), …)`
  mirror lookup (its filter at `routesForFile` :113-119 already
  normalizes through `declarationStart`, so the pair can diverge).

Divergence mechanism (why the raw anchor resolves the NESTED type):
`SourceMetadataScanner.methods`' METHOD regex captures the previous
member's closing braces/whitespace before the declaration, so
`method.start()` can land ON the nested type's closing `}`, which is
inside the nested type's `[start, end]` span
(`SourceMetadataScannerTextLayer08.extractTypeDeclarations`,
`computeTypeContaining` filter `start <= index && end >= index`).
`declarationStart` skips exactly those braces/whitespace.

Scratch-probe receipts (temporary test class, run on the clean tree,
never committed; fixtures below are the probe fixtures):

- **Bean mirror seam**: the C4 fixture's binding fact carries
  `javacMirrorAbstention=mirror_member_absent` and
  `javacMirrorAbstentionMember=demo.ProducerConfig$Helper#build(1)` —
  the wrong-owner identity, observed.
- **Injection seam**: a `@Component` setter declared after a nested
  type produces the fact
  `caller=spring:injection:demo.Consumer$Helper#setThing(Thing)` with
  evidence symbol `demo.Consumer$Helper#setThing(Thing)` — the fact
  names the NESTED owner, observed.
- **Route seam**: a Quarkus resource whose method-level `@Path` is a
  folded constant (`@Path("greet" + "ing")`) declared after a nested
  type yields NO route flag-on (the mirror lookup fails under the
  nested owner, the textual extraction cannot fold, and
  `methodPathsAllowed` declines the explicit-empty Quarkus path) —
  observed `routes=[]`; the no-nested-type twin resolves
  `/items/greeting` (the existing
  `quarkusFoldedPathIsResolvedFlagOnOnly` shape).

### Observables that change (honest scope, wider than the issue's
"identity string" phrasing)

- Bean mirror seam: post-fix the lookup runs against the TRUE owner.
  In the C4 shape it SUCCEEDS (single member), so the
  `mirror_member_absent` decline and its `$Helper` identity disappear;
  in a same-arity overload shape it declines `mirror_member_ambiguous`
  with the NORMALIZED identity. Mirror-derived annotation values can
  now stand in where the raw anchor forced a textual fallback — the
  standard flag-on migration behavior every non-divergent member
  already gets.
- Injection seam: the `owner` local feeds `InjectionPoint.ownerType`,
  which reaches the injection fact's ID
  (`BoundedFrameworkModelIdentity.injectionFactId` :71), input name
  (`spring:injection:owner#member(type)`, `injectionFact` :493),
  evidence symbol (:475), the per-owner injection-site uniqueness count
  (`injectionFacts` :104-107, gating `legacyIdAllowed` :208), the
  binding-metadata row match
  (`BoundedFrameworkModelMetadataMatching.injectionOwnerMatches` :41 —
  a row naming the TRUE owner cannot match a misattributed nested
  owner today), the type-shape bijection member lookup
  (`JavacInjectionTypeShapes.memberFor` :108), the legacy-ID gate
  (`legacyInjectionFactId` :95 requires `ownerType` ==
  `file.typeName()`), and the member/parameter mirror lookups.
  Normalizing CORRECTS a misattributed owner (the fact named the
  nested type for a member of the top-level type), and can thereby
  RESTORE a binding-row match the misattribution blocked — a
  correctness repair identical to what every non-divergent member
  already gets, pinned by M2's binding-row arm. The #62/#76 channel
  contract is untouched; no fabricated call target becomes reachable
  (the matched row is external metadata that names the true owner,
  the same evidence class that promotes non-divergent injections).
- Route seam: the member mirror lookup succeeds against the true
  owner, so mirror-resolved route values (e.g. folded-constant paths)
  apply; the raw anchor silently forced the textual fallback.

All three are the safe direction: the raw anchor never fabricated a
fact, it misnamed owners and over-declined lookups.

## 2. GREEN_90 design (one expression per site, #77's shape)

- `FrameworkBeanScanning.producerCandidate`:
  `javac.memberLookup(scanner.ownerTypeFor(file, method), …)`; extend
  the seam's existing #77 comment by one sentence (the mirror lookup
  shares the normalization).
- `FrameworkInjectionScanning.methodInjectionPoints`:
  `String owner = scanner.ownerTypeFor(file, method);` with a comment
  mirroring the seam comment style.
- `FrameworkRouteScanning.routesForMethod`:
  `javac.memberAnnotations(scanner.ownerTypeFor(file, method), …)`.

No other production changes. No refactors, no entrypoint/route-fact
family work (#84 coordination), no `scan.diagnostics()`, no DEFINITE
blocking, no attribute-key/certainty/status/fact-ID scheme changes.

## 3. Cell matrix (RED_90), derived from the constructible divergence shapes

Divergence axes per seam: (a) member declared after a nested type in
the owner file (the raw-vs-normalized split), (b) lookup outcome
(declares-and-declines vs lookup-succeeds vs mirror-value load-bearing),
(c) framework arm (Spring vs Quarkus where the seam is arm-specific),
(d) flag state (the #62/#76 opt-out golden), (e) producer declared
INSIDE the nested type (kills RESOLUTION-77 mutation 3's equivalent
mutant: `ownerTypeFor` vs `file.typeName()`).

- **M1 — bean mirror identity (Spring)**:
  `JavacAbstentionEvidenceTest`, next to C8. Fixture: `@Service` class
  with `class Helper { }` then TWO same-arity `make` overloads (one
  `@Bean`), a `@Component` consumer binding `Thing`. RED: assert
  `javacMirrorAbstention = mirror_member_ambiguous` and
  `javacMirrorAbstentionMember = demo.AmbiguousAfterNested#make(1)`
  (the NORMALIZED owner identity). Today: `mirror_member_absent` with
  the `$Helper` identity — both assertions red. (The ambiguity shape
  is chosen over the C4 succeed-shape because it pins the identity
  string directly and stays red under "revert to raw anchor".)
- **M1b — bean mirror succeeds-shape control (Spring)**: extend M1's
  test (same method) or the C4-shaped twin: single `@Bean build` after
  `class Helper` — post-fix the mirror lookup SUCCEEDS, so assert
  `javacMirrorAbstention` is NULL while `javacAbstention` stays
  `multi_type_file` and `javacProducerTypeAbstentionMember` stays the
  normalized identity (non-vacuity: the flag is on and the sibling
  seams still abstain). Today this is red (mirror_member_absent
  present with the `$Helper` identity).
- **M2 — injection-point owner (Spring)**:
  `JavacAbstentionEvidenceTest`. Fixture: `@Component class Consumer`
  with `class Helper { }` then `@Autowired void setThing(Thing thing)`,
  a `Thing` producer candidate, and ONE resolved-binding row naming
  the TRUE owner (`demo.Consumer`, member `setThing`, selected+active,
  shape mirroring `JavaAnalysisAdapterTest
  .resolvedBindingMatchesNestedInjectionOwner` :2291-2361 — that cell
  pins the nested-owner row match for a FIELD inside the nested type,
  where raw and normalized anchors agree; M2 pins the complementary
  top-level-member shape). RED: the injection fact's evidence symbols
  contain `demo.Consumer#setThing(Thing)`, the fact input/caller is
  `spring:injection:demo.Consumer#setThing(Thing)`, and the binding
  row SELECTS (callee resolved to the producer candidate, certainty
  raised from today's UNRESOLVED — the misattributed
  `demo.Consumer$Helper` owner cannot match the row today). Today all
  three assertions are red.
- **M3 — route mirror owner (Quarkus)**:
  `JavacFrameworkAnnotationMigrationTest`, next to
  `quarkusFoldedPathIsResolvedFlagOnOnly`. Fixture: `@Path("/items")`
  resource with `class Helper { }` then
  `@POST @Path("greet" + "ing") String hello()`. Flag-on: a route with
  path `/items/greeting` and method POST exists (today: none — red).
  Flag-off twin in the same method: no route (the folded constant is
  mirror-only; the opt-out golden keeps the cell non-vacuous).
- **M4 — nested-type producer (mutation-3 kill, Spring)**:
  `JavacAbstentionEvidenceTest`, next to N1. Fixture: `@Service class
  Config` with a nested `class Helper` DECLARING the `@Bean` producer.
  GREEN characterization today (the producer-type seam already
  normalizes): assert `javacProducerTypeAbstentionMember =
  demo.Config$Helper#make(1)` — the nested owner is the TRUE owner.
  Red under `ownerTypeFor → file.typeName()` (would name
  `demo.Config#make(1)`): kills RESOLUTION-77 mutation 3.

Sanctioned existing-test edits: NONE. No current cell pins any
`javacMirrorAbstentionMember` value, the injection owner of a
divergent shape, or the folded route path of a divergent shape (rg
over src/test; the full-suite gate is the backstop).

Pairwise interaction coverage: M1b composes the bean mirror seam with
the #77-normalized producer-type seam on ONE fact (both identities on
the same evidence); M2 composes the injection owner with the member
mirror lookups (qualifier repair path stays exercised by the existing
suite); M3 composes the route mirror with the normalized filter
(filter passes the method, mirror must agree). Lambda/switch/catch
compositions are out of scope: the anchors are declaration offsets of
type members, not name resolution or scoping.

## 4. Commits and gates

- **PLAN_90** (this file).
- **RED_90**: the five cells (M1, M1b, M2, M3, M4 — M4 expected-green
  and labeled so, per the #77 precedent for control cells). Gate run:
  the new cells fail for the observed reason; no other test changes.
- **GREEN_90**: the three one-expression swaps + seam comments.
  Gates: `make adapter-test` (floor 801 → 801 + 4 = 805 expected; the
  Makefile :24 literal is raised to the observed count with
  EVIDENCE_90), `make java-lint` (zero findings), `make
  evidence-validate`. [Review-round amendment: the prediction
  undercounted the cell list — five cells (M1, M1b, M2, M3, M4) ran,
  observed 806 = 801 + 5, recorded in RESOLUTION-90 §Acceptance.]
- **EVIDENCE_90**: gates + mutation receipts + 
  `docs/resolutions/RESOLUTION-90.md`.
- **REVIEW_90_n**: implementation-review rounds, appended.

## 5. Mutation receipts (executed at EVIDENCE time)

1. Revert each site to the raw anchor (one at a time) ⇒ its own cell
   red (M1/M1b bean; M2 injection; M3 route).
2. `ownerTypeFor(file, method)` → `file.typeName()` at the bean
   producer-type seam ⇒ M4 red (the mutation-3 equivalent mutant is
   now killed).
3. Corrupt `memberIdentity` rendering ⇒ M1 red (assertions not
   vacuous).
4. M3's flag-off arm ⇒ no route (the mirror is the only folding
   source — the opt-out golden).

## 6. Risks

- **Injection fact ID churn**: normalizing the owner changes the fact
  ID / input name for constructible divergent shapes (misattributed
  today). No existing cell pins such a shape (rg); the full-suite gate
  is the detector. Downstream consumers keyed on the ID see the
  CORRECTED identity — the same class of change #77 shipped for
  evidence identities.
- **Mirror stand-in where the raw anchor declined** (bean values,
  route values): this is the flag-on migration's designed behavior for
  every non-divergent member; the render-parity work (#85) keeps
  mirror values byte-equal to textual spellings for standard
  spellings. Full-suite gate is the detector.
- **#84 coordination**: changes are confined to the three owner
  expressions + comments; no route-fact family or entrypoint-kind
  surface is touched.

Non-goals: field/constructor injection anchors
(`FrameworkInjectionScanning.fieldInjectionPoints` :115,
`constructorInjectionPoints` :62 — the constructor regex's match start
does not capture the preceding `}`, so those anchors sit AFTER the
nested type's end and resolve the true owner; documented as residuals
only if the review confirms), reflection-layer raw `typeContaining`
uses that are site-offset semantics (not member-declaration anchors),
any refactor of the seams.

## 7. Plan review record

Round 1 (adversarial self-review — the harness blocks nested
subagents at depth 1, so the review rounds were conducted by the
implementing agent with fresh verification passes through
engram-zvec-grep and engram_docs_recall; deviation recorded here and
in RESOLUTION-90):

- **MAJOR (incorporated)**: "no new DEFINITE becomes reachable" was
  wrong — `injection.ownerType()` feeds `injectionOwnerMatches`
  (MetadataMatching:41) and `JavacInjectionTypeShapes.memberFor:108`;
  normalization can RESTORE a binding-row match the misattribution
  blocked. §1 rewritten honestly; M2 strengthened with the
  binding-row arm (RED today: the row cannot match `$Helper`).
- **Verified**: no test pins `javacMirrorAbstentionMember` (rg over
  src/test: no matches); `resolvedBindingMatchesNestedInjectionOwner`
  pins a nested-owner row for a field INSIDE the nested type (anchors
  agree there — unaffected); constructor/field injection anchors are
  non-divergent for the after-nested-type shape (the constructor
  regex's match cannot start on `}`; INJECT_FIELD requires a leading
  `@`, so its start sits after the nested type's end).
- **Verified**: METHOD's regex starts `(?:^|[;{}\n])` — it consumes
  the previous member's closing `}`, confirming the divergence
  mechanism claimed in §1.

Confidence score: **0.88** — every claimed fact is line-cited and the
three divergences are probe-observed on the clean tree; the fix is
three one-line expression swaps in #77's exact shape; the residual
risk is hidden consumers of the injection fact's owner spelling,
which the full-suite gate and the adversarial review rounds are
positioned to catch.
