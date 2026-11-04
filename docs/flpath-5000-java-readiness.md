# FLPATH-5000 Java framework readiness result

Status: **candidate assessment and implementation handoff ready**
Decision source: **ADR-001: Evidence-Driven Vulnerability Impact Prioritization**

## Decision boundary

The candidate set is **Eclipse JDT Language Server**, **SootUp**, and **CodeQL
for Java/Kotlin**. All three have evidence runs against the fixture contract and
the pinned FSI Spring Boot baseline. SootUp 3.0.1 now passes both Quarkus and
Spring Boot runtime-scope dependency analysis; CodeQL's database uses
`--build-mode=none` on macOS 27. This is a candidate evaluation, not a
production-provider commitment; the production adapter belongs to FLPATH-5002
after the matrix is reviewed.

The evaluation has two framework inputs. The primary local fixture targets Java
21 and Quarkus 3.40.1; it records production and test Maven profiles separately
and includes a generated source root. The external Spring Boot baseline targets
Spring Boot 2.7.18 and Java 17 and supplies MVC, `@Bean`, Spring Data, security,
transaction, and lifecycle cases. The analyzer run is separate from the
application toolchain: JDT LS 1.63.0 currently requires a Java 25 runtime,
while the Quarkus fixture is compiled with `--release 21`. Both framework and
toolchain versions are recorded as analysis context. The synthetic advisory and
expected call facts are deliberately versioned fixtures; they do not claim a
real vulnerability or exploitability result.

## Acceptance criteria

The machine-readable gate is [`spike/acceptance-criteria.json`](../spike/acceptance-criteria.json).
A candidate is selectable only when it can:

1. consume the exact build/profile/source context;
2. resolve direct reverse calls with stable symbols and source locations;
3. preserve definite/possible/unresolved certainty;
4. expose or explicitly abstain on framework entrypoints and dynamic features;
5. emit provenance-rich facts that can be normalized to `SemanticCallFacts`.

The gate also requires an operational setup and a closure estimate for every
conditional or failed criterion. General reflection/proxy resolution is not a
valid acceptance requirement unless the target is constrained by source or
configuration evidence.

## Candidate comparison

| Candidate | Evidence level | Strength | Current gaps | Rough closure |
| --- | --- | --- | --- | --- |
| JDT LS 1.63.0 | Empirical Java 21 fixture and FSI probes | Lowest integration risk; strong source/compiler facts | CDI/Spring wiring, framework entrypoints, ServiceLoader binding, reflection/proxy modeling, production adapter | **20–33 engineer-days** |
| SootUp 3.0.1 | Empirical CHA/RTA fixture run with full runtime dependency scope | Concrete bytecode dispatch, callback/provider implementors, generated classes; Quarkus runtime graph measured at 68 methods/59 calls and FSI graph at 6,224 methods/7,853 calls | Framework models, source/provenance adapter, dynamic features | **22–38 engineer-days** |
| CodeQL 2.27.4 / Java pack 9.3.4 | Empirical buildless database/query runs; production facts normalized from test-inclusive output | 64 Quarkus facts and 2,356 FSI facts, source locations, interface/callback/generated-source query evidence | `--build-mode=none`, query/fact adapter, framework models, process integration, dynamic features | **26–45 engineer-days** |

The estimates are rough (plus or minus 50 percent), assume one engineer who
knows Java/Maven/static analysis, and exclude the graph engine, controller,
endpoint mapping, runtime validation platform, and general dynamic-language
resolution. Full details and per-gap estimates are in
[`spike/candidate-matrix.json`](../spike/candidate-matrix.json).

## Framework inputs

Both framework inputs are required for candidate evidence. Quarkus is the Java
21 build-fidelity fixture; Spring Boot is the framework-entrypoint and container
baseline. The machine-readable identities are in
[`spike/capability-matrix.json`](../spike/capability-matrix.json), and the
Spring manifest is pinned in
[`spike/baselines/fsi-spring-boot.json`](../spike/baselines/fsi-spring-boot.json).

## Spring Boot baseline

