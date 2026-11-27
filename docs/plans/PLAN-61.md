# PLAN-61 — Injection-contract type matching via javac index shapes

Issue: project-lightpath/java-analysis-adapter#61 (part of #20; the last
major TEXTUAL consumer of the javac symbol-index seam).
Branch: `issue-61-injection-javac-shapes` from `origin/main` @ `fb8a664`.
Floor at branch time: **754** executions (Makefile literal, post-#80).

## 1. Binding requirements

- Route the injection-contract type matching through
  `flpath.java.symbolindex` (default ON): correlate the declaring members
  and compare canonical index shapes, with the fail-closed ladder
  (textual fallback, no fabricated DEFINITE).
- Flag-off (`-Dflpath.java.symbolindex=false`) is byte-identical on every
  wired surface — pinned by flag-off goldens, count-form where identity
  strings are involved (#23's CodeRabbit identity-split lesson).
- Same-spelling declarations keep ONE identity across files regardless of
  import style (the #23 repository-signature file-INDEPENDENT rule).
- No fabricated DEFINITE: ambiguous or unresolvable correlations abstain
  to the textual ladder.
- Abstention evidence (if any is surfaced) never enters
  `scan.diagnostics()` (#62/#76 contracts); the opt-out gate lives INSIDE
  the seam; a failed index build keeps lookup results and silences
  evidence (`withSilencedEvidence`).
- Unique-owner guards wherever attribution would be ambiguous.
- Gates after every item: `make adapter-test` (floor raised from 754),
  `make java-lint` (0 new findings; JDK 27 vs helios08 JDK 17 divergence
  checked per #64 when GodClass is in play), `make evidence-validate`.
- Zero edits to existing tests unless this plan sanctions them
  explicitly (each sanction listed in §7).

## 2. Preflight inventory (verified at fb8a664, file:line)

### 2.1 The textual surface and its full consumer set

`BoundedFrameworkModelTypeParsing.canonicalType` (TypeParsing:32) is the
central resolver; it delegates to the TEXTUAL import resolver
`SourceMetadataScannerTextLayer14.resolveReferenceType` (TextLayer14:47:
explicit imports → dotted-lowercase-first-segment → same-package files →
on-demand imports → java.lang list). Every defect class of #26 applies:
on-demand JDK imports do not resolve (probe-verified in #23 CR-2 thread
r4675771839), nested `$` spellings, case-insensitive simple-name
fallbacks, generics handling is textual.

Consumers (weakness-disposition audit — the COMPLETE set, rg-verified):

| # | Consumer | Site | Resolves |
|---|----------|------|----------|
| C1 | `directTypeCompatibility` | TypeSupport:56 | requestedRaw, candidateRaw, requestedCanonical, candidateCanonical |
| C2 | `directTypesHaveDifferentRawTypes` | TypeSupport:96 | requestedRaw, candidateRaw |
| C3 | `injectionTypesMatch` | TypeSupport:119 (matchingBindings, MetadataMatching:42) | metadata, source, metadataRaw, sourceRaw |
| C4 | `contractMatches` / `contractMatchesType` | TypeMatching:71/92 | requested, candidateRaw, candidate (contracts) |
| C5 | `metadataCandidateTypeMatches` | MetadataState:101 (metadataOnlyCandidates:95) | requested, row.candidateType, row contracts |
| C6 | `metadataTypeMatches` | TypeSupport:32 | simple-name fallback, `declaredTypeNames` uniqueness, case-INSENSITIVE |

Call paths into the surface (BoundedFrameworkModel.InjectionScanning):
`matches` at :158 and :255, `typeMatchCertain` at :316 and :328
(certainty gates for DEFINITE), `metadataOnlyCandidates` at :168,
`matchingBindings` at :181. `candidateMetadataMatches`
(CandidateMetadata:51,55) calls C6 for row-vs-candidate identity — used
by `matchingCandidateMetadata` everywhere.

Injection points are extracted textually in `FrameworkInjectionScanning`
in three kinds — constructor (:46, regex with the phantom-site
`countsMatch` guard at :80), field (:118, INJECT_FIELD regex), method
(:150, MethodInfo-based). Parameter TYPE spellings stay textual
(`injectionParameters` repairs only QUALIFIERS); `Parameter.type()`
feeds evidence strings and fact IDs (`owner#member(type)` in
InjectionScanning.injectionFact :460,:478) — those identity strings must
NOT change flag-on (they are display/ID inputs, not matching inputs).

### 2.2 Seam facts (verified)

- `JavacFrameworkMemberLookup.lookup(owner, name, kind, arity)`
  (JavacFrameworkMemberLookup:45): the #24/#62 unique-member seam —
  flag-gated INSIDE, unique usable owner type
  (`shapeIsUsable`), unique member in the name+kind+arity group,
  `mirror_member_error_type`/`_absent`/`_ambiguous` declines,
  `withSilencedEvidence` on failed builds. Handles kind `field`
  (arity −1 matches any). This is the SAME seam the injection extractor
  already uses for qualifier repair — reusing it keeps one member-rule
  per site.
- `JavacMemberCorrelation.producerCorrelation`
  (JavacMemberCorrelation:284): candidate-own-parameter-text overload
  identification → correlated member (seam A, bijection guards).
- Producer candidate types are ALREADY partially index-derived:
  `FrameworkBeanScanning.producerCandidate` (:140-197) resolves the
  producer return textually (:149) and maps the correlated member's
  `returnType()` (:161). The NON-producer candidate type is the textual
  `file.typeName()`.
- `MemberInfo.MemberTypes` (JavacSourceSymbolIndex:1108) carries
  canonical AND binary, ERASED parameter/return spellings. FIELD members
  are indexed (isMember :850) but `memberReturnType` returns `""` for
  fields (:875-883) — field type shapes are a GAP. `memberHasErrorType`
  already covers field error types via `isError(element.asType())`.
- `TypeInfo` (:1066) has NO supertype component — contracts cannot be
  compared as index shapes without an additive extension.
- The index compiles the FULL selected source set in ONE JavacTask
  (RESOLUTION-60 probe; JavacSourceSymbolIndex:294-300) — cross-file
  references among selected files resolve; residual errors are JLS
  6.5.5.1 scoping failures or missing classpath types.
- Parallel work: PR for #77 (producer-anchor normalization) is open —
  different files; all new tests go in a NEW class; rebase and re-measure
  the floor if it merges mid-flight.

### 2.3 Preflight probes to run at RED (receipts in the RED commit)

- P1: nested-type `file.typeName()` spelling (`$` vs `.`) vs the index
  `canonicalName`/`binaryName` for a nested bean candidate — decides
  whether the candidate arm changes identity for nested types.
- P2: `resolveReferenceType` on `java.util.List` under
  `import java.util.*;` returns empty (re-verify at HEAD) — the
  requested-side defect-class fixture.
- P3: full-suite churn measurement with the prototype arms wired
  (scratch checkout, not committed): enumerate every existing golden
  whose flag-on output changes. Each changed fixture is either (a)
  re-derived in the sanctioned-edits list (§7) or (b) the arm is
  narrowed to keep it stable — decided per fixture, documented.

## 3. Design

### 3.1 New seam: `JavacInjectionTypeShapes` (package-private, final)

All methods return `Optional`/empty on EVERY decline; the flag gate and
`withSilencedEvidence`-style failed-build handling live INSIDE (mirroring
`JavacFrameworkMemberLookup`); nothing enters `scan.diagnostics()`;
no method ever returns a shape the textual path could contradict —
every shape is javac's own resolution of an ACTUAL indexed declaration.

- **`requestedShape(injection, parameterIndex)`** — the canonical
  ERASED index shape of one injection parameter:
  - constructor/method: `JavacFrameworkMemberLookup.of(scanner).lookup(
    injection.ownerType(), memberName, kind, parameters.size())`
    where memberName is the injection member (`<init>` maps to the
    owner's simple name, the index's constructor naming) →
    `member.parameterTypes().get(parameterIndex)`.
  - field: same lookup with kind `field`, arity −1 → the member's FIELD
    TYPE SHAPE (§3.2 extension).
  - constructor sites carry the extractor's `countsMatch` verdict
    (§3.3) — a phantom-site file declines (empty), the same guard the
    qualifier repair uses.
  - Any decline (ambiguous overload, error-typed member, multi-type
    owner, absent member, flag off, failed build) → empty.
- **`candidateShape(candidate)`** — the canonical ERASED identity of the
  candidate's own declaration:
  - non-producer: `typesIn(candidate.file())` filtered to entries whose
    `canonicalName` OR `binaryName` equals `candidate.candidateType()`
    (the MirrorLookup named-filter rule, JavacFrameworkAnnotations:66-70),
    unique usable → `canonicalName`.
  - producer: `JavacMemberCorrelation.producerCorrelation(candidate)` →
    `member.returnType()` (existing seam; verify the candidate-type
    wiring at FrameworkBeanScanning and reuse, do not duplicate).
- **`contractShapes(candidate)`** — `Optional<List<String>>` of the
  candidate's DIRECT supertypes (canonical, erased) from the new
  `TypeInfo.supertypes` component (§3.2); empty when the candidate's
  type does not resolve uniquely or the type is error-shaped.

### 3.2 Index extensions (additive, `MemberPosition`/`publicMember` precedent)

1. **Field type shapes**: FIELD and ENUM_CONSTANT members record their
   declared type in `MemberTypes.returnType` / `returnBinaryType`
   (currently `""`). Consumer audit (rg-verified): every current
   `MemberInfo.returnType()` reader is method/constructor-scoped
   (FrameworkBeanScanning:161 producer methods; JavacShapeDescriptors
   method descriptors; reflection ladder). `memberHasErrorType` already
   flags error-typed fields via the declared mirror.
2. **`TypeInfo.supertypes`**: `List<String>` canonical ERASED spellings
   of the DIRECT superclass (omitting `java.lang.Object`) and directly
   implemented interfaces. An ERROR-kind supertype entry is OMITTED (the
   textual contract ladder still covers that spelling — omitting is the
   conservative direction for an additional match source).

Both extensions change no existing reader's answer (audit at RED with
the full suite; see P3).

### 3.3 Threading (extraction-time site provenance)

`InjectionPoint` gains one additive component `boolean sitesMatchIndex`
(compact default `true` for existing constructors): constructor sites
carry the extractor's `countsMatch`; method/field sites carry `true`.
`InjectionScanning.injectionFactsForParameter` computes
`Optional<String> requestedShape` ONCE per parameter via the seam and
passes it down. Signature changes are confined to the
BoundedFrameworkModel family (`matches`, `typeMatchCertain`,
`contractMatches`, `metadataOnlyCandidates`, `matchingBindings` →
`injectionTypesMatch`); the textual parameters remain so the ladders
below stay expressible.

### 3.4 Comparison wiring (the load-bearing ladders)

Substitution rule: an index shape may REPLACE a textual resolution only
where the comparison is ERASED-raw (both sides erased); wherever
GENERICS are retained, the index arm is an ADDITIONAL disjunct (the
index is erased — substituting would lose true generic matches, the
#23 round-2 no-true-answer-lost rule).

- **C1 `directTypeCompatibility`**: `requestedRaw := requestedShape
  .or(textual erased raw)`; `candidateRaw := candidateShape.or(textual
  erased raw)`. The generic tail (producer signatures) stays textual.
- **C2 `directTypesHaveDifferentRawTypes`**: the same two `or`-arms.
- **C3 `injectionTypesMatch`**: in the ERASED branch,
  `sourceRaw := requestedShape.or(textual erased raw)`; the metadata
  side and the bothGeneric branch stay textual (generic binding rows are
  a documented residual).
- **C4 `contractMatches`**: the textual ladder runs unchanged; ADD one
  disjunct — `requestedShape.isPresent() && contractShapes(candidate)
  .contains(requestedShape)` (erased-vs-erased, javac-resolved both
  sides). Metadata-row contracts stay textual (external spellings,
  residual).
- **C5 `metadataCandidateTypeMatches`**: the textual ladder runs
  unchanged; ADD one disjunct — `requestedShape.isPresent() &&
  canonicalType(eraseGenericArguments(row.candidateType())).equals(
  requestedShape)` (the metadata spelling resolved textually as today,
  compared against the javac-resolved requested shape).
- **C6 `metadataTypeMatches`**: UNCHANGED, dispositioned — its
  case-insensitive unique-simple-name arm is an over-approximation;
  tightening could lose true answers, and no index arm adds a match the
  case-insensitive arm does not already cover. Documented residual.
- **`proxyTargetTypeMatches`** (MetadataMatching:95): OUT of scope —
  the proxy surface's descriptors already correlate through #23; the
  row targetType ladder is its own follow-up. Documented residual.

### 3.5 No-fabricated-DEFINITE argument

Every index shape is javac's resolution of an actual indexed declaration
(the injection's own member parameter, the candidate's own type, the
candidate's own direct supertypes). Wrong-attribution guards: unique
usable owner, unique member in the name+kind+arity group, the extractor's
phantom-constructor count guard, the producer's own-parameter-text
overload identification, unique named-type filter for candidates. A
decline leaves the exact pre-change textual behavior (flag-off
equivalence by construction; the seam is empty flag-off).

## 4. Test matrix — `JavacInjectionContractMigrationTest` (NEW class)

`@Isolated @Execution(SAME_THREAD)`, flag written per cell; every cell
has a flag-off twin asserting the textual golden (count-form where fact
identity strings are involved). Cells:

- **A. Requested-side shapes (constructor/method/field × defect class)**
  - A1 on-demand JDK import (`import java.util.*;` inject `List`) —
    flag-on matches the candidate/metadata FQN; flag-off count-form
    golden (unresolved, P2 re-verified).
  - A2 field injection with the same defect (field type shape path).
  - A3 method injection (`@Inject`-style annotated method).
  - A4 import-style independence: two injection files, one explicit
    import, one on-demand import of the same source type — ONE
    identity; both match the same candidate flag-on.
  - A5 annotation-corrupted parameter spelling (TYPE_USE `@Ann` the
    textual stripper cannot consume) — flag-on shape repairs the match.
  - A6 nested-type identity: candidate nested `Outer.Inner` — the
    candidate arm canonicalizes (P1 decides the exact cell form).
  - A7 same-arity overload ambiguity → seam abstains → textual result
    byte-identical flag-on/flag-off (abstention cell).
  - A8 multi-type owner file → abstention cell.
  - A9 error-typed member (unresolved classpath type in the signature)
    → abstention cell.
  - A10 phantom-constructor guard: void method spelled like the class →
    the whole file's constructor shape arm declines.
- **B. Contract shapes**
  - B1 direct supertype match through index shapes where the implements
    spelling is unresolvable textually (on-demand import).
  - B2 transitive contract NOT matched (parity with the textual
    declared-contracts semantics — no new transitive matching).
  - B3 error supertype omitted; textual contract ladder still answers.
- **C. Certainty / blast radius**
  - C1 flag-on DEFINITE promotion through `typeMatchCertain` on an
    index-proven match (the #61 acceptance cell).
  - C2 no fabricated DEFINITE: a shadowing fixture (sibling type with
    the JDK simple name) where javac resolves differently from the
    textual guess — the index arm must not manufacture a match
    (`reflectionParameterMatchingDoesNotMatchShadowedSimpleNames`
    pattern).
  - C3 an abstaining fixture AND a ServiceLoader DEFINITE site in one
    scan: promotion untouched, `scan.diagnostics()` empty (#62 D1
    pattern) — the abstention channel, if any is wired, never blocks.
  - C4 full-suite zero-churn measurement (P3 receipt in EVIDENCE):
    every flag-on golden change is enumerated and sanctioned or the arm
    narrowed.
- **D. JLS 6.5/6.4.1 name-resolution matrix (AGENTS rule)** — for the
  parameter-type position, pairwise cells where the textual ladder and
  javac can diverge: single-type import × same-package type;
  on-demand × single-type import; java.lang × on-demand;
  same-compilation-unit nested type × import. Each cell: flag-on
  answer is the JLS-correct one; flag-off twin pins the textual answer.

Cell count target: 18–22; floor 754 → 754+N (re-derived at GREEN).

## 5. TDD sequence

1. **PLAN_61** (this document) + adversarial plan-review rounds
   (Engram-grounded subagent; loop until no addressable findings).
2. **RED_61**: the new test class, all cells failing (plus P1/P2 probe
   receipts; P3 churn measurement run on a scratch prototype arm).
   Sanctioned-edit list finalized here (§7).
3. **GREEN_61**: minimal implementation — seam, index extensions,
   threading, ladders. Full gates.
4. **EVIDENCE_61**: mutation receipts (each arm's removal makes exactly
   its cells fail), gate receipts, RESOLUTION-61 draft.
5. **REVIEW_61_n**: independent adversarial implementation reviews
   (fresh Engram-grounded subagent each round) until no new addressable
   findings.

## 6. Risks

- **Flag-on golden churn** (default is ON): measured at P3 before GREEN;
  every change is either sanctioned (§7) or the arm is narrowed. The
  no-true-answer-lost and no-fabricated-DEFINITE rules bound both
  directions.
- **PMD GodClass**: `BoundedFrameworkModel*` chain classes are near the
  threshold; the seam work goes in the NEW class; PMD-forced
  extractions keep behavior identical and are documented in the commit
  message (AGENTS rule).
- **JDK 17/27 lint divergence** (#64): the index extension touches
  `JavacSourceSymbolIndex` (a GodClass-measured file) — verify lint on
  helios08 JDK 17 when in doubt.
- **Parallel PR (#77/#81)**: disjoint files; new test class only; rebase
  protocol per §2.2.

## 7. Sanctioned test edits (the complete list; none others)

- NONE at plan time. P3 may add enumerated fixture re-derivations —
  each added edit is recorded HERE with the fixture name and the
  before/after answer before GREEN proceeds.

## 8. Confidence

Pre-review confidence: **0.72** — the consumer inventory and seam
reuse are verified against source, but three load-bearing facts are
unmeasured at plan time (P1 nested spelling, P2 on-demand failure at
HEAD, P3 churn), and the erased-vs-generics ladder composition is the
kind of subtlety that review rounds have historically corrected (#23
rounds 2–3).
