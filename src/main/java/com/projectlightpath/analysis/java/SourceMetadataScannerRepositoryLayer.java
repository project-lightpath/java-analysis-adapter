package com.projectlightpath.analysis.java;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Responsibility layer for Spring Data repository methods: the guaranteed
 * contract table application, declaration suppression, and query-override
 * evidence.
 */
abstract class SourceMetadataScannerRepositoryLayer
    extends SourceMetadataScannerFragmentLayer {

  SourceMetadataScannerRepositoryLayer(AnalysisContext context) {
    super(context);
  }

  /** Resolves the entity type from the first supported Spring Data contract declaration. */
  Optional<String> springRepositoryEntityType(SourceFile file) {
    return springRepositoryTypeArgument(file, 0);
  }

  /** Returns repository methods guaranteed by the contract or declared by the source interface. */
  List<RepositoryMethod> springRepositoryMethods(SourceFile file) {
    List<String> contracts = springRepositoryContracts(file);
    if (contracts.isEmpty()) {
      return List.of();
    }
    Map<String, RepositoryMethod> result = new LinkedHashMap<>();
    for (String contract : contracts) {
      for (String name : guaranteedContractMethods(contract)) {
        result.putIfAbsent(name, new RepositoryMethod(name, name, true));
      }
    }
    for (MethodInfo method : methods(file)) {
      if (typeContaining(file, declarationStart(file, method.start())).equals(file.typeName())
          && abstractInstanceRepositoryMethod(method)
          && !redeclaresGuaranteedContractMethod(file, method, result)) {
        RepositoryMethod repositoryMethod = new RepositoryMethod(
            method.name(), repositoryMethodSignature(method), false);
        result.putIfAbsent(repositoryMethod.identity(), repositoryMethod);
      }
    }
    return List.copyOf(result.values());
  }

  /** Detects an exact source redeclaration of a guaranteed contract method. */
  boolean redeclaresGuaranteedContractMethod(
      SourceFile file, MethodInfo method, Map<String, RepositoryMethod> guaranteed) {
    RepositoryMethod inherited = guaranteed.get(method.name());
    if (inherited == null || !inherited.guaranteed()) {
      return false;
    }
    if (queryAnnotated(file, method)) {
      // An @Query redeclaration reroutes execution; it stays a declared
      // method so the delegation ladder can treat it as a query override.
      return false;
    }
    List<Parameter> methodParameters = parameters(method.parameters());
    if (methodParameters.size() != 1 || !springRepositoryEntityType(file).isPresent()) {
      return false;
    }
    String parameterType = repositoryTypeToken(methodParameters.get(0).type(), file).orElse("");
    return parameterType.equals(springRepositoryEntityType(file).orElse("\u0000"))
        || parameterType.equals(springRepositoryIdType(file).orElse("\u0000"));
  }

  /**
   * Returns the guaranteed method names whose contract slot is rerouted by a
   * same-name query-annotated declaration that redeclares the slot's
   * parameter shape. A same-name overload with a different shape does not
   * replace the slot, and a plain redeclaration does not reroute the proxy
   * backing at all.
   */
  Set<String> queryOverriddenGuaranteedNames(SourceFile file) {
    Set<String> guaranteedNames = springRepositoryMethods(file).stream()
        .filter(RepositoryMethod::guaranteed)
        .map(RepositoryMethod::name)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    Set<String> result = new LinkedHashSet<>();
    for (MethodInfo method : methods(file)) {
      if (!typeContaining(file, declarationStart(file, method.start())).equals(file.typeName())
          || !abstractInstanceRepositoryMethod(method)
          || !guaranteedNames.contains(method.name())
          || !queryAnnotated(file, method)) {
        continue;
      }
      int parameterCount = parameters(method.parameters()).size();
      if (SourceMetadataScannerState
          .springDataContractMethodArities(method.name()).contains(parameterCount)) {
        result.add(method.name());
      }
    }
    return result;
  }

  /**
   * Returns the source signatures of declared repository methods carrying
   * the canonical Spring Data query annotation, keyed for identity lookup.
   */
  Set<String> queryAnnotatedMethodIdentities(SourceFile file) {
    Set<String> result = new LinkedHashSet<>();
    for (MethodInfo method : methods(file)) {
      if (!typeContaining(file, declarationStart(file, method.start())).equals(file.typeName())
          || !abstractInstanceRepositoryMethod(method)
          || !queryAnnotated(file, method)) {
        continue;
      }
      result.add(repositoryMethodSignature(method));
    }
    return result;
  }

  /** Matches only the canonical Spring Data query annotation, not lookalikes. */
  private static boolean queryAnnotated(SourceFile file, MethodInfo method) {
    return !canonicalAnnotationOccurrences(
        file, method.annotationBlock(), "Query",
        SourceMetadataScannerState.SPRING_DATA_QUERY_ANNOTATIONS).isEmpty();
  }
}
