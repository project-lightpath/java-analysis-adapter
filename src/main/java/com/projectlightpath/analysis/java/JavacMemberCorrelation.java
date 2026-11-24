package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * javac member correlation for one scanner's context: maps textual
 * {@code MethodInfo} declarations to {@code JavacSourceSymbolIndex} members
 * through the group-scoped rule, and renders index shapes into the
 * consumer dialects (comma-canonical proxy descriptors, JVM descriptors).
 *
 * <p>Group-scoped rule: a unique index member in the name+arity group
 * correlates directly (the repair path); a multi-member group correlates
 * only when every textual sibling overload's resolved parameter vector
 * forms a bijection onto the index vectors, failing closed otherwise.
 * Comment-corrupted parameter lists and multi-type files never correlate.
 */
final class JavacMemberCorrelation {
  private final AnalysisContext context;
  private final SourceMetadataScanner scanner;

  private JavacMemberCorrelation(SourceMetadataScanner scanner) {
    this.context = scanner.context;
    this.scanner = scanner;
  }

  /** Returns the correlation bound to one scanner's context. */
  static JavacMemberCorrelation of(SourceMetadataScanner scanner) {
    return new JavacMemberCorrelation(scanner);
  }

  /**
   * Whether the correlated member's canonical parameter-0 type is one of
   * the expected canonical types (repository contract matching); false
   * when the member does not correlate or has no parameters.
   */
  boolean correlatedFirstParameterIs(
      SourceMetadataScannerState.SourceFile file,
      SourceMetadataScannerState.MethodInfo method,
      List<String> expected) {
    String ownerType = scanner.typeContaining(file,
        SourceMetadataScanner.declarationStart(file, method.start()));
    return correlatedMethod(file, ownerType, method)
        .filter(member -> !member.parameterTypes().isEmpty())
        .map(member -> expected.contains(member.parameterTypes().get(0)))
        .orElse(false);
  }

  /** Reports whether the correlation guards block one file and parameter list. */
  boolean blocked(SourceMetadataScannerState.SourceFile file, String parameters) {
    return hasCommentText(parameters) || !singleTypeFile(file);
  }

