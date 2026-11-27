# PLAN-78 — Caller-abstention coverage for transitively inferred receivers

Issue: project-lightpath/java-analysis-adapter#78 (follow-up from #62 /
PR #72; `docs/resolutions/RESOLUTION-62.md` Residuals).
Branch: `issue-78-caller-abstention-coverage` from `origin/main`.

## Scope (from the issue and the #62 residuals)

Two gaps at the `isDeclaredVariableBefore` choke point
(`SourceMetadataScannerReflectionLayer05`):

- **Gap A (dead entries)**: the type-INFERENCE callers of
  `isDeclaredVariableBefore` — `isClassLoaderResultExpression` (Layer05:159),
  `isRecognizedClassLoaderLookup` (Layer05:176, plus its `isJdkApiOwner` call
  at Layer05:174), and `isRecognizedClassLookup` (Layer05:221) — pass
  initializer-internal offsets into the `hasParameter` arm's
  `parameterArmOffsets` capture. Those offsets are file offsets of the
  initializer's sub-receiver (verified: `LocalDeclaration.initializerOffset`
  is `matcher.start(2)`, file-absolute), so classification still works, but
  they only coincide with a site offset by accident and are otherwise dead
  map entries.
- **Gap B (transitive coverage)**: a site whose receiver type was transitively
  INFERRED from a parameter-initialized local (`var m = param.getMethod(...)`
  then `m.invoke(...)`) consults the parameter arm one hop away at an
  initializer-internal offset; the site's own offset is never captured, so
  `parameterReceiverOffsets(file).contains(site.offset())` is false and the
  fact gets no `javacCallerAbstention`.

Out of scope (documented, unchanged): the chained-receiver site entries whose
classified offset differs from the emitted site offset
(`isSupportedChainedReceiver` SIMPLE arm captures `receiver.start()` while
`addChainedLookupSites` emits the lookup offset — a pre-existing #62
residual class, not an inference-caller dead entry); proxy transitive
receivers (the inference grammar yields only Method/Constructor/Class/
ClassLoader, never Proxy); #76's member-level diagnostics join
(`JavacSourceSymbolIndex`/`MemberInfo` untouched beyond zero).

## Spike evidence (probe-verified on main, 2026-11-26)

Fixture `void call(Class<?> loader) { var method = loader.getMethod("run");
method.invoke(this); }` in a multi-type-file caller:

- the `getMethod` fact carries `javacCallerAbstention=multi_type_file`;
- the `Method.invoke` fact carries NONE (gap B);
- `parameterArmOffsets=[110]` — the initializer's sub-receiver offset
  coincided with the `getMethod` site offset in this fixture (the nested
  dead entry and the direct capture landed on the same value), proving the
  coincidence hazard, not the absence of dead entries.

`LocalDeclaration.initializerOffset` = `matcher.start(2)` (file-absolute
start of the initializer text; `SourceMetadataScannerReflectionLayer00:
simpleLocalDeclaration`), and `lookup.start(1)` is an index within the
initializer string — so `offset + lookup.start(1)` is the file offset of the
sub-receiver inside the initializer.

## Design

One mechanism, extending the #62 choke-point capture with a
**classification-scope capture offset** held in a scanner field:

- `isDeclaredVariableBefore(file, type, variable, offset)` keeps its
  signature and ALL existing site-classification callers
  (`SourceMetadataScanner` METHOD_INVOKE loop and `addQualifiedProxyOffsets`,
  Layer05 `isJdkApiOwner`, Layer06 `addClassForNameSites` /
  `addReceiverCallSites` / `addReceiverSites` /
  `isSupportedChainedReceiver`). It becomes the site entry: it sets
  `captureSiteOffset = offset` (save/restore around the call) and delegates
  to the new core `classifyDeclaredVariable(file, type, variable, offset)`.
- `classifyDeclaredVariable` is the current body, except the `hasParameter`
  arm records `captureSiteOffset` (when set) instead of its own `offset`.
