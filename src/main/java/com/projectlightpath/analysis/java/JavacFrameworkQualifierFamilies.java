package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Qualifier-family interpretation over index mirrors: the six-family table
 * (canonical-Spring guard, Resource, bean-name, NON_QUALIFIER,
 * Qualifier/Named, simple name) with the injection enrichment derivations
 * appended after, reproducing the textual pipeline's exact code order and
 * simple-name trigger semantics.
 *
 * <p>Processing is per textual occurrence: an occurrence whose mirror is
 * found and can stand in for it (resolved, with usable rendered values)
 * takes the mirror's families — this is where the {@code $}/Unicode name
 * corrections come from; every other occurrence (the annotation type did
 * not resolve — some compilers drop error-typed mirrors from
 * {@code getAnnotationMirrors} — or the occurrence is type-use or nested)
 * keeps the textual family answer, so nothing is dropped and nothing
 * fabricated. Mirrors with no possible textual occurrence (Unicode-initial
 * names) append as pure index corrections. A mirror with an ASCII name that
 * no occurrence consumed means the correlation is untrustworthy: the site
 * declines.
 */
final class JavacFrameworkQualifierFamilies {
  /**
   * The textual occurrence pattern of {@code qualifierNames}; used to walk
   * occurrences in textual order, never for value extraction.
   */
  private static final Pattern TEXTUAL_ANNOTATION_NAME = Pattern.compile("@([A-Za-z_][\\w.]*)");

  private JavacFrameworkQualifierFamilies() {}

  /** Six-family qualifier table on index mirrors (guarded arm optional). */
  static Optional<List<String>> qualifiers(
      SourceMetadataScannerState.SourceFile file,
      String block,
      List<JavacSourceSymbolIndex.AnnotationInfo> mirrors,
      boolean guarded) {
    String source = SourceMetadataScanner.stripComments(block == null ? "" : block);
    String masked = SourceMetadataScanner.maskStringLiterals(source);
    List<String> result = new ArrayList<>();
    List<JavacSourceSymbolIndex.AnnotationInfo> remaining = new ArrayList<>(mirrors);
    Matcher matcher = TEXTUAL_ANNOTATION_NAME.matcher(masked);
    while (matcher.find()) {
      addOccurrenceFamilies(file, block, source, matcher, guarded, remaining, result);
    }
    for (JavacSourceSymbolIndex.AnnotationInfo mirror : remaining) {
      if (asciiPrefix(JavacFrameworkAnnotations.simpleName(mirror)).isEmpty()) {
        List<String> families =
            JavacFrameworkQualifierTable.mirrorFamilies(file, mirror, guarded, null);
        if (families != null) {
          result.addAll(families);
        }
      } else {
        // An ASCII-named mirror with no textual occurrence cannot be
        // attributed: decline the whole site.
        return Optional.empty();
      }
    }
    return Optional.of(List.copyOf(new LinkedHashSet<>(result)));
  }

  /**
   * Resolves one textual occurrence's families: the paired mirror's table
   * answer when it can stand in, else the textual occurrence answer (the
   * no-mirror arm, and the crossed-pairing decline — the textual answer
   * carries the per-occurrence guard and value, the safe direction).
   */
  private static void addOccurrenceFamilies(
      SourceMetadataScannerState.SourceFile file,
      String block,
      String source,
      Matcher matcher,
      boolean guarded,
      List<JavacSourceSymbolIndex.AnnotationInfo> remaining,
      List<String> result) {
    SourceMetadataScannerState.ParsedAnnotation occurrence =
        SourceMetadataScanner.parseAnnotation(source, matcher);
    JavacSourceSymbolIndex.AnnotationInfo mirror =
        takeMirror(remaining, matcher, occurrence);
    List<String> families =
        mirror == null || !mirrorCanReplace(mirror, block)
            ? null
            : JavacFrameworkQualifierTable.mirrorFamilies(
                file, mirror, guarded, occurrence);
    if (families != null) {
      result.addAll(families);
    } else {
      result.addAll(SourceMetadataScanner.annotationQualifiers(
          guarded ? file : null, source, matcher));
    }
  }

