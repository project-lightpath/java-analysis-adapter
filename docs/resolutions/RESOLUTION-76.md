# RESOLUTION-76 — Member-level line join for the javac abstention diagnostics

Issue: project-lightpath/java-analysis-adapter#76 (follow-up from #62 /
PR #72; scope recorded in `docs/resolutions/RESOLUTION-62.md`
Residuals).
Branch: `issue-76-member-level-diagnostics` from `origin/main` @
`1a2486f`.
Commits: PLAN_76 (+ 2 review-round amendments), RED_76, GREEN_76,
REVIEW_76_1.

## What changed

The #62 per-member abstention evidence's `javacAbstentionDiagnostics` /
`javacMirrorAbstentionDiagnostics` values narrow from FILE-level to
MEMBER-level: each abstaining member carries the javac error codes
recorded within its own source line span, not every code in its file.
Visibility-only — the abstention codes, member identities, attribute
keys, certainty, status, limitation, and fact IDs are unchanged; only
the diagnostics VALUES narrow.

- **`MemberPosition(startLine, endLine)`** (additive `MemberInfo`
  component, the `publicMember`/`staticType` precedent): recorded at
  the single `ElementReader.memberInfo` site through
  `Trees.getSourcePositions` — null tree (synthetic members) → unknown;
  `end == NOPOSITION` (implicit constructors) → a point span at the
  start; a non-positive start → unknown (defensive; the spike never
  observed one).
- **`errorLinesByFile`** (record-time): every `(line, code)` javac
  error row, keyed by the raw normalized `Path`, deep-copied at
  `finish()` — no record-time dedup (the same code on multiple lines
  stays distinct rows); `errorCodesByFile` (the #62 file-level API) is
  unchanged.
- **The join** (`errorCodesForMember(file, member)`, in the
  PMD-forced `JavacMemberDiagnostics` extraction): rows whose line
  lies in the member's inclusive span, deduped by code at join time. A
  NOPOSITION row (non-positive line) joins EVERY member-level lookup
  for its file — never silently dropped. An unknown member position
  falls back to the conservative file-level set.
- **Consumers**: the seam-B and seam-C lookups join the FOUND error
  member's span (unique-owner guard preserved); the correlation's
  `error_type_member` unions the error-typed group members' spans —
  the filter set (unique owner, NAME, kind, arity, errorType)
  reproduces the group's error members exactly (review round 1,
  finding 1, fixed red-first); `member_absent` stays file-level (no
  member, no span — unchanged #62 behavior).

## Acceptance evidence (issue #76)

- The join is member-level and precise: E1 (two error members with
  distinct code KINDS — `compiler.err.doesnt.exist` vs
  `compiler.err.cant.resolve.location` — each abstention carries only
  its own code), E2 (a body error is excluded from the error-typed
  sibling's diagnostics), E3 (the union is name-filtered — same-arity
  different-name error members do not leak codes).
- Gates: `make adapter-test` **738 executions** (main's floor 735 +
  3 cells), zero failures; `make java-lint` PASS with 0 new findings
  on BOTH the local JDK 27 and helios08 Temurin 17 (the #64
  discipline); `make evidence-validate` pass.

## Mutation checks (executed, red under exactly their own mutation)

1. Remove the span filter from the join (every row joins) ⇒ E1 and E2
   both fail (each abstention regains the sibling's code).
2. Remove the name filter from the error-members union ⇒ E3 fails
   (alpha carries beta's `cant.resolve.location`) — the round-1
   finding's own RED receipt, run before the fix.

## Review record

- **Plan review**: 3 rounds. Round 1, 9 findings (2 HIGH — E1's
  fixture needed distinct code KINDS (two missing packages dedupe to
  one code) and the fallback rule extended to non-positive lines; 3
  MEDIUM — the (file, member) pairing named as a seam change, the
  unique-owner guard preserved, E3 demoted to a probe pin). Round 2, 2
  findings (NOPOS rows join every lookup; E1's literals probed at
  RED). Round 3 clean — loop closed, confidence 0.90.
- **Implementation review**: round 1, 6 findings (1 HIGH — the
  name-filter omission in the union, fixed red-first with E3; 2 LOW
  and 1 NIT dispositioned below; 2 NIT fixed). Round 2 verified every
  incorporation (the filter set reproduces the group exactly; the
  memberCodes reorder behavior-identical in all five input cells under
  the recording invariant) and found **no new addressable findings** —
  loop closed.

## Dispositions (review round 1, findings 2, 3, 6)

- **Finding 2 (the E1 literal)**: the RED cell asserted
  `compiler.err.cannot.find.symbol`; GREEN calibrated it to
  `compiler.err.cant.resolve.location`. The RED probe read javac's
  MESSAGE ("cannot find symbol"), not its CODE — the plan's
  probe-at-RED rule was followed but the probe itself measured the
  wrong field. Recorded so the rule is strengthened: probes read
  `Diagnostic.getCode()`, never the rendered message.
- **Finding 3 (the E1 flag-off twin)**: the plan's "flag-off twins for
  E1" control is covered by the existing D2 opt-out sweep (the
  generic all-keys flag-off assertion over framework and provider
  facts); the diff touches no gate, so a dedicated twin would pin
  nothing D2 does not. Dispositioned rather than added.
- **Finding 6 (union ordering)**: the union renders in
  member-declaration order then row order within each member, not the
  pre-#76 record order — the same SET for every input, a different
  order only for multi-error-member files. No assertion observes a
  multi-code joined string; noted as a same-set ordering difference.

## Behavior facts discovered (probe-verified)

- The javac CODE for an unresolvable simple name in a resolvable
  package is `compiler.err.cant.resolve.location` — the MESSAGE reads
  "cannot find symbol". A missing package prefix is
  `compiler.err.doesnt.exist`. Distinct KINDS, as the plan requires.
- `return; int x = 1;` is NOT an error in this javac (unreachable
  local declarations after return are accepted); an unresolvable
  VARIABLE in a body IS (`cant.resolve.location`) — E2's body-error
  shape.
- `Trees.getTree` is null for synthetic enum members (`values`,
  `valueOf`); implicit constructors have a VALID start (the class
  declaration line) with `end == NOPOSITION`; `getLineNumber(-1)`
  returns 0 without throwing.

## Residuals

- Class-level errors (reported at the type declaration, outside every
  member span) are visible only through the `member_absent` file-level
  join and the index diagnostics surface — the honest bound of a
  member-scoped join.
- Nested-class attribution asymmetry: errors inside a NESTED class's
  members fall in no outer-member span (the nested type is its own
  `TypeInfo`), while anonymous-class bodies inside a method fall
  inside the enclosing method's span.
- The deprecated-for-removal `SourcePositions` accessors are the only
  portable position API at `--release 17` (the suppression-inventory
  entry on `JavacMemberDiagnostics.position`); revisit when the
  repo's release floor moves.
- Parallel work (#78, separate session): disjoint sources; whichever
  PR merges second re-raises the `adapter-test` floor from the first
  one's count.

Confidence score: **0.90** — visibility-only with the narrowing
mutation-pinned twice (span filter, name filter), the conservative
fallbacks (NOPOS rows, unknown positions, unique-owner guard)
preserved and verified across three review surfaces, and the JDK 17
lint divergence clean.
