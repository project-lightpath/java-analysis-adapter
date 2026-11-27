# PLAN-77 — Normalize the producer-type seam's raw-extraction owner anchor

Issue: project-lightpath/java-analysis-adapter#77 (follow-up from #62 /
PR #72; `docs/resolutions/RESOLUTION-62.md` Residuals).
Branch: `issue-77-producer-anchor-normalization` from `origin/main` @
`fb8a664` (#80 merge — #76 and #78 both in).

## Scope (from the issue and the #62 residuals)

The producer-type seam — `FrameworkBeanScanning.producerCandidate` —
derives the correlation owner through the historical raw-extraction
anchor `typeContaining(file, method.start())`. For a member declared
after a nested type, `method.start()` anchors on the closing brace of
the preceding nested type and `typeContaining` returns the NESTED type,
so:

- the `javacProducerTypeAbstentionMember` identity names e.g.
  `demo.ProducerConfig$Helper#build(1)` for a member declared in
  `demo.ProducerConfig` (pinned as characterization in C4,
  `JavacAbstentionEvidenceTest.producerAbstentionIsVisibleOnTheCandidateEvidence`);
- in anchor-divergence cases where the correlation SUCCEEDS under the
  normalized owner, the producer bean TYPE itself changes (the
  correlated member's canonical return type replaces the textual
  spelling) — this is the deliberate behavior change of this issue.

Normalization: replace the raw anchor with the shared `ownerTypeFor`
helper (`SourceMetadataScannerReflectionLayer00`:
`typeContaining(file, declarationStart(file, owner.start()))`), the
same normalization the descriptor seam
(`JavacMemberCorrelation.producerCorrelation`), the classification/re-
query pair, and `BoundedFrameworkModelCandidateMetadata.methodDescriptor`
already apply.

### In scope

1. The producer-TYPE correlation owner (FrameworkBeanScanning.java:159,
   the `JavacMemberCorrelation.of(scanner).correlatedMethod(...)` call)
   → `scanner.ownerTypeFor(file, method)`.
2. The C4 characterization update the issue sanctions:
   `javacProducerTypeAbstentionMember` changes from
   `demo.ProducerConfig$Helper#build(1)` to `demo.ProducerConfig#build(1)`
   (and any producer-TYPE VALUE churn in anchor-divergence fixtures,
   captured from observed output).
3. Golden updates captured from observed output wherever the
   normalized anchor changes a producer-type answer.

### Out of scope (documented residuals, unchanged behavior)

- The method-MIRROR lookup owner in the same method
  (FrameworkBeanScanning.java:170, `javac.memberLookup(
  scanner.typeContaining(file, method.start()), ...)`) — a different
  seam (seam C member lookup) feeding `javacMirrorAbstention` and the
  annotation-mirror value families (same over-abstention defect
  class, safe direction; candidate follow-up with the two below).
- `FrameworkInjectionScanning.methodInjectionPoints:159` — the
  injection-point seam's owner (raw anchor, same defect class,
  different seam; candidate follow-up).
- `FrameworkRouteScanning.routesForMethod:186` — the route mirror
  lookup's raw anchor CAN diverge from the `declarationStart` filter
  at `routesForFile:112-118`: that filter constrains only the
  NORMALIZED owner (a top-level route method declared after
  `class Helper { }` passes it), so the raw anchor at :186 still
  resolves `Ctrl$Helper` (plan-review round 1, finding 2 — the
  original "anchor-safe by construction" claim was wrong). Same
  over-abstention defect class; candidate follow-up.
- Everything else the #62 channel pinned: never `scan.diagnostics()`,
  never blocking DEFINITE promotion, flag-off emits no evidence,
  `withSilencedEvidence` failed-build handling, unique-owner guards.

## Preflight probe (the load-bearing measurement)

Blast-radius inventory of the producer-type answer change under
normalization, measured by running the full suite with the scratch
normalization patch (correlation owner only) on this main:

- Baseline: `make adapter-test` green at **754 executions**
  (RESOLUTION-78 floor).
- Patched run: every failure is an anchor-divergence manifestation;
  each is classified as (a) sanctioned C4 identity churn, (b)
  producer-TYPE VALUE churn (candidateType / binding outcome), or
  (c) an unexpected regression to be designed against.

Structural analysis (probe hypothesis, verified by the run): anchor
divergence requires a type declared BEFORE the method in the SAME
file, which makes `singleTypeFile` false
(`JavacSourceSymbolIndex.typesIn(file.path()).size() == 1` is
FILE-level, `JavacMemberCorrelation:109-113`) — so in every
anchor-divergence case the correlation is already blocked
(`multi_type_file`) and the producer-TYPE VALUE keeps its textual
spelling. The observable change is the abstention member IDENTITY
(and, if the unique-owner diagnostics join resolves differently, the
diagnostics VALUE). A cell where the correlation SUCCEEDS under
divergence is unconstructible by this argument; the probe's failure
inventory is the check that no unconsidered divergence shape (a
type-like construct `typeContaining` credits but the index does not
count) contradicts it.

