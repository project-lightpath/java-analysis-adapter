package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerTextLayer12 extends SourceMetadataScannerTextLayer11 {

  SourceMetadataScannerTextLayer12(AnalysisContext context) {
    super(context);
  }



  /** Extracts the outer return type, retaining generic arguments for producer matching. */
static String returnType(String declaration) {
    String cleaned = maskAnnotationArguments(stripComments(declaration))
        .replaceAll("@[A-Za-z_][\\w.]*", " ")
        .replaceAll("\\b(public|protected|private|static|final|synchronized|abstract|native|default|strictfp)\\b", " ")
        .trim();
    cleaned = compactGenericWhitespace(cleaned);
    if (cleaned.isBlank()) {
      return "";
    }
    String[] parts = cleaned.split("\\s+");
    return parts[parts.length - 1].trim();
  }

  /** Extracts exact source spellings for a bounded set of annotation names. */
static List<String> annotationSources(String block, List<String> names) {
    List<String> result = new ArrayList<>();
    for (String name : names) {
      result.addAll(annotationOccurrences(block, name).stream()
          .map(AnnotationOccurrence::source)
          .toList());
    }
    return List.copyOf(new LinkedHashSet<>(result));
  }

static boolean hasAnnotation(String block, String name) {
    return !annotationOccurrences(block, name).isEmpty();
  }

static List<AnnotationOccurrence> canonicalAnnotationOccurrences(
      SourceFile file, String block, String simpleName, Set<String> canonical) {
    List<AnnotationOccurrence> occurrences = annotationOccurrences(block, simpleName);
    if (occurrences.isEmpty()) {
      return List.of();
    }
    boolean canonicalImport = importedTypes(simpleName, file).stream().anyMatch(canonical::contains);
    return occurrences.stream()
        .filter(occurrence -> canonical.contains(annotationType(occurrence.source()))
            || annotationType(occurrence.source()).equals(simpleName) && canonicalImport)
        .toList();
  }

  /** Checks a method declaration modifier without reading annotation arguments as modifiers. */
static boolean hasModifier(String block, String modifier) {
    String structural = maskAnnotationArguments(stripComments(block == null ? "" : block));
    return Pattern.compile("\\b" + Pattern.quote(modifier) + "\\b").matcher(structural).find();
  }

  /** Recognizes only the CDI producer annotation, not JAX-RS response producers. */
static boolean cdiProducerAnnotation(SourceFile file, String block) {
    return annotationOccurrences(block, "Produces").stream()
        .map(AnnotationOccurrence::source)
        .map(SourceMetadataScanner::annotationType)
        .anyMatch(annotation -> CDI_PRODUCER_ANNOTATIONS.contains(annotation)
            || annotation.equals("Produces") && importsCdiProducer(file));
  }

static String selectedValue(String args, String attribute) {
    if (attribute == null) {
      String explicit = attributeValue(args, "value");
      return explicit == null ? args.trim() : explicit;
    }
    String selected = attributeValue(args, attribute);
    return selected == null ? "" : selected;
  }

static List<String> annotationArguments(String block, String name) {
    return annotationOccurrences(block, name).stream().map(AnnotationOccurrence::arguments).toList();
  }

  /** Resolves on-demand imports only against unambiguous scanned source types. */
  List<String> onDemandImportedTypes(String type, SourceFile sourceFile) {
    return onDemandImportedTypeNames(
        type,
        sourceFile,
        files().stream().map(SourceFile::typeName));
  }

  /** Resolves on-demand imports against the bounded set of known JDK types. */
static List<String> onDemandImportedJdkTypes(String type, SourceFile sourceFile) {
    return onDemandImportedTypeNames(type, sourceFile, KNOWN_JDK_TYPE_NAMES.stream());
  }

static Optional<List<String>> typeVariableBoundsInParameters(
      String parameters, String variable) {
    for (String parameter : splitTopLevelArguments(parameters)) {
      String normalizedParameter = stripLeadingTypeParameterAnnotations(parameter);
      Matcher declaration = Pattern.compile(
              "^\\s*" + Pattern.quote(variable) + "(?:\\s+extends\\s+(.+))?\\s*$")
          .matcher(normalizedParameter);
      if (!declaration.matches()) {
        continue;
      }
      if (declaration.group(1) == null) {
        return Optional.of(List.of());
      }
      return Optional.of(splitTypeIntersectionBounds(declaration.group(1)).stream()
          .map(SourceMetadataScanner::stripLeadingTypeParameterAnnotations)
          .filter(bound -> !bound.isBlank())
          .toList());
    }
    return Optional.empty();
  }

static List<String> parentSpellings(TypeInfo type, String header) {
    int extendsIndex = topLevelKeywordIndex(header, "extends");
    int implementsIndex = topLevelKeywordIndex(header, "implements");
    int permitsIndex = topLevelKeywordIndex(header, "permits");
    List<String> result = new ArrayList<>();
    if (type.kind().equals("interface")) {
      addInterfaceParents(result, header, extendsIndex, permitsIndex);
    } else {
      addClassParents(result, header, extendsIndex, implementsIndex, permitsIndex);
    }
    return result;
  }

static Optional<ForScope> forScopeAt(String source, int declaration, int opening) {
    int closing = matchingDelimiter(source, opening, '(', ')');
    if (closing < 0 || declaration <= opening || declaration >= closing) {
      return Optional.empty();
    }
    int semicolon = topLevelHeaderDelimiter(source, opening + 1, closing, ';');
    int colon = semicolon < 0
        ? topLevelHeaderDelimiter(source, opening + 1, closing, ':')
        : -1;
    if ((semicolon < 0 || declaration >= semicolon)
        && (colon < 0 || declaration >= colon)) {
      return Optional.empty();
    }
    int bodyStart = skipWhitespace(source, closing + 1);
    int bodyEnd = bodyStart < source.length() && source.charAt(bodyStart) == '{'
        ? matchingDelimiter(source, bodyStart, '{', '}')
        : statementEnd(source, bodyStart);
    return bodyEnd < 0
        ? Optional.empty()
        : Optional.of(new ForScope(opening, bodyStart, bodyEnd, semicolon < 0 && colon >= 0));
  }

static String dynamicTarget(String source, int offset, String symbol) {
    String literal = firstQuotedArgument(source, offset);
    if (symbol.equals("Class.forName") || symbol.equals("ClassLoader.loadClass")) {
      return (literal == null ? "unknown" : literal) + "#unknown";
    }
    if (symbol.equals("getDeclaredMethod") || symbol.equals("getMethod")) {
      return "unknown#" + (literal == null ? "unknown" : literal);
    }
    return "unknown#unknown";
  }
}
