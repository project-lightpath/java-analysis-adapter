# PLAN-41 — Reverse call graph through Spring Data repository interface methods

Issue: project-lightpath/java-analysis-adapter#41
Branch: `flpath-5079-repository-reverse-edges` (cut from `origin/main` @ 0297828)
Status: draft v10 (review rounds 1–9 findings addressed)

## Problem

On the ssc-demo FSI baseline (`spike/runs/manual-integration.gGygqD/`, 3,674
provider / 77 framework facts) a reverse-reachability query over provider facts
for an app-declared Spring Data repository method returns zero callers:

- `AccountService#create` calls `accountRepository.save(account)`; no provider
  edge with callee `AccountRepository#save` is emitted.
- The only facts naming the method are `spring-data-proxy` framework facts
  (possible, `generated_implementation`).
- The interface invoke is CHA-dispatched to dependency-internal implementors and
  emitted as `possible` `interface_dispatch` edges into Spring Data / Hibernate
  internals — the declared interface-method target itself is never emitted,
  because `SootUpCallGraphExtractor.applicationCalls` iterates
  `graph.getCalls()` and `applicationCall` builds the callee from
  `call.targetMethodSignature()` (the CHA-resolved target); CHA never targets
  the abstract interface method.

Consequence: repository symbols are dead ends in the reverse call graph; a
reachability query targeting `SimpleJpaRepository#save` or
`PartTreeJpaQuery#execute` wrongly concludes "no application callers".

## Settled decisions (issue thread — binding, not re-litigated)

1. **Two edges are required.**
   - (a) *Declared-target provider edge*: app call site → app-declared
     repository interface method. Sound at emission (bytecode
     `invokeinterface` names the interface method; javac resolves the declared
     receiver type from source). Certainty: `possible` — a declared-target
     fact, not a resolved dispatch.
   - (b) *Framework-model delegation edge* as a promotion ladder
     (FLPATH-5075: definite only from exact unique active metadata-backed
     selection): repository interface method → runtime backing implementation
     (JpaRepository CRUD → `SimpleJpaRepository`; derived queries →
     `PartTreeJpaQuery` machinery). Default `possible`; `definite` when the
     Spring configuration in the analysis context proves unique selection;
     abstain with a diagnostic when config references classes outside scope.
2. Config + source are assumed available; the abstain cell narrows to
   deploy-time environment conditions (externalized property values,
   host-vs-profile Java mismatches).
3. Jar-first input contract for compiled code; directories for source roots.
4. The runtime proxy is never a graph node with a JVM-generated name; the
   stable synthetic identity `spring-data-proxy:<FQCN>` may be reused.
5. Reverse-lookup start node comes from a signed attestation manifest
   (ADR-0057): `class`+`method`+JVM descriptor. mappingStatus resolution and
   digest binding are follow-ups OUTSIDE this issue — do not implement, do not
   preclude (edges below key on the existing node-identity grammar
   `FQCN#method` with raw signature suffixes, which already carries the
   descriptor where overloads need it).

## Design

### Edge 1 — declared-target provider edge (`declared_invokes`)

Emission site: `SootUpCallGraphExtractor` (src/main/java/.../SootUpCallGraphExtractor.java).

- A **dedicated body-scanning pass** is required (round-1 review confirmed
  the CHA path can never produce these targets): for every application method
  body (`applicationMethods` already enumerates body-bearing app methods),
  scan statements for `JInterfaceInvokeExpr` whose **declared**
  `MethodSignature` (the same `getInvokeExpr().getMethodSignature()` access
  already used by `virtualTargetMayDispatch`) has a declaring class in the
  app-declared repository-interface set, and emit
  `CallEdge(caller, declaredCallee, line, sourcePath, POSSIBLE,
  "declared_invokes", TargetContext.application(), receiver)`.
