# RESOLUTION-24 — javac AnnotationMirror for framework annotation scanning

Issue: project-lightpath/java-analysis-adapter#24 (parent #20)
Branch: `feature/flpath-5142-framework-annotation-scanning` (from `origin/main` @ `5aeaee5`)
Plan: ephemeral per the AGENTS document-hygiene rule (5 fresh-session
adversarial review loops; loop-5 verdict PLAN-SOUND after fold-backs of
two CRITICAL/MAJOR local-class-collision findings, the on-demand-import
gate decision, the occurrence-parity guard, and the pre-drop arity rule).
This document and the PR body are the durable record.
Prior art: PLAN-22/RESOLUTION-22 (read layer, @Isolated pattern),
RESOLUTION-37 (MemberInfo.parameterAnnotations), RESOLUTION-21 (index seam).

## Summary

`JavacFrameworkAnnotations` (src/main, package-private) is the framework
annotation read layer over the #21 index. Flag-on
(`flpath.java.symbolindex`, still default OFF), `FrameworkRouteScanning`,
`FrameworkBeanScanning`, and `FrameworkInjectionScanning` read annotation
arguments, qualifiers, profiles, and conditions from index
`AnnotationMirror`s (rendered as `key=value` text and fed into the
UNCHANGED textual leaf shape functions), with silent textual fallback on
every correlation failure. Flag-off output is byte-for-byte unchanged.

Migrated sites (all verified to hold the correlation triple
file + owner + member/kind/arity):

- Route class- and method-level values: mapping paths, RequestMapping
  http methods, media values, headers, params, hasExplicitMappingPath,
  QUARKUS path values.
- Bean type-level qualifiers/profiles/conditions (Spring guarded arm,
  QUARKUS simple-name arm) and the producer path (class + method mirrors).
- Injection: field qualifiers (+ enrichment), method member qualifiers
  (+ setter decapitalize enrichment), constructor member qualifiers
  (unguarded), and per-parameter qualifiers on BOTH arms through the #37
  `parameterAnnotations()` API.

## Design rules (plan fold-backs, all cell-pinned)

1. **Textual gates stay the gates** (loop-1 F3 decision (a)): the
   import-based `canonicalSpringAnnotation` / `hasAnnotation` presence
   checks decide in both flag states and for both frameworks; the index
   supplies values only. The on-demand-import emission-widening vector
   does not exist. Spring mirrors are matched by
   `canonicalName ∈ canonicalSpringAnnotationTypes(simpleName)`
   (unique-or-fallback), never by simple name (loop-2: `@org...GetMapping
   @com.acme.GetMapping` on one member compiles).
2. **Occurrence-parity guard**: a site whose textual ASCII-visible
   annotation occurrences do not multiset-match the mirror simple names
   (type-use return spellings, nested annotation arguments, dotted
   spellings the mirrors would re-spell) falls back to the textual
   answer. This subsumes the nested-`@` fallback and pins that flag-on
   output diverges from textual only through the intended deltas.
3. **Local-class shape rejection**: owners resolving to LOCAL/ANONYMOUS
   nestings, or spelled with a `$digit` segment (loop-3 F1b:
   MEMBER-nested-inside-local binary `Outer$NLocal$Inner` escapes the
   nestingKind check), are rejected by shape — a deliberate safe
   over-approximation (`class A$1` also trips it; rejection = fallback).
4. **Pre-drop arity** (loop-1 F2): member correlation uses
   `splitArguments(raw).size()` before the textual parameter parser drops
   blank/separator-less entries; the post-drop count must equal it and
   the index arity, else the whole member falls back.
5. **Value-less-mirror decline** (GREEN-loop empirical finding): javac
   drops element values of annotations whose types are error types
   (unresolved imports — the common case when the framework jar is not on
   the index classpath). A mirror with an empty values map where the
   textual occurrence carried arguments declines (fallback), as does a
   rendered value containing `<error>` (e.g. an unimported enum
   constant). Without this guard the migration would silently drop
   values; it was found by the route parity cells, not by inspection.
6. **Six-family qualifier table** reproduced in exact code order
   (canonical-Spring guard → Resource → bean-name → NON_QUALIFIER →
   Qualifier/Named → simple name) with the `Named:`/`ResourceDefault:`
   enrichment appended after and simple-name trigger semantics; the
   field/method/constructor member arms stay unguarded (the textual
   `file == null` behavior), the bean type-level Spring arm and the
   Spring parameter arm stay guarded.
7. **Cardinality** (loop-1 F8): an annotation present with an empty
   values map renders one blank arguments entry (present-with-no-attributes
   parity); an absent annotation never reaches the index path.

## Intended flag-on deltas (cell-pinned, #22 canonical-spelling precedent)

- `$`-named and Unicode-named qualifier annotations emit their real
  simple names (`Mar` → `Mar$k`, invisible → `Ωmega`) at the bean
  type-level site and at discovered field sites.
