# RESOLUTION-85 — Triage certainty-fidelity debt; #48 renderer escaping

Issue: project-lightpath/java-analysis-adapter#85 (TRIAGE umbrella).
Branch: `issue-85-certainty-debt-triage` from `origin/main` @ `49d5c1d`.
Commits: PLAN_85 `167a968` (+ rounds 1-3 amendments through `c979e2c`),
RED_85 `8795340`, GREEN_85 `b66a4b8`, EVIDENCE_85 (this commit).

This document doubles as the durable triage record for every item on
#85's list. Full evidence and dispositions: `docs/plans/PLAN-85.md` §2.

## The code change (#48)

`AnnotationRenderer.render` now emits a **valid Java string literal
denoting the value** — the source spelling the unchanged textual leaf
functions read — instead of wrapping the raw runtime string:

- String branch: full JLS letter escape set (backslash, quote, b t n f
  r), a backslash-u hex spelling for any remaining control character,
  and the raw character otherwise (non-ASCII included).
- Character branch (new): routed through the same escape helper,
  quote-wrapped — renderer output never carries a bare backslash or
  quote outside an escaped string context, which is the precondition
  the escape-aware `renderIsUsable` skip rule rests on.
- `renderIsUsable` skips the character after every backslash, so an
  escaped rendering `"a\"b"` keeps an even effective quote count and
  the #24 mirror stands in where it previously declined per
  occurrence (a lost correlation, never a false fact — the textual
  answer survives either way).

### Why this is the right invariant