- `declaredCallee` = `MethodSymbol.fromSootSignature(declaredSignature)` —
  for `accountRepository.save(account)` the constant-pool invoke names the
  compile-time receiver type (`AccountRepository`), including methods
  inherited from `JpaRepository` (empirical probe in Spike 2).
- Line numbers: today only `CallGraph.Call.getLineNumber()` exists in this
  lane. Spike 1 must establish Stmt→line extraction (SootUp position tags /
  LineNumberTable); if positions are unavailable the edge is emitted lineless
  (`line <= 0`) — lineless-edge behavior is already pinned by
  `JavaAnalysisAdapterTest.linelessEdgeDoesNotBindFileWideFallback` and must
  not regress.
- Repository-interface set: built once from
  `scanner.beanCandidates(SPRING_BOOT)` filtered to kind
  `repository_interface` (typeName set), passed into the extractor alongside
  `applicationClasses`. Guarded by framework == SPRING_BOOT.
- **Provider-fact ID namespace (round-1 finding 3, round-2 finding 1):**
  `declared_invokes` edges use a **distinct ID namespace** (a `declaredCallId`
  analogous to `dependencyCallId`), NOT the legacy `sootup-call-` base ID —
  AND `collectCallReferences` must exclude `declared_invokes` edges from
  `applicationBaseIdCounts` (the count is keyed on `legacyCallId`, which
  hashes only caller/callee@line with no relationship type; counting the
  declared edge there would flip the *existing* `invokes` edge to the
  `-sha256` form even though the declared edge itself takes the new
  namespace). Success criterion (Spike 3): in the default-method overlap the
  pre-existing `invokes` edge keeps its `sootup-call-<hex>` ID byte-stable.
  `applicationCallIdentity` already includes the relationship type, so
  in-namespace identity is complete without further changes. Additionally
  (round-3 finding 3) `callId` must branch on
  `relationshipType == "declared_invokes"` **before** the application-origin
  branch — the `dependencyCallId` analogy routes by target origin, and a
  `declared_invokes` edge is APPLICATION-origin, so without the precedence
  it would still land in the legacy-prefix branch.
- The existing CHA `interface_dispatch` edges to dependency internals are
  retained unchanged (pairwise interaction is a required test cell).
- New bounded `relationshipType` value: `declared_invokes`, NOT added to
  `DynamicCertaintyPolicy.isOpenWorldProviderRelationship` (declared
  bytecode fact, not open-world).
- **Skip visibility (round-1 finding 8):** the repository set comes from
  source scanning. If the context is SPRING_BOOT, compiled application code
  is present, but **no source roots are available** (repository interfaces
  unidentifiable), emit the scan-global diagnostic
  `spring_data_repository_sources_unavailable` (channel below) and skip the
  pass. If source roots exist and simply contain no repository interfaces,
  that is genuine absence — no diagnostic. This mirrors the repo's
  fail-visible rule without a whole-scan certainty downgrade for the mere
  absence of Spring Data usage.

### Edge 2 — framework-model delegation ladder

Emission site: `BoundedFrameworkModelSpringFacts.springRepositoryFacts`
(+ ID helper in `BoundedFrameworkModelIdentity`).

