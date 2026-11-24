# RESOLUTION-23 — javac TypeMirror parameter/return/type-shape matching

Issue: project-lightpath/java-analysis-adapter#23 (parent #20).
Branch: `issue-23-typemirror-production`.
Commits: PLAN-23 `fcaaaa7`, RED_23 `6ab4778`, GREEN_23 `c5e4279`,
EVIDENCE_23 (this commit).

## What changed

Production parameter/return/type-shape matching now uses javac index
shapes behind `flpath.java.symbolindex` (default **off** — default output
byte-identical, pinned by flag-off textual goldens per wired surface):

- **`JavacMemberCorrelation`** (new): maps textual `MethodInfo`
  declarations to `JavacSourceSymbolIndex` members through the
  group-scoped rule. Unique name+arity groups correlate directly (the
  #22 repair path, unchanged); multi-member groups correlate only via a
  resolved-textual-vector bijection onto the index vectors — any wrong
  or missing resolution fails the whole group closed to textual.
  Constructors map `<init>` to the owner-simple-name index member.
  Shared guards: comment-corrupted parameter lists and multi-type files
  never correlate.
- **`JavacShapeDescriptors`** (new): renders correlated shapes into the
  bounded-model JVM dialect (`Ljava/lang/String;`); blank components
  abstain so matching falls back rather than fabricating.
- **Wiring (flag on, textual fallback preserved)**: proxy descriptors
  (comma-canonical dialect, triggered repair retained as the subset
  fallback), bounded-model proxy/producer JVM descriptors, producer bean
  types, repository method signatures (all three callers), and
  reflection-path `parameterMatchesType`.
- **Index defect fixed**: `memberHasErrorType` now checks method RETURN
  types. The index compiles per-file, so cross-file returns are ERROR
  types; previously an unresolved return surfaced as an unqualified
  "canonical" spelling (probe: `returnType=Inner`, `errorType=false`).
  Unproven returns now abstain. Blast radius: ALL flag-on consumers of
  member error-typing — the new shape consumers AND the #22/#24
  annotation-mirror seam (`JavacAnnotationAccess.member`,
  `JavacFrameworkAnnotations.member`): a method whose return type is
  declared in another file now abstains to textual for annotation repair
  too. Safe direction (no fabricated facts), but a real flag-on coverage
  cost; a follow-up could let the index compile with the full selected
  source set so cross-file returns resolve.
- **Dead code deleted**: `methodSource` and its only helper
  `descriptorParameterCount` (zero callers, exhaustively verified).

## Acceptance evidence (issue #23)

- `String @$Ann... args` → `java.lang.String[]` and
  `@Ann(value = 1) String p` → `java.lang.String`: pinned by the
  inherited #22 repair cells and the #26 harness; the correlation path
  preserves them (unique groups).
- Overloads differing only by parameter type: the bijection correlates
  both same-arity overloads and the proxy descriptors carry canonical
  shapes (`multiOverloadProxyDescriptorsCorrelateByType`).
- Constructor matching: `<init>`-to-simple-name mapping through the
  reflection path (`constructorCorrelationUsesInitToSimpleNameMapping`).
- Return type matching: producer bean types from the correlated member
  (`producerReturnTypeAbstainsOnUnprovableIndexShapes`).
- Unresolved types: POSSIBLE/abstain — the fail-closed bijection control
  (`bijectionFailsClosedOnUnresolvableSpelling`), the error-return
  abstention, and the flag-off goldens.
- Default output unchanged with the flag off: pinned textual goldens for
  every wired surface (proxy descriptors, bounded-model descriptors,
  producer type, repository signatures, reflection matching).
- Gates: `make adapter-test` 610 executions (floor 534), zero failures;
  `make java-lint` PASS (0 new findings); `make evidence-validate` pass.

## Change-review record

- **Round 1** (Engram-grounded subagent, full change): 5 addressable
  findings, all addressed in REVIEW_23_1:
  1. CRITICAL — `producerJvmDescriptorFromIndex` matched producer
     methods by name+arity only, attributing the first same-arity
     overload's descriptor to every candidate (probe-confirmed false
     DEFINITE). Fixed: the candidate's own parameter text identifies its
     overload; pinned by `producerOverloadDescriptorsDifferentiate
     ByType` (each candidate renders ITS OWN JVM descriptor).
  2. HIGH — flag-on `parameterMatchesType` compared canonical index
     types against the simple names production callers actually pass,
     flipping true answers to false. Fixed: the canonical comparison is
     an ADDITIONAL match source (canonical or simple spelling); the
     textual ladder always runs, so no true answer is lost.
  3. MEDIUM — the plan's `redeclaresGuaranteedContractMethod` clause was
     not implemented. Fixed: the correlated member's canonical
     parameter-0 type compares against the entity/id types flag-on,
     OR'd after the textual token path.
  4. MEDIUM — no positive flag-on producer differential test. Fixed by
     the round-1 finding-1 pin (see 1); the abstention cell stays.
  5. LOW — the return-type fix's blast radius was understated;
     RESOLUTION corrected (see the index-defect entry above).
  NITs (duplicate `of(scanner)` construction, stray blank lines,
  fully-qualified collections) fixed.
