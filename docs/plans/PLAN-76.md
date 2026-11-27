# PLAN-76 — Member-level line join for the javac abstention diagnostics

Issue: project-lightpath/java-analysis-adapter#76 (follow-up from #62 /
PR #72; scope recorded in `docs/resolutions/RESOLUTION-62.md`
Residuals).
Branch: `issue-76-member-level-diagnostics` from `origin/main` @
`1a2486f` (the PR #72 merge).

## Preflight (verified on this main, 2026-11-27, JDK 27)

- **The current join is file-level.** `JavacAbstentionEvidence`'s
  `indexDiagnostics` carries every `compiler.err.*` code recorded for
  the member's declaring FILE (`errorCodesFor(Path)`, unique-owner-only
  — a file with two error members attributes BOTH codes to EACH
  abstention). Three consumers: `JavacMemberCorrelation.errorCodesFor`
  (`error_type_member`, `member_absent`), `JavacAnnotationMemberLookup.
  uniqueOwnerErrorCodes` (`annotation_member_error_type`),
  `JavacFrameworkMemberLookup` (`mirror_member_error_type`).
- **The rows carry line data.** `IndexDiagnostic` records
  `line`/`column` from javac's own diagnostics; the record-time capture
  (`recordErrorCodesByFile`) currently discards the line, keeping only
  the code, keyed by the raw `Path` (never the rendered row path).
- **Position spike (scratch, outside the tree; the plan's load-bearing
  measurement).** A javac probe over a fixture with an error-typed
  method, an implicit constructor, an enum with synthetic methods, and
  a nested class:
  - `Trees.getTree(member)` is NULL for synthetic enum members
    (`values`, `valueOf`) — no position exists.
  - `getEndPosition` returns `-1` for implicitly-declared constructors
    (`endLine` renders 0) — a point span, not a range.
  - Real members carry exact spans (`broken` lines 3..3, `probe`
    6..6, `inner` 8..8), and the fixture's
    `compiler.err.doesnt.exist` error (line 3) falls INSIDE `broken`'s
    span — the member-level join captures it. The implicit
    constructor carried a VALID start (the class declaration line,
    start=21 → line 2) with end=-1; synthetic members had null trees.
    A `start == -1` position was NOT observed in the spike — the
    unknown-position rule below treats ANY non-positive line as
    unknown (defensive for the unobserved case, per AGENTS boundary
    discipline).
  - Nested TYPE elements also appear in `getEnclosedElements` but are
    excluded by `isMember` (METHOD/CONSTRUCTOR/FIELD/ENUM_CONSTANT
    only) — type positions are irrelevant to `MemberInfo`.
  - `SourcePositions.getStartPosition/getEndPosition` are
    deprecated-for-removal on newer JDKs (warning only; the repo
    compiles `--release 17` and both JDKs in play provide them).
- **The construction site is single.** `ElementReader.memberInfo` is
  the only `MemberInfo` constructor; no test constructs `MemberInfo`
  directly (verified), so the additive component needs no
  compatibility constructor. The `Extractor` already holds `Trees` and
  visits `ClassTree`s with the `CompilationUnitTree` in scope
  (`visitClass`), so the position is recordable at the same site.
- **Parallel work**: issue #78 runs in a separate worktree on the
  scanner capture seams (ReflectionLayer05, feature builders) —
  disjoint from this change; the shared test file
  (`JavacAbstentionEvidenceTest`) gets append-only cells on both
  branches.
- **Gates on this main**: `make adapter-test` floor 735 (observed
  735); `make java-lint`; `make evidence-validate`. JDK 17 divergence
  (#64): the touched classes are not near PMD limits, but lint is
  verified on helios08 with `~/jdk17` and a scratch checkout before
  pushing per the standing rule.

## Design

### The position component (additive, the publicMember/staticType precedent)

```java
record MemberPosition(long startLine, long endLine) {}
```

`MemberInfo` gains `MemberPosition position`. Recorded in
`ElementReader.memberInfo` — which therefore needs the member's tree
and the compilation unit: the `Extractor` passes its `Trees` into
`ElementReader` (constructor) and the unit through
`typeInfo(element, file, unit)` → `memberInfo(element, unit)`. Per the
spike:

- `Tree tree = trees.getTree(element)`; null (synthetic members) →
  position `null` (unknown).
- `end == -1` (implicit constructors) → `(startLine, startLine)` — a
  point span.
- Otherwise `(startLine, endLine)` inclusive.

`MemberInfo.position()` is nullable; every reader treats null as
"unknown position".

### The record-time line capture

`recordErrorCodesByFile` additionally records the LINE with each code:
`Map<Path, List<ErrorCodeRow(long line, String code)>>
errorLinesByFile` (new; `errorCodesByFile` stays for the file-level
API). Keyed by the same raw normalized `Path`, deep-copied at
`finish()`.

### The member-level join

New index accessor:

```java
List<String> errorCodesForMember(Path file, MemberInfo member)
```

- Joins `errorLinesByFile` by the member's file, keeping the codes of
  rows whose line lies within `[startLine, endLine]` inclusive,
  deduped by code at JOIN time (the record-time map keeps EVERY
  `(line, code)` row — the same code on multiple lines is one row
  each; `errorCodesByFile`'s record-time code dedup stays as is for
  the file-level API). Line numbers come from
  `CompilationUnitTree.getLineMap()` at record time.
- Unknown position — `member.position() == null` OR ANY non-positive
  line (`startLine < 1`; defensively covers an unobserved
  `start == -1`, plan-review round 1, finding 2) → the file-level set
  (`errorCodesFor(file)`): the conservative #62 behavior (a synthetic
  member cannot be narrowed; dropping codes would under-report).
  Symmetrically, a diagnostic ROW with a non-positive line
  (`Diagnostic.getLineNumber() == NOPOS`, plan-review round 2, F1) is
  treated like unknown position: its code joins EVERY member-level
  lookup for that file, never silently dropped.
