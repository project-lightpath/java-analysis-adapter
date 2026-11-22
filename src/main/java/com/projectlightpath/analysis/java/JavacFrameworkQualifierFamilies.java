package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Qualifier-family interpretation over index mirrors: the six-family table
 * (canonical-Spring guard, Resource, bean-name, NON_QUALIFIER,
 * Qualifier/Named, simple name) with the injection enrichment
 * derivations appended after, reproducing the textual pipeline's exact
 * code order and simple-name trigger semantics. The occurrence-parity
 * guard declines whenever the textual occurrences and the mirrors do not
 * multiset-match, so the caller keeps the textual answer.
 */
final class JavacFrameworkQualifierFamilies {
  /**
   * The textual occurrence pattern of {@code qualifierNames}; used only
   * for the occurrence-parity guard, never for value extraction.
   */
  private static final Pattern TEXTUAL_ANNOTATION_NAME = Pattern.compile("@([A-Za-z_][\\w.]*)");

  private JavacFrameworkQualifierFamilies() {}

  /** Six-family qualifier table on index mirrors (guarded arm optional). */
  static Optional<List<String>> qualifiers(
      SourceMetadataScannerState.SourceFile file,
      String block,
      List<JavacSourceSymbolIndex.AnnotationInfo> mirrors,
      boolean guarded) {
    if (!occurrenceParity(block, mirrors)) {
      return Optional.empty();
    }
    List<String> result = new ArrayList<>();
    for (JavacSourceSymbolIndex.AnnotationInfo annotation : mirrors) {
      String args = JavacFrameworkAnnotations.renderedArguments(annotation);
      if (hasUnusableRender(args) || valuelessMirrorWithTextualArguments(block, annotation)) {
        return Optional.empty();
      }
      String name = JavacFrameworkAnnotations.simpleName(annotation);
      if (guarded && SourceMetadataScannerState.SPRING_BEAN_ANNOTATION_TYPES.containsKey(name)
          && !SourceMetadataScanner.canonicalSpringAnnotation(file, block, name)) {
        continue;
      }
      if (name.equals("Resource")) {
        result.addAll(SourceMetadataScanner.resourceQualifier(args));
      } else if (SourceMetadataScanner.isBeanNameAnnotation(name)) {
        result.addAll(SourceMetadataScanner.beanNameQualifier(args));
      } else if (SourceMetadataScannerState.NON_QUALIFIER_ANNOTATIONS.contains(name)) {
        continue;
      } else if (name.equals("Qualifier") || name.equals("Named")) {
        result.add(name + ":" + SourceMetadataScanner.quotedValue(
            args.replaceFirst("^value\\s*=\\s*", "").trim()));
      } else {
        result.add(name);
      }
    }
    return Optional.of(List.copyOf(new LinkedHashSet<>(result)));
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
      boolean hasNamed = mirrors.stream()
          .anyMatch(mirror -> JavacFrameworkAnnotations.simpleName(mirror).equals("Named"));
      if (cdi && hasNamed) {
        for (int index = 0; index < result.size(); index++) {
          if (result.get(index).equals("Named:") && !defaultName.isBlank()) {
            result.set(index, "Named:" + defaultName);
          }
        }
      }
      boolean hasResource = mirrors.stream()
          .anyMatch(mirror -> JavacFrameworkAnnotations.simpleName(mirror).equals("Resource"));
      if (hasResource
          && result.stream().noneMatch(value -> value.startsWith("Resource:"))) {
        result.add("ResourceDefault:" + setterDefaultName(defaultName, setterMethod));
      }
      return List.copyOf(result);
    });
  }

  /** Reports whether a rendered argument string is unusable (index fails). */
  private static boolean hasUnusableRender(String rendered) {
    boolean quoted = false;
    for (int index = 0; index < rendered.length(); index++) {
      char character = rendered.charAt(index);
      if (character == '"') {
        quoted = !quoted;
      } else if (!quoted && (character == '@'
          || rendered.startsWith("<error>", index))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Reports whether a value-less mirror (unresolved annotation type) faces
   * textual occurrences that carried arguments: the index cannot stand in.
   */
  private static boolean valuelessMirrorWithTextualArguments(
      String block, JavacSourceSymbolIndex.AnnotationInfo annotation) {
    if (!annotation.values().isEmpty() || block == null) {
      return false;
    }
    return SourceMetadataScanner.annotationArguments(
            block, JavacFrameworkAnnotations.simpleName(annotation)).stream()
        .anyMatch(args -> !args.isBlank());
  }

  /**
   * Reports whether the textual ASCII-visible annotation occurrences in a
   * block multiset-match the index mirrors. A divergence (type-use
   * spellings, nested annotation arguments, dotted names the mirrors would
   * re-spell) means the site falls back to the textual answer.
   */
  private static boolean occurrenceParity(
      String block, List<JavacSourceSymbolIndex.AnnotationInfo> mirrors) {
    List<String> textual = new ArrayList<>();
    String source = SourceMetadataScanner.stripComments(block == null ? "" : block);
    Matcher matcher = TEXTUAL_ANNOTATION_NAME.matcher(
        SourceMetadataScanner.maskStringLiterals(source));
    while (matcher.find()) {
      textual.add(matcher.group(1));
    }
    List<String> expected = new ArrayList<>();
    for (JavacSourceSymbolIndex.AnnotationInfo mirror : mirrors) {
      String prefix = asciiPrefix(JavacFrameworkAnnotations.simpleName(mirror));
      if (!prefix.isEmpty()) {
        expected.add(prefix);
      }
    }
    Collections.sort(textual);
    Collections.sort(expected);
    return textual.equals(expected);
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
