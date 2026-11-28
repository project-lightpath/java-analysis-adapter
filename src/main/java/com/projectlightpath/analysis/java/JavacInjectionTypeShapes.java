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
    // CodeRabbit 4724039702: an explicit receiver parameter
    // (`Consumer this` / `Outer.Consumer this`) is kept by the textual
    // split — it inflates the lookup arity and misaligns the formal
    // index, attributing a sibling overload's type. Receiver parameters
    // are reserved-position (first, name `this` or `Qual.this`), so the
    // whole site abstains.
    if (hasReceiverParameter(injection)) {
      return Optional.empty();
    }
    return memberFor(injection)
        .filter(member -> shapeUsable(injection, member, parameterIndex))
        .map(member -> parameterShape(injection, member, parameterIndex))
        .orElse(Optional.empty());
  }

  /** Reports whether the first parameter is an explicit receiver. */
  private static boolean hasReceiverParameter(
      SourceMetadataScannerState.InjectionPoint injection) {
    if (injection.parameters().isEmpty()) {
      return false;
    }
    String firstName = injection.parameters().get(0).name();
    return "this".equals(firstName) || firstName.endsWith(".this");
  }

  /**
   * The unique index member for one injection site, or empty on every
   * decline: constructor sites re-apply the extractor's phantom-site
   * guard through the SHARED extraction rule; method and field sites
   * look up by owner, name, kind, and arity.
   */
  private Optional<JavacSourceSymbolIndex.MemberInfo> memberFor(
      SourceMetadataScannerState.InjectionPoint injection) {
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
    return JavacFrameworkMemberLookup.of(scanner)
        .lookup(injection.ownerType(), memberName, kind, arity)
        .member();
  }

  /** Reports whether the found member carries a shape at the index. */
  private static boolean shapeUsable(
      SourceMetadataScannerState.InjectionPoint injection,
      JavacSourceSymbolIndex.MemberInfo member,
      int parameterIndex) {
    if ("field".equals(injection.kind())) {
      return !member.returnType().isEmpty();
    }
    return parameterIndex < member.parameterTypes().size();
  }

  /** The shape itself: a field's declared type, or one formal type. */
  private static Optional<String> parameterShape(
      SourceMetadataScannerState.InjectionPoint injection,
      JavacSourceSymbolIndex.MemberInfo member,
      int parameterIndex) {
    if ("field".equals(injection.kind())) {
      return Optional.of(member.returnType());
    }
    return Optional.of(member.parameterTypes().get(parameterIndex));
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