  /**
   * The bounded-model JVM descriptor rendered from index shapes when the
   * member correlates (either path); empty when it does not.
   */
  Optional<String> correlatedJvmDescriptor(
      SourceMetadataScannerState.SourceFile file,
      String ownerType,
      SourceMetadataScannerState.MethodInfo method) {
    return correlatedMethod(file, ownerType, method)
        .flatMap(JavacShapeDescriptors::jvmDescriptorFromMember);
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

  /** Reports whether the file declares exactly one indexed type (owner provable). */
  private boolean singleTypeFile(SourceMetadataScannerState.SourceFile file) {
    return JavacSourceSymbolIndex.of(context)
        .map(index -> index.typesIn(file.path()).size() == 1)
        .orElse(true);
  }

  /**
   * Correlates one textual method declaration to its index member through
   * the group-scoped rule: a unique index member in the name+arity group
   * correlates directly (the repair path); a multi-member group correlates
   * only when every textual sibling overload's resolved parameter vector
   * forms a bijection onto the index vectors, failing closed otherwise.
   *
   * <p>Guards shared by every wired consumer: comment-corrupted parameter
   * lists and multi-type files (unprovable owner) never correlate.
   */
  Optional<JavacSourceSymbolIndex.MemberInfo> correlatedMethod(
      SourceMetadataScannerState.SourceFile file,
      String ownerType,
      SourceMetadataScannerState.MethodInfo method) {
    return correlated(
        file, ownerType, method,
        SourceMetadataScanner.methods(file), "method", method.name());
  }

  /**
   * Correlates one textual constructor declaration ({@code <init>}) to its
   * index member, which the index names by the owner's simple name with
   * kind {@code constructor}, through the same group-scoped rule.
   */
  Optional<JavacSourceSymbolIndex.MemberInfo> correlatedConstructor(
      SourceMetadataScannerState.SourceFile file,
      String ownerType,
      SourceMetadataScannerState.MethodInfo constructor) {
    return correlated(
        file, ownerType, constructor,
        SourceMetadataScanner.constructors(file), "constructor",
        ownerType.substring(ownerType.lastIndexOf('.') + 1));
  }

  private Optional<JavacSourceSymbolIndex.MemberInfo> correlated(
      SourceMetadataScannerState.SourceFile file,
      String ownerType,
      SourceMetadataScannerState.MethodInfo declaration,
      List<SourceMetadataScannerState.MethodInfo> declarations,
      String kind,
      String indexName) {
    if (hasCommentText(declaration.parameters()) || !singleTypeFile(file)) {
      return Optional.empty();
    }
    int arity = SourceMetadataScanner.parameters(declaration.parameters()).size();
    List<JavacSourceSymbolIndex.MemberInfo> group = JavacSourceSymbolIndex.of(context)
        .map(index -> index.members(ownerType, indexName).stream()
            .filter(member -> member.kind().equals(kind)
                && member.parameterTypes().size() == arity)
            .toList())
        .orElse(List.of());
    // Error-typed members are counted before any uniqueness or type filter:
    // an unresolved overload must not let a resolved sibling look unique.
    if (group.stream().anyMatch(JavacSourceSymbolIndex.MemberInfo::errorType)) {
      return Optional.empty();
    }
    if (group.size() == 1) {
      return Optional.of(group.get(0));
    }
    if (group.size() < 2) {
      return Optional.empty();
    }
    return correlatedByBijection(file, declaration, declarations, group, indexName);
  }

  /**
   * The multi-overload bijection: every textual sibling in the group must
   * resolve to a distinct canonical parameter vector, and the vector set
   * must equal the index vector set. A single wrong or missing resolution
   * breaks the bijection (vector collision or set mismatch), so the group
   * fails closed to textual rather than selecting a wrong overload.
   */
  private Optional<JavacSourceSymbolIndex.MemberInfo> correlatedByBijection(
      SourceMetadataScannerState.SourceFile file,
      SourceMetadataScannerState.MethodInfo declaration,
      List<SourceMetadataScannerState.MethodInfo> declarations,
      List<JavacSourceSymbolIndex.MemberInfo> group,
      String indexName) {
    List<SourceMetadataScannerState.MethodInfo> siblings = declarations.stream()
        .filter(other -> other.name().equals(declaration.name()))
        .filter(other -> !hasCommentText(other.parameters()))
        .filter(other -> SourceMetadataScanner.parameters(other.parameters()).size()
            == declarationArity(declaration))
        .toList();
    if (siblings.size() != group.size()) {
      return Optional.empty();
    }
    List<List<String>> textualVectors = new ArrayList<>();
    for (SourceMetadataScannerState.MethodInfo sibling : siblings) {
      List<String> vector = resolvedParameterVector(file, sibling, indexName);
      if (vector.isEmpty()) {
        return Optional.empty();
      }
      textualVectors.add(vector);
    }
    List<List<String>> indexVectors = group.stream()
        .map(JavacSourceSymbolIndex.MemberInfo::parameterTypes)
        .toList();
    if (textualVectors.stream().distinct().count() != textualVectors.size()
        || indexVectors.stream().distinct().count() != indexVectors.size()
        || !new HashSet<>(textualVectors).equals(new HashSet<>(indexVectors))) {
      return Optional.empty();
    }
    List<String> queried = resolvedParameterVector(file, declaration, indexName);
    if (queried.isEmpty()) {
      return Optional.empty();
    }
    return group.stream()
        .filter(member -> member.parameterTypes().equals(queried))
        .findFirst();
  }

  private int declarationArity(SourceMetadataScannerState.MethodInfo declaration) {
    return SourceMetadataScanner.parameters(declaration.parameters()).size();
  }

  /** The canonical parameter vector of one textual declaration, or empty. */
  private List<String> resolvedParameterVector(
      SourceMetadataScannerState.SourceFile file,
      SourceMetadataScannerState.MethodInfo declaration,
      String indexName) {
    JavacTypeParameterClause clause =
        JavacTypeParameterClause.parse(file, declaration, indexName);
    if (clause.abstain()) {
      // An unparseable type-parameter clause leaves the parameter names
      // unknown, reopening the mirrored-overload derangement window: the
      // group must abstain rather than correlate on possibly-swapped
      // vectors.
      return List.of();
    }
    Set<String> typeParameters = clause.names();
    List<String> vector = new ArrayList<>();
    for (SourceMetadataScannerState.Parameter parameter
        : SourceMetadataScanner.parameters(declaration.parameters())) {
      String textual = SourceMetadataScanner.parameterType(
          parameter.type() + " " + parameter.name());
      boolean array = textual.endsWith("[]");
      String element = array ? textual.substring(0, textual.length() - 2) : textual;
      if (typeParameters.contains(element)) {
        // A method type parameter erases to its BOUND in the index but
        // resolves through imports textually — the two denotations can
        // derange across mirrored overloads, so the group must abstain
        // rather than correlate on swapped vectors.
        return List.of();
      }
      Optional<String> canonical = PRIMITIVE_TYPES.contains(element)
          ? Optional.of(element)
          : scanner.resolveReferenceType(element, file);
      if (canonical.isEmpty()) {
        return List.of();
      }
      vector.add(canonical.get() + (array ? "[]" : ""));
    }
    return vector;
  }

  private static final Set<String> PRIMITIVE_TYPES = Set.of(
      "boolean", "byte", "char", "short", "int", "long", "float", "double", "void");

  /** The index-rendered producer descriptor, matching the producer method by its own parameter text. */
  Optional<String> producerJvmDescriptorFromIndex(
      SourceMetadataScannerState.BeanCandidate candidate) {
    int arity = SourceMetadataScanner.parameters(candidate.producerParameters()).size();
    String candidateParameters = candidate.producerParameters()
        .replaceAll("\\s+", " ").trim();
    for (SourceMetadataScannerState.MethodInfo method
        : SourceMetadataScanner.methods(candidate.file())) {
      // The candidate's own parameter text identifies its overload: with
      // same-arity producer overloads the name+arity alone would attribute
      // the first overload's descriptor to every candidate.
      if (method.name().equals(candidate.producerMethod())
          && method.parameters().replaceAll("\\s+", " ").trim()
              .equals(candidateParameters)
          && SourceMetadataScanner.parameters(method.parameters()).size() == arity) {
        return correlatedJvmDescriptor(
            candidate.file(),
            scanner.typeContaining(candidate.file(),
                SourceMetadataScanner.declarationStart(candidate.file(), method.start())),
            method);
      }
    }
    return Optional.empty();
  }
}
