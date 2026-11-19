# RESOLUTION-9 — CodeRabbit findings on PR #12

Two inline findings, both triaged as valid. Neither changes the issue's scope
items (a)/(b)/(c); both fix code introduced by this PR. The independent review
of this document (round 1) found ten actionable gaps; all are folded in below.

## Finding 1 — annotation names containing `$` defeat argument masking

File: `src/main/java/com/projectlightpath/analysis/java/ServiceLoaderInheritance.java:209`
(shared helper: `SourceMetadataScannerTextLayer07.maskAnnotationArguments`).

**Validity: valid.** `maskAnnotationArguments` uses `@[A-Za-z_][\w.]*`. Java
identifiers may contain `$` at any position, so `@$Mark({Object.class})` is
not recognized, its argument braces are not masked, and
`declarationModifiers` stops at the `}` inside the array initializer. A
preceding `public` is lost, a public cross-package member type is classified
package-private, and the site is attributed to `java.util.ServiceLoader` — a
false DEFINITE.

**Resolution:** add one shared pattern to `SourceMetadataScannerState`
(`@\p{javaJavaIdentifierStart}[\p{javaJavaIdentifierPart}.]*`), use it in
`maskAnnotationArguments`, and use it at the three `replaceAll` sites that
duplicate the same name rule. This intentionally broadens those sites to
Unicode-initial and `$`-containing annotation names; for ASCII
letter/underscore/dot names the matched span is identical.

**Complete manifestation inventory (AGENTS.md weakness disposition).** The
same `$`-unsafe annotation-name assumption appears in these fourteen places:

| Site (1-based) | Shape | Disposition |
|---|---|---|
| `SourceMetadataScannerTextLayer07.java:85` | mask helper | **fixed here** |
| `SourceMetadataScannerTextLayer12.java:23` | strip annotation from return type | **fixed here** |
| `SourceMetadataScannerTextLayer19.java:175` | strip annotation from parameter | **fixed here** |
| `SourceMetadataScannerTextLayer02.java:80` | strip annotation from parameter type | **fixed here** |
| `SourceMetadataScannerState.java:25-26` (`METHOD`) | method-declaration regex | deferred, safe direction (method lost → call site abstains) |
| `SourceMetadataScannerState.java:101-102` (`INJECT_FIELD`) | field-declaration regex | deferred, safe direction (injection fact lost) |
| `SourceMetadataScannerTextLayer11.java:38` | constructor regex | deferred; the pattern re-anchors only at `;`, `{`, `}`, or a newline (`TextLayer11.java:37`), so when the annotation and header share a line the constructor `MethodInfo` is lost and dynamic sites in that body lose their explicit caller, and when the annotation is on its own line the constructor is kept with an empty annotation block (qualifier lost) — not proven safe in either form, same follow-up |
| `SourceMetadataScannerTextLayer15.java:25` | field/initializer regex | deferred, safe direction |
| `SourceMetadataScannerReflectionLayer03.java:25` | reflection field regex | deferred, safe direction |
| `SourceMetadataScannerReflectionLayer03.java:30` | reflection field regex | deferred, safe direction |
| `SourceMetadataScannerReflectionLayer03.java:47` | field-start regex | deferred, safe direction |
| `FrameworkInjectionScanning.java:48` | constructor-injection regex | deferred; annotation/qualifier text lost, constructor still recognised (implicit fallback), so qualifier-based selection can change |
| `SourceMetadataScannerTextLayer18.java:115` | qualifier enumeration | deferred; a `@$Qualifier` is invisible, so the qualifier set can shrink and an ambiguous bean match can become unique — **not proven safe**, same follow-up |
| `SourceMetadataScannerTextLayer09.java:26` | annotation occurrence lookup | deferred, latent (no caller queries a `$`-named annotation) |

The deferred sites mostly lose a method/field/injection fact and abstain. The
qualifier-enumeration site can change wiring uniqueness and is tracked as a
follow-up risk rather than claimed safe. All are recorded as an annotation-name
follow-up alongside the FLPATH-5124 policy work instead of being expanded into
this fix.

**Regression test (RED):** `public class Base` in `other` declares
`public @$Mark({Object.class}) static class ServiceLoader<T>` plus a nested
member `@interface $Mark { Class<?>[] value(); }`; `demo.Bridge extends Base`
must abstain. Test method:
`dollarNamedAnnotationArgumentDoesNotHidePublicMemberType`. At head the
annotation argument is unmasked, `public` is lost, and the site is attributed.

**Guards:** existing `privateTypeAfterArrayAnnotationDoesNotShadow` and
`privateNestedServiceLoaderDoesNotShadow` keep plain/array annotations working.

## Finding 2 — truncated `$` prefix can select an unrelated top-level type

File: `src/main/java/com/projectlightpath/analysis/java/SourceMetadataScannerTextLayer00.java:26`.

**Validity: valid.** The loop returns the first prefix whose `typeName`
matches. For owner `demo.Outer$Inner$Leaf`, a selected top-level
`demo/Outer$Inner.java` (type name `demo.Outer$Inner`) is returned before the
loop reaches `demo.Outer` (which declares `Inner.Leaf`). Before this PR the
first-`$` lookup selected `Outer.java`; the progressive loop introduced the
wrong-file regression.