- **Scanner additions required first (round-1 findings 1, 5, 6 — explicit
  work items, not free):**
  1. *Per-contract, per-version guaranteed-method table* (generalizing the
     `findById`/`SPRING_DATA_FIND_BY_ID_CONTRACTS` special case): a bounded,
     documented method set keyed by (contract family, Boot major version) —
     reusing `springBootMajorVersion()` the same way
     `guaranteedFindByIdContract` already does, because contract guarantees
     are version-sensitive (e.g. `deleteInBatch`/`getOne` changed across the
     Spring Data 2→3 boundary). A version-blind table can fabricate
     guaranteed methods and feed the false-DEFINITE bug class. The
     `CrudRepository` baseline (`save`, `saveAll`, `findById`, `existsById`,
     `findAll`, `findAllById`, `count`, `deleteById`, `delete`, `deleteAll`)
     and `JpaRepository` additions are pinned per version by Spike 5 against
     the spring-data jars on the classpath, not from memory. This makes
     inherited-but-not-declared CRUD methods (the issue's own `save`
     example) enumerate as `RepositoryMethod`s with `guaranteed=true`.
     This table **replaces** the `findById` injection in
     `springRepositoryMethods` (single source of guaranteed-ness — no
     dual gates that can disagree), and the version-unparsable cell
     (`springBootMajorVersion()` empty) mirrors the existing
     `.orElse(false)` resolution: no method is guaranteed.
  2. *Reactive contracts*: add the reactive contract FQCNs **actually
     present on the analysis classpath, pinned by Spike 5** (spring-data-jpa
     is blocking; reactive repositories live in other module families, and
     at least one historical reactive contract was removed from
     spring-data-commons — do not enumerate from memory) to
     `SPRING_DATA_REPOSITORY_CONTRACTS` so reactive repositories classify as
     `repository_interface` at all; reactive guaranteed-method set from the
     same spike. Mind the `Map.of` 10-pair ceiling (currently 6 entries) —
     switch to `Map.ofEntries` if the addition would exceed it.
  3. *Fragment composition*: detect repository fragments (a repository
     interface extending an app-declared custom interface, plus the `Impl`-
     suffixed implementation class in the application scope).
  4. *`@EnableJpaRepositories` attribute reading*: bounded source-text/AST
     read of `repositoryBaseClass` (and presence detection of the annotation
     at all) from Spring configuration classes in scope.
- For each repository method, emit an additional framework fact: caller =
  `<repository FQCN>#<methodIdentity>`, kind `delegated_implementation`,
  feature `spring-data-delegation`, target = backing implementation:
  - **CRUD methods** (guaranteed): target
    `org.springframework.data.jpa.repository.support.SimpleJpaRepository#<method>`
    — an ordinary `implements`-backed class in the spring-data jar on the
    analysis classpath.
  - **Derived queries** (declared, non-guaranteed): target
    `org.springframework.data.jpa.repository.query.PartTreeJpaQuery#execute`
    (query-derivation machinery).
  - **Reactive variants**: reactive backing class (exact FQCN from Spike 5).
  - **Fragments**: the backing for fragment methods is the app-declared
    `Impl` class — edge 2 target is that class's method when it is in the
    application scope.
- Certainty ladder (FLPATH-5075):
  - `definite` — config in scope, no override mechanisms present (no
    `repositoryBaseClass`, no fragments, not reactive), unique backing class
    for the contract family. **Precedent (round-1 finding 7, resolved):
    FrameworkModel facts MAY be definite** — `BoundedFrameworkModel.
    InjectionScanning.certainty` returns DEFINITE, and
    `DynamicCertaintyGuardTest.frameworkDynamicEvidenceCannotBePromotedOrLinked`
    pins that bounded metadata-backed facts stay DEFINITE and emitted; the
    guard abstains only open-world relationship types and
    `reflection_api`/`dynamic_proxy_call` evidence kinds. The delegation
    facts therefore use a non-open-world evidence kind
    (`spring_data_backing_selection`) and stay off the open-world list.
  - `possible` — override mechanisms present but resolvable to a bounded
    candidate set (e.g. `repositoryBaseClass` names an in-scope class;
    fragment Impl present).
  - `unresolved` (abstain) + scan-global diagnostic — configuration
    references classes outside the analysis scope.
