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

## CodeRabbit round (PR #59)

- **Round CR-1** (CodeRabbit on PR #59, all four findings triaged grounded
  in facts): 3 fixed in REVIEW_23_6, 1 dispositioned as a follow-up:
  1. Major — method-scoped type parameters could derange the bijection:
     `<List extends java.util.Set> run(List p)` next to
     `<Set extends java.util.List> run(Set p)` resolves the textual vectors
     through imports while the index erases to the bounds, so the vector
     sets match and a declaration correlates to the OTHER overload. Fixed:
     a parameter whose simple name is a type parameter of its own method
     (extracted from the declaration header, anchored after modifiers so a
     generic return type cannot false-positive) abstains — the group fails
     closed. Pinned by `typeVariableParametersFailClosedInTheBijection`.
  2. Minor — nested reference types render with slashes in the JVM
     descriptor (canonical dots, not binary `$`). DISPOSITIONED, not
     fixed: the flag-on renderer is byte-identical to the pre-existing
     TEXTUAL `jvmDescriptor` dialect (both map canonical dots to slashes),
     so there is no flag-on/off divergence and no regression from this
     change; fixing the dialect requires carrying binary names through
     the index AND the textual path — a cross-cutting follow-up, recorded
     in Residuals.
  3. Minor — a type variable with an unresolved bound
     (`<T extends missing.Thing>`) has a non-error declared return mirror
     but erases to an error type, whose spelling could be published as a
     canonical producer bean type. Fixed: `memberHasErrorType` checks the
     erased return as well (valid bounds like `<T extends Number>` stay
     eligible). Pinned by `unresolvedTypeVariableBoundReturnAbstains`.
  4. Major — fragment and repository declarations of the same method
     could get different signature identities when only one file's member
     was blocked (unresolved cross-file return), losing the
     fragment-backed attribution. Fixed: `repositoryMethodSignature`'s
     flag-on rule is PER-PARAMETER canonicalization through
     `resolveReferenceType` (deterministic across files; unresolvable
     parameters keep their textual token so both sides stay identical),
     deliberately independent of member correlation. Pinned by
     `fragmentAndRepositorySignaturesShareOneRule`.

- **Round CR-2** (CodeRabbit follow-ups on the CR-1 threads; 2
  actionable residuals, both addressed in REVIEW_23_7):
  1. Thread r4675771816 — the type-parameter clause extraction was
     anchored at the declaration start, which includes a preceding
     `{`, `}`, or `;` for methods with bodies, so the clause was not
     found and the derangement window reopened for concrete classes.
     Fixed: the anchor tolerates the preceding delimiters. Pinned by
     `typeVariableAbstentionSurvivesMethodBodies` (a concrete class
     with bodies; both mirrored overloads abstain).
  2. Thread r4675771839 — the per-parameter canonicalization still
     split identities when the same spelling was imported differently
     (explicit import resolves, on-demand does not — probe-verified:
     resolveReferenceType returns empty for a single java.util.*
     on-demand import). Fixed: the signature rule canonicalizes only
     file-INDEPENDENT resolutions (primitives, already-qualified
     spellings, java.lang types, which every compilation unit imports
     implicitly); import-dependent names keep their textual token, so
     same-spelling declarations get one identity regardless of import
     style. Pinned by `fragmentAndRepositorySignaturesShareOneRule
     AcrossImportStyles` (explicit vs on-demand import of the same
     simple name). Different spellings of the same type (qualified vs
     simple) keep the pre-existing textual identity split — not a
     regression, recorded here.
  Thread r4675771826 (JVM descriptor binary names) remains the
  agreed documented follow-up from CR-1.
