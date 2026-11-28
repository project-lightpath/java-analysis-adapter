# PLAN-84 — New entrypoint kinds for CVE reachability

Issue: project-lightpath/java-analysis-adapter#84 (umbrella decision point).
Driver: #71 (advisory-driven visibility — "is this class reachable from
production code?").
Branch: `issue-84-entrypoint-kinds` from `origin/main` @ `3e0f3d0`.

## 0. Preflight evidence (executed 2026-11-28, probe outside commits)

A throwaway JUnit probe (`EntrypointProbeTest`, deleted before any commit;
transcript in the session record) verified the extraction machinery against
the new shapes:

- `MethodInfo.parameters()` and `annotationBlock()` carry **raw source
  text** (the METHOD regex runs on `declarationMaskedSource()`, groups are
  sliced from `file.source()`), so a parameter-position annotation with
  arguments — `@Observes(during = TransactionPhase.IN_PROGRESS) Object
  event` — survives intact in `parameters()`.
- `canonicalAnnotation(file, block, simpleName, fqns...)` resolves both
  method-position (`@Scheduled` via `import io.quarkus.scheduler.Scheduled`)
  and parameter-position (`@Observes` via `import
  jakarta.enterprise.event.Observes`) occurrences; simple-name lookalikes
  without a canonical import resolve to false (the #66 guard machinery,
  already mutation-pinned by the lookalike families).
- Main-method shapes are distinguishable: `annotationBlock` ends with the
  modifier+return-type prefix (`public static void `), `parameters()`
  carries the raw parameter text, `returnType()` is `void`.
- `Quarkus.run(` is present in `structuralSource()`; the runner check must
  run on the literal-masked source so a string literal cannot fabricate a
  runner usage.
- No listener/scheduler registration metadata exists anywhere in the
  bounded contracts: the only `metadataObserved` consumers are
  `spring-web-annotations`/`quarkus-rest-annotations`,
  `spring-mvc-handler-mappings`/`quarkus-rest-registration`,
  `spring-bean-annotations`, `spring-data-repository-contract`,
  `quarkus-generated-source-root`, and the #61 binding metadata rows.
  Message-listener endpoints and scheduler registrations are runtime-built
  container state, not captured build-time artifacts — so **no new family
  can ever reach DEFINITE under the current contract**; POSSIBLE is the
  ceiling, exactly like routes today (route facts never emit DEFINITE:
  presence of registration metadata is not candidate-level evidence).

## 1. The umbrella decision (issue #84 acceptance item 1)

**In scope for #71's reachability answer — all three kinds, all eight
mechanisms, delivered in this PR:**

| Kind | Mechanism | Canonical vocabulary | Framework |
|---|---|---|---|
| message listener | `@KafkaListener` | `org.springframework.kafka.annotation.KafkaListener` | Spring Boot |
| message listener | `@RabbitListener` | `org.springframework.amqp.rabbit.annotation.RabbitListener` | Spring Boot |
| message listener | `@JmsListener` | `org.springframework.jms.annotation.JmsListener` | Spring Boot |
| message listener | `@EventListener` | `org.springframework.context.event.EventListener` | Spring Boot |
| message listener | `@Observes` (parameter position) | `jakarta.enterprise.event.Observes`, `javax.enterprise.event.Observes` | Quarkus |
| scheduled task | `@Scheduled` | `org.springframework.scheduling.annotation.Scheduled` | Spring Boot |
| scheduled task | `@Scheduled` | `io.quarkus.scheduler.Scheduled` | Quarkus |
| main method | plain `public static void main` + Quarkus runner variant | JLS shape + `Quarkus.run(` usage | both |

