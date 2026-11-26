# PLAN-62 — Blast-radius-safe visibility for javac index abstentions (per-member evidence channel)

Issue: project-lightpath/java-analysis-adapter#62 (part of #20; follow-up
from #23 PR #59 CodeRabbit thread r4681365963, CR-3 finding 3).
Branch: `issue-62-per-member-evidence` from `origin/main` @ `5c050ec`
(the PR #67 merge — the flag is default ON with explicit opt-out).

## Preflight (verified on this main, 2026-11-25, JDK 27)

- **The hazard is confirmed live.** `ServiceLoaderDescriptorScanner.isUnique`
  (line 120) requires `scan.diagnostics().isEmpty()` for DEFINITE promotion.
  Routing javac's per-file index errors there would downgrade certainty for
  every archive and entry from unrelated abstentions — the #23 certainty
  model's deliberate choice, and the reason this channel is per-member.
- **The #25 precedent is the design template.** `JavacTargetEvidence`
  (per-fact `diagnostic` evidence attribute + limitation, consumed by
  `ProviderFactNormalizer.dynamicFact`) is the per-member channel that never
  enters `scan.diagnostics()`. RESOLUTION-25's Residuals name this issue as
  the generalization to the other javac consumers.
- **Empirical abstention probe (the plan's load-bearing measurement).** A
  scratch checkout (outside the tree, `git archive` copy) instrumented every
  decline point of `JavacMemberCorrelation.correlated` and
  `JavacAnnotationAccess.member` with a reason code; the full suite
  (`mvn test`, 709 executions, green, exit 0) produced, across all three
  surefire lanes (append-mode log):
  - `multi_type_file` × 99 — files declaring more than one indexed type
    (nested/sibling types) make the owner unprovable for name+arity
    correlation; every consumer falls back silently today.
  - `bijection_failed` × 26 — multi-overload groups whose textual vectors
    cannot biject onto the index vectors; the recurring shape is the
    unresolved-bounds class (`demo.GenericRepository#findById`: type
    parameters erase to bounds in the index but resolve through imports
    textually).
  - `error_type_member` × 6 — unresolved shapes (missing classpath types in
    signatures, e.g. `demo.Api#run`, `demo.Producer#make`, `demo.Bound#run`).
    These are exactly the members javac records ERROR diagnostics for; the
    index stores them (`IndexRenderer.addCompilerDiagnostics`, codes like
    `compiler.err.cannot.find.symbol`) and no production code reads them.
  - `index_absent` × 5, `comment_corrupted` × 2 (parameter text containing
    comment markers), seam-B `annotation_member_ambiguous_or_absent` × 5 and
    `annotation_member_error_type` × 1.
  All abstention classes fire on main's corpus; none is visible in scan
  output today.
- **Post-#60/#66 scoping confirmed.** With the index compiling the full
  selected source set and the flag default-on, the surviving abstentions are
  the ones above: genuinely-missing classpath types (`error_type_member`),
  unresolved bounds (`bijection_failed`), and the structural guards
  (multi-type files, comment corruption, ambiguity). No per-file-compilation
  error-type flood remains to design around.
- **The boolean-consumer problem has a pure-function answer.** The two
  predicate-shaped consumers named in the issue context — repository
  signatures (`correlatedFirstParameterIs`) and reflection classification
  (`parameterMatchesType`) — do not carry member shapes into facts.
  `parameterMatchesType` sits four boolean layers below site extraction
  (`parameterMatchesType` → `isDeclaredVariableBefore` → `isJdkApiOwner` →
  site extractors → `DynamicFeature`); threading a result type through would
  rewrite ~8 call sites that never care. But `correlated(file, ownerType,
  method)` is a pure function of (file, member, index), and the reflection
  and proxy feature constructors already hold the caller `MethodInfo`
  (`reflectionFeatures`, SourceMetadataScanner.java:149), so the SAME
  correlation query can be re-issued at feature-build time — PROVIDED the
  ownerType input is identical by construction: classification derives it as
  `typeContaining(file, declarationStart(file, owner.start()))`
  (ReflectionLayer00.java:163-164, whose comment documents how sensitive
  this is to extraction anchoring), while the feature path's
  `typeContaining(file, site.offset())` (SourceMetadataScanner.java:152) is
  a DIFFERENT expression. The plan therefore extracts one shared
  `ownerTypeFor(file, owner)` helper (the `declarationStart` normalization)
  used by BOTH `parameterMatchesType` and the re-query; the feature path's
  `MethodSymbol` owner construction at line 152 is NOT touched (its output
  is fact-visible today). A RED cell pins the anchoring divergence case
  (a caller extracted after a preceding nested type's closing brace).
  No side channel, no cache-ordering hazard (the scanner extraction cache
  caches extracts, not `reflectionSites`/`dynamicFeatures`, so the re-query
  reads the same cached inputs), no dual implementation (one `correlated`).
- **Schema-exception precedents.** `MemberInfo.publicMember` (#25) and
  `TypeShape.staticType` (CR-1 of #25) are the additive-component
  discipline; this plan's additive fields follow it.
- **Gates on this main**: `make adapter-test` floor 709 (observed 709);
  `make java-lint`; `make evidence-validate`. JDK 17 divergence (#64):
  `JavacMemberCorrelation` and `BoundedFrameworkModelCandidateMetadata` are
  near PMD limits — the abstention RENDERING must live in the new
  `JavacAbstentionEvidence` record (self-rendering, the #25
  PMD-forced-placement precedent), helper method names must stay distinct
  (JDK 17 PMD mis-resolves private overloads as unused), and lint is
  verified on helios08 with `~/jdk17` and a scratch checkout before pushing.

## Design

### The channel record (new file `JavacAbstentionEvidence.java`)

```java
record JavacAbstentionEvidence(
    String code,               // enumerated decline reason, never blank
    String member,             // "owner#name(arity)" identity, never blank
    List<String> indexDiagnostics) { // deduped javac/index error codes for
                                     // the member's file, empty when none
  static final JavacAbstentionEvidence NONE;   // sentinel, code ""
  boolean present();
  Map<String, String> attributes();            // self-rendering
}
```

- `attributes()` contributes `javacAbstention` (the code) and
  `javacAbstentionMember` (the member identity) when present, plus
  `javacAbstentionDiagnostics` (comma-joined codes) when the index
  diagnostics are non-empty. Absent ⇒ contributes nothing (callers merge
  into their existing attribute maps).
- The class javadoc states the blast-radius contract: this evidence NEVER
  enters `scan.diagnostics()`; `ServiceLoaderDescriptorScanner.isUnique`
  must be unaffected (pinned by D1).
- Certainty is UNCHANGED by this channel everywhere. It is visibility-only:
  every abstaining consumer keeps its current fallback answer, certainty,
  and limitation. (Unlike #25, whose evidence changed certainty — here the
  facts already exist; the channel annotates them. The #23 acceptance leg
  "unresolved types produce POSSIBLE/unresolved plus a diagnostic" is
  satisfied by the attribute, the diagnostic leg this issue owns.)

### Decline codes (enumerated, one set per seam)

- Seam A — `JavacMemberCorrelation.correlated`:
  `comment_corrupted_parameters`, `multi_type_file`, `index_absent`,
  `member_absent`, `error_type_member`, `type_parameter_overload_group`,
  `overload_group_mismatch` (the bijection split: a textual vector that hit
  a type parameter / unparseable type-parameter clause / unestablishable
  enclosing scope is `type_parameter_overload_group` — the unresolved-bounds
  class; sibling-count or set/collision mismatches are
  `overload_group_mismatch`).
- Seam B — `JavacAnnotationAccess.member`: `annotation_member_absent`,
  `annotation_member_ambiguous`, `annotation_member_error_type`.
- Seam C — `JavacFrameworkAnnotations`: `mirror_owner_unusable`
  ($digit / LOCAL / ANONYMOUS / error / duplicate shape),
  `mirror_member_absent`, `mirror_member_ambiguous`,
  `mirror_member_error_type`.

### The correlation result type

`JavacMemberCorrelation.correlatedMethod` / `correlatedConstructor` return a
new `JavacCorrelation` record (exactly one of member/abstention non-null,
enforced in the compact constructor; static factories `correlated(member)`
and `abstained(evidence)`). Every existing caller updates mechanically.
**The opt-out gate lives INSIDE the seam (plan-review finding 1, HIGH):**
`correlated` returns the empty correlation (`abstention` NONE) when
`!JavacSourceSymbolIndex.enabled()` — the flag-off invariant must not depend
on call-site discipline, because `JavacAnnotationAccess.methodDescriptor`
and the reflection re-query are reachable ungated. Seam B's outcome factory
gates identically. The abstention records the member identity
(`owner#name(arity)`), the code, and — for `error_type_member` and
`member_absent` — the index error codes for the member's owning type's
file. The join is by `Path`, not by rendered string (plan-review finding
4): the index builds an additive `Map<Path, List<String>>
errorCodesByFile` at record time, keyed by the normalized absolute
`sourcePath` of each compiler ERROR diagnostic (the existing normalization
at `IndexRenderer.sourcePath`, lines 457-463); `errorCodesFor(Path file)`
joins by `Path` equality against the member's owning `TypeInfo.file`
(normalized the same way). Rendering stays display-only. Boundary probe
per AGENTS before commit: null/empty path, single-char name, prefix/suffix
overlap, dotless unnamed-package spellings, and `Path` normalization
equality on both sides.

### Consumer wiring (per fact family)

1. **Proxy target descriptors (#23 bounded model).**
   `BoundedFrameworkModelCandidateMetadata.methodDescriptor(scanner,
   target)` and `producerDescriptor` return a new
   `JavacDescriptorOutcome(Optional<String> descriptor,
   JavacAbstentionEvidence abstention)`. The matching callers
   (`BoundedFrameworkModelMetadataMatching`, `producerSignatureMatches`)
   consume `.descriptor()` only; the fact-emitting callers attach:
   `BoundedFrameworkModel.proxyFact` → `proxyAttributes` merges the
   abstention attributes; `candidateEvidence` merges them into the
   `bean_candidate` evidence map.
2. **Producer types.** `FrameworkBeanScanning.producerCandidate` consumes
   the `JavacCorrelation`; `BeanCandidate` gains an additive
   `producerTypeAbstention` field (NONE default; 2 construction sites).
   `candidateEvidence` renders it.
3. **Interface proxy descriptors (#22 seam).**
   `JavacAnnotationAccess.methodDescriptor` returns `JavacDescriptorOutcome`
   carrying EITHER the correlation abstention (seam A) or, on the
   per-parameter repair path, the `repairedParameterType` decline (seam B:
   the `member()` lookup's own codes; both arms gate on `enabled()` inside
   the seam). `DynamicFeature` gains an additive
   `JavacAbstentionEvidence abstention` field (NONE default, compatibility
   constructors preserved) — NOT included in `dynamicFeatureIdentity` /
   `dynamicFeatureId` (fact IDs stay byte-stable).
   `BoundedFrameworkModelUtilities.dynamicProxyEvidenceAttributes` renders
   it for the bounded-model dynamic facts, AND
   `ProviderFactNormalizer.dynamicFact` (the `sootup-reflection-*` /
   `sootup-dynamic_proxy-*` renderer) merges the feature's abstention
   attributes into the evidence map alongside the #25 `targetEvidence`
   attributes (plan-review finding 3).
4. **Repository signatures.** `redeclaresGuaranteedContractMethod` consumes
   the `JavacCorrelation` from the first-parameter check;
   `RepositoryMethod` gains an additive `abstention` field (NONE default; 3
   production construction sites — the guaranteed row
   `SourceMetadataScannerRepositoryLayer.java:37`, the declared row `:44`,
   and the fragment row `SourceMetadataScannerFragmentLayer.java:42`; the
   first and third stay NONE).
   `BoundedFrameworkModelSpringFacts.delegationFact` merges it into the
   evidence attribute map (which already carries `repositoryMethod`).
5. **Reflection classification (`parameterMatchesType`).** No signature
   change. `reflectionFeatures` / `proxyFeaturesAt` re-issue the same pure
   `correlated` query for the site's caller `MethodInfo`, deriving the
   ownerType through the SAME shared `ownerTypeFor(file, owner)` helper
   `parameterMatchesType` uses (the `declarationStart` normalization — see
   Preflight), and attach the abstention to the `DynamicFeature` (the
   additive field from item 3) when the caller's member abstains — the
   classification's textual-ladder fallback becomes visible per site.
6. **#24 mirror seam.** `JavacFrameworkAnnotations.member` and
   `typeAnnotations` return lookups carrying their decline evidence;
   `FrameworkBeanScanning`'s `ProducerSite` holds it and the
   `bean_candidate` evidence renders it when the producer's class-level or
   method-level mirror declined. The `memberCount` textual-occurrence
   cross-check is NOT an abstention (it is a guard, not a lookup decline)
   and stays unwired.

### What deliberately does not change

- Certainty, status, limitation, and fact IDs of every existing fact.
- `scan.diagnostics()` (zero new rows — pinned by D1).
- `ServiceLoaderDescriptorScanner`, `DynamicCertaintyPolicy`, the #26
  harness, the #25 reflection-target channel (`JavacTargetEvidence`
  semantics and its `diagnostic` attribute are untouched; the new
  `javacAbstention*` attributes are additive and distinct).
- The opt-out path: with the flag `"false"`, no `JavacAbstentionEvidence`
  is ever constructed (every wired lookup is behind `enabled()`), so the 57
  opt-out goldens stay byte-identical (pinned by D2).

## Test matrix (new class `JavacAbstentionEvidenceTest`, @Isolated, flag-writing)

RED cells (all fail against the pre-change tree):

- C1 proxy-target-descriptor abstention: an advisor/proxy target whose
  owner file declares a nested type (`multi_type_file`) → the proxy
  framework fact's evidence carries `javacAbstention=multi_type_file` and
  `javacAbstentionMember`.
- C2 `error_type_member`: a signature referencing a classpath-absent type →
  code plus `javacAbstentionDiagnostics` containing the javac error code
  (e.g. `compiler.err.cannot.find.symbol`) for that member's file.
- C3 `type_parameter_overload_group`: mirrored overloads whose vectors hit
  a type-parameter bound (the `demo.GenericRepository#findById` probe
  shape) → the affected fact carries the code.
- C4 producer-descriptor abstention → `bean_candidate` evidence attribute.
- C5 producer-type abstention (nested-type owner) → `bean_candidate`
  evidence attribute.
- C6 interface-proxy descriptor abstention → dynamic-proxy fact evidence
  attribute (seam A via correlation).
- C7 repair decline (seam B: same-arity overload ambiguity) → the
  descriptor outcome carries `annotation_member_ambiguous`.
- C8 mirror decline (seam C: `$digit`-spelled owner or error-typed member)
  → `bean_candidate` evidence attribute.
- C9 repository-signature abstention → the delegation fact for that
  declared method carries the attribute.
- C10 reflection-feature caller abstention: a reflection site inside a
  member whose correlation abstains → the reflection fact carries
  `javacAbstention` for the CALLER member (distinct from the #25
  target-evidence attributes on the same fact). Includes the anchoring
  divergence fixture (a caller extracted after a preceding nested type's
  closing brace — the shared-helper pin).
- D1 blast-radius pin: a scan containing an abstaining fixture AND a
  ServiceLoader DEFINITE site → the ServiceLoader fact stays DEFINITE,
  `scan.diagnostics()` stays empty and free of every index code (the
  isUnique hazard pin).
- D2 opt-out twin — pinned on the UNGATED paths specifically (the
  annotation-access descriptor, the repair decline, and the reflection
  re-query, plan-review finding 1): `setProperty(FLAG, "false")` on the
  C1/C4/C6/C7/C9/C10 fixtures → no `javacAbstention*` attributes anywhere
  (byte-identical to main).
- D3 successful-correlation control: a correlating member → NO attribute.
- D4 cache-ordering pin, cold-then-warm and warm-then-cold ACROSS the flag
  boundary (the `JavacAnnotationMigrationTest:290-311` warm-cache pattern,
  plan-review finding 6): the abstention attributes appear and disappear
  only with the flag, never with cache order.

Sanctioned existing-test edits — by criterion, not a class list
(plan-review finding 5): every test class whose fixtures appear in the
probe abstention inventory (`demo.DynamicSites`, `demo.GenericRepository`,
`demo.Api`, `demo.Producer`, `demo.Bound`, the nested-configuration
producers, the comment-corrupted fixtures) may need golden updates where
the new attributes or the additive `DynamicFeature`/`RepositoryMethod`/
`BeanCandidate` components surface in assertions; the full list is
enumerated at RED with values captured from observed output. Known
candidates: `JavaAnalysisAdapterTest`, `SpringDataDelegationLadderTest`,
`JavacAnnotationMigrationTest`, `JavacTypeShapeMigrationTest`,
`JavacSymbolIndexDefaultOnTest`, `JavacReflectionTargetMigrationTest`.
The Makefile floor is raised from 709 to the observed execution count.

## TDD / gates / commits

1. RED_62: `JavacAbstentionEvidenceTest` cells C1–C10, D1–D4 failing (plus
   behavior-neutral compile stubs if unavoidable, documented in the
   message); run the suite, record expected-red units.
2. GREEN_62: minimal implementation (channel record, result types, wiring);
   suite green; sanctioned golden updates captured from observed output.
3. REVIEW_62 rounds: fresh Engram-grounded adversarial subagent reviews
   (independent change review), findings addressed red-first; loop until no
   new addressable findings.
4. EVIDENCE_62: `docs/resolutions/RESOLUTION-62.md` with gate receipts,
   mutation checks (remove attribute rendering ⇒ C1–C10 fail; route one
   abstention into `scan.diagnostics()` ⇒ D1 fails; include the abstention
   field in `dynamicFeatureIdentity` ⇒ fact-ID golden fails), probe
   receipts, residuals.
5. Gates after every item: `make adapter-test` (count ≥ floor, raised),
   `make java-lint` (zero new findings; helios08 JDK 17 verification per
   #64 when PMD-risky classes changed), `make evidence-validate`.
6. PR base `main`, head `issue-62-per-member-evidence`; `@coderabbitai
   full review` after pushing; every finding answered red-first with
   mutation checks where applicable; no manual thread resolution; no merge
   (user decides). Commit messages state the blast radius per AGENTS.

## Residuals (to state in the resolution)

- The index-diagnostics join is file-level (`MemberInfo` carries no
  position); a member-level line join would need an additive position
  component — follow-up candidate, not required by acceptance.
- `memberCount`-style guards and `JavacReflectionTargets`' own abstention
  ladder (#25 channel) are out of scope by design.
- PMD-forced placement changes (if any) keep behavior identical and are
  documented in the commit message per AGENTS.

## Plan review record

Round 1 (Engram-grounded adversarial subagent): 7 addressable findings
(1 HIGH, 4 MEDIUM, 1 LOW, 1 NIT), all incorporated above: the opt-out gate
moved inside the seams (finding 1, HIGH — `JavacAnnotationAccess` and the
reflection re-query are reachable ungated); the shared `ownerTypeFor`
helper with the anchoring-divergence RED cell (finding 2);
`ProviderFactNormalizer.dynamicFact` added as the C10 wiring point
(finding 3); the `errorCodesByFile` `Path`-keyed join replacing the
rendered-string join (finding 4); the golden-churn sanction widened to the
criterion (finding 5); D4 reshaped cold/warm across the flag boundary
(finding 6); the construction-site inventory corrected to the three
production rows including the fragment row (finding 7). Verified clean:
identity functions cannot leak the additive field; attribute consumers are
key-based; the extraction cache caches extracts, not sites/features.
Confidence score after round 1: **0.84**.

Confidence score (pre-plan-review): **0.78** — the design is grounded in a
green-suite probe of what actually abstains on main, the #25 precedent, and
verified wiring points for every named consumer; the open risks are the
default-on golden-churn size (bounded by the probe's owner inventory), the
PMD JDK-17 measurement on the touched classes, and the path-render join,
each with a named mitigation above.
