package com.projectlightpath.analysis.java;

import java.util.List;
import java.util.Map;

/**
 * The javac correlation arm of the Spring Data guaranteed-contract
 * redeclaration check (#62, extracted from
 * {@link SourceMetadataScannerRepositoryLayer} per the PMD GodClass
 * measurement — behavior identical, placement documented in the GREEN
 * commit). Two reads over one pure correlation: the boolean the
 * redeclaration decision consumes, and the per-member abstention the
 * declared row's delegation fact carries — same implementation, so the
 * two cannot drift.
 */
final class JavacRepositoryContractCorrelation {
  private final SourceMetadataScanner scanner;

  JavacRepositoryContractCorrelation(SourceMetadataScanner scanner) {
    this.scanner = scanner;
  }

  /**
   * Whether the correlated member's canonical parameter-0 type is the
   * entity or id type — the javac disjunct of the redeclaration check.
   */
  boolean firstParameterMatchesContract(
      SourceMetadataScannerState.SourceFile file,
      SourceMetadataScannerState.MethodInfo method,
      String entityType,
      String idType) {
    if (!JavacSourceSymbolIndex.enabled()) {
      return false;
    }
    return JavacMemberCorrelation.of(scanner)
        .correlatedFirstParameter(file, method)
        .memberOptional()
        .filter(member -> !member.parameterTypes().isEmpty())
        .map(member -> List.of(entityType, idType)
            .contains(member.parameterTypes().get(0)))
        .orElse(false);
  }

  /**
   * The first-parameter correlation abstention for one declared method,
   * causally gated: evidence is produced ONLY when the redeclaration
   * check actually consulted the javac arm for this method (a guaranteed
   * contract slot, not @Query-rerouted, single parameter, entity type
   * present, textual mismatch) — the same preconditions the decision
   * applies before its correlated lookup.
   */
  JavacAbstentionEvidence guaranteedContractCheckAbstention(
      SourceMetadataScannerState.SourceFile file,
      SourceMetadataScannerState.MethodInfo method,
      Map<String, SourceMetadataScannerState.RepositoryMethod> guaranteed) {
    SourceMetadataScannerState.RepositoryMethod inherited = guaranteed.get(method.name());
    if (inherited == null || !inherited.guaranteed()
        || scanner.queryAnnotated(file, method)) {
      return JavacAbstentionEvidence.NONE;
    }
    List<SourceMetadataScannerState.Parameter> methodParameters =
        SourceMetadataScanner.parameters(method.parameters());
    if (methodParameters.size() != 1
        || scanner.springRepositoryEntityType(file).isEmpty()) {
      return JavacAbstentionEvidence.NONE;
    }
    String parameterType = scanner.repositoryTypeToken(
        methodParameters.get(0).type(), file).orElse("");
    String entityType = scanner.springRepositoryEntityType(file).orElse("\u0000");
    String idType = scanner.springRepositoryIdType(file).orElse("\u0000");
    if (parameterType.equals(entityType) || parameterType.equals(idType)) {
      return JavacAbstentionEvidence.NONE;
    }
    return JavacMemberCorrelation.of(scanner)
        .correlatedFirstParameter(file, method)
        .abstentionOptional()
        .orElse(JavacAbstentionEvidence.NONE);
  }
}
