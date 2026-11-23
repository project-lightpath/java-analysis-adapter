# PLAN-41 — Reverse call graph through Spring Data repository interface methods

Issue: project-lightpath/java-analysis-adapter#41
Branch: `flpath-5079-repository-reverse-edges` (cut from `origin/main` @ 0297828)
Status: draft v1 (pre-implementation, pending adversarial review)

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
  because CHA resolves `invokeinterface` to concrete implementors (or drops the
  edge entirely when no implementor is in view), never to the abstract
  interface method.

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

- Current pipeline iterates `graph.getCalls()` (CHA output). CHA never
  targets the abstract interface method, so a **dedicated body-scanning pass**
  is required: for every application method body (the extractor already has
  `applicationMethods`), scan statements for `JInterfaceInvokeExpr` whose
  **declared** `MethodSignature` declaring class is an app-declared Spring
  Data repository interface, and emit
  `CallEdge(caller, declaredCallee, line, sourcePath, POSSIBLE,
  "declared_invokes", TargetContext.application(), receiver)`.
- `declaredCallee` = `MethodSymbol.fromSootSignature(declaredSignature)` —
  for `accountRepository.save(account)` the constant-pool invoke names the
  compile-time receiver type (`AccountRepository`), including methods
  inherited from `JpaRepository` (verified shape; empirical probe in Spike 2).
- Repository-interface set: built once from
  `scanner.beanCandidates(SPRING_BOOT)` filtered to kind
  `repository_interface` (typeName set), passed into the extractor alongside
  `applicationClasses`. Guarded by framework == SPRING_BOOT. If the analysis
  context did not observe `spring-data-repository-contract` metadata, skip the
  pass and emit a scan-global diagnostic (incomplete scan must be visible).
- The existing CHA `interface_dispatch` edges to dependency internals are
  retained unchanged (pairwise interaction is a required test cell).
- New bounded `relationshipType` value: `declared_invokes`. It must NOT be
  added to `DynamicCertaintyPolicy.isOpenWorldProviderRelationship` (it is a
  source/bytecode-declared fact, not open-world). Provider-fact ID: flow
  through the existing `ProviderFactNormalizer` edge pipeline; identity must
  incorporate the relationship type so a `declared_invokes` edge can never
  collide with an `invokes`/`interface_dispatch` edge at the same
  caller/callee/line (Spike 3 confirms the exact insertion point).
- Certainty: always `POSSIBLE` at emission (declared-target fact). Limitation
  text: declared target; runtime dispatch resolution is carried by the
  spring-data framework facts.

### Edge 2 — framework-model delegation ladder

Emission site: `BoundedFrameworkModelSpringFacts.springRepositoryFacts`
(+ ID helper in `BoundedFrameworkModelIdentity`).

- For each repository method already iterated there, emit an additional
  framework fact: caller = `<repository FQCN>#<methodIdentity>`, kind
  `delegated_implementation`, feature `spring-data-delegation`, target =
  backing implementation:
  - **CRUD methods** (guaranteed contract methods — the existing `guaranteed`
    flag and the canonical `CrudRepository`/`JpaRepository` method set the
    scanner already models): target
    `org.springframework.data.jpa.repository.support.SimpleJpaRepository#<method>`
    — an ordinary `implements`-backed class in the spring-data jar on the
    analysis classpath.
  - **Derived queries** (declared, non-guaranteed): target
    `org.springframework.data.jpa.repository.query.PartTreeJpaQuery#execute`
    (query-derivation machinery).
  - **Reactive variants** (contract from `org.springframework.data.repository.reactive.*`):
    reactive backing class (Spike 5 fixes the exact bounded target).
  - **Fragments** (custom interface + `Impl` suffix): the backing for fragment
    methods is the app-declared `Impl` class — edge 2 target is that class's
    method when it is in the application scope.
- Certainty ladder (FLATH-5075):
  - `definite` — config in scope, no override mechanisms present
    (no `@EnableJpaRepositories(repositoryBaseClass=…)`, no fragments, not
    reactive), unique backing class for the contract family.
  - `possible` — override mechanisms present but resolvable to a bounded
    candidate set (e.g. `repositoryBaseClass` names an in-scope class;
    fragment Impl present).
  - `unresolved` (abstain) + scan-global diagnostic — configuration references
    classes outside the analysis scope. NOTE: diagnostics are scan-global and
    block DEFINITE promotion for the whole scan (AGENTS.md); commit message
    must state this blast radius.
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

1. **CHA pass coverage**: compile a fixture with a repository-like interface
   (a) with and (b) without an implementor in the analysis view; print
   `graph.getCalls()` for the invoke site. Confirms whether the body-scan pass
   is the only reliable emission path (expected: yes) and whether CHA
   duplicates must be deduped against `declared_invokes` edges.