- Condition evidence renders canonically:
  `@ConditionalOnProperty(name = "enabled")` →
  `@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="enabled")`.
  Downstream-safe: conditions are consumed only for emptiness and a
  joined attribute (verified by the plan review against
  `BoundedFrameworkModel` and the spike contracts).
- Static-import enum constants (`method = {GET, POST}`) are found
  flag-on (the index renders them fully qualified; the textual regex
  needs the `RequestMethod.` prefix the source does not spell).
- Folded string concatenation (`@GetMapping("a" + "b")`) resolves to
  `"ab"` flag-on; the textual literal extraction drops the route.

## Test matrix — `JavacFrameworkAnnotationMigrationTest` (@Isolated, SAME_THREAD)

18 cells: flag-off byte-for-byte + cache poisoning; Spring route/bean/
injection parity; QUARKUS route/qualifier parity (incl. varargs and
zero-parameter per-parameter alignment); string/comment decoys in both
states; lookalike annotations (user `@GetMapping`/`@Named`/`@Service`)
in both states; conditions canonical delta; `$`/Unicode corrections
(bean site + field site); local-class owner rejection (anonymous class
between same-named locals + member-type-nested-in-local); same-arity
overload ambiguity; comment-in-parameter-list arity drift; type-use
return annotation fallback; nested annotation argument fallback;
static-import enum delta; folded-concatenation delta; empty-mapping
cardinality; determinism. The class is @Isolated because it writes the
flag; no existing test was edited.

Fixture note: framework annotations used by value-bearing cells are
declared in the fixtures (test sources under the real package names) so
the index resolves them; the value-less-mirror decline keeps
unresolved-import fixtures (the real-world case) on the textual path.

## Review round 1 (post-implementation, CodeRabbit-style subagent)

CHANGES-REQUESTED with two real divergences and hardening; all folded
back in the same change (REVIEW_24_1) per the AGENTS same-change rule:

1. **MEDIUM — values-path occurrence-count parity**: the textual
   consumers union over EVERY occurrence (a nested `@Name` inside another
   annotation's arguments counts), while the index path answered from the
   one declaration mirror — `@Wrapper(@Profile("inner")) @Profile("prod")`
   would drop the inner profile flag-on. Fixed:
   `JavacFrameworkAnnotationValues.arguments` declines unless the textual
   occurrence count is exactly one; cell
   `nestedSameSimpleNameAnnotationArgumentFallsBackOnValues`.
2. **MEDIUM — phantom constructor correlation**: the unanchored
   constructor regex matches void methods spelled like the class.
   Empirically the phantom's annotations group is always empty (the
   return type separates the annotations from the name), so it is never
   selected as an injection point — but its match still inflates the
   textual site count. Fixed with a count-parity decline
   (`JavacFrameworkAnnotations.memberCount`): when the textual
   constructor-site count disagrees with the index constructor count, the
   whole file keeps the textual answer; cell
   `methodSpelledLikeTheClassFallsBackToTextual` pins both the
   non-selection and the decline.
3. **MINOR — dead `orElse(true)` default** in `mappingPaths` (silently
   route-suppressing if ever live): replaced with the already-extracted
   arguments' explicit-path check.