Every consumer of rendered arguments
(`JavacFrameworkAnnotationValues.arguments/pathValues/
conditionAnnotations`, `JavacFrameworkQualifierTable.mirrorFamilies`,
`JavacFrameworkQualifierFamilies.mirrorCanReplace`) feeds the UNCHANGED
textual leaf functions (`SourceMetadataScanner.values`, `quotedValue`,
`literalPathValues`, `attributeValue`), which read source spelling.
Emitting the literal spelling keeps the mirror extraction identical to
the textual extraction — including the shared `QUOTED` truncation
behavior on escaped quotes (both paths extract `a\` from `"a\"b"`;
parity, not correctness of the extraction itself).

### Cells

- `JavacAnnotationMigrationTest.annotationValuesRenderAsEscaped
  StringLiterals` (RED): quote, backslash, newline, and char arms —
  the rendering is pinned on all four.
- `JavacFrameworkAnnotationMigrationTest.renderIsUsableTolerates
  EscapedQuotes` (RED): the escaped rendering is usable, the
  backslash-pair rendering is usable, the unbalanced pre-fix rendering
  still declines.
- `JavacFrameworkAnnotationMigrationTest.escapedQuoteInRenderedValue
  KeepsMirrorParity` (sanctioned rename of the #48 characterization
  cell; assertions unchanged): end-to-end #24 value path — the mirror
  now stands in for the `Label` occurrence while `Foo`/`Role` still
  decline via the nested-`@` rule, and on/off outputs stay equal.

### Mutation checks (executed, red under exactly their own mutation)

0. Remove the decoded-quote decline (coderabbit 85-1) ⇒ the octal
   cell AND the renderIsUsable cell (observed `[Qualifier:\]`
   collapse; `true` where `false` expected).
1. Revert the String-branch escaping ⇒ the quote arm (observed
   `"a"b"`).
2. Revert `renderIsUsable` to the naive quote counter ⇒ the
   escape-tolerance cell (observed false).
3. Escape only the quote, not the backslash ⇒ the backslash arm
   (observed `"a\b"`).
4. Drop the newline letter escape (falls through to backslash-u hex)
   ⇒ the control-char arm (observed `"a\u000ab"`).
5. Un-escape the Character arm ⇒ the char arm (observed bare `\`).

### Gates

`make adapter-test` **801 executions** (default=687, large=1,
sootup=113; main's floor 798 + the 3 new cells — the two #48 cells
plus the coderabbit octal cell), zero failures;
`make java-lint` PASS with 0 new findings (local JDK — a two-file
production touch with no PMD-relevant structural change, so the #64
helios08 cross-check is not triggered); `make evidence-validate` pass.

## Documented residual (recorded on #85)

Perfect byte-parity with the SOURCE spelling is unattainable from the
mirror: a value can be spelled many ways in source (backslash-u hex
vs letter escapes, octal escapes). The escape set restores parity for
every standard spelling; a source that spells a value through
backslash-u hex or octal escapes diverges mirror-vs-textual — and the
backslash-u-spelled control chars and backslashes ALREADY diverged
silently pre-fix. The divergence is a value-spelling difference in the
mirror-replaced answer, never a fabricated call fact. Char-typed
members with quote/backslash values join this family (single-quote
source spellings vs the quote-wrapped mirror rendering). Recorded with
the QUOTED wontfix family on #85.

## The triage record (issue #85 acceptance)

| Item | Disposition |
|---|---|
| #9 deferred ServiceLoader/test-runner gaps | **closed superseded-in-part** with per-item mapping (see #9); two scoped issues filed (#87 provider eligibility; #88 multi-statement derived chains + receiver-mismatch); superclass residuals folded into the FLPATH-5124 tracker (AGENTS.md); items T/Q recorded as accepted adjacent changes of PR #7 (Q is wontfix: perf-only); test-runtime sharding superseded by PR #18; TCCL policy wontfix-with-reason; the stale `issue-9/service-loader-deferred` branch dispositioned superseded (grounded in deleted textual-resolver machinery) |
| #48 renderer escaping | **fixed in this PR** (decoded quotes decline — coderabbit 85-1 guard) |
| post-#61: `proxyTargetTypeMatches` unmigrated | **#89 filed** |
| post-#61: metadata-side spellings (binding/proxy rows, C6) | **wontfix-with-reason on #85** (external spellings; tightening C6 loses true answers) |
| post-#61: generic binding rows, producer generic tail | **wontfix-with-reason on #85** (the index is erased by construction) |
| post-#77: raw-extraction owner anchors (3 sites + equivalent mutant) | **#90 filed** (anchor-normalization family, follow-up to #77) |
| #48-adjacent: textual `QUOTED` escape-blindness | **wontfix-with-reason on #85** (shared #21/#24 leaf code; both flag states now agree; no bounded fixture uses escaped quotes) |

## Review record

- **Plan review**: 4 adversarial rounds (Engram-grounded subagents):
  7 → 3 → 3 → 0 findings. Material corrections: the full JLS escape
  set + backslash-u control fallback (round 1 — the quote/backslash-
  only design left control-char and char-arm manifestations); the
  issue-9 branch disposition (round 1); the Character-arm closure and
  floor arithmetic (round 2); the char-member residual scoping and
  cell-3 comment scope (round 3). Loop closed at confidence **0.88**.
- **CodeRabbit full review** (post-push): 1 Major finding — octal-
  spelled quotes (`@Qualifier("\042red")`) decode to quote-bearing
  values whose escaped rendering truncates in the escape-blind QUOTED
  extraction, collapsing distinct source spellings flag-on (verified:
  `[Qualifier:\042red]` vs `[Qualifier:\]` without the guard).
  Addressed red-first in CODERABBIT_85_1: `renderIsUsable` declines a
  DECODED quote (rendered `\"`) so the textual answer survives; the
  octal cell pins the distinctness end-to-end and the mutation receipt
  (guard removed) reproduces the collapse exactly. Backslash-bearing
  and control-char values carry no quote, do not truncate, and keep
  mirror parity (probe-verified on five constructs: plain, octal
  quote, standard quote, backslash, newline — flag-on == flag-off on
  all five).
- **Implementation review**: round 1 (fresh Engram-grounded
  adversarial subagent) verified the core change, the parity traces,
  the blast radius (including the condition-evidence consumers), and
  the floor arithmetic correct; 4 findings (2 MINOR — garbled
  javadoc, overclaiming test comment; 2 NIT — capacity hint, unenforced
  skip-rule precondition), all addressed in REVIEW_85_1. Round 2
  (incorporation verification) performed inline after the review
  subagent stalled: the javadoc tail, obligation paragraph, capacity
  hint, and comment scoping confirmed in place; full gates re-run
  green at the amended head. **No new addressable findings; loop
  closed.**

Confidence score: **0.88** — the parity invariant is enforced by the
unchanged leaf functions themselves, five mutation receipts hold, the
full suite shows exactly the two new cells and zero churn, and every
triage disposition cites line-level code or resolution facts.
