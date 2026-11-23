package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerFrameworkLayer01 extends SourceMetadataScannerFrameworkLayer00 {

  SourceMetadataScannerFrameworkLayer01(AnalysisContext context) {
    super(context);
  }



  /** Resolves the ID type from the first supported Spring Data contract declaration. */
  Optional<String> springRepositoryIdType(SourceFile file) {
    String header = maskStringLiterals(typeHeader(file));
    int extendsIndex = topLevelKeywordIndex(header, "extends");
    if (extendsIndex < 0) {
      return Optional.empty();
    }
    int permitsIndex = topLevelKeywordIndex(header, "permits");
    String extended = header.substring(
        extendsIndex + "extends".length(), permitsIndex >= 0 ? permitsIndex : header.length());
    for (String type : splitArguments(extended)) {
      if (resolveKnownFrameworkType(type, file, SPRING_DATA_REPOSITORY_CONTRACTS.keySet()).isEmpty()) {
        continue;
      }
      String arguments = genericArguments(type);
      List<String> parameters = splitArguments(arguments);
      if (parameters.size() >= 2) {
        Optional<String> resolved = repositoryTypeToken(parameters.get(1), file);
        if (resolved.isPresent()) {
          return resolved;
        }
      }
    }
    return Optional.empty();
  }

  /** Returns source types registered by canonical Spring component bean methods. */
  Set<String> registeredSpringBeanTypes() {
    Set<String> result = new LinkedHashSet<>();
    for (SourceFile file : files()) {
      for (MethodInfo method : methods(file)) {
        if (!springBeanAnnotation(file, method.annotationBlock()) || method.returnType().isBlank()) {
          continue;
        }
        String declaringType = typeContaining(file, declarationStart(file, method.start()));
        if (!springComponent(file, declaringType)) {
          continue;
        }
        resolveReferenceType(
            method.returnType(), file, declarationStart(file, method.start()))
            .filter(type -> !type.isBlank())
            .ifPresent(result::add);
      }
    }
    return Set.copyOf(result);
  }

  /** Returns whether a source type header implements the named interface. */
  boolean implementsNamedInterface(
      SourceFile file, String typeName, String interfaceName) {
    String header = maskStringLiterals(typeHeader(file, typeName));
    int implementsIndex = keywordIndex(header, "implements");
    if (implementsIndex < 0) {
      return false;
    }
    int end = keywordIndexAfter(header, "permits", implementsIndex + "implements".length());
    String implemented = header.substring(
        implementsIndex + "implements".length(), end >= 0 ? end : header.length());
    return typeNames(implemented).stream()
        .map(type -> resolveKnownFrameworkType(type, file, Set.of(interfaceName)))
        .flatMap(Optional::stream)
        .anyMatch(interfaceName::equals);
  }



  /** Finds bounded Spring Data repository interfaces, including annotated repositories. */
  Optional<String> springRepositoryContract(SourceFile file) {
    return springRepositoryContracts(file).stream().findFirst();
  }

  /** Detects a source declaration that exactly redeclares the repository ID contract. */
  boolean redeclaresInheritedFindById(SourceFile file, MethodInfo method) {
    if (!method.name().equals("findById")) {
      return false;
    }
    Optional<String> repositoryIdType = springRepositoryIdType(file);
    List<Parameter> methodParameters = parameters(method.parameters());
    if (repositoryIdType.isEmpty() || methodParameters.size() != 1) {
      return false;
    }
    return repositoryTypeToken(methodParameters.get(0).type(), file)
        .filter(repositoryIdType.get()::equals)
        .isPresent();
  }

  /** Scans Spring @Bean and bounded CommandLineRunner lifecycle callbacks. */
  List<LifecycleMethod> springLifecycleMethods() {
    List<LifecycleMethod> result = new ArrayList<>();
    Set<String> registeredBeanTypes = registeredSpringBeanTypes();
    for (SourceFile file : files()) {
      for (MethodInfo method : methods(file)) {
        String declaringType = typeContaining(file, declarationStart(file, method.start()));
        boolean springBean = springComponent(file, declaringType);
        boolean commandLineRunner = (springBean || registeredBeanTypes.contains(declaringType))
            && implementsNamedInterface(file, declaringType, SPRING_COMMAND_LINE_RUNNER);
        if (springBeanAnnotation(file, method.annotationBlock())) {
          if (!springBean) {
            continue;
          }
          result.add(new LifecycleMethod(
              file,
              method,
              declaringType,
              "bean",
              lifecycleAnnotationSymbols(file, method, declaringType)));
        }
        if (commandLineRunner
            && method.name().equals("run")
            && commandLineRunnerParameters(method.parameters())) {
          result.add(new LifecycleMethod(
              file,
              method,
              declaringType,
              "runner",
              lifecycleAnnotationSymbols(file, method, declaringType)));
        }
      }
    }
    return List.copyOf(result);
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
  private boolean redeclaresGuaranteedContractMethod(
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

  /** Resolves the entity type from the first supported Spring Data contract declaration. */
  Optional<String> springRepositoryEntityType(SourceFile file) {
    String header = maskStringLiterals(typeHeader(file));
    int extendsIndex = topLevelKeywordIndex(header, "extends");
    if (extendsIndex < 0) {
      return Optional.empty();
    }
    int permitsIndex = topLevelKeywordIndex(header, "permits");
    String extended = header.substring(
        extendsIndex + "extends".length(), permitsIndex >= 0 ? permitsIndex : header.length());
    for (String type : splitArguments(extended)) {
      if (resolveKnownFrameworkType(type, file, SPRING_DATA_REPOSITORY_CONTRACTS.keySet()).isEmpty()) {
        continue;
      }
      String arguments = genericArguments(type);
      List<String> parameters = splitArguments(arguments);
      if (parameters.size() >= 2) {
        Optional<String> resolved = repositoryTypeToken(parameters.get(0), file);
        if (resolved.isPresent()) {
          return resolved;
        }
      }
    }
    return Optional.empty();
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
            .filter(candidate -> isConcreteClass(candidate))
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
            .map(MethodInfo::name)
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