4. **MINOR — matrix gaps**: added cells for the values-path nested case
   (1), the phantom constructor (2), FQCN-spelled annotations, QUARKUS ×
   local-class owners, and QUARKUS user-defined `@Named` (the unguarded
   simple-name-semantics pin from the #24 design comment).
5. **INFO — duplicated unquoted-scan logic** unified in
   `JavacFrameworkAnnotations.renderIsUsable`.

Empirical correction from the FQCN cell: the textual pipeline NORMALIZES
dotted captures to simple names (`@org.springframework...Service("fq")`
emits `BeanName:fq`), so the occurrence-parity guard now accepts a
package-qualified capture for its simple-name mirror — FQCN-spelled
qualifier sites migrate instead of falling back, and
`fqcnSpelledAnnotationsMatchSimpleNameMirrors` pins the both-state
agreement. The residual list's FQCN bullet is withdrawn accordingly.

## Review round 2 / CI rounds (post-implementation)

Round 2 (same reviewer, delta): the round-1 count guard applied only to
`arguments()` — `conditionAnnotations` and QUARKUS `pathValues` built
their pipelines directly; both now require the textual occurrence count
to be exactly one (REVIEW_24_2, cell
`nestedArgumentsFallBackOnConditionsAndPaths`), and the theoretical
`countsMatch` balance vector is documented as a residual.

CI round 1 (REVIEW_24_3): the `$`/Unicode correction cells passed on
JDK 27 but declined on Temurin 17 — first mis-attributed to the fixture
file spellings, then reproduced locally with Temurin 17 and traced to
the real cause:

**Some compilers drop unresolved annotation types from
`getAnnotationMirrors` entirely** (Temurin 17) while others retain them
as error mirrors (JDK 27). The occurrence-parity guard then saw textual
occurrences without mirrors and declined — which would also have made
the migration silently vacuous for unresolved framework annotations
(the common no-classpath case) on the CI/production JDK.

Fixed in REVIEW_24_4 by REPLACING the whole-site parity guard with
per-occurrence processing: each textual occurrence takes its mirror's
families when the mirror can stand in for it (resolved, usable rendered
values — where the `$`/Unicode corrections come from), and the TEXTUAL
family answer otherwise (unresolved, type-use, or nested occurrences —
nothing dropped, nothing fabricated). Unicode-initial mirrors with no
possible occurrence append as pure index corrections; an ASCII-named
mirror no occurrence consumed declines the site. The enrichment
triggers moved to the textual `hasAnnotation` (exact textual simple-name
semantics). The redesign was verified on BOTH Temurin 17 and JDK 27.

Round 3 (coordinating session, read-only, head d5961cc): (1) stale
receipts — the gate-receipts table now records every commit (below);
the PR body's 447 receipt was current, the "385" in the finding was
RESOLUTION-22's number; (2) `renderIsUsable` could be defeated by an
unescaped quote in a rendered string value (the #48 renderer gap)
flipping the quote tracking — it now also declines on an odd quote
count, with cell `unescapedQuoteInRenderedValueFallsBackToTextual`
(the conservative direction until #48 lands).

## Gate receipts

| Commit | adapter-test | java-lint | evidence-validate |
|---|---|---|---|
| RED_24 | 5 of 18 cells fail exactly as partitioned (the five delta cells); 13 parity/fallback cells green; rest of suite green | pass (stub, 0 new) | pass |
| GREEN_24 + EVIDENCE_24 | 441/441 green locally (Makefile floor tightened 399 → 441; main ran 423 before these 18 cells) | 0 new findings, 0 CPD (GodClass split into three classes and record-parameter collapses are lint-driven, behavior-identical) | pass (10 criteria) |
| REVIEW_24_1 | 446/446 green locally (floor 441 → 446, the five review cells) | 0 new findings, 0 CPD | pass |
| REVIEW_24_2 | 447/447 green locally (floor 446 → 447, the nested conditions/paths cell) | 0 new findings, 0 CPD | pass |
| REVIEW_24_3 | 447/447 green locally (fixture-only change; CI still red on Temurin 17 — the mis-diagnosis) | 0 new findings, 0 CPD | pass |
| REVIEW_24_4 | 448/448 green locally on BOTH Temurin 17.0.21 and JDK 27 (floor 447 → 448, the odd-quote cell; the per-occurrence redesign fixed the CI-only decline); CI re-runs the full suite | 0 new findings, 0 CPD | pass |

## Residuals

- Discovery regexes (INJECT_FIELD, constructor pattern, cdiBean/
  springComponent/springRepositoryContract) and evidence-symbol
  spellings (routeSymbols, annotation source occurrences) stay textual
  in both flag states: the index has no positions, and the symbol
  evidence is source text the index cannot reproduce. Unicode-named
  annotations still cannot make a field discoverable as an injection
  point (the injection-annotation set is fixed ASCII); a Unicode
  qualifier on a discovered field IS corrected.
- FQCN-spelled qualifier annotations: MIGRATED (review round 1 — the
  textual pipeline normalizes dotted captures to simple names, and the
  parity guard accepts package-qualified captures for their simple-name
  mirrors); the `fqcnSpelledAnnotationsMatchSimpleNameMirrors` cell pins
  the both-state agreement. Dotted captures whose mirror is nested or
  ambiguous still fall back by the unique-mirror rule.
- Unresolved annotation types (framework jar absent from the index
  classpath) keep the textual answer via the value-less-mirror decline;
  the index path engages when the types resolve (classpath present or
  fixture-declared).
- Same-arity overload ambiguity, local/anonymous owners, comment-hidden
  arity, error types, duplicate binary names: silent textual fallback
  (measured by #26's parity harness).
- The constructor count-parity guard compares the per-owner index
  constructor count with the file-wide textual site count; a phantom
  regex match (+1 textual) could in theory balance against a real
  constructor the regex fails to capture (an unmasked `)` in a parameter
  list), leaving the counts equal while a phantom exists. No compilable
  counterexample was constructible (maskAnnotationArguments covers the
  annotation-argument paren case, and the phantom's empty annotations
  group keeps it unselected unless it is the only site), so this is
  documentation-grade; the #26 parity harness owns the measurement.
- Renderer quote-escaping (`AnnotationRenderer` does not re-escape `"`
  in string values) is #21 code: filed as a follow-up issue rather than
  grown here (scope-creep rule).