**Explicitly out, with reasons (recorded in RESOLUTION-84 and on #84):**

- EJB `@MessageDriven` beans, JCA activation specs: outside the two
  checked-in framework contracts (the bounded model recognizes only the
  Quarkus and Spring Boot vocabularies; no bounded fixture exercises them).
- Other broker/listener annotations (`@StreamListener`,
  `@ServiceActivator`, `@SqsListener`, `@RabbitHandler`, …): not in the
  issue's enumerated candidate set. The mechanism table makes each a
  one-entry extension when an owner asks; extending preemptively would add
  vocabulary no fixture pins.
- Non-Spring/non-Quarkus schedulers (Quartz, EJB `@Schedule`): same
  contract-boundedness reason.
- DEFINITE promotion for any family: no registration metadata exists
  (§0); the ladder ceiling is documented, not an out-of-scope kind.

**Why one PR instead of per-family issues (pressure-test target):** the
issue proposes "each family may need its own issue/plan once triaged".
Triage outcome: all three families share one homogeneous seam —
declaration-shape extraction in a `Framework*Scanning` class +
`framework_entrypoint` fact emission in a `BoundedFrameworkModel*Facts`
layer, following the route/lifecycle precedents end-to-end. No family
needs registration-metadata machinery, javac-seam migration, or its own
certainty policy; each mechanism is one table entry plus cells. Splitting
would buy three review cycles for one table-driven change. The plan review
must attack this: if any family is found to need its own machinery, it
gets spun out to a per-family issue before RED.

## 2. Design

### 2.1 Extraction (scanner side)

New `FrameworkEntrypointScanning` (final class, static methods, the
`FrameworkRouteScanning`/`FrameworkBeanScanning` pattern) with three
entry points surfaced on `SourceMetadataScanner`:

- `messageListenerMethods(framework)` — Spring: method-position canonical
  annotations from the table in §1 on methods of bean-declared types;
  Quarkus: parameter-position canonical `@Observes` on methods of CDI
  bean-declared types.
- `scheduledTaskMethods(framework)` — canonical `@Scheduled` (per-framework
  FQN table) on methods of bean-declared types.
- `mainMethods()` — `public static void main` with a `String[]`-shaped
  single parameter (bounded shape set: `String[] a`, `String a[]`,
  `String... a`, `java.lang.String[]`/`...` variants, optional `final`),
  any declaring type; `quarkusMain` flag when the literal-masked method
  body contains a `Quarkus.run(` receiver call.

Bean-declaration requirement (the route/lifecycle precedent —
`classIsRouteable` requires `@RestController`; `springLifecycleMethods`
requires `springComponent || registeredBeanTypes`): Spring families
require `springComponent(file, declaringType) ||
registeredSpringBeanTypes().contains(declaringType)`; Quarkus families
require `cdiBean` on the declaring type's annotation block. A listener or
scheduled method on a non-registered type is not framework-invoked;
emitting an edge for it would fabricate reachability. `main` has no bean
requirement (static JVM entrypoint).

One record in `SourceMetadataScannerState`:

```java
record FrameworkEntrypointMethod(
    SourceFile file, MethodInfo method, String declaringType,
    String kind, /* message_listener | scheduled_task | main */
    String mechanism, List<String> symbols) {}
```

Flag-independent by construction (textual extraction + canonical import
resolution), like the lifecycle family; no `flpath.java.symbolindex`
gating on the extraction itself.

### 2.2 Fact emission (model side)

New layer `BoundedFrameworkModelEntrypointFacts extends
BoundedFrameworkModelSpringFacts`; `BoundedFrameworkModel` extends it.
One method `entrypointFacts(context, scanner)`, called from `enrich()` for
BOTH frameworks (families dispatch internally by framework, like
`routeFacts`).

- Relationship type: **`framework_entrypoint`** (reused — the root
  treatment in `attachExactProviderIdentities`/`links` applies unchanged).
- Certainty ladder per family:

| Family | UNRESOLVED | POSSIBLE | DEFINITE |
|---|---|---|---|
| Spring listeners | listener metadata id not observed | metadata observed + canonical annotation + bean-declared type | never (no registration metadata, §0) |
| CDI observers | observer metadata id not observed | observed + canonical + bean-declared | never |
| Spring @Scheduled | scheduling metadata id not observed | observed + canonical + bean-declared | never |
| Quarkus @Scheduled | scheduler metadata id not observed | observed + canonical + bean-declared | never |
| main (plain/Quarkus) | — | always (bounded declared candidate; launcher selection not captured) | never |

  New source-annotation metadata ids (the `spring-web-annotations`
  pattern): `spring-listener-annotations`, `spring-scheduling-annotations`,
  `quarkus-cdi-observer-annotations`, `quarkus-scheduler-annotations`.
  Main needs no metadata family: the declaration is Java-language
  evidence, not framework vocabulary; the missing input is deployment
  selection, stated in the limitation.
- Caller/rule/evidence per mechanism (route-fact shape): caller
  `external Kafka listener` / `external RabbitMQ listener` /
  `external JMS listener` / `Spring ApplicationContext event` /
  `CDI event dispatcher` / `Spring task scheduler` / `Quarkus scheduler` /
  `external JVM launcher` / `Quarkus application launcher`; rule
  `spring-message-listener` / `cdi-observer-method` /
  `spring-scheduled-task` / `quarkus-scheduled-task` /
  `jvm-main-entrypoint` / `quarkus-main-runner`; evidence kinds
  `listener_annotation`, `observer_parameter_annotation`,
  `scheduling_annotation`, `main_declaration`, `quarkus_runner_usage`,
  with attributes mirroring routeFacts (`annotationMetadata`,
  `registrationMetadataObserved=false`, `mechanism`).
- Fact ids via `entrypointFactId` in `BoundedFrameworkModelIdentity`
  (identity = framework, kind, mechanism, declaringType, displayPath,
  method name/params/returnType/start; digest suffix) — the
  `lifecycleFactId` pattern, no legacy fixture ids.

### 2.3 Boundaries probed

- Parameter-argument annotations survive raw in `parameters()` (§0).
- Modifier checks run on `annotationBlock` (masked for matching, raw text
  sliced) — `public`/`static` detection is the existing `hasModifier`
  path.
- `Quarkus.run(` matching on the literal-masked body (string literals
  masked), receiver-word boundary `(?<![\w$.])Quarkus\s*\.\s*run\s*\(`
  plus the qualified `io.quarkus.runtime.Quarkus.run` spelling.
- Main parameter shape check normalizes whitespace and `final`, then
  matches the bounded set; wrong shapes (`String a`, no params, non-void
  return, missing modifiers) decline (no fact — a main the JVM cannot
  launch is not an entrypoint).

### 2.4 Contract wiring (the metadata ids must be documented, not invented)

`metadataObserved` reads `AnalysisContext.frameworkMetadata`, which the
CALLER supplies (the spike contracts record the two pinned fixtures;
production contexts for #71 are built by VPE from their own sources —
`AnalysisContextLoaderTest` pins that flow). Two honest facts constrain
the wiring:

- The pinned fixtures contain **none** of the new listener/scheduler
  vocabularies (verified: `spring-boot-example-application` has only
  `SpringApplication.run(Application.class, args)` in `Application#main`;
  `spike/java21-quarkus` has only REST/reflection/ServiceLoader shapes).
  So the fixture contexts in `spike/contracts/analysis-context.json` must
  NOT declare the new ids as observed — that would fabricate observation.
- The Spring fixture's `Application#main` means the main family fires
  end-to-end on real fixture source with no metadata dependency.

Plan: add four `metadataSources` rows to
`spike/contracts/framework-model.json` (`spring-listener-annotations`,
`spring-scheduling-annotations` under spring-boot;
`quarkus-cdi-observer-annotations`, `quarkus-scheduler-annotations` under
quarkus), kind `source_annotations`, patterns = the §1 canonical FQNs,
`supports: ["framework_entrypoint"]`, with per-fixture status rows in
`analysis-context.json` recording that the pinned fixtures do not observe
them (the not-observed direction; an abstention note, mirroring the
`spring-bean-definition-registry` not_captured row shape). No new rows in
`expected-call-facts.json`: the validator requires enrichment and
semantic-facts observations to match expected ids exactly
(`validate_spike.py:223,254`), the new kinds have no pinned fixture case,
and regenerating those observations is out of scope. `make
evidence-validate` must stay green on the amended contracts (the
validator only requires non-empty metadataSources and valid
rules/edge types). The #71 caller-facing note — "declare these ids to
reach POSSIBLE for listener/scheduler families" — goes in RESOLUTION-84
and the #84 close-out comment.

## 3. Cell matrix (equivalence classes, not examples)

New test class `FrameworkEntrypointFactsTest` (fact-level, the
`JavaAnalysisAdapterTest` fixture-builder style; separate file to keep
GodClass pressure off the existing suite).

Listeners: L1 canonical @KafkaListener + bean + metadata → POSSIBLE;
L2 metadata absent → UNRESOLVED; L3 lookalike (user-defined
same-simple-name, no canonical import) → no fact, including on a
@Component type; L4 canonical annotation on non-bean type → no fact;
L5 @RabbitListener/@JmsListener/@EventListener mechanism coverage; L6
@EventListener on a @Bean-registered type → fact; L7 fully-qualified
spelling without import → fact; L8 wildcard import
(`import org.springframework.kafka.annotation.*;`) → no fact (on-demand
import never resolves a canonical occurrence — `importedTypes` matches
single-type imports only; documented abstention consistent with
@RestController today); L9 private `@KafkaListener` on a registered bean
→ fact emitted (characterization cell pinning the documented
over-approximation, §2.5); L10 repeatable container spelling
`@KafkaListeners({@KafkaListener(...)})` → fact (nested occurrence in
the raw annotation block).
Quarkus: Q1 @Observes (jakarta) + @ApplicationScoped + metadata →
POSSIBLE; Q2 @Observes with arguments (`during = ...`) → fact (parameter
fidelity); Q3 user-defined lookalike @Observes → no fact; Q4 javax
spelling → fact; Q5 @Observes on non-bean → no fact; Q6 Quarkus
@Scheduled (io.quarkus.scheduler) + metadata → POSSIBLE; Q7 user-defined
lookalike @Scheduled → no fact; Q8 cross-framework decline: Spring-canonical
`@Scheduled` (org.springframework…) under a Quarkus scan → no
quarkus-scheduled-task fact (and the symmetric Spring-side cell).
Spring scheduled: S1 canonical + bean + metadata → POSSIBLE; S2 metadata
absent → UNRESOLVED; S3 lookalike → none; S4 non-bean → none.
Main: M1 `public static void main(String[] args)` → POSSIBLE,
caller `external JVM launcher`; M2 shape negatives (static-but-not-public,
public-but-not-static, `String a`, non-void return) → no fact — bounded
to the classic JVM launch protocol; JEP 512 (JDK 25+) relaxed forms
(instance main, non-public, no-arg) are a documented residual, not
modeled; M3 boundary variants (`String... a`, `java.lang.String[] a`,
`final String[] a`, `String a[]`, `final @SuppressWarnings("x") String[]
a` — parameter-position annotations must be stripped by the shape
normalization) → fact; M4 Quarkus main with `Quarkus.run(` in body →
`quarkus-main-runner` fact; M5 `Quarkus.run(` only inside a string
literal → plain main fact only; M6 two mains in one scan → two distinct
bounded candidates.
Interactions: I1 @KafkaListener + @Scheduled on one method → two facts
(kind separation); I2 listener method that is also a @GetMapping route →
route fact and entrypoint fact coexist; I3 main inside a @Component class
→ bean families and main fact independent.
Adapter: A1 `analyze()` surfaces the new facts as `framework_entrypoint`
with the certainty above (pass-through already proven by routes; pinned
once). A2 the Spring fixture shape end-to-end: a `SpringApplication.run`
main → plain jvm-main-entrypoint fact (no Quarkus runner).

### 3.1 Documented over-approximations and residuals

- On-demand imports (`import …kafka.annotation.*`) do not resolve a
  canonical occurrence: abstention, pinned by L8. Manifestations: every
  mechanism in §1 (same `importedTypes` machinery); same behavior as the
  existing route/bean/proxy families — parity, not a new weakness.
- Listener/scheduled method modifiers are not filtered: private, static,
  or interface-default annotated methods on registered bean types still
  emit POSSIBLE facts (L9 pins it). Rationale: the container processors
  (@KafkaListener/@Scheduled post-processors) accept non-public methods
  via reflection in at least the common configurations; modeling the
  per-processor policy is out of bounded scope, and the failure direction
  is an over-broad POSSIBLE, never a fabricated DEFINITE.
- `cdiBean` is a simple-name gate (TextLayer13:133-137): a user-defined
  same-simple-name scope lookalike satisfies the Quarkus bean
  requirement. Documented residual shared with the existing
  injection/proxy Quarkus paths (FrameworkLayer00:180); Q9 cell
  (lookalike `@ApplicationScoped` + canonical `@Observes` → fact) pins
  the current behavior instead of leaving it accidental.
- JEP 512 relaxed main forms are not modeled (M2): abstention.
- Main in a nested type is emitted (JVM can launch `Outer$Inner`); no
  top-level filter, unlike routes.

## 4. TDD sequencing and gates

- RED_84: all cells above failing (plus, if unavoidable, behavior-neutral
  compile stubs — scanner facade methods returning `List.of()` —
  documented in the commit message).
- GREEN_84: minimal implementation (§2).
- EVIDENCE_84: mutation receipts — (a) drop the canonical guard on
  @KafkaListener ⇒ L3; (b) drop the bean-declared requirement ⇒ L4/Q5;
  (c) drop the main modifier/shape checks ⇒ M2; (d) force the metadata
  gate to always-observed ⇒ L2/S2; (e) runner check on structural instead
  of literal-masked source ⇒ M5; (f) slice `parameters()` from the masked
  source ⇒ Q2. Gates after every item: `make adapter-test` (floor read
  off main: 801 executions; raised by the new cell count),
  `make java-lint` (0 new findings), `make evidence-validate`.
- Existing files touched (honest list, so the #64 lint cross-check can be
  scoped): `BoundedFrameworkModel.java` (extends clause + one
  `enrich()` call — `enrich` itself is already `final` at :758 and is NOT
  edited beyond adding the `entrypointFacts` call), `SourceMetadataScanner.java`
  (three facade methods), `SourceMetadataScannerState.java` (one record),
  `Makefile` (SurefireTestCounter floor), `spike/contracts/framework-model.json`
  + `analysis-context.json` (§2.4 rows). All other production changes are
  new files (`FrameworkEntrypointScanning`,
  `BoundedFrameworkModelEntrypointFacts`, `FrameworkEntrypointFactsTest`).
  `make java-lint` runs on the local JDK; if PMD measures any MODIFIED
  file differently than CI's JDK 17 (the #64 GodClass-measurement trap),
  verify on helios08 with `~/jdk17` and a scratch checkout before
  pushing.
- Zero edits to existing tests; Makefile floor update only.
- Post-push: `@coderabbitai full review`; reply red-first with mutation
  checks; no manual thread resolution; no merge (user decides).
- RESOLUTION-84.md records the decision table, ladder, residuals, and
  review arc.
