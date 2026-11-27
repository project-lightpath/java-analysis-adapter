package com.projectlightpath.analysis.java;

import java.util.List;
import java.util.Optional;

/**
 * javac index shapes for the injection-contract type matching (issue #61).
 *
 * <p>Every shape is javac's resolution of an ACTUAL indexed declaration —
 * the injection's own member parameter, the candidate's own declared type,
 * or the candidate's own direct supertypes — and every method returns
 * empty on every decline (flag off, absent index, failed build, ambiguous
 * owner or overload, error-typed member, phantom constructor guard). The
 * opt-out gate lives INSIDE: with the flag off the seam is empty and the
 * matching surface keeps its exact textual behavior.
 *
 * <p>Participation rule (PLAN-61 §3.4): a shape fills a textual VACUUM
 * (repair-only — {@code canonicalType(...).or(shape)}), never overriding a
 * non-null textual answer, or serves a generics-gated additional disjunct.
 * The seam carries NO evidence channel: a decline IS the textual fallback.
 */
final class JavacInjectionTypeShapes {
  private final SourceMetadataScanner scanner;

  private JavacInjectionTypeShapes(SourceMetadataScanner scanner) {
    this.scanner = scanner;
  }

  /** Returns the shapes bound to one scanner's context. */
  static JavacInjectionTypeShapes of(SourceMetadataScanner scanner) {
    return new JavacInjectionTypeShapes(scanner);
  }

  /**
   * The canonical erased index shape of one injection parameter, or empty
   * on every decline. Constructor sites re-apply the extractor's
   * phantom-site guard through the SHARED extraction rule
   * ({@link FrameworkInjectionScanning#constructorInfos}); method and
   * field sites look up their unique member by owner, name, kind, and
   * arity (the #24/#62 unique-member seam).
   */
  Optional<String> requestedShape(
      SourceMetadataScannerState.InjectionPoint injection,
      int parameterIndex) {
    if (!JavacSourceSymbolIndex.enabled() || injection == null
        || parameterIndex < 0
        || parameterIndex >= injection.parameters().size()) {
      return Optional.empty();
    }
    String kind = injection.kind();
    String memberName;
    int arity;
    if ("constructor".equals(kind)) {
      // The extractor's phantom-site guard, recomputed through the same
      // extraction implementation: a textual site count that diverges from
      // the index's constructor count declines the whole file.
      long indexCount = JavacFrameworkAnnotations.of(scanner).memberCount(
          injection.ownerType(), injection.file().simpleTypeName(),
          "constructor");
      if (indexCount != FrameworkInjectionScanning
          .constructorInfos(scanner, injection.file()).size()) {
        return Optional.empty();
      }
      memberName = injection.file().simpleTypeName();
      arity = injection.parameters().size();
    } else if ("method".equals(kind) || "field".equals(kind)) {
      memberName = injection.member();
      arity = "field".equals(kind)
          ? -1
          : injection.parameters().size();
    } else {
      return Optional.empty();
    }
    JavacFrameworkMemberLookup.MemberLookup lookup =
        JavacFrameworkMemberLookup.of(scanner)
            .lookup(injection.ownerType(), memberName, kind, arity);
    if (lookup.member().isEmpty()) {
      return Optional.empty();
    }
    JavacSourceSymbolIndex.MemberInfo member = lookup.member().orElseThrow();
    if ("field".equals(kind)) {
      return member.returnType().isEmpty()
          ? Optional.empty()
          : Optional.of(member.returnType());
    }
    return parameterIndex < member.parameterTypes().size()
        ? Optional.of(member.parameterTypes().get(parameterIndex))
        : Optional.empty();
  }

  /**
   * The canonical erased identity of the candidate's own declaration, or
   * empty on every decline: a producer's correlated return shape, or the
   * unique usable index type named by the candidate's type in its file
   * (the MirrorLookup named-filter rule).
   */
  Optional<String> candidateShape(
      SourceMetadataScannerState.BeanCandidate candidate) {
    if (!JavacSourceSymbolIndex.enabled() || candidate == null) {
      return Optional.empty();
    }
    if (candidate.producer()) {
      return JavacMemberCorrelation.of(scanner)
          .producerCorrelation(candidate)
          .memberOptional()
          .map(JavacSourceSymbolIndex.MemberInfo::returnType)
          .filter(returnType -> !returnType.isEmpty());
    }
    return declaredType(candidate).map(
        JavacSourceSymbolIndex.TypeInfo::canonicalName);
  }

  /**
   * The canonical erased DIRECT supertypes of the candidate's declared
   * type, or empty on every decline (producers carry no declared-class
   * contract semantics here; an error-shaped type publishes nothing).
   */
  Optional<List<String>> contractShapes(
      SourceMetadataScannerState.BeanCandidate candidate) {
    if (!JavacSourceSymbolIndex.enabled() || candidate == null
        || candidate.producer()) {
      return Optional.empty();
    }
    return declaredType(candidate)
        .map(JavacSourceSymbolIndex.TypeInfo::supertypes);
  }

  /**
   * The unique usable index type named by the candidate's type spelling in
   * the candidate's own file: the canonical or binary name must match the
   * candidate type exactly (cross-file name ambiguity and unusable shapes
   * decline).
   */
  private Optional<JavacSourceSymbolIndex.TypeInfo> declaredType(
      SourceMetadataScannerState.BeanCandidate candidate) {
    Optional<JavacSourceSymbolIndex> index =
        JavacSourceSymbolIndex.of(scanner.context);
    if (index.isEmpty()) {
      return Optional.empty();
    }
    List<JavacSourceSymbolIndex.TypeInfo> named =
        index.orElseThrow().typesIn(candidate.file().path()).stream()
            .filter(type -> type.canonicalName().equals(candidate.candidateType())
                || type.binaryName().equals(candidate.candidateType()))
            .toList();
    List<JavacSourceSymbolIndex.TypeInfo> usable =
        named.stream().filter(JavacFrameworkAnnotations::shapeIsUsable).toList();
    return usable.size() == 1
        ? Optional.of(usable.get(0))
        : Optional.empty();
  }
}