The external baseline is [`lzspangler/spring-boot-example-application`](https://github.com/lzspangler/spring-boot-example-application),
pinned at commit `01a18a1b40e151ffcc1d7bef86bf335f87999750` and cloned beside
this repository. It targets Spring Boot 2.7.18/Java 17, contains 51 production
classes, and passed 121 tests on the authoritative run. It adds acceptance
coverage that the small Quarkus fixture cannot provide:

- Spring MVC HTTP entrypoints;
- constructor injection and `@Bean` methods;
- Spring Data JPA generated repository implementations;
- `@Transactional` and Spring Security framework proxies; and
- `CommandLineRunner` lifecycle callbacks.

JDT LS resolved ordinary source edges such as
`TransferController#transfer -> TransactionService#transfer` and
`TransactionService#transfer -> AuditService#log`. It returned no incoming
Java caller for `TransferController#transfer`, `SecurityConfig#securityFilterChain`,
or `DataSeeder#run`, demonstrating that HTTP routing, `@Bean` creation, and
Spring Boot lifecycle callbacks require framework-entrypoint modeling rather
than ordinary call hierarchy.

The baseline remains external and is not vendored. Its manifest and normalized
observations are in `spike/baselines/` and `spike/results/`.

## Measured SootUp and CodeQL evidence

The ignored raw runs are reproducible with `spike/scripts/run_static_analysis.py`
and are normalized into portable observations under `spike/results/`.

### SootUp 3.0.1

- Quarkus application-only CHA: **66 methods / 57 calls / 2.2 seconds**;
  RTA: **55 methods / 31 calls / 8 ms**.
- The full Quarkus runtime classpath now passes with **118 Maven runtime-scope
  entries**: CHA measured **68 methods / 59 calls / 3.6 seconds** and RTA
  measured **55 methods / 31 calls / 6 ms**.
- The earlier 222-entry failure was caused by the runner passing
  `-Dmdep.includeScope=runtime`, which Maven ignored; that classpath included
  Quarkus deployment/test artifacts. The runner now uses Maven's
  `-DincludeScope=runtime` property and records the corrected scope.
- CHA found the direct `ServerUserAuthService -> UserAuthPublicKey` edge, the
  generated mapper edge, a concrete callback implementation, and a concrete
  ServiceLoader implementor. CDI selection, framework entrypoints, reflection,
  and proxy targets remain possible or unresolved.
- FSI runtime-scope CHA completed with **104 dependency entries** in **8.5
  seconds** with **6,224 methods / 7,853 calls**; RTA measured **404 methods /
  494 calls**. Spring MVC, `@Bean`,
  lifecycle, and Spring Data generated implementation edges remain framework
  context rather than ordinary application callers.

### CodeQL 2.27.4

- The pinned CLI/bundle and `codeql/java-all` **9.3.4** pack created both Java
  databases with `--build-mode=none` and ran the checked-in reverse-call query.
- Quarkus produced **64 raw facts**, of which **52** are production and **12**
  are test facts. FSI produced **2,356 raw facts**, of which **582** are
  production and **1,774** are test facts. Normalization filters by source path
  and records the discarded test scope.
- The query found direct, interface, callback, ServiceLoader call-site, and
  generated-source facts. It did not create framework-entrypoint or dynamic
  target edges, so those remain explicit gaps/unresolved relationships.
- `--build-mode=none` is a host limitation: these results are source-extraction
  evidence, not proof that CodeQL consumed the exact compiled Maven dependency
  graph.

## Capability questions

JDT LS is expected to provide useful source/compiler-backed evidence for direct
calls, symbols, source ranges, and some Java dispatch. The following require
specific evidence rather than optimistic inference:

| Capability | Initial treatment | Why |
| --- | --- | --- |
| Direct calls | Existing support in the probe | Baseline for `SemanticCallFacts` |
| Interface/virtual dispatch | Existing source-level support; retain possible certainty | Target selection may be broader than one implementation |
| CDI/Spring injection | Configuration work or possible path | Container wiring is not a Java method call |
| Callbacks/lambdas | Existing source-level support; preserve possible certainty | `CompletionStage` and `invokedynamic` add indirection |
| Service loading | Configuration work / possible path | Provider binding lives in `META-INF/services` |
| Reflection/proxies | Confirmed gap for JDT LS alone | String targets and generated proxy classes need explicit modeling |
| Generated sources | Existing support when the source root is included | The profile must include and fingerprint generated roots |
| Maven profiles | Configuration work | Production/test scope must be separate in `AnalysisContext` |
| Spring/Quarkus framework entrypoints | Confirmed gap for source-only call hierarchy | HTTP routes, lifecycle callbacks, bean factories, and security filters need explicit framework models |

The checked-in `capability-matrix.json` is the source for case IDs,
classifications, expected outcomes, and required evidence. A case is not
production-capable until its run records provider/toolchain versions, source
and dependency fingerprints, profile, generated-source fingerprint,
configuration, command, coverage, and limitations.

### Framework enrichment handoff

The spike now defines two explicit handoff contracts:

- [`framework-model.json`](../spike/contracts/framework-model.json) describes
  Quarkus and Spring Boot metadata sources, synthetic edge types, resolution
  rules, and abstentions.
- [`analysis-context.json`](../spike/contracts/analysis-context.json) records
  the framework version, selected Maven profile, source/generated roots,
  dependency mode, metadata availability, scope, and provenance requirements.

Expected provider facts remain separate from synthetic framework facts in
[`expected-call-facts.json`](../spike/contracts/expected-call-facts.json).
The examples cover CDI/Spring injection, Quarkus and Spring routes, Arc and
Spring AOP proxy dispatch, Spring Data generated repositories, Quarkus generated
source, and Spring lifecycle callbacks. The contract keeps unique evidence
`definite`, bounded candidates `possible`, and missing/open-world behavior
`unresolved`.

For routes specifically, a framework fact asserts a registered route mapping;
it does not depend on an incoming Java caller from the provider graph. No
incoming reference therefore does not prove dead code or route absence. The
model should enumerate all bounded route mappings that could reach the target;
the runtime request determines which matching route is selected.

`make framework-enrichment` runs the spike-only bounded source metadata scan in
[`enrich_framework_facts.py`](../spike/scripts/enrich_framework_facts.py). This
demonstrates the enrichment layer emitting the contract facts; it does not mean
that JDT LS, SootUp, or CodeQL provide those framework edges natively. Arc,
Spring `ApplicationContext`, generated proxy, and runtime interceptor metadata
remain explicit follow-up inputs.

`make semantic-facts-spike` runs the bounded handoff proof in
[`assemble_semantic_facts.py`](../spike/scripts/assemble_semantic_facts.py).
Its output keeps SootUp provider observations and FrameworkModel facts in
separate relationship sets, records explicit enrichment links, and preserves
certainty without implementing the production adapter or mutating the provider
graph.

## Expected evidence path

```text
synthetic advisory
  -> candidate target symbols
  -> exact source/build context
  -> attested symbols
  -> SemanticCallFacts
  -> reverse caller graph
  -> shortest canonical witnesses
```

The fixture contains a representative path:

```text
TransferResource#transfer
  -> TransferService#transfer
  -> DefaultTransferService#transfer
  -> AuthGateway#authorize
  -> ServerUserAuthService#handleUserAuthRequestMessage
  -> UserAuthPublicKey#doAuth
```

`TransferResource` and `TransferService` also exercise CDI and interface
dispatch. The callback, service-loader, reflection, proxy, generated-source,
and profile cases are intentionally separate so an unresolved relationship
cannot be mistaken for a definite path.

## Observed probe result

JDT LS 1.63.0 imported the Maven project and resolved these incoming-call
relationships from the fixture:

- `ServerUserAuthService#handleUserAuthRequestMessage -> UserAuthPublicKey#doAuth`;
- `AuthGateway#authorize -> ServerUserAuthService#handleUserAuthRequestMessage`;
- `DefaultTransferService#transfer -> GeneratedTransferMapper#toResponse`;
- interface/callback and `ServiceLoader` call sites as source-level incoming
  calls.

`ReflectiveTarget#validate` returned no incoming call even though the fixture
invokes it through `Class.forName` and `Method.invoke`. This is a confirmed
JDT-LS-only gap and must remain unresolved/unknown in `SemanticCallFacts`.

The checked-in probe output is intentionally not committed because it contains
host paths. Re-run `make download-jdtls` followed by `make jdtls-probe` to
produce a local provenance record under `spike/runs/`.

## Handoff

- **FLPATH-5002:** implement the selected adapter only after provider capability,
  candidate decision, and abstention behavior are agreed; emit source-backed
  `SemanticCallFacts`.
- **FLPATH-5003:** attest advisory candidates against the exact dependency
  source/version and emit `SymbolMappingResult`.
- **FLPATH-5005:** convert this matrix into regression checks for accuracy,
  coverage, provenance, freshness, and abstention.
- **FLPATH-5012:** supply the durable `RepositoryAnalysisProfile` and
  fingerprinted `AnalysisContext` used by every run.

Before committing to a provider, add the required framework/context models and
compare normalized precision, coverage, setup cost, provenance completeness,
and unresolved-edge behavior against the JDT LS and FSI baseline results.

Runtime validation, Python, endpoint mapping, exhaustive paths, and
model-based prioritization remain outside this spike.