- **Round 2** (delta-only, on REVIEW_23_1): 2 HIGH findings, both
  addressed in REVIEW_23_2:
  1. `Set.of(entityType, idType)` threw IllegalArgumentException for
     same-entity/id repositories (`CrudRepository<User, User>` is legal
     Java; probe-confirmed crash). Fixed: the expected types are a List
     (duplicates legal), and the member check uses contains.
  2. The round-1 simple-name disjunct in `parameterMatchesType`
     fabricated matches when a same-simple-name type shadowed the
     queried JDK name (probe: file with sibling `demo.Class`, parameter
     `Class`, query "Class" — textual correctly false, flag-on wrongly
     true). Fixed: ONLY the exact canonical spelling compares; simple
     -name queries keep the textual ladder unchanged (no loss, no
     fabrication).
  One NIT (stale producer-descriptor javadoc) fixed.
- **Round 3** (delta-only, on REVIEW_23_2): 3 addressable findings, all
  addressed in REVIEW_23_3:
  1. The crash fix was unpinned —
     `sameEntityIdRepositoryContractCheckDoesNotCrash` now pins
     `CrudRepository<User, User>` (equal entity/id types) through the
     flag-on contract check. Round 4 found the first pin vacuous
     (deleteByName is not a guaranteed contract name, so the check never
     ran); the pin now declares `delete` (a contract name whose single
     parameter differs from the entity/id types), which the round-4
     mutation probe showed reaches the expected-types collection.
  2. The shadow fix was unpinned —
     `reflectionParameterMatchingDoesNotMatchShadowedSimpleNames` pins a
     sibling `demo.Class` shadowing `java.lang.Class`: the simple-name
     query does not match, the canonical query does.
  3. The flag-on canonical disjunct in `parameterMatchesType` is
     production-dead (every production caller queries simple names; the
     index stores canonical names, so only exact-canonical queries can
     fire — currently just the test). Documented here so a future round
     does not assume live coverage; the direction is conservative either
     way. The shadow pin's canonical-query assertTrue is likewise
     non-load-bearing (the textual ladder already answers it); the
     assertFalse on the simple name is the load-bearing assertion. The round-2 commit message's sentinel claim was also
     imprecise (only the entity type is sentinel-guarded; the id
     sentinel reaches the List harmlessly) — corrected by this record.

## Plan-review record

Six adversarial plan-review rounds (Engram-grounded subagent):
8 → 4 → 1 → 4 → 1 → 0 addressable findings. The material design changes
forced by review: the bijection rule (round 1), its scoping to
multi-overload groups so the unique-group repair path survives
(round 2), the constructor name mapping (round 1), per-consumer
descriptor dialects (round 1), count-form flag-off goldens (rounds 2/5),
and the retention disposition for the repair seam (round 4).

## Behavior facts discovered at GREEN (probe-verified)

- The textual `MethodInfo` extractor cannot parse annotated return
  types — the plan's "annotation-corrupted producer return" defect class
  is unreachable (extraction fails before producerCandidate). The
  producer wiring remains (canonical when correlated) but its pinned
  cell asserts abstention on unprovable cross-file returns plus parity
  for clean producers.
- The textual `Parameter` splitter strips declaration annotations, so
  `Parameter::type` is cleaner than `parameterType` — the repository
  signature defect is unqualified spelling, not annotation corruption.
- The index collapses same-shape overloads to one member; identical
  shapes are legitimately unique-group correlates.
- The index compiles per-file (empty sourcepath): cross-file type
  references are ERROR types — see the return-type fix above.

## Sanctioned test edits

- `commentTextInParameterListsSkipsIndexedRepair`: the fail-closed
  control is now a COUNT (exactly one canonical descriptor; the
  corrupted overload stays textual) because proxy features carry only
  `owner#name` identity.
- `sameArityOverloadsFallBackToTextualValues` needed NO edit: the
  arity-only `member()` API is unchanged; the bijection lives in the
  correlation path. The plan's conditional sanction was not triggered.

## Residuals

- Flag-on downgrade lands on the existing fallback ladder (textual
  shapes, then the arity/overload-count heuristic) — unchanged.
- `BoundedFrameworkModelTypeParsing.canonicalType` and the injection
  contract type-matching surface remain textual — out of scope,
  enumerated in PLAN-23; a follow-up issue owns them.
- Unqualified nested-type parameter spellings fail closed to textual
  (same-package lookup is top-level only); owner-qualified fallback
  spellings are a follow-up.
- The default is not flipped: enabling the flag by default is a
  follow-up (the ServiceLoader slice's two-step landing pattern).
