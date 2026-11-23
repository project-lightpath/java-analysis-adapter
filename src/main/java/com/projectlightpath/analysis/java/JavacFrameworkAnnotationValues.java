package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Framework annotation-value interpretation over index mirrors: route
 * paths, http methods, media values, headers, params, profiles, and
 * condition evidence, rendered from the #21 index and fed into the
 * UNCHANGED textual leaf shape functions. Every method declines (empty)
 * when the mirrors cannot stand in for the textual occurrences, so the
 * caller keeps the textual answer — the safe direction.
 */
final class JavacFrameworkAnnotationValues {
  private static final Pattern REQUEST_METHOD = Pattern.compile(
      "RequestMethod\\.(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)");

  private JavacFrameworkAnnotationValues() {}

  /** Attribute values of one gate-accepted annotation, from the index. */
  static Optional<List<String>> values(
      List<JavacSourceSymbolIndex.AnnotationInfo> mirrors,
      SourceMetadataScannerState.SourceFile file,
      String block,
      String annotation,
      String attribute,
      boolean canonical) {
    return arguments(mirrors, file, block, annotation, canonical)
        .map(args -> SourceMetadataScanner.values(List.of(args), attribute));
  }

  /** Spring mapping paths (value/path attributes) from the index. */
  static Optional<List<String>> mappingPaths(
      List<JavacSourceSymbolIndex.AnnotationInfo> mirrors,
      SourceMetadataScannerState.SourceFile file,
      String block,
      String annotation) {
    return arguments(mirrors, file, block, annotation, true).map(args -> {
      LinkedHashSet<String> result = new LinkedHashSet<>();
      for (String attribute : List.of("value", "path")) {
        String selected = SourceMetadataScanner.attributeValue(args, attribute);
        if (selected == null) {
          continue;
        }
        Optional<List<String>> literals =
            SourceMetadataScanner.literalPathValues(selected);
        if (literals.isEmpty()) {
          return List.<String>of();
        }
        result.addAll(literals.get());
      }
      if (!result.isEmpty()) {
        return List.copyOf(result);
      }
      // F8 cardinality: no value/path attribute means the annotation is
      // present with no explicit path (List.of("")), never a drop.
      boolean explicit = SourceMetadataScanner.attributeValue(args, "value") != null
          || SourceMetadataScanner.attributeValue(args, "path") != null;
      return explicit ? List.of() : List.of("");
    });
  }

  /** RequestMapping http methods from the index. */
  static Optional<List<String>> requestMappingHttpMethods(
      List<JavacSourceSymbolIndex.AnnotationInfo> mirrors,
      SourceMetadataScannerState.SourceFile file,
      String block) {
    return arguments(mirrors, file, block, "RequestMapping", true).map(args -> {
      List<String> result = new ArrayList<>();
      String selected = SourceMetadataScanner.attributeValue(args, "method");
      if (selected == null) {
        result.addAll(SourceMetadataScanner.SUPPORTED_HTTP_METHODS);
        return List.copyOf(new LinkedHashSet<>(result));
      }
      Matcher matcher = REQUEST_METHOD.matcher(selected);
      while (matcher.find()) {
        result.add(matcher.group(1));
      }
      return List.copyOf(new LinkedHashSet<>(result));
    });
  }

  /** Media values of one gate-accepted annotation, from the index. */
  static Optional<List<String>> mediaValues(
      List<JavacSourceSymbolIndex.AnnotationInfo> mirrors,
      SourceMetadataScannerState.SourceFile file,
      String block,
      String annotation,
      String attribute) {
    return values(mirrors, file, block, annotation, attribute, true)
        .map(SourceMetadataScanner::mediaValues);
  }

  /** QUARKUS literal path values from the index. */
  static Optional<List<String>> pathValues(
      List<JavacSourceSymbolIndex.AnnotationInfo> mirrors,
      String block,
      String annotation) {
    List<String> textual = SourceMetadataScanner.annotationArguments(block, annotation);
    // Occurrence-count parity, as in arguments(): the textual consumers
    // union over every occurrence; the index answers from one mirror.
    if (textual.size() != 1) {
      return Optional.empty();
    }
    return uniqueMirror(mirrors, annotation, false)
        .filter(mirror -> mirrorCovers(mirror, textual))
        .map(JavacFrameworkAnnotations::renderedArguments)
        .filter(JavacFrameworkAnnotations::renderIsUsable)
        .map(args -> SourceMetadataScanner.literalPathValues(
            SourceMetadataScanner.selectedValue(args, "value")))
        .filter(Optional::isPresent)
        .map(Optional::orElseThrow)
        .map(literals -> List.copyOf(new LinkedHashSet<>(literals)));
  }