- The three inference callers (Layer05:159/176/221) switch to
  `classifyDeclaredVariable`, and `isJdkApiOwner` splits into the public site
  entry (sets the field, keeps its signature and callers) and an
  `isJdkApiOwnerUncaptured` core used by the two inference paths
  (`isJdkClassForNameExpression`, `isRecognizedClassLoaderLookup`).
  Inference-internal classification therefore never resets the field: the
  parameter-arm consultation one or more hops down records the OUTER site
  offset — closing gap B — and never records initializer-internal offsets —
  closing gap A.
- The var-declaration arm, `inferredReflectionType`, and every helper under
  it keep their signatures: the capture target flows through the field, not
  parameters (no PMD ExcessiveParameterList pressure, no wide churn).
- Re-entrancy: the field is saved/restored by every site entry in a
  `try/finally` (a throw mid-classification must not leak the field and
  mis-attribute later captures); the inference graph calls only the core
  variants (verified by caller map: every inference helper is reached only
  through the var arm or each other, and no extraction loop calls an
  inference helper directly, so no nested site entries exist and the field
  cannot be reset mid-inference). Determinism is unchanged: both rebuilds
  re-derive the same captures from the same cached extracts
  (`DynamicScanning` iterates files sequentially; no parallel streams in
  `src/main`).