- **Diagnostic channel (round-1 finding 2, rounds 2–4 findings 2):** the
  ServiceLoader `Scan` channel is conditionally constructed and has **no
  output surface** outside `ServiceLoaderDescriptorScanner.resolve`, which
  runs only for `service_loader` edges — so codes inserted only there are
  invisible in the target scenario (Spring Data app, no ServiceLoader
  usage). The design therefore adds a **new unconditional diagnostics
  surface**: a `diagnostics` field on `SemanticCallFacts` output (list of
  `{code, path, detail}`, serialized like the existing evidence shapes;
  emitted **sorted by (code, path, detail)** for byte-determinism, required
  by `repeatedRunsProduceIdenticalJsonWithoutLeakingTheExtractionRoot`;
  documented as additive under the existing `schemaVersion` 0.1.0 — no
  consumer asserts an exact top-level key set and `evidence-validate`
  validates the spike observation artifact, not this document).
  Plumbing (round-4 findings 1–3): the codes are computed **exactly once in
  `enrich`**, by a helper that constructs its own `SourceMetadataScanner`
  from the (derived) context at the surface site — mirroring the
  unconditional scanner construction in `BoundedFrameworkModel.enrich:731`
  (`normalize`'s own scanner is conditional on service_loader edges, so it
  is not the mirror). Cost is bounded: the scanner constructor only stores
  the context, and the javac index is cached per `AnalysisContext` in
  `JavacSourceSymbolIndex`'s context-keyed cache — the second instance adds
  one `files()` re-walk, not a second compilation. No
  `FrameworkModel.enrich` API change. `JavaAnalysisAdapter.enrich` invokes
  the helper once and passes the result **down into
  `ProviderFactNormalizer.normalize` as a new parameter**, consumed where
  the ServiceLoader `Scan` is assembled (a `ProviderFactNormalizer`
  signature change — distinct from, and compatible with, avoiding the
  `FrameworkModel.enrich` change — implemented overload-preserving: a new
  4-arg `normalize(context, graph, dependencies, diagnostics)`; the
  existing 2- and 3-arg overloads delegate with `List.of()`, so the direct
  3-arg call in `ServiceLoaderSafetyLifetimeTest` compiles unchanged). The helper's result (a) is passed as a
  constructor parameter into the `SemanticCallFacts` built in `enrich`
  — a **new constructor arity; the existing two arities default
  `diagnostics` to `List.of()`**, keeping the public surface additive and
  the zero-test-edits sanction intact — and **threaded through the
  `analyzeSourceArchive` re-wrap as a constructor parameter** (same as
  `providerFacts`), never re-derived from the caller's original context,
  whose `sourceRoots` shape differs and would fabricate or drop codes. The
  Edge-1 skip predicate is pinned and shared verbatim by the extractor and
  the helper:
  `context.framework() == SPRING_BOOT &&
  Files.exists(context.applicationClasses()) &&
  context.includedSourceRoots().isEmpty()` — both sites use
  `includedSourceRoots()`. The two scanner instances (the extractor's, from
  `SootUpBaseGraphProvider.analyze` on `graphContext`, and the helper's,
  from `derived`) are intentionally unshared: the predicate's inputs are
  invariant across `graphContext`/`derived` (the copy changes only the
  classpath), so the skip decision cannot diverge and no cross-boundary
  scanner sharing is needed. Additionally the same codes are appended to
  the ServiceLoader `Scan` at the normalize site; that `isUnique` DEFINITE
  block fires only when service_loader edges exist (**opportunistic side
  effect**, stated as such — in the no-ServiceLoader scenario the new
  `SemanticCallFacts.diagnostics` field is the visibility mechanism).
  Edge-2 visibility rides on the abstaining facts' evidence in the enrich
  lane plus the same code in the new diagnostics field. The output surface
  (field + getter + `SemanticCallFactsJsonWriter.toDocument` key + re-wrap
  threading) is owned by Item 3's GREEN scope, with RED cell 5/10 asserting
  on `SemanticCallFacts.diagnostics`. New codes:
  `spring_data_backing_selection_out_of_scope` (Edge-2 abstain) and
  `spring_data_repository_sources_unavailable` (Edge-1 skip). Cell 10 and
  cell 5 assert on `SemanticCallFacts.diagnostics`, independent of
  service_loader edges existing.
