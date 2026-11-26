package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The multi-overload bijection of the javac member correlation (#23,
 * extracted from {@link JavacMemberCorrelation} per the PMD GodClass
 * measurement — behavior identical, placement documented in the GREEN
 * commit): every textual sibling in the name+arity group must resolve to
 * a distinct canonical parameter vector, and the vector set must equal
 * the index vector set. A single wrong or missing resolution breaks the
 * bijection (vector collision or set mismatch), so the group fails
 * closed to textual rather than selecting a wrong overload.
 *
 * <p>Issue #62: each decline returns the per-member abstention evidence
 * — {@code type_parameter_overload_group} when a vector resolution hit a
 * type parameter / unparseable clause / unestablishable enclosing scope
 * (the unresolved-bounds class), {@code overload_group_mismatch} for
 * sibling-count, reference-resolution, collision, and set mismatches.
 */
final class JavacOverloadBijection {
  private final SourceMetadataScanner scanner;

  JavacOverloadBijection(SourceMetadataScanner scanner) {
    this.scanner = scanner;
  }

  /** Runs the bijection for one declaration against its index group. */
  JavacCorrelation correlate(
      SourceMetadataScannerState.SourceFile file,
      SourceMetadataScannerState.MethodInfo declaration,
      List<SourceMetadataScannerState.MethodInfo> declarations,
      List<JavacSourceSymbolIndex.MemberInfo> group,
      String indexName,
      String identity) {
    List<SourceMetadataScannerState.MethodInfo> siblings = declarations.stream()
        .filter(other -> other.name().equals(declaration.name()))
        .filter(other -> !JavacMemberCorrelation.hasCommentText(other.parameters()))
        .filter(other -> SourceMetadataScanner.parameters(other.parameters()).size()
            == declarationArity(declaration))
        .toList();
    if (siblings.size() != group.size()) {
      return JavacCorrelation.abstained(new JavacAbstentionEvidence(
          "overload_group_mismatch", identity, List.of()));
    }
    List<List<String>> textualVectors = new ArrayList<>();
    for (SourceMetadataScannerState.MethodInfo sibling : siblings) {
      VectorResolution vector = resolvedParameterVector(file, sibling, indexName);
      if (vector.typeParameterAbstention()) {
        return JavacCorrelation.abstained(new JavacAbstentionEvidence(
            "type_parameter_overload_group", identity, List.of()));
      }
      if (vector.vector().isEmpty()) {
        return JavacCorrelation.abstained(new JavacAbstentionEvidence(
            "overload_group_mismatch", identity, List.of()));
      }
      textualVectors.add(vector.vector());
    }
    List<List<String>> indexVectors = group.stream()
        .map(JavacSourceSymbolIndex.MemberInfo::parameterTypes)
        .toList();
    if (textualVectors.stream().distinct().count() != textualVectors.size()
        || indexVectors.stream().distinct().count() != indexVectors.size()
        || !new HashSet<>(textualVectors).equals(new HashSet<>(indexVectors))) {
      return JavacCorrelation.abstained(new JavacAbstentionEvidence(
          "overload_group_mismatch", identity, List.of()));
    }
    VectorResolution queried = resolvedParameterVector(file, declaration, indexName);
    if (queried.typeParameterAbstention()) {
      return JavacCorrelation.abstained(new JavacAbstentionEvidence(
          "type_parameter_overload_group", identity, List.of()));
    }
    if (queried.vector().isEmpty()) {
      return JavacCorrelation.abstained(new JavacAbstentionEvidence(
          "overload_group_mismatch", identity, List.of()));
    }
    return group.stream()
        .filter(member -> member.parameterTypes().equals(queried.vector()))
        .findFirst()
        .map(JavacCorrelation::correlated)
        .orElseGet(() -> JavacCorrelation.abstained(new JavacAbstentionEvidence(
            "overload_group_mismatch", identity, List.of())));
  }

  private int declarationArity(SourceMetadataScannerState.MethodInfo declaration) {
    return SourceMetadataScanner.parameters(declaration.parameters()).size();
  }

  /** One vector resolution: the vector, or why it could not be built. */
  private record VectorResolution(List<String> vector, boolean typeParameterAbstention) {
    static final VectorResolution EMPTY = new VectorResolution(List.of(), false);
    static final VectorResolution TYPE_PARAMETER =
        new VectorResolution(List.of(), true);
  }

  /** The canonical parameter vector of one textual declaration, or empty. */
  private VectorResolution resolvedParameterVector(
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
      return VectorResolution.TYPE_PARAMETER;
    }
    Set<String> typeParameters = clause.names();
    // Class-level type parameters shadow imports exactly like method
    // ones: a parameter naming one resolves through the import textually
    // while javac erases it to the enclosing type's bound, so mirrored
    // overloads can swap vectors. An unestablishable enclosing scope
    // abstains, fail closed.
    Set<String> enclosingTypeParameters =
        SourceMetadataScannerTextLayer14.enclosingTypeParameterNames(file, declaration);
    if (enclosingTypeParameters == null) {
      return VectorResolution.TYPE_PARAMETER;
    }
    List<String> vector = new ArrayList<>();
    for (SourceMetadataScannerState.Parameter parameter
        : SourceMetadataScanner.parameters(declaration.parameters())) {
      String textual = SourceMetadataScanner.parameterType(
          parameter.type() + " " + parameter.name());
      // CR-1, finding 4709913104: strip EVERY trailing [] rank before the
      // base-name test — T[][] must recognize its type parameter at any
      // rank, or mirrored rank-2 overloads could derange their vectors.
      String element = textual;
      while (element.endsWith("[]")) {
        element = element.substring(0, element.length() - 2);
      }
      String dimensions = textual.substring(element.length());
      if (typeParameters.contains(element)
          || enclosingTypeParameters.contains(element)) {
        // A method OR CLASS type parameter erases to its BOUND in the
        // index but resolves through imports textually — the two
        // denotations can derange across mirrored overloads, so the
        // group must abstain rather than correlate on swapped vectors.
        return VectorResolution.TYPE_PARAMETER;
      }
      Optional<String> canonical = JavacMemberCorrelation.PRIMITIVE_TYPES.contains(element)
          ? Optional.of(element)
          : scanner.resolveReferenceType(element, file);
      if (canonical.isEmpty()) {
        return VectorResolution.EMPTY;
      }
      vector.add(canonical.get() + dimensions);
    }
    return new VectorResolution(vector, false);
  }
}
