# RESOLUTION-84 — New entrypoint kinds for CVE reachability

Issue: project-lightpath/java-analysis-adapter#84 (umbrella decision point).
Driver: #71 (advisory-driven visibility / CVE reachability).
Branch: `issue-84-entrypoint-kinds` from `origin/main` @ `3e0f3d0`.
Commits: PLAN_84 `dec8b77` (+ rounds 1-3 amendments through `d346a76`),
RED_84 `7d6eb21`, GREEN_84 `8d27351`, EVIDENCE_84 (this commit).

## The decision (issue acceptance item 1)

**In scope for #71's reachability answer — all three kinds, all eight
mechanisms, delivered in this PR** (the issue's "each family may need its
own issue" proposal was triaged out: every family is a table entry over
the identical canonical-annotation + bean-gate + route-fact-emission
seam; none needs registration-metadata machinery or a javac-seam
migration, so a split would buy three review cycles for one
table-driven change — plan review round 1 pressure-tested and upheld
this):

| Kind | Mechanism | Canonical vocabulary | Framework |
|---|---|---|---|
| message_listener | @KafkaListener | org.springframework.kafka.annotation.KafkaListener | Spring Boot |
| message_listener | @RabbitListener | org.springframework.amqp.rabbit.annotation.RabbitListener | Spring Boot |
| message_listener | @JmsListener | org.springframework.jms.annotation.JmsListener | Spring Boot |
| message_listener | @EventListener | org.springframework.context.event.EventListener | Spring Boot |
| message_listener | @Observes (parameter) | jakarta/javax.enterprise.event.Observes | Quarkus |
| scheduled_task | @Scheduled | org.springframework.scheduling.annotation.Scheduled | Spring Boot |
| scheduled_task | @Scheduled | io.quarkus.scheduler.Scheduled | Quarkus |
| main | plain + Quarkus runner | JLS classic launch shape + Quarkus.run usage | both |

**Out, with reasons:** EJB @MessageDriven/JCA (outside the two checked-in
framework contracts); other broker annotations (@StreamListener,
@ServiceActivator, @SqsListener, @RabbitHandler — not in the issue's
enumerated candidate set; one table entry each when an owner asks);
Quartz/EJB @Schedule (same); non-classic JEP 512 main forms (abstention).

**Labeling choice:** @EventListener keeps kind `message_listener` per the
issue's grouping; it is an in-process event callback, and the distinction
lives in the mechanism attribute and caller string
(`Spring ApplicationContext event`).

## The certainty ladder (acceptance item 2)

| Family | UNRESOLVED | POSSIBLE | DEFINITE |
|---|---|---|---|
| Spring listeners | `spring-listener-annotations` not observed | observed + canonical + bean-declared | never |
| CDI observers | `quarkus-cdi-observer-annotations` not observed | observed + canonical + bean-declared | never |
| Spring @Scheduled | `spring-scheduling-annotations` not observed | observed + canonical + bean-declared | never |
| Quarkus @Scheduled | `quarkus-scheduler-annotations` not observed | observed + canonical + bean-declared | never |
| main (plain/Quarkus) | — | always | never |

No family reaches DEFINITE: listener endpoints and scheduler
registrations are runtime-built container state, and the bounded
contracts capture no registry for them (probed: the only
`metadataObserved` ids are the route/bean/repository/generated-root and
#61 binding families). This mirrors routes today. **Caller-facing note
for #71: declare the four metadata ids above as OBSERVED (only
OBSERVED/NOT_CAPTURED exist in `AnalysisContext.MetadataStatus`) to
reach POSSIBLE; absent that, facts stay UNRESOLVED.** The ids live in
the adapter code (routeFacts precedent) and this record — no edits under
`spike/` (the spike Python and its contracts are ephemeral per the
de-Pythonization direction; only captured results persist).