  /** Qualifier table plus the injection enrichment derivations. */
  static Optional<List<String>> injectionQualifiers(
      SourceMetadataScannerState.SourceFile file,
      String block,
      List<JavacSourceSymbolIndex.AnnotationInfo> mirrors,
      String defaultName,
      boolean cdi,
      boolean setterMethod) {
    return qualifiers(file, block, mirrors, false).map(families -> {
      List<String> result = new ArrayList<>(families);
      // The enrichment triggers reproduce the textual simple-name
      // semantics over the raw block, exactly as the textual path does.
      boolean hasNamed = block != null
          && SourceMetadataScanner.hasAnnotation(block, "Named");
      if (cdi && hasNamed) {
        for (int index = 0; index < result.size(); index++) {
          if (result.get(index).equals("Named:") && !defaultName.isBlank()) {
            result.set(index, "Named:" + defaultName);
          }
        }
      }
      boolean hasResource = block != null
          && SourceMetadataScanner.hasAnnotation(block, "Resource");
      if (hasResource
          && result.stream().noneMatch(value -> value.startsWith("Resource:"))) {
        result.add("ResourceDefault:" + setterDefaultName(defaultName, setterMethod));
      }
      return List.copyOf(result);
    });
  }

  /**
   * Reports whether a mirror can stand in for its textual occurrence: the
   * rendered values must be usable (no unquoted nested annotation or
   * unresolved {@code <error>}), and a value-less mirror (javac drops the
   * element values of error-typed annotations) must face an occurrence
   * that carried no arguments.
   */
  private static boolean mirrorCanReplace(
      JavacSourceSymbolIndex.AnnotationInfo mirror, String block) {
    if (!JavacFrameworkAnnotations.renderIsUsable(
        JavacFrameworkAnnotations.renderedArguments(mirror))) {
      return false;
    }
    return !mirror.values().isEmpty() || !hasTextualArguments(
        block, JavacFrameworkAnnotations.simpleName(mirror));
  }

  /** Reports whether the textual occurrences of one name carry arguments. */
  private static boolean hasTextualArguments(String block, String name) {
    if (block == null) {
      return false;
    }
    return SourceMetadataScanner.annotationArguments(block, name).stream()
        .anyMatch(args -> !args.isBlank());
  }

  /**
   * Removes and returns the mirror one textual occurrence denotes, if any.
   * An ASCII-prefix match (a {@code Family$k}-style name whose capture
   * stopped at the {@code $} or non-ASCII character) is valid only when
   * the occurrence itself carries no arguments: a value-bearing
   * {@code @Named("x")} occurrence whose own mirror was dropped (unresolved
   * types are absent from the mirrors on some compilers) must not consume
   * a {@code Named$k} mirror and silently replace its fact. Field-site
   * blocks can truncate at the {@code $}, so the occurrence's own parsed
   * arguments — not the block's spelling — carry the decision.
   */
  private static JavacSourceSymbolIndex.AnnotationInfo takeMirror(
      List<JavacSourceSymbolIndex.AnnotationInfo> remaining,
      Matcher matcher,
      SourceMetadataScannerState.ParsedAnnotation occurrence) {
    String occurrenceArguments = occurrence.arguments();
    for (int index = 0; index < remaining.size(); index++) {
      String mirrorName = JavacFrameworkAnnotations.simpleName(remaining.get(index));
      if (captureMatches(matcher.group(1), mirrorName, occurrenceArguments)) {
        return remaining.remove(index);
      }
    }
    return null;
  }

  /** Reports whether one textual capture can denote one mirror name. */
  private static boolean captureMatches(
      String capture, String mirrorName, String occurrenceArguments) {
    if (mirrorName.equals(capture) || capture.endsWith("." + mirrorName)) {
      return true;
    }
    String prefix = asciiPrefix(mirrorName);
    return !prefix.isEmpty() && !prefix.equals(mirrorName)
        && prefix.equals(capture) && occurrenceArguments.isBlank();
  }

  /** Returns the maximal ASCII prefix the textual pattern could capture. */
  private static String asciiPrefix(String name) {
    if (name.isEmpty() || !isAsciiIdentifierStart(name.charAt(0))) {
      return "";
    }
    int end = 1;
    while (end < name.length() && isAsciiWord(name.charAt(end))) {
      end++;
    }
    return name.substring(0, end);
  }

  private static boolean isAsciiIdentifierStart(char character) {
    return (character >= 'a' && character <= 'z')
        || (character >= 'A' && character <= 'Z') || character == '_';
  }

  private static boolean isAsciiWord(char character) {
    return isAsciiIdentifierStart(character)
        || (character >= '0' && character <= '9');
  }

  /** Applies the setter-property decapitalize rule of the textual path. */
  private static String setterDefaultName(String name, boolean setterMethod) {
    if (!setterMethod || !name.startsWith("set") || name.length() <= 3) {
      return name;
    }
    String property = name.substring(3);
    if (property.length() < 2
        || !Character.isUpperCase(property.charAt(0))
        || !Character.isUpperCase(property.charAt(1))) {
      return Character.toLowerCase(property.charAt(0)) + property.substring(1);
    }
    return property;
  }
}
