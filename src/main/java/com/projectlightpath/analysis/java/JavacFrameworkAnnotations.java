package com.projectlightpath.analysis.java;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * javac framework-annotation lookups for one scanner's context: type and
 * member correlation against the #21 index, with silent decline whenever
 * the flag is off, the index is absent, or the correlation is ambiguous.
 *
 * <p>Owners resolving to local or anonymous nestings, or spelled with a
 * {@code $digit} segment, are rejected by shape (a deliberate safe
 * over-approximation: rejection means the caller keeps the textual
 * answer). Value and qualifier interpretation live in
 * {@link JavacFrameworkAnnotationValues} and
 * {@link JavacFrameworkQualifierFamilies}.
 */
final class JavacFrameworkAnnotations {
  private static final Pattern DOLLAR_DIGIT = Pattern.compile("\\$\\d");

  private final AnalysisContext context;

  private JavacFrameworkAnnotations(AnalysisContext context) {
    this.context = context;
  }

  /** Returns the helper bound to one scanner's context. */
  static JavacFrameworkAnnotations of(SourceMetadataScanner scanner) {
    return new JavacFrameworkAnnotations(scanner.context);
  }

  /** Returns the type-level annotation mirrors of a file's first type. */
  Optional<List<JavacSourceSymbolIndex.AnnotationInfo>> typeAnnotations(
      SourceMetadataScannerState.SourceFile file) {
    return typeAnnotationsLookup(file).mirrors();
  }

  /** One seam-C mirror lookup: the mirrors, or the decline evidence (#62). */
  record MirrorLookup(
      Optional<List<JavacSourceSymbolIndex.AnnotationInfo>> mirrors,
      Optional<JavacAbstentionEvidence> abstention) {

    static final MirrorLookup EMPTY =
        new MirrorLookup(Optional.empty(), Optional.empty());
  }

  /**
   * The type-level mirror lookup with its decline evidence. The opt-out
   * gate lives inside: with the flag off, no evidence is constructed.
   * The three empty arms are distinguished: no name-matching candidate
   * ({@code mirror_type_absent}), all matches shape-unusable
   * ({@code mirror_owner_unusable}), more than one usable candidate
   * ({@code mirror_owner_ambiguous}).
   */
  MirrorLookup typeAnnotationsLookup(
      SourceMetadataScannerState.SourceFile file) {
    if (!JavacSourceSymbolIndex.enabled()) {
      return MirrorLookup.EMPTY;
    }
    Optional<JavacSourceSymbolIndex> index =
        JavacSourceSymbolIndex.intactIndex(context);
    if (index.isEmpty()) {
      // Off, absent, or a failed build: no per-member evidence.
      return MirrorLookup.EMPTY;
    }
    List<JavacSourceSymbolIndex.TypeInfo> named =
        index.orElseThrow().typesIn(file.path()).stream()
            .filter(type -> type.canonicalName().equals(file.typeName())
                || type.binaryName().equals(file.typeName()))
            .toList();
    String identity = file.typeName();
    if (named.isEmpty()) {
      return new MirrorLookup(Optional.empty(), Optional.of(
          new JavacAbstentionEvidence("mirror_type_absent", identity, List.of())));
    }
    List<JavacSourceSymbolIndex.TypeInfo> usable =
        named.stream().filter(JavacFrameworkAnnotations::shapeIsUsable).toList();
    if (usable.isEmpty()) {
      return new MirrorLookup(Optional.empty(), Optional.of(
          new JavacAbstentionEvidence("mirror_owner_unusable", identity, List.of())));
    }
    if (usable.size() > 1) {
      return new MirrorLookup(Optional.empty(), Optional.of(
          new JavacAbstentionEvidence("mirror_owner_ambiguous", identity, List.of())));
    }
    return new MirrorLookup(
        Optional.of(usable.get(0).annotations()), Optional.empty());
  }

  /** Returns the unique index member matching owner, name, kind, and arity. */
  Optional<JavacSourceSymbolIndex.MemberInfo> member(
      String owner, String name, String kind, int arity) {
    return memberLookup(owner, name, kind, arity).member();
  }

  /** One seam-C member lookup: the member, or the decline evidence (#62). */
  record MemberLookup(
      Optional<JavacSourceSymbolIndex.MemberInfo> member,
      Optional<JavacAbstentionEvidence> abstention) {}