- Empty result when the file recorded no errors — same as today.

### The consumers

1. **`JavacAnnotationMemberLookup`** (`annotation_member_error_type`):
   the found member is specific (`matches.get(0)`); the join switches
   to `errorCodesForMember(ownerFile, found)` — the owner entry is the
   unique `index.type(typeName)` entry already in hand.
2. **`JavacFrameworkMemberLookup`** (`mirror_member_error_type`): the
   found member is specific; same switch with the unique owner
   entry's file.
3. **`JavacMemberCorrelation`**:
   - `error_type_member`: the current group construction
     (`index.members(ownerType, indexName)`) returns bare `MemberInfo`
     with NO owner file (plan-review round 1, finding 3) — the seam
     changes to iterate `index.type(ownerType)` entries directly and
     build `(file, member)` pairs, preserving the existing
     kind/arity/name filters. The abstention carries the union of the
     error-typed members' member-level codes (deduped, recording
     order) — computed ONLY when the owner resolves to exactly one
     `type(ownerType)` entry; with multiple same-name entries the
     union stays `List.of()` (the #62 unique-owner guard and its
     rationale — "the ambiguity is already the decline's story" — are
     preserved verbatim; plan-review round 1, finding 4).
   - `member_absent`: NO member exists, so there is no span — the
     join stays FILE-level (unchanged #62 behavior, documented: a
     missing member's cause is often an error elsewhere in the file,
     e.g. the whole class failed to resolve).

### What deliberately does not change

- The abstention codes, member identities, attribute keys, and the
  visibility-only contract (certainty, status, limitation, fact IDs).
- `scan.diagnostics()` — untouched.
- The file-level `errorCodesFor(Path)` API (still used by
  `member_absent` and the unknown-position fallback).
- The opt-out path (no evidence with the flag off — unchanged gates).

## Test matrix (append-only cells in `JavacAbstentionEvidenceTest`)

- E1 **precision (the issue's core)**: one single-type file with TWO
  error-typed members carrying DISTINCT javac codes — one member with
  a missing-PACKAGE return type (`compiler.err.doesnt.exist`), one
  with an unresolvable SIMPLE-NAME parameter type in a resolvable
  package (`compiler.err.cannot.find.symbol`) — two missing package
  prefixes would yield the SAME code and dedupe, so the codes must
  differ by KIND (plan-review round 1, finding 1). The exact javac
  code literals are enumerated at RED from a probe (plan-review round
  2, F2: the repo pins `doesnt.exist` for missing packages; the
  simple-name failure literal — `cannot.find.symbol` or
  `cant.resolve.location` — is probed, not pre-committed). Each
  member's abstention carries ONLY its own code (pre-change both
  abstentions carry both codes).
- E2 **body-error exclusion (plan-review round 1, finding 6)**:
  member A error-typed at its signature; member B carries an error
  INSIDE its body (e.g. an unreachable statement) without being
  error-typed — A's abstention carries ONLY A's code, where the
  file-level join carried both.
- E3 **implicit-constructor point span**: DEMOTED to a probe-receipt /
  code-inspection pin decided at RED (plan-review round 1, finding 5)
  — an implicit constructor is never the abstaining member in any
  consumer (correlation abstentions reference textual declarations,
  which implicit constructors lack; mirror lookups target annotated
  members), so the `end == -1` point-span rule is not observable
  end-to-end. The rule stays in the implementation (the spike
  receipt pins it) with a unit-level characterization if RED finds a
  constructible path.
- E4 **synthetic-member fallback**: unknown position falls back to the
  file-level set (characterized via an enum whose synthetic member is
  the abstaining one, or — if not constructible end-to-end — via the
  fallback unit path; the spike says synthetic members are not
  error-typed in practice, so this may document as a code-inspection
  pin with a probe receipt rather than a cell; decided at RED).
