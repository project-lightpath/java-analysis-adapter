package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
 *
 * <p>Issue #62: every decline returns a {@link JavacCorrelation} carrying
 * per-member abstention evidence (code + member identity + the javac
 * error codes recorded for the member's file) — the blast-radius-safe
 * visibility channel that never enters {@code scan.diagnostics()}. The
 * opt-out gate lives INSIDE the seam: with the flag off, the correlation
 * is empty and no evidence is constructed, whatever the caller.
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
    JavacCorrelation correlation = correlatedFirstParameter(file, method);
    return correlation.memberOptional()
        .filter(member -> !member.parameterTypes().isEmpty())
        .map(member -> expected.contains(member.parameterTypes().get(0)))
        .orElse(false);
  }

  /**
   * The first-parameter check with its abstention evidence (#62): the
   * repository layer surfaces the decline on the declared method's
   * delegation fact.
   */
  JavacCorrelation correlatedFirstParameter(
      SourceMetadataScannerState.SourceFile file,
      SourceMetadataScannerState.MethodInfo method) {
    return correlatedMethod(file, scanner.ownerTypeFor(file, method), method);
  }

  /** Reports whether the correlation guards block one file and parameter list. */
  boolean blocked(SourceMetadataScannerState.SourceFile file, String parameters) {
    return hasCommentText(parameters) || !singleTypeFile(file);
  }

  /** The correlation of one declaration, with its abstention evidence. */
  JavacCorrelation correlation(
      SourceMetadataScannerState.SourceFile file,
      String ownerType,
      SourceMetadataScannerState.MethodInfo method) {
    return correlatedMethod(file, ownerType, method);
  }

  /**
   * The caller member's abstention, re-issued at feature-build time for
   * the reflection and proxy classification seams (#62): the SAME pure
   * correlation query the classification ran, derived through the SAME
   * shared owner normalization, so the evidence is causal and
   * cache-order independent by construction.
   */
  Optional<JavacAbstentionEvidence> callerAbstention(
      SourceMetadataScannerState.SourceFile file,
      SourceMetadataScannerState.MethodInfo caller) {
    return correlatedMethod(file, scanner.ownerTypeFor(file, caller), caller)
        .abstentionOptional();
  }

  /**
   * Reports whether the raw parameter text contains comment markers. A
   * comment's parentheses distort {@code splitTopLevelArguments} (the split
   * is not comment-aware), so the derived arity cannot be trusted for
   * overload selection — such methods keep the textual spelling.
   */
  static boolean hasCommentText(String parameters) {
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
   * lists and multi-type files (unprovable owner) never correlate. The
   * flag-off arm returns the empty correlation BEFORE any evidence is
   * constructed (the in-seam opt-out gate).
   */
  JavacCorrelation correlatedMethod(
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
  JavacCorrelation correlatedConstructor(
      SourceMetadataScannerState.SourceFile file,
      String ownerType,
      SourceMetadataScannerState.MethodInfo constructor) {
    return correlated(
        file, ownerType, constructor,
        SourceMetadataScanner.constructors(file), "constructor",
        ownerType.substring(ownerType.lastIndexOf('.') + 1));
  }

  private JavacCorrelation correlated(
      SourceMetadataScannerState.SourceFile file,
      String ownerType,
      SourceMetadataScannerState.MethodInfo declaration,
      List<SourceMetadataScannerState.MethodInfo> declarations,
      String kind,
      String indexName) {
    if (!JavacSourceSymbolIndex.enabled()) {
      return JavacCorrelation.empty();
    }
    Optional<JavacSourceSymbolIndex> index = JavacSourceSymbolIndex.of(context);
    if (index.isEmpty()) {
      return JavacCorrelation.empty();
    }
    return withSilencedEvidence(index.orElseThrow(),
        correlatedWithIndex(file, ownerType, declaration, declarations, kind, indexName,
            index.orElseThrow()));
  }

  /**
   * Suppresses the per-member abstention evidence when the index build
   * failed (the #25 integrity codes) while KEEPING the lookup result: a
   * partial index (e.g. one missing source root) still carries usable
   * members whose correlation must not change (CodeRabbit CR-1, finding
   * 4709913078) — a scan-global build failure is not a per-member
   * abstention, so the channel stays silent, but the value path is the
   * pre-#62 one either way.
   */
  private static JavacCorrelation withSilencedEvidence(
      JavacSourceSymbolIndex index, JavacCorrelation outcome) {
    if (outcome.memberOptional().isPresent() || !index.buildFailed()) {
      return outcome;
    }
    return JavacCorrelation.empty();
  }

  private JavacCorrelation correlatedWithIndex(
      SourceMetadataScannerState.SourceFile file,
      String ownerType,
      SourceMetadataScannerState.MethodInfo declaration,
      List<SourceMetadataScannerState.MethodInfo> declarations,
      String kind,
      String indexName,
      JavacSourceSymbolIndex index) {
    int arity = SourceMetadataScanner.parameters(declaration.parameters()).size();
    String identity =
        JavacAbstentionEvidence.memberIdentity(ownerType, indexName, arity);
    if (hasCommentText(declaration.parameters())) {
      return JavacCorrelation.abstained(new JavacAbstentionEvidence(
          "comment_corrupted_parameters", identity, List.of()));
    }
    if (!singleTypeFile(file)) {
      return JavacCorrelation.abstained(new JavacAbstentionEvidence(
          "multi_type_file", identity, List.of()));
    }
    List<JavacSourceSymbolIndex.MemberInfo> group = index
        .members(ownerType, indexName).stream()
        .filter(member -> member.kind().equals(kind)
            && member.parameterTypes().size() == arity)
        .toList();
    // Error-typed members are counted before any uniqueness or type filter:
    // an unresolved overload must not let a resolved sibling look unique.
    if (group.stream().anyMatch(JavacSourceSymbolIndex.MemberInfo::errorType)) {
      return JavacCorrelation.abstained(new JavacAbstentionEvidence(
          "error_type_member", identity,
          errorCodesForErrorMembers(index, ownerType, indexName, kind, arity)));
    }
    if (group.size() == 1) {
      return JavacCorrelation.correlated(group.get(0));
    }
    if (group.isEmpty()) {
      return JavacCorrelation.abstained(new JavacAbstentionEvidence(
          "member_absent", identity,
          errorCodesFor(index, ownerType)));
    }
    return new JavacOverloadBijection(scanner).correlate(
        file, declaration, declarations, group, indexName, identity);
  }

  /**
   * The member-scoped javac error codes for the error-typed members of
   * one name+arity group (#76): each member is joined against its OWN
   * declaring file and line span. The union is computed only when the
   * owner name resolves to exactly one index type — with same-name
   * types across files, javac errors cannot be attributed, and the
   * ambiguity is already the decline's story (the #62 unique-owner
   * guard, preserved verbatim).
   */
  private static List<String> errorCodesForErrorMembers(
      JavacSourceSymbolIndex index, String ownerType, String indexName,
      String kind, int arity) {
    List<JavacSourceSymbolIndex.TypeInfo> entries = index.type(ownerType);
    if (entries.size() != 1) {
      return List.of();
    }
    JavacSourceSymbolIndex.TypeInfo owner = entries.get(0);
    LinkedHashSet<String> codes = new LinkedHashSet<>();
    for (JavacSourceSymbolIndex.MemberInfo member : owner.members()) {
      if (member.kind().equals(kind)
          && member.parameterTypes().size() == arity
          && member.errorType()) {
        codes.addAll(index.errorCodesForMember(owner.file(), member));
      }
    }
    return List.copyOf(codes);
  }

  /**
   * The javac error codes recorded for the owner's declaring files.
   * Joined only when the owner name resolves to exactly one index type:
   * with same-name types across files, javac errors cannot be
   * attributed to one member's file, and the ambiguity is already the
   * decline's story (display-only honesty).
   */
  private static List<String> errorCodesFor(
      JavacSourceSymbolIndex index, String ownerType) {
    List<JavacSourceSymbolIndex.TypeInfo> entries = index.type(ownerType);
    if (entries.size() != 1) {
      return List.of();
    }
    return List.copyOf(index.errorCodesFor(entries.get(0).file()));
  }

  /** The primitive spellings the textual vector resolution admits directly. */
  static final Set<String> PRIMITIVE_TYPES = Set.of(
      "boolean", "byte", "char", "short", "int", "long", "float", "double", "void");

  /** The index-rendered producer correlation, matching the producer method by its own parameter text. */
  JavacCorrelation producerCorrelation(
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
        return correlatedMethod(
            candidate.file(),
            scanner.ownerTypeFor(candidate.file(), method),
            method);
      }
    }
    return JavacCorrelation.empty();
  }
}
