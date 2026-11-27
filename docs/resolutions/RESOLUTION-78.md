# RESOLUTION-78 — Caller-abstention coverage for transitively inferred receivers

Issue: project-lightpath/java-analysis-adapter#78 (follow-up from #62 /
PR #72 residuals, `docs/resolutions/RESOLUTION-62.md`).
Branch: `issue-78-caller-abstention-coverage` from `origin/main` @
`1a2486f`. Commits: PLAN_78 (+ 4 plan-review amendments), RED_78,
GREEN_78, EVIDENCE_78 (+ implementation-review amendments if any).

## What changed

The #62 choke-point capture at `isDeclaredVariableBefore` is now
site-scoped instead of inquiry-scoped:

- **`captureSiteOffset`** (new scanner field, Layer05): set by the two
  site-classification entries — `isDeclaredVariableBefore` and
  `isJdkApiOwner` — in a `try/finally` save/restore, read by the
  parameter-match arm. Nested site entries pass an equal offset
  (`isJdkApiOwner` internally calls the `isDeclaredVariableBefore` entry
  with the same offset; the chained-receiver arms thread one receiver
  start), so the restore is idempotent.
- **`classifyDeclaredVariable` / `isJdkApiOwnerUncaptured`** (new cores,
  Layer05): the current bodies, capture-inert. The parameter-match arm
  records `captureSiteOffset` (when set) instead of its own inquiry
  offset.
- **The inference callers switch to the cores**:
  `isClassLoaderResultExpression` (Layer05:159),
  `isRecognizedClassLoaderLookup` (:176 + its owner check at :174),
  `isRecognizedClassLookup` (:221), and `isJdkClassForNameExpression`'s
  owner check (:40).

Consequences (exactly the two issue gaps):

- **Gap A (dead entries)**: a parameter-arm consultation beneath a
  var-initializer type inference no longer records its
  initializer-internal offset (the file offset of the initializer's
  sub-receiver — verified: `LocalDeclaration.initializerOffset` is
  `matcher.start(2)`, file-absolute — which coincided with a site offset
  only by accident).
- **Gap B (transitive coverage)**: the same consultation records the
  OUTER site entry's offset, so a reflection site whose receiver type was
  transitively inferred from a parameter-initialized local now carries
  `javacCallerAbstention` when the caller member's correlation abstains.
  Multi-hop chains (`var c = klass.forName(...); var m =
  c.getMethod(...); m.invoke(...)`) thread the site offset through every
  nested var arm.

Direct-site captures are byte-identical (every site caller passes the
offset it passed before; the involved patterns are group-1-anchored so
`matcher.start() == matcher.start(1)`). The channel remains
visibility-only: never `scan.diagnostics()`, never blocking DEFINITE
promotion, silent on flag-off and failed builds.

## Acceptance evidence (issue #78)

- E1: the `Method.invoke` fact over a var-inferred parameter-initialized
  receiver carries `javacCallerAbstention=multi_type_file` and member
  `demo.Sites#call(1)`; a field-initialized twin carries none on either
  fact (causality).
- E2: the ClassLoader chain (`var cl = klass.getClassLoader();
  cl.loadClass(...)`) carries the abstention, and
  `parameterReceiverOffsets(file)` equals exactly the loadClass site
  offset — the dead entry is pinned gone.
- E3: the two-hop chain carries the abstention on the invoke fact.
- E4: the flag-off twin sweeps every `javac*Abstention*` key on the
  transitive fixture — none.
- E5: the transitive abstention and a ServiceLoader DEFINITE site in one
  scan: promotion untouched, diagnostics empty, the abstention rides the
  invoke fact.
- Gates: `make adapter-test` **740 executions** (main floor 735 + 5
  cells; Makefile counter raised), zero failures, **zero golden churn**
  (the pre-GREEN audit class `JavaAnalysisAdapterTest` — including
  `varUnrelated`, whose transitive consultation is rejected by
  `parameterMatchesType` (`Router` ≠ `ClassLoader`) so its site is not
  admitted — is green, churn none as predicted); `make java-lint` PASS
  with 0 new findings on BOTH local JDK 27 and helios08 Temurin 17
  scratch checkout (the #64 discipline; the only FAIL line in the
  helios08 log is the lint plugin's own `Fixture.java` self-test);
  `make evidence-validate` pass.

## Mutation checks (executed, all red under exactly their own mutation)

1. Core captures the inquiry `offset` instead of `captureSiteOffset` ⇒
   E1/E2/E3/E5 red (a capture still occurs, at the initializer-internal
   offset — the attribute is lost AND E2's set pin sees the dead entry
   return); E4 (flag-off control) unaffected.
2. Capture removed entirely (`&& false` on the guard) ⇒ 8 cells red: the
   #62 cells C10 (`reflectionCallerAbstentionIsVisibleAndCausal`), D6
   (`proxyCallerAbstentionIsVisible`), D1, D4 plus the new E-cells — the
   direct arm stays pinned by the existing suite.
3. `isClassLoaderResultExpression`'s inference call (Layer05:159) uses
   the site entry (field reset) ⇒ exactly ONE cell red, E2's set pin;
   E1 stays green (its chain crosses Layer05:221, not :159) — gap A is
   isolated from gap B.

## Review record

- **Plan review**: 4 rounds (round 1: 4 findings — the golden-churn
  surface named, the chained-receiver residual corrected, the dead-entry
  scoping, `try/finally`; round 2: 4 findings — the `varUnrelated`
  rejection trace, the site-offset dead-entry rescoping, the
  nesting-idempotence invariant, mutation-3's named caller; round 3: 3
  findings — the vacuous-RED fact-selection pin (MEDIUM), the #76
  coordination step, the E4 race note; round 4: clean, named the concrete
  symbols-based selection predicate). Confidence 0.82 → **0.92**, loop
  closed.
- **Implementation review**: see the amendments appended below.

## Residuals

- Dead-entry shapes remain reachable at SITE offsets for transitive
  chains (a capture for a site later rejected after a parameter-arm hit,
  or admitted by the InvocationHandler arm of the METHOD_INVOKE `||`):
  pre-existing for direct sites, newly reachable at site offsets here —
  no behavior impact (the gate only annotates existing facts).
- Chained-receiver shapes outside the choke point: terminal
  `invoke`/`newInstance` chained sites are emitted at `terminalStart`
  while their classification ran at `receiver.start()`, and non-SIMPLE
  chained kinds never reach `isDeclaredVariableBefore` (the SIMPLE arm's
  capture is live — `addChainedLookupSites` skips SIMPLE receivers as
  already emitted by `addReceiverCallSites`).
- Proxy transitive receivers remain unclassifiable (the inference grammar
  yields only Method/Constructor/Class/ClassLoader) — no capture to gate.
- #76 coordination: after any rebase onto a post-#76 base, re-run E1–E5,
  re-derive the Makefile floor, and re-verify the
  `javacCallerAbstentionMember` format pin against #76's index changes.

Confidence score: **0.92** — both gaps reproduced probe-first on main and
closed by one mechanism whose direct-site behavior is byte-identical;
every delta is mutation-pinned end-to-end (gap A and gap B isolated
separately); zero golden churn on the full 740-execution suite; the
blast-radius contract is pinned by E5 and the E4 flag-off twin.
