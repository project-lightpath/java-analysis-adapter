package com.projectlightpath.analysis.java;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The seam-C (#24) member mirror lookup with its decline evidence
 * (issue #62, extracted from {@link JavacFrameworkAnnotations} per the
 * PMD GodClass measurement — behavior identical, placement documented
 * in the commit). The opt-out gate lives inside; the decline evidence
 * is silenced when the index build failed (a partial index still
 * carries usable members — CodeRabbit CR-1, finding 4709913078).
 */
final class JavacFrameworkMemberLookup {
  private static final Pattern DOLLAR_DIGIT = Pattern.compile("\\$\\d");

  private final AnalysisContext context;

  private JavacFrameworkMemberLookup(AnalysisContext context) {
    this.context = context;
  }

  /** Returns the lookup bound to one scanner's context. */
  static JavacFrameworkMemberLookup of(SourceMetadataScanner scanner) {
    return new JavacFrameworkMemberLookup(scanner.context);
  }

  /** Returns the lookup bound to one analysis context. */
  static JavacFrameworkMemberLookup forContext(AnalysisContext context) {
    return new JavacFrameworkMemberLookup(context);
  }

  /** One seam-C member lookup: the member, or the decline evidence. */
  record MemberLookup(
      Optional<JavacSourceSymbolIndex.MemberInfo> member,
      Optional<JavacAbstentionEvidence> abstention) {}

  /**
   * The unique-member lookup. Count ALL same-arity overloads before
   * rejecting error types: filtering first would create false
   * uniqueness when an unresolved overload shadows a resolved one (the
   * #22 ambiguity rule).
   */
  MemberLookup lookup(String owner, String name, String kind, int arity) {
    if (!JavacSourceSymbolIndex.enabled() || owner == null || owner.isEmpty()) {
      return new MemberLookup(Optional.empty(), Optional.empty());
    }
    Optional<JavacSourceSymbolIndex> index = JavacSourceSymbolIndex.of(context);
    if (index.isEmpty()) {
      return new MemberLookup(Optional.empty(), Optional.empty());
    }
    // CR-1, finding 4709913093: an UNKNOWN arity (-1, comment-corrupted
    // parameter text) must not render as the DISTINCT arity zero — the
    // identity uses an explicit marker.
    String identity = JavacAbstentionEvidence.memberIdentity(
        owner, name, arity < 0 ? -1 : arity);
    if (DOLLAR_DIGIT.matcher(owner).find()) {
      return withSilencedEvidence(index.orElseThrow(), new MemberLookup(
          Optional.empty(), Optional.of(new JavacAbstentionEvidence(
              "mirror_owner_unusable", identity, List.of()))));
    }
    List<JavacSourceSymbolIndex.TypeInfo> raw = index.orElseThrow().type(owner);
    return lookupInType(index.orElseThrow(), raw, owner, name, kind, arity, identity);
  }

  /** The lookup within the owner's (unique usable) type. */
  private MemberLookup lookupInType(
      JavacSourceSymbolIndex index,
      List<JavacSourceSymbolIndex.TypeInfo> raw,
      String owner,
      String name,
      String kind,
      int arity,
      String identity) {
    Optional<JavacSourceSymbolIndex.TypeInfo> usable = usableType(raw);
    if (usable.isEmpty()) {
      String code = raw.isEmpty()
          ? "mirror_owner_absent"
          : raw.size() > 1 ? "mirror_owner_ambiguous" : "mirror_owner_unusable";
      return withSilencedEvidence(index, new MemberLookup(
          Optional.empty(), Optional.of(new JavacAbstentionEvidence(
              code, identity, List.of()))));
    }
    List<JavacSourceSymbolIndex.MemberInfo> matches = usable.orElseThrow().members().stream()
        .filter(candidate -> candidate.name().equals(name))
        .filter(candidate -> candidate.kind().equals(kind))
        .filter(candidate -> arity < 0
            || candidate.parameterTypes().size() == arity)
        .toList();
    if (matches.isEmpty()) {
      return withSilencedEvidence(index, new MemberLookup(
          Optional.empty(), Optional.of(new JavacAbstentionEvidence(
              "mirror_member_absent", identity, List.of()))));
    }
    if (matches.size() > 1) {
      return withSilencedEvidence(index, new MemberLookup(
          Optional.empty(), Optional.of(new JavacAbstentionEvidence(
              "mirror_member_ambiguous", identity, List.of()))));
    }
    JavacSourceSymbolIndex.MemberInfo found = matches.get(0);
    if (found.errorType()) {
      List<String> codes = raw.size() == 1
          ? index.errorCodesFor(raw.get(0).file())
          : List.of();
      return withSilencedEvidence(index, new MemberLookup(
          Optional.empty(), Optional.of(new JavacAbstentionEvidence(
              "mirror_member_error_type", identity, codes))));
    }
    return new MemberLookup(Optional.of(found), Optional.empty());
  }

  /**
   * The single usable owner type, or empty when none or several: the
   * shape filter rejects $digit-adjacent unusable shapes and
   * cross-file name ambiguity alike.
   */
  private static Optional<JavacSourceSymbolIndex.TypeInfo> usableType(
      List<JavacSourceSymbolIndex.TypeInfo> raw) {
    List<JavacSourceSymbolIndex.TypeInfo> usable =
        raw.stream().filter(JavacFrameworkAnnotations::shapeIsUsable).toList();
    return usable.size() == 1 ? Optional.of(usable.get(0)) : Optional.empty();
  }

  /**
   * Suppresses the decline evidence when the index build failed while
   * keeping the lookup result (CodeRabbit CR-1, finding 4709913078).
   */
  private static MemberLookup withSilencedEvidence(
      JavacSourceSymbolIndex index, MemberLookup lookup) {
    if (lookup.member().isPresent() || !index.buildFailed()) {
      return lookup;
    }
    return new MemberLookup(Optional.empty(), Optional.empty());
  }
}