- The runtime proxy is referenced only via the existing stable synthetic
  identity `spring-data-proxy:<FQCN>`; no JVM-generated proxy node.
- Fact ID: `spring-data-delegation-<slug>-<digest>` via a new
  `BoundedFrameworkModelIdentity.delegationFactId` (no legacy special case).
- Existing `spring-data-proxy` facts are unchanged.

### What this plan deliberately does NOT do

- No attestation-manifest parsing, mappingStatus, or digest binding (ADR-0057
  follow-ups). Edge identities stay on the `FQCN#method` grammar so the
  manifest symbol triple (`class`+`method`+descriptor) resolves against them
  later without redesign.
- No change to `interface_dispatch` classification or CHA edge emission.
- No synthesis of proxy nodes with JVM-generated names.

## Spikes (empirical, before RED_01 — scratch probes outside the tree)

1. **CHA pass coverage + Stmt line numbers**: compile a fixture with a
   repository-like interface (a) with and (b) without an implementor in the
   analysis view; print `graph.getCalls()` for the invoke site; establish
   Stmt→line extraction (position tags / LineNumberTable) and the lineless
   fallback. Also confirms whether CHA duplicates need deduping against
   `declared_invokes` edges (default-method case).
2. **javac constant-pool shape**: `invokeinterface` for an inherited interface
   method — confirm the constant pool names the receiver's static type
   declared at the call site. Boundary probes: single-char names, `$`-nested
   types, default methods calling other interface methods.
3. **Provider-fact identity**: confirm the `declaredCallId` namespace design
   against `collectCallReferences`/`callId` (distinct base prefix → no
   legacy-ID flip, no cross-namespace collision).
4. **Certainty policy guard** (cheap confirmation only — R1 resolved by
   round-1 review): `declared_invokes` and `delegated_implementation` /
   `spring_data_backing_selection` stay off the open-world lists.
5. **Backing targets + method sets**: pin the exact CRUD/reactive guaranteed
   method sets and reactive/fragment backing FQCNs by reading the spring-data
   jars on the analysis classpath (do not invent).

## Test matrix (equivalence classes, not examples)

Cells (each = one test method):

1. Derived query method: edges 1 + 2 (derived → PartTreeJpaQuery target).
2. CRUD method **declared** in the interface: edge 1 targets the
   app-declared interface method; edge 2 → SimpleJpaRepository.
3. CRUD method **inherited but not declared** (`save` from JpaRepository —
   round-1 finding 1 cell): enumerated by the guaranteed-method table; both
   edges present.
4. Repository fragment (custom interface + `Impl` suffix): fragment method
   delegation targets the app Impl class; certainty per ladder.
5. `repositoryBaseClass` via `@EnableJpaRepositories`: possible (in-scope
   base class) / unresolved + diagnostic (out-of-scope class).
6. Reactive repository variant: classified as repository_interface; reactive
   backing target, no SimpleJpaRepository.
7. Negative: NON-repository app interface invoked at an app call site — no
   `declared_invokes` edge, no synthetic delegation facts.
8. Pairwise: same call site keeps its `interface_dispatch` edge to dependency
   internals AND gains the `declared_invokes` edge; existing
   `spring-data-proxy` facts unchanged (count and certainty); provider IDs of
   pre-existing edges at the same site are byte-stable (no legacy-ID flip).
9. Negative pairwise: lookalike `@Repository` interface extending a
   non-Spring `Repository` (existing guard) — no new edges.
10. No-source-roots SPRING_BOOT compiled input: Edge-1 skip +
    `spring_data_repository_sources_unavailable` diagnostic.
11. Boundary probes (empirical, per AGENTS.md): empty/simple method names,
    `$`-nested repository interfaces, overload-colliding derived methods
    (signature-suffixed identity), exact-boundary receiver names.

Existing tests are not edited; new cells go into new test methods (the plan
sanctions zero edits to existing tests).

