package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Responsibility layer for Spring Data repository composition metadata:
 * fragment interfaces, their backing Impl classes, and the configured
 * repository base class.
 */
abstract class SourceMetadataScannerRepositoryLayer extends SourceMetadataScannerFrameworkLayer01 {

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
    List<Parameter> methodParameters = parameters(method.parameters());
    if (methodParameters.size() != 1 || !springRepositoryEntityType(file).isPresent()) {
      return false;
    }
    String parameterType = repositoryTypeToken(methodParameters.get(0).type(), file).orElse("");
    return parameterType.equals(springRepositoryEntityType(file).orElse("\u0000"))
        || parameterType.equals(springRepositoryIdType(file).orElse("\u0000"));
  }

  /** Returns the repository fragment compositions visible in application source. */
  List<RepositoryFragment> springRepositoryFragments() {
    List<RepositoryFragment> result = new ArrayList<>();
    for (SourceFile file : files()) {
      if (springRepositoryContracts(file).isEmpty()) {
        continue;
      }
      for (String fragmentType : extendedFragmentInterfaces(file)) {
        String implementationType = fragmentType + "Impl";
        SourceFile implementation = files().stream()
            .filter(candidate -> candidate.typeName().equals(implementationType))
            .filter(this::isConcreteClass)
            .findFirst()
            .orElse(null);
        if (implementation == null) {
          continue;
        }
        SourceFile fragmentFile = files().stream()
            .filter(candidate -> candidate.typeName().equals(fragmentType))
            .findFirst()
            .orElseThrow();
        List<String> methodNames = methods(fragmentFile).stream()
            .filter(method -> typeContaining(
                fragmentFile, declarationStart(fragmentFile, method.start()))
                .equals(fragmentFile.typeName()))
            .filter(method -> abstractInstanceRepositoryMethod(method))
            .map(method -> method.name())
            .distinct()
            .toList();
        result.add(new RepositoryFragment(
            file.typeName(), fragmentType, implementationType, methodNames));
      }
    }
    return List.copyOf(result);
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
      String qualified = qualify(type, file.packageName());
      boolean isAppInterface = files().stream()
          .filter(candidate -> candidate.typeName().equals(qualified))
          .anyMatch(this::isInterfaceFile);
      if (isAppInterface) {
        result.add(qualified);
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

  /** Returns the configured repository base class, when one is present. */
  Optional<RepositoryBaseClass> springRepositoryBaseClass() {
    Pattern attribute = Pattern.compile(
        "repositoryBaseClass\\s*=\\s*([\\w.]+)\\.class");
    for (SourceFile file : files()) {
      String block = file.classAnnotationBlock();
      for (AnnotationOccurrence occurrence : canonicalAnnotationOccurrences(
          file, block, "EnableJpaRepositories",
          SPRING_DATA_REPOSITORY_CONFIGURATION_ANNOTATIONS)) {
        Matcher matcher = attribute.matcher(occurrence.source());
        if (matcher.find()) {
          String configured = matcher.group(1);
          String qualified = configured.contains(".")
              ? configured
              : qualify(configured, file.packageName());
          boolean applicationScoped = files().stream()
              .anyMatch(candidate -> candidate.typeName().equals(qualified));
          return Optional.of(new RepositoryBaseClass(qualified, applicationScoped));
        }
      }
    }
    return Optional.empty();
  }
}
