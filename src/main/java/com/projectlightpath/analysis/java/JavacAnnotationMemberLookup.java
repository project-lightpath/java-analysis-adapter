package com.projectlightpath.analysis.java;

import java.util.List;
import java.util.Optional;

/**
 * The seam-B (#22) member lookup with its decline evidence (issue #62,
 * extracted from {@link JavacAnnotationAccess} per the PMD GodClass
 * measurement — behavior identical, placement documented in the commit).
 * The opt-out gate lives inside: with the flag off, or when the index is
 * absent or its build failed, no evidence is constructed. The decline
 * classification mirrors the accessor's counting rule — ALL same-arity
 * overloads are counted before rejecting error types, so an unresolved
 * overload cannot let a resolved sibling look unique.
 */
final class JavacAnnotationMemberLookup {
  private final AnalysisContext context;

  private JavacAnnotationMemberLookup(AnalysisContext context) {
    this.context = context;
  }

  /** Returns the lookup bound to one scanner's context. */
  static JavacAnnotationMemberLookup of(SourceMetadataScanner scanner) {
    return new JavacAnnotationMemberLookup(scanner.context);
  }

  /** One seam-B member lookup: the member, or the decline evidence. */
  record MemberLookup(
      Optional<JavacSourceSymbolIndex.MemberInfo> member,
      Optional<JavacAbstentionEvidence> abstention) {

    static final MemberLookup EMPTY =
        new MemberLookup(Optional.empty(), Optional.empty());
  }

  /** Runs the unique-member lookup for owner, name, and arity. */
  MemberLookup lookup(String typeName, String memberName, int parameterCount) {
    if (!JavacSourceSymbolIndex.enabled()) {
      return MemberLookup.EMPTY;
    }
    Optional<JavacSourceSymbolIndex> index = JavacSourceSymbolIndex.of(context);
    if (index.isEmpty()) {
      return MemberLookup.EMPTY;
    }
    List<JavacSourceSymbolIndex.MemberInfo> matches =
        index.orElseThrow().members(typeName, memberName).stream()
            .filter(member -> member.kind().equals("method")
                && member.parameterTypes().size() == parameterCount)
            .toList();
    String identity =
        JavacAbstentionEvidence.memberIdentity(typeName, memberName, parameterCount);
    if (matches.isEmpty()) {
      return withSilencedEvidence(index.orElseThrow(), new MemberLookup(
          Optional.empty(), Optional.of(new JavacAbstentionEvidence(
              "annotation_member_absent", identity, List.of()))));
    }
    if (matches.size() > 1) {
      return withSilencedEvidence(index.orElseThrow(), new MemberLookup(
          Optional.empty(), Optional.of(new JavacAbstentionEvidence(
              "annotation_member_ambiguous", identity, List.of()))));
    }
    JavacSourceSymbolIndex.MemberInfo found = matches.get(0);
    if (found.errorType()) {
      return withSilencedEvidence(index.orElseThrow(), new MemberLookup(
          Optional.empty(), Optional.of(new JavacAbstentionEvidence(
              "annotation_member_error_type", identity,
              uniqueOwnerErrorCodes(index.orElseThrow(), typeName)))));
    }
    return new MemberLookup(Optional.of(found), Optional.empty());
  }

  /**
   * Suppresses the decline evidence when the index build failed while
   * keeping the lookup result: a partial index still carries usable
   * members (CodeRabbit CR-1, finding 4709913078) — a scan-global build
   * failure is not a per-member decline.
   */
  private static MemberLookup withSilencedEvidence(
      JavacSourceSymbolIndex index, MemberLookup lookup) {
    if (lookup.member().isPresent() || !index.buildFailed()) {
      return lookup;
    }
    return MemberLookup.EMPTY;
  }

  /**
   * The declaring file's error codes, only when the owner name resolves
   * to exactly one index type (same-name types across files cannot be
   * attributed to one member's file — the ambiguity is already the
   * decline's story).
   */
  private static List<String> uniqueOwnerErrorCodes(
      JavacSourceSymbolIndex index, String typeName) {
    List<JavacSourceSymbolIndex.TypeInfo> entries = index.type(typeName);
    if (entries.size() != 1) {
      return List.of();
    }
    return index.errorCodesFor(entries.get(0).file());
  }
}
