# PLAN-85 — Triage certainty-fidelity debt (deferred ServiceLoader gaps, renderer escaping, post-#61/#77 textual residuals)

Issue: project-lightpath/java-analysis-adapter#85 (TRIAGE umbrella).
Branch: `issue-85-certainty-debt-triage` from `origin/main` @ `49d5c1d`
(PR #82 merge — #61/#76/#77/#78 landed, umbrella #20 closed).

## 1. Scope

Two deliverables:

- **(a) The triage record** — every item on #85's list gets a disposition
  (filed-with-issue, wontfix-with-reason on #85, or folded into existing
  tracking), each backed by code/resolution facts verified through
  engram-zvec-grep and Engram recall.
- **(b) The one sanctioned code fix** — #48's `AnnotationRenderer`
  quote/backslash re-escaping (the only item the triage confirms as
  immediately-actionable small correctness-adjacent work; its failure mode
  is a lost correlation, never a false fact).

Everything else becomes follow-up issues or documented wontfix reasons —
no bulk implementation, no bulk-close.

## 2. Triage dispositions (the deliverable)

### Item 1 — #9 (PR #7 scope split): SUPERSEDED-IN-PART → close with evidence + 2 scoped issues

Verified against current main:

- The textual ServiceLoader name resolver is **deleted** (PR #31,
  FLPATH-5142; `ServiceLoaderCallSiteParser`'s import-ownership machinery
  gone, `JavacServiceLoaderSiteResolver` is the only resolver). #9's
  parser-side name-resolution scope is therefore moot.
- The shadow cells #9 worried about are pinned on main:
  `ServiceLoaderSourceCacheTest.abstainsForNestedServiceLoaderShadowing
  ExplicitImport` (:92) and `.abstainsForLocalServiceLoaderShadowing
  ExplicitImport` (:106) — the Engram-noted explicit-import nested/local
  shadow gap is fixed.
- Test-runtime sharding: decided against by PR #18 (one adapter-test
  target, JUnit-scheduled parallelism, no sharding) — superseded.
- Items T (dependency-resolution diagnostics) and Q (descriptor-evidence
  allocation cache): recorded on #9 as **accepted adjacent changes landed
  with PR #7** (the #9 comment's own recommendation); T's diagnostic
  carrier is visible on main (`ServiceLoaderDescriptorScanner.Diagnostic`,
  :277). Q is performance-only — wontfix-with-reason on #85.