Mains are always POSSIBLE: the declaration is Java-language evidence
(the classic launch protocol shape), and the missing input — deployed
launcher selection — is stated in every limitation. `cdiBean` remains a
simple-name gate (shared residual with the injection/proxy paths, pinned
by the Q9 characterization cell).

## What changed

- `FrameworkEntrypointScanning` (new): bean-gated canonical-annotation
  scan; CDI @Observes read from the raw parameter list (parameter
  position); per-framework @Scheduled FQN tables; classic-protocol main
  check reusing `commandLineRunnerParameters` (public static void +
  String[]-shaped single parameter, annotations/final stripped by the
  shared normalization); Quarkus runner detection bounded to the
  brace-matched, literal-masked main body (simple + qualified spellings,
  lookbehinds reject lookalike receivers).
- `BoundedFrameworkModelEntrypointFacts` (new layer above SpringFacts):
  the ladder above, one fact per (method, mechanism), relationship type
  `framework_entrypoint` (the existing root treatment in
  `attachExactProviderIdentities`/`links` applies unchanged),
  `EntrypointEmission` request record, digest-suffixed stable ids.
- `BoundedFrameworkModel.enrich()` calls `entrypointFacts` for BOTH
  frameworks; scanner facades and one record on
  `SourceMetadataScannerState`; Makefile floor 801 → 842.

## Cells (43, `FrameworkEntrypointFactsTest`)

L1-L10 (Spring listeners: ladder, lookalike, non-bean, mechanisms,
@Bean-registered, FQN spelling, wildcard-import abstention, private
characterization, repeatable container), Q1-Q10 (CDI observers: ladder,
argument fidelity, lookalike, javax, non-bean, Quarkus @Scheduled,
lookalike, cross-framework decline, cdiBean residual, @Schedules
container), S1-S4, M1-M9 + M8b (main shapes, runner containment incl.
sibling-method, string-literal, and same-package simple-spelling
lookalike negatives), I1/I1b/I2/I3 (interactions incl. route
coexistence — two framework_entrypoint facts for one callee), N1-N4
(nested declaring types; method-local CDI class declines), A2 (Spring
fixture replica main).

## Mutation receipts (executed, red under exactly their own mutation)

- (a) canonical guard → `hasAnnotation`: L3+Q3+S3+Q7 red (4), L1 green.
- (b) bean gate dropped: L4+Q5+S4+N2 red (4), L1 green.
- (c) main modifier/shape checks dropped: M2 red, M1 green.
- (d) metadata gate forced observed — each half separately: listener
  half ⇒ L2 red; scheduled half ⇒ S2 red; positives green.
- (e) runner literal masking dropped: M5 red, M4 green.
- (f) observer annotation read from the method block instead of the
  parameter list: Q1+Q2 red, L1 green. (Re-scoped from the planned
  masked-slice variant: slicing `parameters()` from the masked source is
  shared extraction machinery outside this diff, and the canonical
  resolution does not depend on argument content — the killable
  manifestation inside this PR is the parameter-list block selection.)
- (g) per-framework scheduled tables collapsed: Q8+Q6 red, S1 green.
- (h) parameters() normalization bypassed (naive raw regex): M3 red.
- (i) mechanism collapsed from the fact identity: I1b errors with
  "fact IDs must be unique within a relationship set".
- (j) per-occurrence emission: L10+Q10 error (duplicate ids), L1 green.
- (k) runner check at file scope: M9 red, M4 green.

## Deviations from RED (documented in GREEN_84)

- The C-style `String args[]` main variant expects NO fact: the shared
  parameters() normalization (the CommandLineRunner lifecycle precedent)
  does not fold a trailing [] on the name into the type, so the bounded
  shape set abstains; the M3 cell pins the decline.
- The I2 cell asserts exactly two framework_entrypoint facts for the
  route+listener callee — the coexistence the cell exists to pin.

## Gates