## TDD sequencing (RED/GREEN/EVIDENCE triples, gates after every item)

- **Item 0 (scanner substrate)**: RED_00 failing tests for the guaranteed
  CRUD/reactive method table, fragment detection, and
  `@EnableJpaRepositories` reading (scanner-level) → GREEN_00 → EVIDENCE_00.
- **Item 1 (Edge 1)**: RED_01 failing tests for cells 1–5, 7, 8, 11 and
  cell 10 as a **differential pair** — the same compiled fixture run twice,
  once without source roots (no `declared_invokes` edges) and once with a
  source-root control (the `declared_invokes` edge DOES appear). The pair's
  actual coverage: the control half fails at RED, pins
  emission-with-sources, and is the only Item-1 guard against over-skipping
  — inverted and always-skip predicates are caught by the **control half**
  (the expected edge goes missing); the no-source-roots half itself catches
  nothing beyond false edges, since with no source roots `beanCandidates`
  is empty whether or not the pass runs. The skip predicate firing at all
  (never-skip, weakened-conjunct defects) is verifiable only via the
  Item-3 diagnostic assertion. The `spring_data_repository_sources_unavailable` assertion on
  `SemanticCallFacts.diagnostics` belongs to RED_03/GREEN_03, where the
  carrier is built (cell 4's fragment method is an ordinary interface
  invoke — its `declared_invokes` edge is Item-1 behavior) →
  GREEN_01 body-scan pass + `declared_invokes` + `declaredCallId` (with the
  `collectCallReferences` count exclusion) → EVIDENCE_01. Gates:
  `make adapter-test` (floor 534 executions), `make java-lint` (0 new),
  `make evidence-validate`.
- **Item 2 (Edge 2 default)**: RED_02 failing tests for cells 1–4, 6, 9 →
  GREEN_02 delegation facts (possible) → EVIDENCE_02.
- **Item 3 (ladder + diagnostics surface)**: RED_03 failing tests for cell 5
  (promotion + abstain diagnostic) and the diagnostics carrier (cells 5/10
  asserting on `SemanticCallFacts.diagnostics`) → GREEN_03 ladder + codes +
  `SemanticCallFacts.diagnostics` field/getter/JsonWriter key/constructor
  arity + re-wrap threading + normalize parameter (blast radius in the
  commit message) → EVIDENCE_03.
- Resolution record: `docs/resolutions/RESOLUTION-41.md`.

## Risks / residuals

- R1 (RESOLVED, round 1): FrameworkModel definite is permitted for bounded
  metadata-backed facts; delegation facts keep non-open-world evidence kinds.
- R2: CHA duplicate edges vs the body-scan pass (dedup rule from Spike 1;
  default-method case tested in cell 8).
- R3 (RESOLVED by design, rounds 2–3): distinct `declaredCallId` namespace
  PLUS `collectCallReferences` count exclusion PLUS `callId` precedence over
  the application-origin branch prevents the legacy-ID flip; Spike 3 success
  criterion is byte-stable pre-existing `invokes` IDs in the default-method
  overlap.
- R4: the ServiceLoader `isUnique` DEFINITE block (scan-global per AGENTS.md)
  fires only when service_loader edges exist — opportunistic; the always-on
  visibility is the new `SemanticCallFacts.diagnostics` field. Commit
  messages state whichever blast radius applies.
- R5: reactive/fragment exact backing FQCNs and CRUD method sets verified
  against dependency jars in Spike 5.
- R6 (rounds 2–4): diagnostics surface is a new `SemanticCallFacts.diagnostics`
  field, computed once in `enrich` by a context-derived helper, threaded
  through the `analyzeSourceArchive` re-wrap, deterministically sorted,
  additive under schemaVersion 0.1.0; Edge-2 visibility rides on the
  abstaining facts' evidence in the enrich lane.

## Confidence score

