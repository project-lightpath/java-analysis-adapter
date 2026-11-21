package com.projectlightpath.analysis.java;

import java.util.List;
import java.util.Optional;

/**
 * javac annotation read layer for one scanner's context.
 *
 * <p>Correlates textual {@code MethodInfo} questions to the #21 index by
 * owner type, member name, and arity, with silent textual fallback when
 * the flag is off, the index is absent, or the correlation is ambiguous.
 * The flag gate is call-site-local: this class never changes behavior
 * for consumers that do not route through it.
 */
final class JavacAnnotationAccess {
  private final AnalysisContext context;

  private JavacAnnotationAccess(AnalysisContext context) {
    this.context = context;
  }

  /** Returns the accessor bound to one scanner's context. */
  static JavacAnnotationAccess of(SourceMetadataScanner scanner) {
    return new JavacAnnotationAccess(scanner.context);
  }

  /** Reports whether the index seam flag is enabled. */
  boolean enabled() {
    return JavacSourceSymbolIndex.enabled();
  }

  /** Returns the unique index member matching owner, name, and arity. */
  Optional<JavacSourceSymbolIndex.MemberInfo> member(
      String typeName, String memberName, int parameterCount) {
    return JavacSourceSymbolIndex.of(context)
        .map(index -> index.members(typeName, memberName).stream()
            .filter(member -> member.kind().equals("method")
                && member.parameterTypes().size() == parameterCount)
            .toList())
        .filter(matches -> matches.size() == 1)
        .map(matches -> matches.get(0));
  }

  /**
   * Reports whether a raw parameter text contains an annotation the textual
   * stripper cannot fully consume (probe-pinned predicate).
   */
  boolean repairTriggered(String rawParameter) {
    if (rawParameter == null || rawParameter.indexOf('@') < 0) {
      return false;
    }
    int search = 0;
    while (search < rawParameter.length()) {
      int at = rawParameter.indexOf('@', search);
      if (at < 0) {
        break;
      }
      int end = maximalAsciiNameEnd(rawParameter, at);
      if (end < 0) {
        return true; // '@' not followed by [A-Za-z_]: Unicode or $ name
      }
      char after = end < rawParameter.length() ? rawParameter.charAt(end) : ' ';
      if (Character.isJavaIdentifierPart(after) && !isAsciiWord(after)) {
        return true; // unconsumed tail: $ or non-ASCII identifier part
      }
      search = end;
    }
    String stripped = SourceMetadataScanner.parameterType(rawParameter);
    return stripped.indexOf('@') >= 0 || stripped.indexOf('(') >= 0;
  }

  /** Returns the repaired canonical spelling of one parameter, if unique. */
  Optional<String> repairedParameterType(
      String rawParameter, String typeName, String memberName,
      int parameterCount, int parameterIndex) {
    if (!repairTriggered(rawParameter)) {
      return Optional.empty();
    }
    return member(typeName, memberName, parameterCount)
        .filter(found -> parameterIndex < found.parameterTypes().size())
        .map(found -> found.parameterTypes().get(parameterIndex));
  }

  /**
   * Builds a proxy method descriptor, repairing triggered parameters from
   * the index and keeping the textual spelling for everything else.
   */
  String methodDescriptor(SourceMetadataScannerState.MethodInfo method, String ownerType) {
    String parameters = method.parameters().trim();
    if (parameters.isEmpty()) {
      return "()";
    }
    List<String> split = SourceMetadataScannerTextLayer06.splitTopLevelArguments(parameters);
    StringBuilder descriptor = new StringBuilder("(");
    for (int index = 0; index < split.size(); index++) {
      if (index > 0) {
        descriptor.append(',');
      }
      descriptor.append(parameterTypeOrRepair(
          split.get(index), ownerType, method.name(), split.size(), index));
    }
    return descriptor.append(')').toString();
  }

  /** Returns the repaired spelling when triggered and unambiguous, else the textual one. */
  private String parameterTypeOrRepair(
      String rawParameter, String ownerType, String memberName,
      int parameterCount, int parameterIndex) {
    String textual = SourceMetadataScanner.parameterType(rawParameter);
    return repairedParameterType(rawParameter, ownerType, memberName,
            parameterCount, parameterIndex)
        .orElse(textual);
  }

  private static int maximalAsciiNameEnd(String text, int at) {
    int end = at + 1;
    if (end >= text.length()) {
      return -1;
    }
    char first = text.charAt(end);
    boolean startsName =
        (first >= 'a' && first <= 'z') || (first >= 'A' && first <= 'Z') || first == '_';
    if (!startsName) {
      return -1;
    }
    while (end < text.length() && (isAsciiWord(text.charAt(end)) || text.charAt(end) == '.')) {
      end++;
    }
    return end;
  }

  private static boolean isAsciiWord(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
        || (c >= '0' && c <= '9') || c == '_';
  }

  /** Returns the annotation's simple name (last canonical segment). */
  static String simpleName(JavacSourceSymbolIndex.AnnotationInfo annotation) {
    String canonical = annotation.canonicalName();
    int separator = canonical.lastIndexOf('.');
    return separator < 0 ? canonical : canonical.substring(separator + 1);
  }

  /** Strips the renderer's surrounding quotes from a string value. */
  static String unquoted(String renderedValue) {
    if (renderedValue != null && renderedValue.length() >= 2
        && renderedValue.startsWith("\"") && renderedValue.endsWith("\"")) {
      return renderedValue.substring(1, renderedValue.length() - 1);
    }
    return renderedValue;
  }
}