`make adapter-test` **844 executions** (default=730, large=1, sootup=113;
main's floor 801 + the 43 cells after the review round below), zero
failures, zero churn in existing tests; `make java-lint` PASS with 0 new
findings (the initial run's two findings — ExcessiveParameterList,
CognitiveComplexity 33 — were refactored away with the EntrypointEmission
record and the shared entrypointMethods/addMatchingMechanisms helpers
before GREEN landed); `make evidence-validate` pass (no spike/ inputs
touched).

## Additional residuals (review round 1)

- The static-import runner spelling (`import static
  io.quarkus.runtime.Quarkus.run; … run(args);`) declines (the bounded
  patterns require the receiver or qualified spelling) — safe abstention,
  not modeled.
- Interface `static void main` without an explicit `public` keyword
  declines (the classic-protocol check requires the explicit modifier) —
  safe abstention.
- `mainMethods()` is framework-independent: a Spring-framework scan of a
  main calling `io.quarkus.runtime.Quarkus.run` emits a
  `quarkus-main-runner` fact under a `spring-boot-…` id. The launch
  protocol is framework-agnostic; recorded as intended behavior.

## Review record

- **Plan review**: 4 adversarial rounds (Engram-grounded subagents):
  11 → 9 → 2+1 → 0 findings. Material corrections: the metadata-id
  wiring (round 1 — originally spike-contract rows, re-scoped after the
  user ruling that spike Python is ephemeral), the honest touched-files
  list, nested-type semantics (lifecycle precedent, not routes), the
  brace-matched runner body extent, wildcard-import and JEP 512
  residuals, repeatable containers, cross-framework decline. Loop closed
  at confidence **0.93**.
- **Implementation review round 1** (fresh Engram-grounded adversarial
  subagent): 5 findings, all addressed red-first in REVIEW_84_1 —
  (1) MAJOR: the simple-spelling `Quarkus.run(` receiver had no import
  gate, so a JLS-legal same-package/user-defined `Quarkus` class
  fabricated a `quarkus-main-runner` fact (the file's own lookalike
  rule, violated); fixed by requiring an explicit
  `import io.quarkus.runtime.Quarkus;` for the simple spelling (the
  qualified spelling stays unguarded — it is self-identifying), pinned
  by M8b; (2) MINOR: the Quarkus bean gate admitted method-local
  classes (local classes cannot be CDI beans); fixed with the
  springComponent local-type filter mirrored (`cdiBeanDeclared`),
  pinned by N4; (3) MINOR: evidence symbols were synthesized simple
  spellings; now the exact `canonicalAnnotationOccurrences` source
  spellings (the lifecycle precedent), pinned by the L7 symbol
  assertion; (4) NIT: listener metadata-id attribute now derived from
  LISTENER_METADATA instead of duplicated literals; (5) NIT: the
  residuals above recorded. Gates re-run green at 844.
- **Implementation review round 2** (incorporation verification + final
  pass): all five round-1 fixes verified correct and complete (the
  import gate opens for M4, declines on-demand imports, and is
  suffix-overlap safe; cdiBeanDeclared mirrors springComponent; symbol
  fidelity verified; map keys cover the Framework enum). 3 new findings,
  all NIT (unused import, dead `scanner` parameter on
  addMatchingMechanisms, stale cell-count heading), addressed in
  REVIEW_84_2; gates re-run green at 844. **No new addressable findings;
  loop closed.**
- **CodeRabbit full review** (post-push): see the PR thread; findings
  are dispositioned there red-first.

Confidence score: **0.92** — the ladder is enforced at one seam, eleven
mutation receipts hold, the full suite shows exactly the new cells and
zero churn, every design claim in the plan cites code that was read or
probed, and the round-1 lookalike hole in the runner check is closed
with its own red-first cell. The residual 0.08: the always-POSSIBLE main
ladder and the private-method over-approximation are judgment calls
documented above, not mechanically pinned truths.
