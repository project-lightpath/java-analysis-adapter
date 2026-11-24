# PLAN-23 — javac TypeMirror parameter/return/type-shape matching in production

Branch: `issue-23-typemirror-production` (from `origin/main` at `46c8cfc`,
which includes PR #57's 103-cell harness).
Issue: project-lightpath/java-analysis-adapter#23 (parent #20).
Plan-review round 1: 8 addressable findings, all incorporated below.

## Preflight (verified on this main)

- The textual shape heuristics and their live consumers:
  - `SourceMetadataScannerTextLayer02.parameterType` — the annotation-blind
    stripper. Production callers: `JavacAnnotationAccess` only
    (`repairTriggered`, `textualDescriptor`, `parameterTypeOrRepair`);
    the #26 harness pins its defects (21 wrong-type cells).
  - `SourceMetadataScanner.parameters(String)` — the textual parameter
    splitter feeding descriptor building.
  - `BoundedFrameworkModelCandidateMetadata.methodDescriptor` /
    `jvmDescriptor` — builds JVM descriptors (`Ljava/lang/String;`) from
    TEXTUAL type strings via `scanner.resolveReferenceType`; an
    annotation-corrupted type string resolves to nothing, so the
    descriptor is absent and proxy/producer matching falls back to
    overload-count heuristics (`BoundedFrameworkModelMetadataMatching`,
    `producerSignatureMatches`).
  - `JavacAnnotationAccess.methodDescriptor` — proxy descriptors in a
    COMMA-CANONICAL dialect (`"(java.lang.String)"`, pinned end-to-end);
    already half-javac via triggered repair correlated by owner+name+arity.
  - `FrameworkBeanScanning.producerCandidate` — resolves the producer
    method's RETURN TYPE to the producer bean type; annotation-corrupted
    return types yield an empty bean type (same defect class).
  - `SourceMetadataScannerFrameworkLayer00.repositoryMethodSignature` +
    `SourceMetadataScannerRepositoryLayer` — Spring repository method
    identities from textual parameter renderings; parameter 0's textual
    token (`repositoryTypeToken`) is compared against entity/id types in
    `redeclaresGuaranteedContractMethod` (source-bound parameter matching).
  - `SourceMetadataScannerReflectionLayer00.parameterMatchesType` —
    reflection-path parameter matching on textual types.
  - `SourceMetadataScannerTextLayer20.methodSource` — **dead code**: zero
    callers in src/main and src/test (exhaustively verified). Its only
    helper, `SourceMetadataScannerTextLayer00.descriptorParameterCount`,
    has `methodSource` as its ONLY caller and is orphaned by the deletion.
- The javac seam exists and is flag-gated: `JavacSourceSymbolIndex`
  (`flpath.java.symbolindex`, default **off**) provides
  `MemberInfo { name, kind ("method" | "constructor" — constructors are
  named by the owner's SIMPLE name in the index, while textual
  `MethodInfo` constructors are named `<init>` and live in
  `constructors(file)`), parameterTypes (canonical erased, e.g.
  "java.lang.String[]"), returnType, errorType }` per indexed source file.
- `JavacServiceLoaderSiteResolver` (ServiceLoader slice) is javac by
  default with no flag — that migration already landed; this issue keeps
  the symbolindex flag convention because these consumers are
  production-default-textual today.

## Scope (binding)

Production sources in `src/main`, flag-gated by `flpath.java.symbolindex`
(default off → **default output unchanged**), extending the existing
`JavacAnnotationAccess`/`JavacSourceSymbolIndex` seam:

1. **Member correlation by types with a bijection rule, scoped to
   multi-overload groups.** Extend `JavacAnnotationAccess.member()`: when
   the name+arity group has EXACTLY ONE index member, keep the existing
   owner+name+arity correlation unchanged (this is the repair path — an
   annotated parameter's textual vector is unresolvable by design, and
   the unique group needs no disambiguation). When the group has MORE
   THAN ONE same-arity overload, apply the bijection rule: resolve EVERY
   candidate overload's textual parameter vector (`parameterType` +
   `scanner.resolveReferenceType` to canonical) and correlate only when
   the resolved vectors form a BIJECTION onto the index's
   parameter-type vectors for those overloads (each overload resolves,
   and the vectors are pairwise distinct). A wrong or missing resolution
   fails the correlation closed to textual — a present-but-wrong
   `resolveReferenceType` answer produces a vector collision or a set
   mismatch, so multi-overload correlation fails closed rather than
   selecting a wrong overload. Error-typed index members are counted
   BEFORE the uniqueness/type filter: an `errorType` overload blocks the
   correlation for its whole name+arity group (a generalization of the
   current single-member errorType filter, applied for the same
   false-uniqueness rationale).
