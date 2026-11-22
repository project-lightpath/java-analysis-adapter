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

## Gate receipts

| Commit | adapter-test | java-lint | evidence-validate |
|---|---|---|---|
| RED_24 | 5 of 18 cells fail exactly as partitioned (the five delta cells); 13 parity/fallback cells green; rest of suite green | pending (stub) | pass |
| GREEN_24 | see PR checks (full count; Makefile floor tightened to the exact new total — main had drifted to 399 vs 423 actual) | see PR checks | see PR checks |

## Residuals

- Discovery regexes (INJECT_FIELD, constructor pattern, cdiBean/
  springComponent/springRepositoryContract) and evidence-symbol
  spellings (routeSymbols, annotation source occurrences) stay textual
  in both flag states: the index has no positions, and the symbol
  evidence is source text the index cannot reproduce. Unicode-named
  annotations still cannot make a field discoverable as an injection
  point (the injection-annotation set is fixed ASCII); a Unicode
  qualifier on a discovered field IS corrected.
- FQCN-spelled annotations (`@org.springframework...Service`) fall back
  (occurrence parity): the textual path emits dotted names for them, a
  shape the simple-name mirrors cannot reproduce.
- Unresolved annotation types (framework jar absent from the index
  classpath) keep the textual answer via the value-less-mirror decline;
  the index path engages when the types resolve (classpath present or
  fixture-declared).
- Same-arity overload ambiguity, local/anonymous owners, comment-hidden
  arity, error types, duplicate binary names: silent textual fallback
  (measured by #26's parity harness).
- Renderer quote-escaping (`AnnotationRenderer` does not re-escape `"`
  in string values) is #21 code: filed as a follow-up issue rather than
  grown here (scope-creep rule).