**Consumer blast radius:**

- **Fabricates sites:** `ServiceLoaderSourceCache.source` caches and parses the
  selected file's call sites wholesale (`ServiceLoaderSourceCache.java:87-89,
  109-117`). A wrong file here can produce wrong ServiceLoader facts. This is
  the false-DEFINITE vector.
- **Degrades to empty / re-filters:** `declaredType` compares
  `typeContaining(...) == typeName`; `methodSource` filters
  `typeContaining(...) == symbol.owner()`; `sourceLineContains` only reads a
  line; `ServiceLoaderCallSiteReader.read` re-selects by caller/callee/receiver
  and uses the file for type resolution. A wrong file makes these empty or
  unresolved rather than fabricating a fact.

**Resolution:** keep exact `typeName` matching first. For each non-exact prefix
candidate, derive the candidate's declared owner paths from one
`SourceMetadataScanner.typeDeclarations(candidate)` list, mapping each declared
type through `SourceMetadataScanner.typeContaining(candidate, type.start())`
(the qualified form used by `TextLayer17:135` and `TextLayer20:42`; unqualified
`typeContaining` does not resolve from the base `TextLayer00`), then accept the
candidate only when one declared path:

- equals `owner` (the file declares the complete nested chain), or
- is a proper prefix of `owner` and the remainder after it begins with `$`
  followed by a digit (anonymous/local suffix such as `$1` or `$1Local`).

Otherwise continue to the next shorter prefix. Use
`filter(typeName::equals).filter(predicate).findFirst()` so a valid sibling
with the same `typeName` is not skipped. Parse adjacent `$` components
correctly (`demo.Outer$$Inner` has first nested segment `$Inner`), and keep the
loop bounded by the last package dot.

This replaces the earlier draft's redundant "first dropped segment" rule,
which could accept a decoy file that declares only `Leaf` under an unrelated
`Outer$Inner` top-level. The digit rule is defined over declared paths, not
over loop steps, so `demo.Outer$Inner$1` with only `Outer.java` selected
resolves to `Outer.java` (declared path `demo.Outer$Inner` + digit remainder)
instead of regressing to empty.

**Regression test (RED):** `demo/Outer.java` declares `Inner.Leaf`;
`demo/Outer$Inner.java` is an unrelated top-level `Outer$Inner` without
`Leaf`. Owner `demo.Outer$Inner$Leaf` must resolve `Outer.java`, not
`Outer$Inner.java`. At head it returns `Outer$Inner.java`.

**Guards (each a named test):**

- `nestedOwnerWithDollarTopLevelPrefixPrefersDeclaringFile` — the RED cell.
- `nestedOwnerStillResolvesEnclosingTopLevel` — `demo.Outer$Inner` →
  `Outer.java` (covered by the existing
  `ordinaryNestedOwnerFallsBackToTopLevel`).
- `nestedInsideDollarNamedTopLevelIsResolved` — `demo.Cash$Flow$Inner` →
  `Cash$Flow.java` (existing C4).
- `anonymousOwnerResolvesEnclosingTopLevel` — `demo.Outer$1` → `Outer.java`.
- `nestedAnonymousOwnerResolvesEnclosingTopLevel` — only `demo/Outer.java` is
  selected; `demo.Outer$Inner$1` → `Outer.java` (declared-path digit rule).
- `exactTopLevelDollarOwnerResolvesItself` — when `Outer$Inner.java` exists,
  owner `demo.Outer$Inner` resolves that exact file.
- `dollarBoundaryEdgesAreResolved` — adjacent `$$` and single-character
  components (existing).

**Residuals:**

- Owners whose dropped suffix is a non-digit synthetic segment (e.g. a lambda
  `$$Lambda$...`) now return empty instead of guessing the enclosing file;
  safe direction.
- For `demo.Outer$Inner$1`, the longest matching prefix wins: a selected
  `Outer$Inner.java` is preferred over `Outer.java` when both are selected
  (same exact-first ambiguity as the `demo.Outer$Inner` residual). The guard
  above deliberately selects only `Outer.java`.
- Exact-first wins for `demo.Outer$Inner` whenever `Outer$Inner.java` is
  selected, even if `Outer.java` also declares `Inner`. That pair cannot
  compile together but source scanning can reach it; cross-reference the
  existing `PLAN-9.md` collision residual.
- Deferred annotation-name sites above remain a follow-up.

## TDD / gate plan

- `RED_09c`: the two failing tests plus the three new `(c)` guard tests named
  above (the existing C3/C4 and boundary tests already cover the rest).
- `GREEN_09c`: shared annotation pattern (four sites) and the prefix
  eligibility predicate.
- `EVIDENCE_09c`: PLAN/resolution records and full gate receipts.
- Gates: `make adapter-test` (count must grow from 304), `make java-lint`
  (zero findings), `make evidence-validate` (pass).
- No diagnostics are added; `ServiceLoaderScanner.isUnique` behavior is
  unchanged (scan-global blast radius none).