**0.86 (review rounds 1–9 findings addressed; implementable as planned).**
- High (≥0.9): Edge 1 emission path (body-scan pass, declared-signature
  access, POSSIBLE certainty, distinct ID namespace), test harness shape,
  negative cells, diagnostic channel (PLAN-42 precedent).
- Medium (0.65–0.75): scanner substrate size (Item 0 is real work: CRUD
  table, reactive contracts, fragments, `@EnableJpaRepositories` reading);
  Edge-2 default facts.
- Lower (0.55): the `definite` promotion cell (mechanism grounded, but the
  "unique selection" proof conditions must be pinned exactly during Item 3)
  and reactive/fragment exact targets (R5).

## Review history

- Round 1 (full, pre-implementation): 9 findings (2 HIGH, 4 MEDIUM, 2 LOW,
  1 NIT); all addressed in v2 — CRUD scanner premise corrected into Item 0,
  diagnostic channel specified, ID namespace split, line-number spike folded
  in, reactive/fragment scanner additions enumerated, R1 resolved from
  precedent, skip-diagnostic asymmetry resolved, typo fixed.
- Round 2 (delta on v2): 5 findings (2 HIGH, 2 MEDIUM, 1 LOW, 1 NIT folded);
  all addressed in v3 — `collectCallReferences` count exclusion added to the
  ID-namespace cure, diagnostic channel made unconditional, reactive FQCN
  enumeration deferred to Spike 5, per-Boot-version guaranteed-method table,
  cell 4 moved into Item 1.
- Round 3 (delta on v3): 4 addressable findings (1 HIGH, 3 MEDIUM) + 2
  editorial nits; addressed in v4 — new `SemanticCallFacts.diagnostics`
  surface named as the visibility mechanism (Edge-1 skip re-derived from
  context at the surface site), normalize-site scope corrected to Edge 1 +
  isUnique side effect, `callId` precedence over the origin branch pinned,
  guaranteed-method table replaces the findById injection with the
  version-unparsable cell pinned, status line and risk ordering fixed.
- Round 5 (delta on v4): 5 findings (1 HIGH, 3 MEDIUM, 1 LOW); addressed in
  v5 — diagnostics computed once in `enrich` and threaded through the
  source-archive re-wrap (never re-derived from the caller context),
  isUnique side effect reworded as opportunistic, helper constructs its own
  scanner at the surface site (no enrich API change), skip predicate pinned
  to `includedSourceRoots()` + `Files.exists(applicationClasses())`,
  deterministic ordering and schemaVersion additivity stated.
- Round 6 (delta on v5): 5 findings (2 MEDIUM, 3 LOW); addressed in v6 —
  normalize-site transport pinned (helper result passed as a new
  `ProviderFactNormalizer.normalize` parameter), constructor arity
  strategy pinned (new arity, existing two default to `List.of()`),
  correct mirror cited (`BoundedFrameworkModel.enrich:731`) with scanner
  cost/consistency facts recorded, cross-context scanner invariance
  argument stated, diagnostics output surface assigned to Item 3.
- Round 7 (delta on v6): 2 findings (1 MEDIUM, 1 LOW); addressed in v7 —
  overload-preserving 4-arg `normalize` addition pinned (existing 2-/3-arg
  overloads delegate with `List.of()`), cell 10 split between Item 1 (skip
  behavior) and Item 3 (diagnostics assertion).
- Round 8 (delta on v7): 1 finding (1 MEDIUM); addressed in v8/v9 — cell
  10's Item-1 test made a differential pair whose coverage is stated
  honestly: control half fails at RED (emission-with-sources) and at GREEN
  catches inverted/always-skip predicates; the no-source-roots half pins
  no-false-edges; never-skip and weakened-conjunct defects remain
  verifiable only via the Item-3 diagnostic assertion.
- Round 9 (delta on v8): 1 finding (1 LOW); addressed in v9/v10 —
  inverted/always-skip detection re-attributed to the control half (the
  no-source-roots half catches nothing beyond false edges).