2. **Descriptor building from index shapes, per-consumer dialect.** When
   the flag is on and a member CORRELATES — by either path: the existing
   owner+name+arity rule for unique groups, or the bijection rule for
   multi-overload groups — build its descriptor from
   `MemberInfo.parameterTypes()`/`returnType()`. For unique-group
   members, index shapes REPLACE the per-parameter triggered repair as
   the primary source (the repair's outcome is a subset: it returns
   index parameter types for exactly these members); the textual
   spelling remains the fallback when the member does not correlate.
   Two renderers, one canonical source:
   `BoundedFrameworkModelCandidateMetadata` keeps its
   JVM dialect (`Ljava/lang/String;`, `Optional.empty` on any
   unresolvable component so matching falls back rather than fabricates);
   `JavacAnnotationAccess.methodDescriptor` keeps its pinned
   comma-canonical dialect (`(java.lang.String)`). Wire into
   `BoundedFrameworkModelCandidateMetadata.methodDescriptor` (proxy +
   producer descriptors) and `JavacAnnotationAccess.methodDescriptor`.
3. **Producer return type.** Flag-on: `FrameworkBeanScanning
   .producerCandidate` takes the producer bean type from the correlated
   member's `returnType` when the correlation holds.
4. **Repository method signatures and contract parameter.** Flag-on:
   canonical parameter types from the index for
   `repositoryMethodSignature`, and `redeclaresGuaranteedContractMethod`
   compares the correlated member's canonical parameter-0 type against
   the entity/id types.
5. **Reflection-path parameter matching.** Flag-on:
   `SourceMetadataScannerReflectionLayer00.parameterMatchesType` compares
   canonical index parameter types where the member correlates.
6. **Constructor correlation.** Constructors correlate by either path
   (unique group → owner+name+arity; multi-overload group → the bijection
   rule), with the name mapping: textual `<init>`
   (from `constructors(file)` / `executableMethods`) maps to index
   members of kind `constructor` named by the owner's simple name; the
   owner-type correlation uses the existing `singleTypeFile` rule. The
   production consumer is reflection-layer parameter matching reaching
   constructor parameters via `executableMethods`
   (`SourceMetadataScannerReflectionLayer05`); the constructor
   acceptance cell is written against that path — never vacuously.
7. **Dead code.** Delete `SourceMetadataScannerTextLayer20.methodSource`
   AND `SourceMetadataScannerTextLayer00.descriptorParameterCount`
   (orphaned by the deletion; no other callers — exhaustively verified).
   No output change by construction.
8. **Harness.** The #26 harness keeps its LIVE_TEXTUAL baseline and
   pinned-defect sets unchanged (production flag-off stays textual).
   Production-level tests for the flag-on seam (pattern:
   `JavacParameterAnnotationTest`) cover the issue's acceptance cells:
   varargs with type-use annotation → `java.lang.String[]`; annotated
   parameter → `java.lang.String`; primitive/array/generic/wildcard/
   nested/varargs matching (nested types tested with QUALIFIED spellings;
   unqualified nested spellings fail closed to textual — documented, not
   silently credited); overloads differing only by parameter type
   (bijection correlates, wrong-spelling controls fail closed);
   constructor matching via the `<init>` mapping; return type; error
   type → POSSIBLE/unresolved. Flag-off guard: PINNED TEXTUAL GOLDENS for
   each newly wired surface (comma-canonical proxy descriptors,
   bounded-model JVM descriptors, producer type, repository
   signatures + contract parameter, reflection matching) —
   exact expected textual outputs asserted, not off-vs-off comparison.
9. **Sanctioned test edits.** `JavacAnnotationMigrationTest` cells that
   pin the ARITY-ONLY correlation for MULTI-overload groups
   (`sameArityOverloadsFallBackToTextualValues` and any cell whose
   premise is "same-arity overloads cannot correlate") are updated to
   the bijection semantics: same-arity overloads with DISTINCT
   resolvable type vectors now correlate; the ambiguity guards they
   encode must survive as new controls — `hasCommentText` applies to
   the primary path (comment-corrupted splits skip index correlation
   entirely; the guard moves into the shared correlation seam so every
   wired consumer inherits it, not only
   `JavacAnnotationAccess.methodDescriptor`), `singleTypeFile`
   (nested-owner ambiguity), and
   count-error-overloads-before-uniqueness. Each edited cell keeps a
   fail-closed twin. Unique-group repair cells
   (`typeUseAndVarargsAnnotationsRepairParameterShapes`,
   `mixedNameTriggerShapesRepair`,
   `proxyDescriptorRepairsTriggeredParametersEndToEnd`,
   `flagOnScanDoesNotPoisonTheTextualCache`,
   `unresolvedSameArityOverloadPreventsFalseUniqueness`) are NOT edited —
   the group-scoped rule preserves them.
   `commentTextInParameterListsSkipsIndexedRepair` IS sanctioned for
   edit: under index-shapes-primary its CLEAN same-file sibling overload
   upgrades to `(java.lang.String)` while the comment-corrupted overload
   stays textual. The edited cell asserts this with a COUNT, not a
   scoped predicate — proxy features carry only `owner#name` identity
   (no per-overload identity), so the fail-closed control is: exactly
   one `demo.Api#call` feature carries descriptor
   `(java.lang.String)`, the corrupted overload's feature keeps a
   textual descriptor containing `int`, and a twin covers the clean
   sibling's upgrade.
   `repairTriggered`/`repairedParameterType` are RETAINED as the pinned
   characterization of the now-subsumed repair seam (their outcome is a
   subset of index-shapes-primary, and
   `cleanGenericParametersKeepTheirTextualSpelling` plus the repair
   cells call them directly); their production caller
   `parameterTypeOrRepair` remains as the fallback spelling path for
   non-correlated members. Recorded here per the item-7 dead-code
   discipline: they are characterization-pinned, not dead.