  /**
   * The member lookup with its decline evidence. Count ALL same-arity
   * overloads before rejecting error types: filtering first would create
   * false uniqueness when an unresolved overload shadows a resolved one
   * (the #22 ambiguity rule).
   */
  MemberLookup memberLookup(String owner, String name, String kind, int arity) {
    if (!JavacSourceSymbolIndex.enabled() || owner == null || owner.isEmpty()) {
      return new MemberLookup(Optional.empty(), Optional.empty());
    }
    Optional<JavacSourceSymbolIndex> index =
        JavacSourceSymbolIndex.intactIndex(context);
    if (index.isEmpty()) {
      // Off, absent, or a failed build: no per-member evidence.
      return new MemberLookup(Optional.empty(), Optional.empty());
    }
    String identity =
        JavacAbstentionEvidence.memberIdentity(owner, name, Math.max(arity, 0));
    if (DOLLAR_DIGIT.matcher(owner).find()) {
      return new MemberLookup(Optional.empty(), Optional.of(
          new JavacAbstentionEvidence("mirror_owner_unusable", identity, List.of())));
    }
    List<JavacSourceSymbolIndex.TypeInfo> raw =
        index.orElseThrow().type(owner);
    if (raw.isEmpty()) {
      return new MemberLookup(Optional.empty(), Optional.of(
          new JavacAbstentionEvidence("mirror_owner_absent", identity, List.of())));
    }
    List<JavacSourceSymbolIndex.TypeInfo> usable =
        raw.stream().filter(JavacFrameworkAnnotations::shapeIsUsable).toList();
    if (usable.isEmpty()) {
      return new MemberLookup(Optional.empty(), Optional.of(
          new JavacAbstentionEvidence("mirror_owner_unusable", identity, List.of())));
    }
    if (usable.size() > 1) {
      return new MemberLookup(Optional.empty(), Optional.of(
          new JavacAbstentionEvidence("mirror_owner_ambiguous", identity, List.of())));
    }
    List<JavacSourceSymbolIndex.MemberInfo> matches = usable.get(0).members().stream()
        .filter(candidate -> candidate.name().equals(name))
        .filter(candidate -> candidate.kind().equals(kind))
        .filter(candidate -> arity < 0
            || candidate.parameterTypes().size() == arity)
        .toList();
    if (matches.isEmpty()) {
      return new MemberLookup(Optional.empty(), Optional.of(
          new JavacAbstentionEvidence("mirror_member_absent", identity, List.of())));
    }
    if (matches.size() > 1) {
      return new MemberLookup(Optional.empty(), Optional.of(
          new JavacAbstentionEvidence("mirror_member_ambiguous", identity, List.of())));
    }
    JavacSourceSymbolIndex.MemberInfo found = matches.get(0);
    if (found.errorType()) {
      List<String> codes = raw.size() == 1
          ? index.orElseThrow().errorCodesFor(raw.get(0).file())
          : List.of();
      return new MemberLookup(Optional.empty(), Optional.of(
          new JavacAbstentionEvidence("mirror_member_error_type", identity, codes)));
    }
    return new MemberLookup(Optional.of(found), Optional.empty());
  }

  /** Returns one member's declaration annotation mirrors. */
  Optional<List<JavacSourceSymbolIndex.AnnotationInfo>> memberAnnotations(
      String owner, String name, String kind, int arity) {
    return member(owner, name, kind, arity)
        .map(JavacSourceSymbolIndex.MemberInfo::annotations);
  }

  /** Returns one member's per-parameter annotation mirrors (#37 API). */
  Optional<List<List<JavacSourceSymbolIndex.AnnotationInfo>>> parameterAnnotations(
      String owner, String name, String kind, int arity, int parsedCount) {
    return member(owner, name, kind, arity)
        .filter(found -> found.parameterTypes().size() == parsedCount)
        .map(JavacSourceSymbolIndex.MemberInfo::parameterAnnotations);
  }

  /**
   * Returns the index member count for one owner, name, and kind, or -1
   * when the owner does not resolve. Callers compare it with the textual
   * occurrence count: a textual regex can match more sites than the index
   * knows members (e.g. a void method spelled like the class matches the
   * constructor regex), and correlating such a site would attribute the
   * wrong member's mirrors to it.
   */
  long memberCount(String owner, String name, String kind) {
    return resolvedType(owner)
        .map(type -> type.members().stream()
            .filter(candidate -> candidate.name().equals(name))
            .filter(candidate -> candidate.kind().equals(kind))
            .count())
        .orElse(-1L);
  }

  /** Returns the pre-drop parameter count, or -1 when comments hide it. */
  static int parameterArity(String parameters) {
    if (parameters == null) {
      return -1;
    }
    String text = parameters.trim();
    if (text.isEmpty()) {
      return 0;
    }
    if (text.contains("/*") || text.contains("//")) {
      return -1;
    }
    return SourceMetadataScanner.splitArguments(text).size();
  }

  /**
   * Reports whether a rendered argument string is usable outside quotes: a
   * nested annotation value ({@code @}), or an unresolved value
   * ({@code <error>}, e.g. an enum constant whose type the index could not
   * resolve), means the mirror cannot stand in for the textual answer. An
   * odd quote count (the #21 renderer does not re-escape quotes inside
   * string values, so a value containing {@code "} flips the tracking
   * state) also declines — the conservative direction for #48.
   */
  static boolean renderIsUsable(String rendered) {
    boolean quoted = false;
    for (int index = 0; index < rendered.length(); index++) {
      char character = rendered.charAt(index);
      if (character == '"') {
        quoted = !quoted;
      } else if (!quoted && (character == '@'
          || rendered.startsWith("<error>", index))) {
        return false;
      }
    }
    return !quoted;
  }

  /** Returns the annotation's simple name (last canonical segment). */
  static String simpleName(JavacSourceSymbolIndex.AnnotationInfo annotation) {
    return JavacAnnotationAccess.simpleName(annotation);
  }

  /** Renders one annotation's element values as {@code key=value} text. */
  static String renderedArguments(JavacSourceSymbolIndex.AnnotationInfo annotation) {
    return annotation.values().entrySet().stream()
        .map(entry -> entry.getKey() + "=" + entry.getValue())
        .collect(java.util.stream.Collectors.joining(", "));
  }

  /** Resolves one owner name to a unique, shape-usable index type. */
  private Optional<JavacSourceSymbolIndex.TypeInfo> resolvedType(String owner) {
    if (owner == null || owner.isEmpty() || DOLLAR_DIGIT.matcher(owner).find()) {
      return Optional.empty();
    }
    List<JavacSourceSymbolIndex.TypeInfo> candidates =
        JavacSourceSymbolIndex.of(context)
            .map(index -> index.type(owner))
            .orElse(List.of()).stream()
            .filter(JavacFrameworkAnnotations::shapeIsUsable)
            .toList();
    return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
  }

  private static boolean shapeIsUsable(JavacSourceSymbolIndex.TypeInfo type) {
    return !type.shape().errorType()
        && !type.shape().duplicateBinaryName()
        && !"LOCAL".equals(type.nestingKind())
        && !"ANONYMOUS".equals(type.nestingKind());
  }
}