- E5 **member_absent keeps the file-level join**: unchanged from #62
  (characterization; the codes are the file's, not a span's).
- Controls: C2 (the existing `error_type_member` cell) stays green —
  the missing-`missing.Thing` error sits on `broken()`'s own line, so
  the member-level join yields the same code; flag-off twins for E1;
  no `javacAbstentionDiagnostics` regressions elsewhere (grep: only
  C2 asserts the attribute today).

Sanctioned existing-test edits: none expected (C2's assertion is the
`compiler.err.` family prefix, robust to the narrowing); if a golden
shifts, enumerated at RED with observed values.

## TDD / gates / commits

1. RED_76: the E-cells failing (E1/E2/E3 red against the file-level
   join; E4/E5 as decided at RED).
2. GREEN_76: position component + line capture + member-level joins.
3. REVIEW_76 rounds: fresh Engram-grounded adversarial subagents
   (subagents use the engram MCP tools — plain grep is blocked in
   their environment), findings addressed red-first, loop until clean.
4. EVIDENCE_76: `docs/resolutions/RESOLUTION-76.md` with gate
   receipts, mutation checks (revert the span filter ⇒ E1 fails with
   both codes; drop the end=-1 handling ⇒ E3 fails; drop the null-tree
   fallback ⇒ probe-receipted), the spike receipt, residuals.
5. Gates after every item: `make adapter-test` (floor raised from
   whatever main reports), `make java-lint` (helios08 JDK 17
   verification), `make evidence-validate`.
6. PR base `main`, head `issue-76-member-level-diagnostics`;
   `@coderabbitai full review` after pushing; every finding answered
   red-first; no manual thread resolution; no merge (user decides).

## Residuals (to state in the resolution)

- Class-level errors (reported at the type declaration, outside every
  member span) are visible only through the `member_absent` file-level
  join and the index diagnostics surface, not through member-level
  abstentions — the honest bound of a member-scoped join.
- Nested-class attribution asymmetry (plan-review round 1, finding 7):
  errors inside a NESTED class's members fall in no outer-member span
  (dropped from member-level joins; the nested type is its own
  `TypeInfo`), while anonymous-class bodies inside a method DO fall
  inside the enclosing method's span (attributed to that method) —
  documented as the honest bound.
- The deprecated-for-removal `SourcePositions` accessors are the only
  portable position API at `--release 17`; revisit when the repo's
  release floor moves.
- Parallel work (plan-review round 1, finding 8): #78 runs
  concurrently on the scanner seams (disjoint sources; the shared
  `JavacAbstentionEvidenceTest` gets append-only cells on both
  branches); whichever of the two PRs merges SECOND must re-raise the
  `adapter-test` floor from the first one's count.

## Plan review record

Round 1 (Engram-grounded adversarial subagent): 9 addressable findings
(2 HIGH, 3 MEDIUM, 2 LOW, 2 NIT), all incorporated: E1's fixture
re-specified for DISTINCT code kinds (two missing packages dedupe to
one code — finding 1, HIGH); the unknown-position rule extended to any
non-positive line, defensively covering the unobserved `start == -1`
(finding 2, HIGH); the `error_type_member` group construction named as
a seam change (iterate `type(ownerType)` entries for `(file, member)`
pairs — finding 3); the unique-owner guard preserved verbatim for the
union (finding 4); E3 demoted to a probe-receipt pin (finding 5); E2
re-specified as the exclusion form (finding 6); the nested-class
attribution asymmetry added to residuals (finding 7); the #78
parallel-work floor note (finding 8); the `errorLinesByFile` row
semantics and LineMap specified (finding 9). Verified clean: the
single `typeInfo` caller, positions surviving `flaggedCopy`, no direct
test constructions of `MemberInfo`.
Confidence score after round 1: **0.87**.

Round 2 (fresh adversarial subagent): 2 addressable findings (1
MEDIUM, 1 LOW), both incorporated: a diagnostic ROW with a
non-positive line (`NOPOS`) joins every member-level lookup for its
file rather than being silently dropped (F1 — the symmetric
under-report guard); E1's javac code literals are probed at RED, not
pre-committed (F2 — the repo pins `doesnt.exist` only; the
simple-name failure literal is probed). Verified clean: the (file,
member) pairing preserves the bijection's inputs exactly (identical
group list and order); the unique-owner guard holds on both
`error_type_member` and `member_absent`; no `MemberInfo`
equality/hashCode consumers; a single `ElementReader` instantiation;
E1's two members produce two distinguishable end-to-end abstentions.
Confidence score after round 2: **0.90**.

Confidence score (pre-plan-review): **0.82** — the design is grounded
in a position spike on the exact boundary classes (null trees,
end=-1), a single verified construction site, and the #62 join's
shape; the open risks are the E4 synthetic-member cell's
constructibility (decided at RED) and the group-union semantics for
`error_type_member` (a plan-review question), each with a named
fallback.
