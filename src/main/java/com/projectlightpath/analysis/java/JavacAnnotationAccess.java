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
        // Count ALL same-arity overloads before rejecting error types:
        // filtering first would create false uniqueness when an unresolved
        // overload shadows a resolved one (name+arity cannot tell which
        // textual declaration is which).
        .filter(matches -> matches.size() == 1)
        .map(matches -> matches.get(0))
        .filter(found -> !found.errorType());
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
      // Code-point aware: a supplementary letter (e.g. U+10400) reads as a
      // surrogate pair via charAt and would escape the identifier check.
      int afterPoint = end < rawParameter.length() ? rawParameter.codePointAt(end) : ' ';
      if (Character.isJavaIdentifierPart(afterPoint) && !isAsciiWord(afterPoint)) {
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
   * the index and keeping the textual spelling for everything else. The
   * repair only runs when the interface file declares exactly one type:
   * {@code methods(file)} returns methods of nested and sibling types too,
   * and a name+arity correlation cannot prove which declaration a textual
   * method belongs to — ambiguous files fall back to fully textual.
   */
  String methodDescriptor(
      SourceMetadataScannerState.MethodInfo method, String ownerType,
      SourceMetadataScannerState.SourceFile file) {
    String parameters = method.parameters().trim();
    if (parameters.isEmpty()) {
      return "()";
    }
    if (!singleTypeFile(file) || hasCommentText(parameters)) {
      return textualDescriptor(parameters);
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

  /**
   * Reports whether the raw parameter text contains comment markers. A
   * comment's parentheses distort {@code splitTopLevelArguments} (the split
   * is not comment-aware), so the derived arity cannot be trusted for
   * overload selection — such methods keep the textual spelling.
   */
  private static boolean hasCommentText(String parameters) {
    return parameters.contains("/*") || parameters.contains("//");
  }

  /** Returns the fully textual descriptor for files the repair cannot own. */
  private static String textualDescriptor(String parameters) {
    return "(" + String.join(",", SourceMetadataScannerTextLayer06
        .splitTopLevelArguments(parameters).stream()
        .map(SourceMetadataScanner::parameterType)
        .toList()) + ")";
  }

  /** Reports whether the file declares exactly one indexed type (owner provable). */
  private boolean singleTypeFile(SourceMetadataScannerState.SourceFile file) {
    return JavacSourceSymbolIndex.of(context)
        .map(index -> index.typesIn(file.path()).size() == 1)
        .orElse(true);
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

  private static boolean isAsciiWord(int codePoint) {
    return (codePoint >= 'a' && codePoint <= 'z') || (codePoint >= 'A' && codePoint <= 'Z')
        || (codePoint >= '0' && codePoint <= '9') || codePoint == '_';
  }

  /** Returns the annotation's simple name (last canonical segment). */
  static String simpleName(JavacSourceSymbolIndex.AnnotationInfo annotation) {
    String canonical = annotation.canonicalName();
    int separator = canonical.lastIndexOf('.');
    return separator < 0 ? canonical : canonical.substring(separator + 1);
  }

  /**
   * Correlates one textual method declaration to its index member through
   * the group-scoped rule (unique group: owner+name+arity; multi-overload
   * group: resolved-textual-vector bijection onto the index vectors).
   *
   * <p>RED_23 behavior-neutral stub: returns the arity-only result so the
   * failing acceptance tests compile; the group-scoped rule lands in
   * GREEN_23. Documented in the RED commit message.
   */
  Optional<JavacSourceSymbolIndex.MemberInfo> correlatedMethod(
      SourceMetadataScannerState.SourceFile file,
      String ownerType,
      SourceMetadataScannerState.MethodInfo method) {
    return member(ownerType, method.name(),
        SourceMetadataScanner.parameters(method.parameters()).size());
  }

  /**
   * Correlates one textual constructor declaration ({@code <init>}) to its
   * index member, which the index names by the owner's simple name with
   * kind {@code constructor}, through the same group-scoped rule.
   *
   * <p>RED_23 behavior-neutral stub: arity-only, as above.
   */
  Optional<JavacSourceSymbolIndex.MemberInfo> correlatedConstructor(
      SourceMetadataScannerState.SourceFile file,
      String ownerType,
      SourceMetadataScannerState.MethodInfo constructor) {
    String simpleName = ownerType.substring(ownerType.lastIndexOf('.') + 1);
    return JavacSourceSymbolIndex.of(context)
        .map(index -> index.members(ownerType, simpleName).stream()
            .filter(member -> member.kind().equals("constructor")
                && member.parameterTypes().size()
                    == SourceMetadataScanner.parameters(constructor.parameters()).size())
            .toList())
        .filter(matches -> matches.size() == 1)
        .map(matches -> matches.get(0))
        .filter(found -> !found.errorType());
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