- Still live (safe-direction precision gaps, no false DEFINITE):
  1. **Provider eligibility model** — no eligibility handling for
     records / implicit constructors / non-static inner provider classes /
     unreadable dependency providers exists in main (rg over
     `ServiceLoaderDescriptorScanner` and the provider-fact path finds no
     eligibility surface). → **new issue**.
  2. **Multi-statement derived chains** (`P p = load(...); p.record();`)
     and receiver-mismatch over-abstention — `ServiceLoaderCallSiteSource
     .sameStatement` is statement-scoped by design (coverage tests :87-96)
     → **new issue**.
  3. **Superclass residuals** (multi-level chains, nested-type
     qualifiers, superclasses outside selected files) — already tracked as
     the documented false-attribution residual in AGENTS.md, pointing at
     FLPATH-5124 policy work → **folded**: FLPATH-5124 is the agreed
     tracker (stated explicitly in the #85 disposition comment; no
     GitHub duplicate is filed to avoid double-tracking).
  4. **The stale `issue-9/service-loader-deferred` branch** (plan-review
     finding 2): it carries the TCCL guard and provider-eligibility check
     grounded in the DELETED textual resolver machinery
     (`StaticImportOwnerResolver`, `ServiceLoaderInheritance`,
     `ServiceLoaderMemberTypes` — removed by PR #31) and is hundreds of
     commits behind main → **superseded**: the closing comment on #9
     dispositions the branch (archive/delete; do not rebase), and the new
     provider-eligibility issue cites it as prior art only.
  5. **TCCL policy** — `setContextClassLoader` appears nowhere in src/
     (rg, case-insensitive); the two-arg `ServiceLoader.load(Class,
     ClassLoader)` site already carries `explicitClassLoader` evidence and
     stays POSSIBLE (`ServiceLoaderCallSite` record :4-8;
     `SootUpBaseGraphProviderIntegrationTest.explicitLoaderWithClean
     ProviderRemainsPossible` :225). → **wontfix-with-reason on #85**: no
     bounded fixture installs a TCCL; the explicit-loader arm is already
     the safe direction; the old branch's guard targeted deleted machinery,
     and guarding hypothetical TCCL installs corpus-wide is speculative
     work with no observable today.

Disposition: **close #9 as superseded-in-part** with the per-item mapping
in the closing comment; file the two scoped issues above.

### Item 2 — #48: FIX IN THIS PR (§3)

### Item 3 — post-#61 textual residuals (RESOLUTION-61 §Residuals)

- `proxyTargetTypeMatches` (BoundedFrameworkModelMetadataMatching.java:98) stays textual — the
  resolution names it "its own follow-up" → **new issue**.
- Metadata-side spellings (binding rows, proxy rows; C6's
  case-insensitive simple-name over-approximation) → **wontfix-with-reason
  on #85**: external metadata spellings cannot be resolved through the
  index without a false-DEFINITE risk, and tightening C6 loses true
  answers (the resolution's own measured reason).
- Generic binding rows (C3 bothGeneric) and the producer generic tail →
  **wontfix-with-reason on #85**: the index is erased by construction;
  generic-argument resolution is a feature (a generic-aware index), not a
  fidelity repair.

### Item 4 — post-#77 residuals (RESOLUTION-77 §Residuals)

The raw-extraction owner-anchor family (same over-abstention defect
class, safe direction, three production sites + one equivalent mutant):

- `FrameworkBeanScanning.producerCandidate`'s mirror lookup
  (`javacMirrorAbstentionMember` names the nested owner),
- `FrameworkInjectionScanning.methodInjectionPoints:137`,
- `FrameworkRouteScanning.routesForMethod:171`,
- mutation 3's equivalent mutant (a producer declared inside a nested
  type).

→ **one new scoped issue** covering the anchor-normalization family
(follow-up to #77; NOT code in this PR per #85's bounded scope).

### #48-adjacent residual: textual `QUOTED` extraction

→ **wontfix-with-reason on #85**: `SourceMetadataScannerState.QUOTED`
(:27) is shared #21/#24 leaf code; making it escape-aware changes
flag-off textual semantics for every consumer, while no bounded fixture
uses escaped quotes in annotation values, and after this PR's fix both
flag states extract the SAME (source-spelled) text — parity, not
divergence. Reopen only if a fixture needs true escaped-value
extraction.

## 3. The #48 fix (the sanctioned code work)

### Design invariant

`AnnotationRenderer.render`'s String branch must produce a **valid Java
string literal denoting the value** — not merely wrap it. Escaping is
the full JLS letter set plus unicode fallback:

- `\` → `\\`, `"` → `\"` (the #48 case),
- `\b` `\t` `\n` `\f` `\r` → their letter escapes (a value spelled
  `"a\nb"` in source must render `"a\nb"`, not a raw newline — javac's
  `getValue()` hands the RUNTIME string, and the textual path reads the
  source spelling; letter-escaping restores extraction parity),
- any remaining control char (`c < 0x20 || c == 0x7F`) → `\uXXXX`,
- non-ASCII (`c >= 0x80`) → raw character (parity with literal-spelled
  sources; see the residual below).

Every consumer of rendered arguments (`JavacFrameworkAnnotationValues`,
`JavacFrameworkQualifierTable.mirrorFamilies`,
`JavacFrameworkQualifierFamilies.mirrorCanReplace`) feeds the UNCHANGED
textual leaf functions (`SourceMetadataScanner.values`,
`quotedValue`, `literalPathValues`…), which operate on source spelling.
Byte-parity with the source spelling is therefore exactly the invariant
that keeps mirror answers identical to textual answers — including for
values containing `"` or `\` (today the mirror diverges or declines).

`renderIsUsable` must become escape-aware (skip the character after
`\`), else the escaped rendering `"a\"b"` still counts three quote
characters and declines — the fix would be inert. The skip rule is
sound because backslashes occur only inside string values in renderer
output (enum/class/number renderings contain none); stated in the
javadoc.

### Documented residual (plan-review round 1, finding 1)

Perfect byte-parity with the SOURCE spelling is unattainable from the
mirror: a value can be spelled many ways in source (`\n` vs `\u000a`,
`"` vs `\u0022`, octal escapes, `\uXXXX`-spelled non-ASCII). The escape
set above restores parity for every STANDARD spelling (letter escapes,
`\"`, `\\`, raw non-ASCII). A source that spells a value through
`\uXXXX`/octal escapes diverges mirror-vs-textual after the fix where
the quote cases declined before — a value-spelling divergence in the
mirror-replaced answer, never a fabricated call fact, and strictly
rarer than the constructs the fix repairs. Recorded on #85 with the
QUOTED wontfix family; a fixture that needs it reopens both.


### Cells (RED_85)

1. **Renderer cell** (JavacAnnotationMigrationTest, next to
   `annotationValuesRenderMinusQuoting`): fixture
   `@Val(name = "a\"b", ...)` asserts
   `annotation.values().get("name")` equals the escaped rendering
   `"a\"b"` (6 chars). Backslash arm: `@Val(name = "a\\b")`
   renders `"a\\b"`. Control-char arm (plan-review finding 1):
   `@Val(name = "a\nb")` renders `"a\nb"` (the letter escape, not the
   raw newline).
2. **renderIsUsable cell** (new small test class or the migration test):
   `renderIsUsable("value=\"a\\\"b\"")` is TRUE after the fix (red today:
   the naive counter sees 3 quotes); an unbalanced rendering
   (`"a"b"`) still declines; a `\\`-pair rendering stays usable.
3. **End-to-end #24 value path** (sanctioned edit — the ONLY existing
   test touched): `unescapedQuoteInRenderedValueFallsBackToTextual`
   (JavacFrameworkAnnotationMigrationTest:789) — the mirror no longer
   declines per occurrence, so the comment is rewritten and the cell
   renamed to pin mirror-stands-in parity (the on/off equality assertion
   itself is unchanged and stays load-bearing).

Floor: 798 → 802 (4 cells; Makefile:24 literal raised with EVIDENCE).

### GREEN_85

- `AnnotationRenderer.render` String branch: wrap with the full escape
  set of §3 Design invariant (one helper; nested renderers and array
  joins route through `render`, so one site covers all).
- `JavacFrameworkAnnotations.renderIsUsable`: escape-aware scan; javadoc
  updated (the "#48 conservative decline" note becomes the escape rule
  and its precondition).
- `JavacAnnotationAccess.unquoted` javadoc: one sentence noting the
  interior keeps the literal (escaped) spelling post-#85.

### EVIDENCE_85

- Gates: `make adapter-test` (802, zero failures; Makefile floor literal
  798 → 802), `make java-lint` (zero findings — a two-file touch with no
  PMD-relevant structure; the #64 helios08 cross-check is not
  triggered), `make evidence-validate`.
- Mutation receipts: (1) revert the render escaping ⇒ cell 1 red
  (all three arms); (2) revert renderIsUsable to the naive counter ⇒
  cell 2 red; (3) escape only `"` not `\` ⇒ the backslash arm of cell 1
  red; (4) drop the letter escapes (raw newline) ⇒ the control-char arm
  red.

## 4. Issue-filing checklist (executed at PR time, from this branch)

1. New: ServiceLoader provider eligibility model (from #9).
2. New: multi-statement derived chains + receiver-mismatch
   over-abstention (from #9).
3. New: `proxyTargetTypeMatches` index migration (from #61 residuals).
4. New: remaining raw-extraction owner anchors (from #77 residuals).
5. Close #9 (superseded-in-part) with the evidence mapping.
6. Comment the wontfix-with-reasons on #85 (TCCL, metadata spellings,
   generic rows/tail, QUOTED alignment, item Q cache) and cross-link the
   four new issues + the folded FLPATH-5124 superclass tracking.
7. Close #48 via the PR commit (`Fixes #48`).

## 5. Non-goals

- No changes to the textual leaf functions (`QUOTED`, `values`,
  `quotedValue`) — flag-off semantics are frozen.
- No anchor-normalization code (post-#77 family) — follow-up issue only.
- No ServiceLoader eligibility or derived-chain work — follow-up issues
  only.
- No edits to existing tests beyond the one sanctioned cell in §3.3.

## 6. Risks

- A hidden consumer of rendered values that compares against the RAW
  (unescaped) spelling — the full-suite gate is the detector; the
  pre-flight audit found all consumers feed textual leaf functions
  (source spelling).
- JDK-version variance in javac's `AnnotationValue.getValue()` string
  form — none known; the renderer owns the wrapping, javac hands the
  runtime string.

Confidence score: **0.86** — the triage evidence is line-cited and each
disposition traces to a resolution/AGENTS record; the #48 fix is small
with a parity invariant that the unchanged leaf functions enforce; the
main residual risk is an unaudited rendered-value consumer, which the
gates and the adversarial review rounds are positioned to catch.