Behavior deltas (exactly two, both intended):
1. Initializer-internal dead entries no longer recorded (gap A). Other
   dead-entry shapes are PRE-EXISTING and unchanged: a capture recorded for
   a site later rejected after a parameter-arm hit (the capture precedes
   `parameterMatchesType`'s result), and the METHOD_INVOKE loop's `||`
   ordering (`SourceMetadataScanner:114-115`), where
   `isInvocationHandlerParameterBefore` can admit a site after
   `isDeclaredVariableBefore` already recorded a capture.
2. A transitive parameter-arm consultation records the site offset, so the
   site's reflection fact gains `javacCallerAbstention` when the caller's
   correlation abstains.

Direct-site captures are byte-identical: every site entry passes the same
offset it passed before (`matcher.start()`/`matcher.start(1)` coincide at
group-1-anchored patterns; `METHOD_INVOKE` passes `invoke.start()`).
Visibility-only: certainty, status, fact IDs, `scan.diagnostics()` untouched;
the channel still never blocks DEFINITE promotion; flag-off stays silent.

## Tests (append-only in `JavacAbstentionEvidenceTest`, shared with #76)

- **E1 (end-to-end transitive, reflection)**: `var method =
  loader.getMethod("run"); method.invoke(this);` with `loader` a parameter,
  multi-type-file caller — the `Method.invoke` fact carries
  `javacCallerAbstention=multi_type_file` and member
  `demo.<Type>#call(1)`; diagnostics stay empty. Field-receiver twin
  (`Class<?> loader;` field): the `Method.invoke` fact carries NO caller
  abstention (causality — the transitive chain consulted a field arm, not
  the parameter arm).
- **E2 (transitive ClassLoader chain + capture-set pin)**:
  `var cl = klass.getClassLoader(); cl.loadClass("X");` — the `loadClass`
  fact carries the caller abstention, and `parameterReceiverOffsets(file)`
  equals exactly `{loadClass site offset}` (the dead initializer-internal
  entry is gone). On main this cell is red both ways (no abstention; the set
  holds the dead offset).
- **E3 (two-hop transitive)**: `var c = klass.forName("X");
  var m = c.getMethod("run"); m.invoke(this);` — the `Method.invoke` fact
  carries the caller abstention (the capture threads through two inference
  hops and a nested var arm).
- **E4 (flag-off twin)**: the E1 fixture with the flag off — no
  `javac*Abstention*` key on any fact (D2-style sweep).
- **E5 (blast radius twin)**: the E1 fixture and a ServiceLoader DEFINITE
  site in one scan — promotion untouched, diagnostics empty, the transitive
  abstention rides the fact (D1-style).

Fixture-shape pins (from the #62 record and the harness): declare
`class Nested` AFTER the caller method (a type immediately before the
caller breaks the textual `methodContaining` anchor); `writeSources` names
files after the FIRST type declaration; `analyzeDynamic` auto-writes
`demo/Account.java`. E2's capture-set pin uses the D9 pattern (construct
`SourceMetadataScanner`, run `dynamicFeatures()`, then read
`parameterReceiverOffsets`) — no new accessor needed.

**Pre-GREEN golden-churn audit (plan review round 1, finding 1)**:
`JavaAnalysisAdapterTest.dynamicSitesKeepCallersAndEquivalentReflectionSpellings`
holds `varUnrelated(Router router) { var type = router.loadClass(); return
type.getMethod("GET"); }` — a transitive parameter-receiver site whose fact
gains `javacCallerAbstention` under this change if that member's
correlation declines. Before GREEN, run that test class and diff its golden
evidence strings; any churn there must be an intended, reviewed attribute
addition (correlation declines on that fixture) or none at all.

## Methodology

- TDD: RED_78 (E1–E5 failing on main) → GREEN_78 (the field-threaded core
  split, minimal) → EVIDENCE_78 (mutation checks, gates receipt).
- Gates after every item: `make adapter-test` (raise the Makefile floor from
  main's 735 by the new cell count), `make java-lint` (zero findings; JDK 27
  local + helios08 JDK 17 scratch checkout per the #64 discipline if any
  GodClass measurement moves), `make evidence-validate`.
- Zero edits to existing tests; additions only.
- Adversarial review: plan review loop and post-implementation review loop
  with fresh Engram-grounded subagents until no addressable findings.

## Mutation checks (EVIDENCE_78)

1. Capture the inquiry offset instead of the field in the core's
   `hasParameter` arm ⇒ E1/E2/E3 red (no site capture) and E2's set pin red
   (dead entry returns).
2. Remove the capture entirely ⇒ C10/D6 red (existing #62 cells pin the
   direct arm).
3. Let an inference caller use the site entry (field reset) ⇒ E2's set pin
   red (dead entries return) while E1 may stay green — isolates gap A from
   gap B.

## Residuals (to document in RESOLUTION-78.md)

- Chained-receiver coverage (plan review round 1, finding 2, corrected):
  the SIMPLE chained arm's capture at `receiver.start()` is LIVE —
  `addChainedLookupSites` skips SIMPLE receivers as already emitted by
  `addReceiverCallSites` (Layer02), so the capture anchors the same
  identifier start as the emitted site. The actually-uncovered chained
  shapes are terminal `invoke`/`newInstance` sites emitted at
  `terminalStart` while their classification ran at `receiver.start()`, and
  the non-SIMPLE kinds, which never reach `isDeclaredVariableBefore` at
  all.
- Residual dead-entry shapes (unchanged, pre-existing): captures recorded
  for sites rejected after a parameter-arm hit, and the METHOD_INVOKE `||`
  ordering where the InvocationHandler arm admits the site.
- Proxy transitive receivers remain unclassifiable (inference grammar has no
  Proxy arm) — no capture to gate.

Confidence score (pre-plan-review): **0.82** — the design is grounded in a
probe-verified reproduction of both gaps on main and the #62 plan's
choke-point rationale; the main uncertainties were hidden callers of the
inference helpers outside the mapped set and golden churn from fixtures
whose var initializers consult parameters.

## Plan review record

Round 1 (fresh Engram-grounded adversarial subagent): caller map verified
complete; all site-entry offsets coincide with emitted site/proxy offsets
(every involved pattern is group-1-anchored, `matcher.start() ==
matcher.start(1)`); E1–E3 fixture chains verified against the grammar.
4 addressable findings, all incorporated as plan amendments: (1) MEDIUM —
the concrete golden-churn surface in
`JavaAnalysisAdapterTest.dynamicSitesKeepCallersAndEquivalentReflectionSpellings`
(`varUnrelated`'s transitive parameter receiver), now a named pre-GREEN
audit step; (2) LOW — the chained-receiver residual text was wrong for the
SIMPLE arm (its capture is live; the uncovered shapes are terminal
invoke/newInstance sites and non-SIMPLE kinds), corrected; (3) LOW —
behavior-delta 1 scoped to initializer-internal entries with the
pre-existing dead-entry shapes documented; (4) LOW — save/restore specified
exception-safe (`try/finally`). Confidence after round 1: **0.88**.