## Non-goals

- Runtime reflection string resolution (#25's binary-name ladder).
- Descriptor provenance or dependency policy.
- Framework route/bean semantics.
- Removing the textual path or flipping the default (follow-up, like the
  ServiceLoader slice's two-step landing).
- `BoundedFrameworkModelTypeParsing.canonicalType` and the injection
  contract type-matching surface (`TypeMatching`, `TypeSupport`,
  `MetadataState`): the largest remaining textual type-matching surface,
  intentionally OUT of scope for this issue — enumerated here per the
  weakness-disposition rule; a follow-up issue owns it.

## Certainty model

- Flag on + correlation (either path: unique-group owner+name+arity, or
  multi-overload bijection) + not `errorType` → javac shapes are the
  answer (DEFINITE-grade matching).
- Flag on + correlation fails (ambiguity, comment text, unresolved
  spelling, `errorType` member) → the EXISTING fallback ladder unchanged:
  textual shapes, then the arity/overload-count heuristic
  (`producerOverloadCount == 1`) where descriptors are absent. The
  downgrade is silent-textual, matching the existing seam behavior —
  NO new scan diagnostic is added, so `scan.diagnostics()` and the
  `ServiceLoaderDescriptorScanner.isUnique` scan-global DEFINITE block
  are untouched (zero diagnostic blast radius; stated here per AGENTS).
- Flag off → byte-identical current behavior, pinned by goldens.

## TDD sequence

- **RED_23**: failing production tests for the flag-on seam (acceptance
  cells above, including the fail-closed controls) + flag-off goldens;
  behavior-neutral stub (type correlation returns the arity-only result)
  documented in the commit.
- **GREEN_23**: implement bijection correlation + per-dialect descriptor
  wiring + producer/repository/reflection wiring + constructor mapping +
  dead-code deletion + the sanctioned `JavacAnnotationMigrationTest`
  cell updates with fail-closed twins.
- **EVIDENCE_23**: `docs/resolutions/RESOLUTION-23.md`, gate receipts,
  and confirmation that the #26 pinned-defect sets are unchanged.

Gates after every item: `make adapter-test` (count must not decrease from
the Makefile floor), `make java-lint` (zero new findings),
`make evidence-validate` (exists on this branch).

## Residuals / risks

- The bijection rule fails closed on any unresolved textual spelling, so
  unqualified nested-type parameters (`Inner` vs index `pkg.Outer.Inner`)
  keep the textual path flag-on — the benefit applies to qualified
  spellings; owner-qualified fallback spellings are a follow-up.
- Flag-on downgrade lands on the arity heuristic, not on javac shapes —
  same-arity overload-name counting remains the last fallback; call-site
  argument typing is #25/#41 territory.
- `resolveReferenceType`'s heuristic defects (verbatim lowercase-qualified
  pass-through, silent on-demand-import preference) are contained by the
  bijection rule but not fixed here — the #26 harness pins them.
- The index is per-`AnalysisContext` and cached; flag-on consumers pay
  the index build cost once per scan (already measured by the #21 lane).
- Injection contract type matching (`BoundedFrameworkModelTypeParsing`)
  remains textual — out of scope, enumerated above.

Confidence score: **0.80** (was 0.72 before plan review) — the bijection
rule closes the wrong-overload window the reviewer opened, the consumer
list is now exhaustive with the producer-type and repository-contract
gaps closed, the constructor mapping is specified, and the dialect split
and flag-off goldens are pinned. Residual risk: the sanctioned
`JavacAnnotationMigrationTest` rewrites touch a dense test matrix, and
the reflection-layer wiring has fewer existing patterns to copy.