2. **javac constant-pool shape**: `invokeinterface` for an inherited interface
   method (`save` inherited from a parent interface) — confirm the constant
   pool names the receiver's static type declared at the call site, not the
   declaring parent interface. Boundary probes: single-char names, `$`-nested
   types, default methods calling other interface methods.
3. **Provider-fact identity**: trace `ProviderFactNormalizer` ID assignment
   (`legacyCallId`/`edgeIdentity`) to confirm where the relationship type
   enters the identity for the new edges without perturbing existing IDs.
4. **Certainty policy guard**: read `DynamicCertaintyPolicy` +
   `DynamicCertaintyGuardTest` to confirm (a) `declared_invokes` is not
   open-world, (b) under what conditions a FrameworkModel fact may carry
   `definite` (the guard test names `frameworkDynamicEvidenceCannotBePromoted`—
   the delegation ladder's `definite` cell must be metadata-backed selection,
   not dynamic evidence, or the definite cell is out of scope and stays
   `possible` + documented).
5. **Reactive/fragment backing targets**: pin the exact bounded FQCNs for
   reactive repository backing and fragment composition from the spring-data
   jars on the analysis classpath (do not invent; verify by reading the
   dependency jars).

## Test matrix (equivalence classes, not examples)

Cells (each = one test method; derived from the JLS-irrelevant but
framework-equivalent classes above):

1. Derived query method: edges 1 + 2 (derived → PartTreeJpaQuery target).
2. CRUD method inherited from JpaRepository (`save`, `findById`): edge 1
   targets the app-declared interface method; edge 2 → SimpleJpaRepository.
3. Repository fragment (custom interface + `Impl` suffix): fragment method
   delegation targets the app Impl class; certainty per ladder.
4. `repositoryBaseClass` via `@EnableJpaRepositories`: possible (in-scope
   base class) / unresolved + diagnostic (out-of-scope class).
5. Reactive repository variant: reactive backing target, no SimpleJpaRepository.
6. Negative: NON-repository app interface invoked at an app call site — no
   `declared_invokes` edge, no synthetic delegation facts.
7. Pairwise: same call site keeps its `interface_dispatch` edge to dependency
   internals AND gains the `declared_invokes` edge; existing
   `spring-data-proxy` facts unchanged (count and certainty).
8. Negative pairwise: lookalike `@Repository` interface extending a
   non-Spring `Repository` (existing guard) — no new edges.
9. Boundary probes (empirical, per AGENTS.md): empty/simple method names,
   `$`-nested repository interfaces, overload-colliding derived methods
   (signature-suffixed identity), exact-boundary receiver names.

Existing tests are not edited; new cells go into new test methods (the plan
sanctions zero edits to existing tests).

## TDD sequencing (RED/GREEN/EVIDENCE triples, gates after every item)

- **Item 1 (Edge 1)**: RED_01 failing tests for cells 1, 2, 6, 7, 9 (compiled
  fixture lane, `@Tag("sootup")`-style integration where needed) → GREEN_01
  minimal body-scan pass + `declared_invokes` → EVIDENCE_01 (characterization,
  refactor under green). Gates: `make adapter-test` (floor 534 executions),
  `make java-lint` (0 new), `make evidence-validate`.
- **Item 2 (Edge 2 default)**: RED_02 failing tests for cells 1–3, 5, 8 →
  GREEN_02 delegation facts (possible) → EVIDENCE_02.
- **Item 3 (ladder)**: RED_03 failing tests for cell 4 (promotion + abstain
  diagnostic) → GREEN_03 ladder + diagnostic → EVIDENCE_03 (documents the
  scan-global diagnostic blast radius).
- Resolution record: `docs/resolutions/RESOLUTION-41.md`.

## Risks / open cells for adversarial review

- R1: whether FrameworkModel facts can carry `definite` at all under
  `DynamicCertaintyPolicy` (Spike 4; if not, the definite cell degrades to
  `possible` + documented residual — a bounded, honest outcome).
- R2: CHA duplicate edges vs the body-scan pass (dedup rule needed).
- R3: provider-fact ID collision risk for same-site edges (Spike 3).
- R4: scan-global diagnostic blast radius (item 3) downgrades certainty for
  the whole scan — acceptable per AGENTS, must be in the commit message.
- R5: reactive/fragment exact backing FQCNs must be verified against the
  dependency jars, not assumed.

## Confidence score

**0.72 (implementable as planned with bounded risk).**
- High confidence (≥0.9): Edge 1 emission path (body-scan pass, declared
  signature availability in JInterfaceInvokeExpr, POSSIBLE certainty, ID
  pipeline reuse), test harness shape, negative cells.
- Medium (0.6–0.75): Edge 2 default-possible facts and CRUD/derived target
  selection (grounded in existing scanner model, but the CRUD method-set
  boundary needs the spike).
- Lower (0.5): the `definite` promotion cell (R1 may force degradation) and
  reactive/fragment exact targets (R5).