- **Round CR-3** (CodeRabbit re-review of 58a8369; 3 findings — 1 Major
  and 1 Minor fixed in REVIEW_23_8, 1 Minor dispositioned):
  1. Major (r4681365981) — `methodTypeParameters` stopped at the first
     `>`, so a nested bound (`<X extends Set<Bar>, Y ...>`) hid later
     type-parameter names and reopened the mirrored-overload derangement
     window; an incomplete parse returning an empty set did NOT abstain
     (indistinguishable from no clause). Fixed: the clause is balanced
     with depth counting, split on top-level commas only, annotations
     stripped from names, and any unparseable clause abstains via a
     three-state result (none / parsed names / abstain). Pinned by
     `nestedTypeParameterBoundsYieldEveryName`.
  2. Minor (r4681366003) — parameters whose declared mirror is a type
     variable with an unresolved bound slipped through `memberHasError
     Type` (TYPEVAR is not an error kind) and published the erased error
     spelling with `errorType=false`. Fixed: parameters get the same
     declared-or-erased check the return type has. Pinned by
     `unresolvedTypeVariableBoundParameterAbstains`.
  4. Follow-up round CR-4 (threads r4681605219 and r4681620081; both
     residuals addressed in REVIEW_23_11):
     a. The clause parser counted `>` characters in raw comments — a
        `/* > */` inside a bound ended the clause before later names,
        and the nested-clause regression used only the FIRST declared
        name. Fixed: the header is comment- and literal-masked
        (offset-preserving) before the balanced parse. Pinned by
        `commentsInsideBoundsAndLaterNamesAreRecognized` (a `>` inside a
        bound comment; parameters use the SECOND declared name in each
        clause; both mirrored overloads abstain; both methods found).
     b. The parameter-bound fixture was vacuous — T was declared on a
        sibling method, not in scope, so the DECLARED error check fired
        and the erased check was untested. Fixed: class-scoped
        `<T extends missing.Thing>` (TYPEVAR declared mirror, error
        erasure) with a `T extends Number` control asserting eligibility
        (parameterTypes [java.lang.Number]).
   5. Follow-up round CR-5 (threads r4681997971 and r4681968872; both
      residuals addressed in REVIEW_23_12):
      a. The clause parser located the parameter-list boundary with a raw
         `source.indexOf(declaration.parameters(), declaration.start())`,
         which can anchor inside a comment BEFORE the clause that repeats
         the parameter text (`public /* List p */ <List ...>`) — and a
         comment before the clause also breaks the method EXTRACTION
         pattern, pushing `declaration.start()` past the clause entirely
         (probe-verified: the header window was `String pick(`), so the
         clause was invisible and the mirrored overloads correlated.
         Fixed per the reviewer's guidance (use the actual parameter-list
         boundary): the boundary is located on the whole-file-masked
         source, verified against the declaration's own name preceding
         the parameter list (the owner's simple name for `<init>`
         constructors), and the header window runs from the previous
         declaration delimiter — tracked at parenthesis depth zero so a
         delimiter inside an annotation argument (`@Ann({1})`) cannot
         truncate it — to the method name. An unestablishable boundary
         abstains (fail closed). Pinned by
         `commentBeforeClauseDoesNotHideTypeParameters` (comment before
         the clause repeating the parameter text verbatim; parameters use
         the SECOND declared name after an actual nested generic bound
         `Set<String>`; both mirrored overloads found and both abstain).
   6. Follow-up round CR-6 (comments 4682526615, 4682526629, and
      4682567901 on thread r4681365981; all three residuals addressed in
      REVIEW_23_13):
      a. HEADER_START's annotation alternative ([^)]* arguments) could
         not consume a nested-parenthesis annotation argument
         (@A(@B(1)), @SuppressWarnings(value = ("unchecked"))) before a
         clause, so the clause went unrecognized and none()
         (non-abstaining) let mirrored overloads correlate. Fixed: the
         regex prefix was replaced by a hand-rolled prefix parser whose
         annotation consumption balances parentheses; an unparseable
         annotation (malformed name or unbalanced argument) abstains,
         fail closed. Pinned by
         `nestedParenthesisAnnotationArgumentBeforeClauseAbstains`
         (committed red first: both mirrored overloads correlated).
      b. The balanced angle scan and splitTopLevel counted every '<' and
         '>', so a bound annotation argument containing '>'
         (@A(1 > 0)) closed the clause before the second name and the
         name resolved through imports. Fixed: parenthesis contents are
         shielded from the bracket count and from comma splitting; an
         unbalanced argument leaves the clause unclosed, which abstains.
         Pinned by `boundAnnotationArgumentBracketsDoNotCloseClause`
         (committed red first: both mirrored overloads correlated).
      b. The mutation check requested on r4681366003 was executed:
         reverting ONLY the erased-parameter check in
         `memberHasErrorType` makes
         `unresolvedTypeVariableBoundParameterAbstains` fail (member
         correlates, `expected: <true> but was: <false>`); restoring the
         check makes it pass. The pin is mutation-sensitive.
   7. Independent adversarial re-review of the CR-6 changes (Engram
      grounded, same session): a 20-cell header battery against the
      compiled parser (wildcard, intersection, and recursive bounds;
      qualified, nested, array-argument, and brace-nested annotations;
      unbalanced arguments abstain), the varargs/generic-array cell
      (isError recurses into ARRAY components, so T.../T[] with an
      unresolved bound is caught by the erased check), the nested-class
      constructor indexName cell (canonical dotted spellings, probed in
      PLAN-21), and the anchor-collision cell (forward search from
      declaration.start() plus name verification makes a foreign
      ident(params) anchor impossible in valid Java) — no new findings.
      Mutation sensitivity of the two CR-6 pins, executed: reverting
      ONLY the parenthesis shield in clauseEnd makes
      boundAnnotationArgumentBracketsDoNotCloseClause fail (the
      nested-paren pin stays green); reverting ONLY the balanced
      annotation consumption in annotationEnd makes
      nestedParenthesisAnnotationArgumentBeforeClauseAbstains fail (the
      bound-annotation pin stays green). The guards are independent and
      each pin is mutation-sensitive against exactly its own guard.
   8. Follow-up round CR-7 (comments 4686083980 and 4686097522;
      addressed in REVIEW_23_15): whitespace between an annotation name
      and its argument list (@A (@B(1))) is legal Java, but annotationEnd
      required '(' immediately after the name — the annotation looked
      argument-less, the prefix stopped at '(', and the clause went
      unrecognized, so none() (non-abstaining) let the mirrored overloads
      correlate. Fixed: the argument-list boundary skips whitespace
      (and, closing the same token-boundary cell class, so does the
      name boundary after '@'). Pinned by
      whitespaceBeforeAnnotationArgumentsKeepsClauseVisible (committed
      red first: the fixture correlated at HEAD).
   9. Follow-up round CR-8 (the four threads left with CodeRabbit's
      last word; addressed in REVIEW_23_16):
      a. Thread 4675771816 (comment 4681423578): annotated type
         parameters (<@Ann List extends java.util.Set>) — the name strip
         landed in 132a7b1 but the thread was never answered and the
         case was unpinned. Pinned by annotatedTypeParameterNamesAre
         Recognized (green characterization pin of the 132a7b1 strip).
      b. Thread 4675771839 (comment 4681408527):
         repositoryMethodSignature overload identities for
         <String extends Number> find(String key) vs
         find(java.lang.String key) — verified EMPIRICALLY that the
         132a7b1 token rule already keeps the identities distinct
         (find(String) vs find(java.lang.String)); pinned by
         typeVariableTokenKeepsDistinctOverloadIdentities (green
         characterization pin; the initial run also proved
         springRepositoryMethods does NOT deduplicate them).
      c. Thread 4684976467: whitespace around the dots of a qualified
         annotation name (@java . lang . SuppressWarnings (...)) —
         qualifiedNameEnd required the '.' immediately after a name
         segment, so the prefix stopped at the '.' and the clause went
         unrecognized (none(), non-abstaining). Fixed: the qualified
         name consumption skips whitespace around dots; a dot not
         followed by a name segment is unparseable and abstains. Pinned
         by whitespaceAroundQualifiedNameDotsKeepsClauseVisible
         (committed red first as RED_23_16: correlated at HEAD).
      d. Thread 4681365981 (comment 4686083980): the whitespace-before-
         annotation-arguments case — already fixed in REVIEW_23_15
         (7f9d904) but the reply was only posted on the 4682526615
         sub-thread; the main-thread reply is now posted with the same
         evidence.
  3. Minor (r4681365963) — index diagnostics (javac errors behind
     flag-on abstentions) do not reach the scan output. DISPOSITIONED:
     routing index diagnostics into `scan.diagnostics()` would block
     DEFINITE promotion for the WHOLE scan (`ServiceLoaderDescriptor
     Scanner.isUnique`), a blast radius this change must not take; the
     plan's certainty model (reviewed through six plan rounds)
     deliberately specifies silent-textual fallback with zero new scan
     diagnostics. A blast-radius-safe visibility channel (per-member
     evidence rather than scan diagnostics) is the follow-up, tracked as
     **#62**; #60 (index shape fidelity) removes most of the abstentions
     it would surface.

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
  enumerated in PLAN-23; tracked as **#61**.
- Unqualified nested-type parameter spellings fail closed to textual
  (same-package lookup is top-level only); owner-qualified fallback
  spellings are a follow-up, tracked with **#60** (index shape
  fidelity).
- The default is not flipped: enabling the flag by default is the
  final step of the **#20** umbrella (the ServiceLoader slice's
  two-step landing pattern), after #25 lands.
- The JVM descriptor dialect renders canonical dots as slashes for
  nested types (`Ljava/util/Map/Entry;` instead of the binary
  `Ljava/util/Map$Entry;`) — a pre-existing property of the TEXTUAL
  `jvmDescriptor` that the flag-on renderer matches exactly; fixing it
  requires binary names through both paths — tracked as **#60**
  (CodeRabbit CR-1 finding 2).
