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
- Re-entrancy: the field is saved/restored by every site entry; the
  inference graph calls only the core variants (verified by caller map:
  `isJdkClassForNameExpression`, `isStandaloneClassForNameExpression`,
  `isClassLoaderResultExpression`, `isRecognizedClassLoaderLookup`,
  `isStandaloneLoadClassExpression`, `isRecognizedClassLookup` are reached
  only through the var arm or each other). Determinism is unchanged: both
  rebuilds re-derive the same captures from the same cached extracts.

Behavior deltas (exactly two, both intended):
1. Dead initializer-internal entries no longer recorded.
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

- Chained-receiver site entries still capture the classified offset, not the
  emitted chained-site offset (pre-existing #62 shape, unchanged here).
- Proxy transitive receivers remain unclassifiable (inference grammar has no
  Proxy arm) — no capture to gate.
- `isSupportedChainedReceiver` and the `startsWithJdkClassForName` /
  `isJdkClassFieldReceiver` arms are outside the choke-point capture by
  construction (they do not reach `isDeclaredVariableBefore`'s parameter arm
  for field receivers).

Confidence score (pre-plan-review): **0.82** — the design is grounded in a
probe-verified reproduction of both gaps on main and the #62 plan's
choke-point rationale; the main uncertainty is hidden callers of the
inference helpers outside the mapped set and golden churn from fixtures
whose var initializers consult parameters (the full-suite gate will pin
both).