The closure is structural, not just fixture coverage (plan-review
round 1, finding 4): `typeDeclarations`/`typeContaining` run on
comment-stripped, literal-masked source (`structuralSource` from
`stripComments`, `TextLayer07.read:59` / `FileExtracts:86-87`), so
commented-out or string-literal "types" are never credited; and the
index's `visitClass` (`JavacSourceSymbolIndex:637-648`) records EVERY
`TypeElement` with no nesting filter — nested, local, anonymous,
enum, record, interface all count toward `typesIn`. Divergence ⇒
`typesIn ≥ 2` in every constructible shape.

[PROBE RESULTS — executed 2026-11-27, this main @ fb8a664]

- Baseline: green, 754 executions (default=640, large=1, sootup=113).
- Patched run (correlation owner → `ownerTypeFor` only): exactly
  ONE failure — the sanctioned C4 cell
  (`JavacAbstentionEvidenceTest.producerAbstentionIsVisibleOnTheCandidateEvidence:280`).
  Observed: `javacProducerTypeAbstentionMember` =
  `demo.ProducerConfig#build(1)` (was `$Helper#build(1)`);
  `javacProducerTypeAbstention` stays `multi_type_file`;
  `candidateType` stays `demo.Thing` (textual); `targetDescriptor`,
  the binding facts, and every other golden are unchanged.
- The structural hypothesis HOLDS: divergence ⇒ multi-type file ⇒
  blocked correlation ⇒ no producer-TYPE VALUE churn anywhere in the
  suite. Zero unconsidered divergence shapes surfaced.
- The same evidence object shows `javacMirrorAbstentionMember` =
  `demo.ProducerConfig$Helper#build(1)` (the method-mirror lookup's
  raw anchor, line 170 — the out-of-scope site, unchanged by the
  probe and by this issue).

## Design

1. `FrameworkBeanScanning.producerCandidate`: replace
   `scanner.typeContaining(file, method.start())` at the correlation
   call with `scanner.ownerTypeFor(file, method)` (package-private,
   same package — accessible). Update the inline comment: the owner now
   derives through the declarationStart normalization like every other
   correlation consumer.
2. Comment updates (plan-review round 1, finding 1): the
   `JavacAbstentionEvidence.producerTypeAttributes` javadoc
   (`JavacAbstentionEvidence.java:95-102`) justifies the separate key
   family by the two-seams-two-derivations argument — now false. New
   rationale: the families describe DIFFERENT lookups (the descriptor
   member vs the producer bean type) that can both decline for one
   candidate.
3. No signature, wiring, attribute-key, or channel changes: the
   producer-type abstention still rides the bean_candidate evidence
   under `javacProducerTypeAbstention*`, never `scan.diagnostics()`.
4. Golden/characterization updates captured from observed output only.

## Test matrix

- C4 (EXISTING test, sanctioned edit): the
  `javacProducerTypeAbstentionMember` assertion changes to
  `demo.ProducerConfig#build(1)`; the comment explaining the two
  derivations is updated (both seams now normalize; the two key
  families remain distinct because they describe different lookups).
- New cells (RED_77) pinning the normalized behavior:
  - N1 (anchor-divergence identity): the C4 fixture shape, asserted
    on the bean_candidate evidence of a DIFFERENT framework arm
    (Quarkus `@Produces`) so the identity change is pinned on both
    frameworks — `javacProducerTypeAbstentionMember` names the
    top-level owner, the code stays `multi_type_file`, and the
    producer type keeps its textual spelling (the value-change arm
    is unconstructible: divergence ⇒ multi-type file ⇒ blocked
    correlation — see the preflight analysis). Scaffolding
    (plan-review round 1, finding 3): `JavacAbstentionEvidenceTest`'s
    `context()` hardcodes SPRING_BOOT, so N1 needs a Quarkus context
    helper (precedent `DynamicCertaintyGuardTest:264-280`), and the
    fixture MUST import the CDI `Produces` (e.g.
    `jakarta.enterprise.inject.Produces`) — `cdiProducerAnnotation`
    requires either an FQN match or a simple `@Produces` with a CDI
    import in the file, or the cell silently produces no
    bean_candidate.
  - N2 (anchor-stability control): a producer in a file with NO
    nested types — byte-identical output before/after (golden pin
    of the producer type and the abstention-free evidence).
- Flag-off twin for N1: `javac*` evidence absent, textual type.

## TDD items

1. RED_77: N1 (fails on the pre-change tree: the raw anchor resolves
   the nested owner, so the identity names
   `demo.<Owner>$<Nested>#<method>`). C4 is an expected-red
   characterization edit sanctioned here (it fails under the change
   until updated to observed output).
2. GREEN_77: the one-line normalization + comment + the C4/golden
   updates captured from observed output.
3. EVIDENCE_77: gates + mutation checks + RESOLUTION-77.md.

## Gates (after every item)

- `make adapter-test` — floor **754** on this main; the new cells
  raise it (N1, its flag-off twin, N2; expected 754 + 3 = 757,
  re-derived from the observed run).
- `make java-lint` — zero new findings; JDK 27 local + helios08
  Temurin 17 scratch-checkout verification when the GodClass
  measurement is in doubt (#64 discipline).
- `make evidence-validate`.

## Mutation checks (planned)

1. Revert the normalization (raw anchor) ⇒ N1 fails red and C4 fails
   on the identity — the mutation that RED_77 already proves.
2. Break the identity rendering (nested-owner spelling) ⇒ N1's
   identity assertion fails in the OTHER direction (guards the
   assertion against vacuous pass).