  /** Reports whether a mapping annotation names an explicit path attribute. */
  static Optional<Boolean> hasExplicitMappingPath(
      List<JavacSourceSymbolIndex.AnnotationInfo> mirrors,
      SourceMetadataScannerState.SourceFile file,
      String block,
      String annotation) {
    return arguments(mirrors, file, block, annotation, true)
        .map(args -> SourceMetadataScanner.attributeValue(args, "value") != null
            || SourceMetadataScanner.attributeValue(args, "path") != null);
  }

  /** Condition-annotation evidence, rendered from the index mirrors. */
  static Optional<List<String>> conditionAnnotations(
      List<JavacSourceSymbolIndex.AnnotationInfo> mirrors,
      SourceMetadataScannerState.SourceFile file,
      String block,
      List<String> names,
      boolean canonical) {
    List<String> result = new ArrayList<>();
    for (String name : names) {
      boolean present = canonical
          ? SourceMetadataScanner.canonicalSpringAnnotation(file, block, name)
          : SourceMetadataScanner.hasAnnotation(block, name);
      if (!present) {
        continue;
      }
      Optional<JavacSourceSymbolIndex.AnnotationInfo> mirror =
          uniqueMirror(mirrors, name, canonical);
      if (mirror.isEmpty()) {
        return Optional.empty();
      }
      List<String> textual = canonical
          ? SourceMetadataScanner.annotationArguments(file, block, name)
          : SourceMetadataScanner.annotationArguments(block, name);
      // Occurrence-count parity, as in arguments(): the textual consumers
      // union over every occurrence; the index answers from one mirror.
      if (textual.size() != 1 || !mirrorCovers(mirror.orElseThrow(), textual)) {
        return Optional.empty();
      }
      String args = JavacFrameworkAnnotations.renderedArguments(mirror.orElseThrow());
      if (!JavacFrameworkAnnotations.renderIsUsable(args)) {
        return Optional.empty();
      }
      result.add(args.isEmpty()
          ? "@" + mirror.orElseThrow().canonicalName()
          : "@" + mirror.orElseThrow().canonicalName() + "(" + args + ")");
    }
    return Optional.of(List.copyOf(new LinkedHashSet<>(result)));
  }

  /** Returns the unique gate-matching mirror's arguments, or declines. */
  private static Optional<String> arguments(
      List<JavacSourceSymbolIndex.AnnotationInfo> mirrors,
      SourceMetadataScannerState.SourceFile file,
      String block,
      String annotation,
      boolean canonical) {
    List<String> textual = canonical
        ? SourceMetadataScanner.annotationArguments(file, block, annotation)
        : SourceMetadataScanner.annotationArguments(block, annotation);
    // Occurrence-count parity: the textual consumers union over EVERY
    // occurrence (a nested @Name inside another annotation's arguments
    // counts), while the index path answers from the one declaration
    // mirror — any count other than exactly one declines.
    if (textual.size() != 1) {
      return Optional.empty();
    }
    return uniqueMirror(mirrors, annotation, canonical)
        .filter(mirror -> mirrorCovers(mirror, textual))
        .map(JavacFrameworkAnnotations::renderedArguments)
        .filter(JavacFrameworkAnnotations::renderIsUsable);
  }

  /** Returns the unique mirror for one gate-accepted annotation name. */
  private static Optional<JavacSourceSymbolIndex.AnnotationInfo> uniqueMirror(
      List<JavacSourceSymbolIndex.AnnotationInfo> mirrors, String name, boolean canonical) {
    List<JavacSourceSymbolIndex.AnnotationInfo> matches = mirrors.stream()
        .filter(mirror -> JavacFrameworkAnnotations.simpleName(mirror).equals(name))
        .filter(mirror -> !canonical
            || SourceMetadataScanner.canonicalSpringAnnotationTypes(name)
                .contains(mirror.canonicalName()))
        .toList();
    return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
  }

  /**
   * Reports whether a mirror can stand in for the textual occurrences.
   * Unresolved annotation types yield value-less mirrors (javac drops the
   * element values of error-typed annotations): when the textual
   * occurrence carried arguments, declining is the safe direction.
   */
  private static boolean mirrorCovers(
      JavacSourceSymbolIndex.AnnotationInfo mirror, List<String> textualArguments) {
    return !mirror.values().isEmpty()
        || textualArguments.stream().noneMatch(args -> !args.isBlank());
  }
}
