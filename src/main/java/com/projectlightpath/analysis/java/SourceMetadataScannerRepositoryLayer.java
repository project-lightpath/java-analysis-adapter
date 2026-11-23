package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Responsibility layer for Spring Data repository composition metadata:
 * fragment interfaces, their backing Impl classes, and guaranteed-method
 * redeclaration evidence.
 */
abstract class SourceMetadataScannerRepositoryLayer
    extends SourceMetadataScannerConfigurationLayer {

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
    Map<String, RepositoryMethod> result = new java.util.LinkedHashMap<>();
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
    if (queryAnnotation(method)) {
      // An @Query redeclaration reroutes execution; it stays a declared
      // method so the delegation ladder can treat it as override evidence.
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
   * Returns the guaranteed method names whose contract default is overridden
   * by a source declaration: exact signature-shape redeclarations, and any
   * same-name declaration carrying a query annotation.
   */
  Set<String> redeclaredGuaranteedNames(SourceFile file) {
    Set<String> guaranteedNames = springRepositoryMethods(file).stream()
        .filter(RepositoryMethod::guaranteed)
        .map(RepositoryMethod::name)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    Set<String> result = new LinkedHashSet<>();
    for (MethodInfo method : methods(file)) {
      if (!typeContaining(file, declarationStart(file, method.start())).equals(file.typeName())
          || !abstractInstanceRepositoryMethod(method)
          || !guaranteedNames.contains(method.name())) {
        continue;
      }
      List<Parameter> methodParameters = parameters(method.parameters());
      boolean exactShape = methodParameters.size() == 1
          && springRepositoryEntityType(file).isPresent()
          && (repositoryTypeToken(methodParameters.get(0).type(), file)
                  .map(type -> type.equals(springRepositoryEntityType(file).orElse("\u0000"))
                      || type.equals(springRepositoryIdType(file).orElse("\u0000")))
                  .orElse(false));
      if (exactShape || queryAnnotation(method)) {
        result.add(method.name());
      }
    }
    return result;
  }

  private static boolean queryAnnotation(MethodInfo method) {
    return Pattern.compile("@\\w*\\.?Query\\b").matcher(method.annotationBlock()).find();
  }

  /** Returns the repository fragment compositions visible in application source. */
  List<RepositoryFragment> springRepositoryFragments() {
    List<RepositoryFragment> result = new ArrayList<>();
    for (SourceFile file : files()) {
      if (springRepositoryContracts(file).isEmpty()) {
        continue;
      }
      for (String fragmentType : extendedFragmentInterfaces(file)) {
        SourceFile fragmentFile = files().stream()
            .filter(candidate -> candidate.typeName().equals(fragmentType))
            .findFirst()
            .orElseThrow();
        SourceFile implementation = fragmentImplementation(fragmentFile);
        if (implementation == null) {
          continue;
        }
        List<RepositoryMethod> fragmentMethods = methods(fragmentFile).stream()
            .filter(method -> typeContaining(
                fragmentFile, declarationStart(fragmentFile, method.start()))
                .equals(fragmentFile.typeName()))
            .filter(method -> abstractInstanceRepositoryMethod(method))
            .map(method -> new RepositoryMethod(
                method.name(), repositoryMethodSignature(method), false))
            .toList();
        result.add(new RepositoryFragment(
            file.typeName(), fragmentType, implementation.typeName(), fragmentMethods));
      }
    }
    return List.copyOf(result);
  }

  /** Returns the Impl class that actually implements one fragment interface. */
  private SourceFile fragmentImplementation(SourceFile fragmentFile) {
    String implementationName = fragmentFile.simpleTypeName() + "Impl";
    return files().stream()
        .filter(candidate -> candidate.simpleTypeName().equals(implementationName))
        .filter(this::isConcreteClass)
        .filter(candidate -> implementsFragment(candidate, fragmentFile))
        .findFirst()
        .orElse(null);
  }

  /** Checks that one concrete class names the fragment interface in its implements clause. */
  private boolean implementsFragment(SourceFile candidate, SourceFile fragmentFile) {
    String header = maskStringLiterals(typeHeader(candidate));
    int implementsIndex = topLevelKeywordIndex(header, "implements");
    if (implementsIndex < 0) {
      return false;
    }
    int clauseStart = implementsIndex + "implements".length();
    int openBrace = header.indexOf('{', clauseStart);
    String clause = header.substring(clauseStart, openBrace < 0 ? header.length() : openBrace);
    for (String type : typeNames(clause)) {
      Optional<String> resolved = resolveReferenceType(type, candidate);
      if (resolved.isPresent() && resolved.get().equals(fragmentFile.typeName())) {
        return true;
      }
    }
    return false;
  }

  /** Returns app-declared interfaces extended by one repository, excluding Spring contracts. */
  private List<String> extendedFragmentInterfaces(SourceFile file) {
    String header = maskStringLiterals(typeHeader(file));
    int extendsIndex = topLevelKeywordIndex(header, "extends");
    if (extendsIndex < 0) {
      return List.of();
    }
    int permitsIndex = topLevelKeywordIndex(header, "permits");
    String extended = header.substring(
        extendsIndex + "extends".length(), permitsIndex >= 0 ? permitsIndex : header.length());
    List<String> result = new ArrayList<>();
    for (String type : typeNames(extended)) {
      if (resolveKnownFrameworkType(type, file, SPRING_DATA_REPOSITORY_CONTRACTS.keySet())
          .isPresent()) {
        continue;
      }
      Optional<String> resolved = type.contains(".")
          ? Optional.of(type)
          : resolveReferenceType(type, file);
      boolean isAppInterface = resolved
          .map(qualified -> files().stream()
              .filter(candidate -> candidate.typeName().equals(qualified))
              .anyMatch(this::isInterfaceFile))
          .orElse(false);
      if (isAppInterface) {
        result.add(resolved.orElseThrow());
      }
    }
    return result;
  }

  private boolean isInterfaceFile(SourceFile file) {
    return maskStringLiterals(typeHeader(file)).matches("(?s).*\\binterface\\s+\\w+\\b.*");
  }

  private boolean isConcreteClass(SourceFile file) {
    String header = maskStringLiterals(typeHeader(file));
    return !isInterfaceFile(file) && header.matches("(?s).*\\bclass\\s+\\w+\\b.*")
        && !header.matches("(?s).*\\babstract\\s+class\\s+\\w+\\b.*");
  }
}
