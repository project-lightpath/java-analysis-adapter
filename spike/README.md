# FLPATH-5000 Java framework readiness spike

This spike implements the bounded work described by [FLPATH-5000](https://redhat.atlassian.net/browse/FLPATH-5000)
and ADR-001: acceptance criteria, reproducible Quarkus and Spring Boot fixture
inputs, analyzer evidence, a candidate matrix, machine-readable expected
relationships, and gap-closure estimates.

## Scope

The candidate set is **Eclipse JDT Language Server**, **SootUp**, and **CodeQL
for Java/Kotlin**. All three now have fixture evidence. SootUp 3.0.1 passes
runtime-scope dependency analysis for both Quarkus and Spring Boot; CodeQL 2.27.4
uses `--build-mode=none` on macOS 27 and filters test facts during normalization.
The provider versions are pinned, but the ADR does not commit the production
adapter to a candidate until the acceptance criteria and closure estimates are
reviewed.

The fixture covers:

- explicit calls and interface dispatch;
- CDI constructor injection and application-owned service calls;
- callback/lambda relationships;
- `ServiceLoader` binding;
- reflection and dynamic proxies;
- generated source input; and
- separate Maven production/test profiles.

The spike has two first-class framework inputs:

- **Quarkus 3.40.1 / Java 21:** the local REST fixture with CDI, generated
  sources, ServiceLoader, callbacks, reflection, proxies, and profile changes.
- **Spring Boot 2.7.18 / Java 17:** the pinned external
  [`lzspangler/spring-boot-example-application`](https://github.com/lzspangler/spring-boot-example-application)
  baseline with constructor injection, `@Bean` methods, Spring MVC entrypoints,
  Spring Data JPA repositories, transaction/security proxies, lifecycle
  callbacks, and OpenAPI generator configuration. It is cloned beside this
  repository and is not vendored here.

Both inputs are required for candidate evidence; Quarkus supplies the Java 21
target and Spring Boot supplies the framework-entrypoint/container cases the
small local fixture cannot provide.

The spike does **not** implement the production adapter, graph engine,
assessment controller, runtime validation, Python support, endpoint mapping,
exhaustive path enumeration, or policy evaluation.

`scripts/enrich_framework_facts.py` is a bounded proof-of-concept enrichment
runner, not a claim that JDT LS, SootUp, or CodeQL now resolve framework wiring
by themselves. It scans the selected source/context metadata and emits the
synthetic framework facts that a future adapter would merge with provider
facts. The remaining runtime/container limitations stay visible in its output.

## Layout

| Path | Purpose |
| --- | --- |
| `java21-quarkus/` | Minimal Quarkus REST fixture and build profiles |
| `baselines/` | Pinned external FSI Spring Boot baseline manifest |
| `contracts/` | Synthetic advisory, FrameworkModel, AnalysisContext, and expected call-fact contracts |
| `acceptance-criteria.json` | Candidate acceptance gate and decision rules |
| `candidate-matrix.json` | Candidate strengths, gaps, and closure estimates |
| `capability-matrix.json` | Versioned Quarkus/Spring capability cases and evidence requirements |
| `scripts/` | Validation, tool bootstrap, fixture run, analyzer runners, normalization, provenance, and JDT LS probe |
| `results/` | Normalized provider and framework-enrichment observations without host paths |

## Reproduce

The authoritative environment is Java 21. Maven 3.9.11 is bootstrapped into a
cache when it is not already installed.

```sh
# Validate contracts without downloading dependencies.
make spike-validate

# Apply the spike-only FrameworkModel to both source fixtures and emit
# synthetic framework facts.
make framework-enrichment

# Build and test the test profile.
make fixture-test

# Package the production profile and generated-source input.
make fixture-package

# Build and test the pinned Spring Boot baseline from its sibling clone.
make spring-test

# Download the pinned JDT LS distribution.
make download-jdtls

# Run the call-hierarchy probe against UserAuthPublicKey#doAuth. Set
# JDTLS_JAVA to the Java 25 executable required by JDT LS 1.63.0.
JDTLS_HOME="$HOME/.cache/project-lightpath/jdtls/1.63.0" \
JDTLS_JAVA="/path/to/jdk-25/bin/java" make jdtls-probe

# Probe a Spring framework entrypoint with the same provider.
JDTLS_HOME="$HOME/.cache/project-lightpath/jdtls/1.63.0" \
JDTLS_JAVA="/path/to/jdk-25/bin/java" \
python3 spike/scripts/probe_jdtls.py \
  --workspace ../spring-boot-example-application \
  --target-file ../spring-boot-example-application/src/main/java/com/redhat/lightwell/controller/TransferController.java \
  --method transfer

# Run the pinned SootUp CHA/RTA probe. The application-only Quarkus run is
# portable evidence; run the full dependency attempt separately to retain its
# known Java 21 front-end failure.
make sootup-quarkus
make sootup-quarkus-full
make sootup-spring

# Run the pinned CodeQL buildless database/query flow. Set CODEQL_BIN when the
# default cache location is not used; pass --replace through the Make target
# to recreate disposable databases.
make codeql-quarkus
make codeql-spring

# Convert ignored raw runs into portable checked-in observations.
make normalize-static-analysis

# Assemble the provider and framework evidence into a separated,
# provenance-preserving SemanticCallFacts handoff.
make semantic-facts-spike
```

Every run should be recorded with `record_run.py` or the JDT LS probe. It
captures the git revision, source fingerprints, Java/Maven/provider versions,
profile, configuration, command, exit status, and limitations. The fixture
target is Java 21; the analyzer runtime is a separate input. JDT LS 1.63.0
currently requires Java 25, so the matrix records both releases explicitly.

## Framework enrichment contract

`contracts/framework-model.json` defines the framework-specific metadata sources,
resolution rules, synthetic edge types, and abstentions for Quarkus and Spring
Boot. `contracts/analysis-context.json` defines the selected framework version,
Maven profile, source/generated roots, dependency classpath mode, metadata
availability, analysis scope, and provenance required for a fact.

`contracts/expected-call-facts.json` keeps provider/language facts in
`relationships` and adds framework-derived facts in `frameworkRelationships`.
The two sets must remain distinguishable. Unique metadata can produce
`definite`; bounded bean/proxy/generated candidates produce `possible`; missing
context and open-world reflection/proxy behavior stay `unresolved`.

Route facts assert framework route registration, not an incoming Java reference.
Therefore, a missing provider caller does not prove that a route is absent or
dead code, and an application reference does not prove that a route is
registered. The enrichment layer enumerates every bounded route that could
reach the target and records its HTTP method, path template, media types, and
header/parameter conditions; request-specific handler selection remains runtime
behavior.

`scripts/assemble_semantic_facts.py` is a spike-only handoff proof. It keeps
SootUp normalized observations and FrameworkModel facts in separate relationship
sets, links framework facts to contract relationships without replacing provider
evidence, and preserves `definite`, `possible`, and `unresolved`. It does not
add synthetic nodes to the SootUp graph or implement the production adapter.

## Acceptance and candidate decision

`acceptance-criteria.json` is the gate for every candidate across both framework
inputs. A candidate may be selected as a starting point only when direct reverse
calls, output normalization, and provenance pass; every framework/dynamic gap
must have an explicit abstention behavior, owner, and closure estimate.

`candidate-matrix.json` records the current result:

- **JDT LS:** preferred empirical starting point; roughly 20–33 engineer-days
  to close the bounded adapter, framework-entrypoint, and enrichment gaps.
- **SootUp 3.0.1:** preferred bytecode foundation; roughly 22–38 engineer-days
  after the probe, with full runtime-scope Quarkus and Spring evidence.
- **CodeQL 2.27.4 / Java pack 9.3.4:** credible measured query-engine
  alternative; roughly 26–45 engineer-days after the buildless database/query
  run, with compiled-build fidelity and operational integration still open.

Estimates are rough (plus or minus 50 percent), exclude the production graph
engine/controller, and do not promise general resolution of arbitrary dynamic
Java behavior.

## Current decision boundary

SootUp is the preferred technical foundation for the production adapter because
its runtime-scope CHA/RTA runs pass on both framework inputs and expose concrete
callback/provider and generated-class edges. JDT LS remains the
source/compiler-backed fallback for direct calls and Java symbols, while CodeQL
supplies measured source facts and production/test scope counts. CDI/Spring
container wiring, framework-owned entrypoints, `ServiceLoader`, reflection,
dynamic proxies, and build-profile fidelity still require explicit evidence. The
matrix classifies them as existing support, configuration work, confirmed gap,
or unverified; unresolved relationships are never promoted to definite paths.

The handoff targets are:

- **FLPATH-5002:** consume the selected provider and emit `SemanticCallFacts`;
- **FLPATH-5003:** attest advisory symbols in the exact dependency context;
- **FLPATH-5005:** turn this matrix into regression checks; and
- **FLPATH-5012:** provide the durable `AnalysisContext` and profile inputs.
