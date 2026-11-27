package com.projectlightpath.analysis.java;

import java.util.LinkedHashSet;
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
  private final SourceMetadataScanner scanner;

  private JavacAnnotationAccess(SourceMetadataScanner scanner) {
    this.scanner = scanner;
  }

  /** Returns the accessor bound to one scanner's context. */
  static JavacAnnotationAccess of(SourceMetadataScanner scanner) {
    return new JavacAnnotationAccess(scanner);
  }

  /** Reports whether the index seam flag is enabled. */
  boolean enabled() {
    return JavacSourceSymbolIndex.enabled();
  }

  /** Returns the unique index member matching owner, name, and arity. */
  Optional<JavacSourceSymbolIndex.MemberInfo> member(
      String typeName, String memberName, int parameterCount) {
    return memberLookup(typeName, memberName, parameterCount).member();
  }

  /** One seam-B member lookup: the member, or the decline evidence. */
  private record MemberLookup(
      Optional<JavacSourceSymbolIndex.MemberInfo> member,
      Optional<JavacAbstentionEvidence> abstention) {

    static final MemberLookup EMPTY =
        new MemberLookup(Optional.empty(), Optional.empty());
  }

  /**
   * The seam-B lookup with its decline evidence (#62), delegated to
   * {@link JavacAnnotationMemberLookup} (the PMD-forced extraction).
   */
  private MemberLookup memberLookup(
      String typeName, String memberName, int parameterCount) {
    JavacAnnotationMemberLookup.MemberLookup lookup =
        JavacAnnotationMemberLookup.of(scanner)
            .lookup(typeName, memberName, parameterCount);
    return new MemberLookup(lookup.member(), lookup.abstention());
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
   * Builds a proxy method descriptor from the index shapes when the member
   * correlates (either path), falling back to the per-parameter repair and
   * then the textual spelling. The repair only runs when the interface file
   * declares exactly one type: {@code methods(file)} returns methods of
   * nested and sibling types too, and a name+arity correlation cannot prove
   * which declaration a textual method belongs to — ambiguous files fall
   * back to fully textual.
   *
   * <p>Issue #62: the outcome carries the correlation abstention (seam A)
   * and, when the repair path also declined, the seam-B repair code.
   */
  JavacDescriptorOutcome methodDescriptor(
      SourceMetadataScannerState.MethodInfo method, String ownerType,
      SourceMetadataScannerState.SourceFile file) {
    JavacMemberCorrelation correlation = JavacMemberCorrelation.of(scanner);
    JavacCorrelation correlated =
        correlation.correlatedMethod(file, ownerType, method);
    if (correlated.memberOptional().isPresent()) {
      return new JavacDescriptorOutcome(Optional.of("("
          + String.join(",", correlated.memberOptional().orElseThrow().parameterTypes())
          + ")"), null, null);
    }
    JavacAbstentionEvidence abstention =
        correlated.abstentionOptional().orElse(JavacAbstentionEvidence.NONE);
    String parameters = method.parameters().trim();
    if (parameters.isEmpty()) {
      return new JavacDescriptorOutcome(Optional.of("()"), abstention, null);
    }
    if (correlation.blocked(file, parameters)) {
      return new JavacDescriptorOutcome(
          Optional.of(textualDescriptor(parameters)), abstention, null);
    }
    List<String> split = SourceMetadataScannerTextLayer06.splitTopLevelArguments(parameters);
    MemberLookup lookup =
        memberLookup(ownerType, method.name(), split.size());
    boolean repairTriggered = split.stream().anyMatch(this::repairTriggered);
    JavacAbstentionEvidence repairAbstention =
        repairTriggered && lookup.member().isEmpty()
            ? lookup.abstention().orElse(JavacAbstentionEvidence.NONE)
            : JavacAbstentionEvidence.NONE;
    StringBuilder descriptor = new StringBuilder("(");
    for (int index = 0; index < split.size(); index++) {
      if (index > 0) {
        descriptor.append(',');
      }
      descriptor.append(parameterTypeOrRepair(
          split.get(index), ownerType, method.name(), split.size(), index,
          lookup.member()));
    }
    return new JavacDescriptorOutcome(
        Optional.of(descriptor.append(')').toString()), abstention, repairAbstention);
  }

  /** Returns the fully textual descriptor for files the repair cannot own. */
  private static String textualDescriptor(String parameters) {
    return "(" + String.join(",", SourceMetadataScannerTextLayer06
        .splitTopLevelArguments(parameters).stream()
        .map(SourceMetadataScanner::parameterType)
        .toList()) + ")";
  }

  /** Returns the repaired spelling when triggered and unambiguous, else the textual one. */
  private String parameterTypeOrRepair(
      String rawParameter, String ownerType, String memberName,
      int parameterCount, int parameterIndex,
      Optional<JavacSourceSymbolIndex.MemberInfo> member) {
    String textual = SourceMetadataScanner.parameterType(rawParameter);
    if (!repairTriggered(rawParameter) || member.isEmpty()) {
      return textual;
    }
    return member
        .filter(found -> parameterIndex < found.parameterTypes().size())
        .map(found -> found.parameterTypes().get(parameterIndex))
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

  /** Strips the renderer's surrounding quotes from a string value. */
  static String unquoted(String renderedValue) {
    if (renderedValue != null && renderedValue.length() >= 2
        && renderedValue.startsWith("\"") && renderedValue.endsWith("\"")) {
      return renderedValue.substring(1, renderedValue.length() - 1);
    }
    return renderedValue;
  }
}
