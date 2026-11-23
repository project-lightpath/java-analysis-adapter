package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Responsibility layer for Spring Data repository composition metadata:
 * fragment interfaces, their backing Impl classes, and the configured
 * repository base classes.
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
    int extendsIndex = topLevelKeywordIndex(header, "extends");
    int clauseStart = implementsIndex + "implements".length();
    int openBrace = header.indexOf('{', clauseStart);
    String clause = header.substring(clauseStart, openBrace < 0 ? header.length() : openBrace);
    for (String type : typeNames(clause)) {
      Optional<String> resolved = resolveReferenceType(type, candidate);
      if (resolved.isPresent() && resolved.get().equals(fragmentFile.typeName())) {
        return true;
      }
    }
    return extendsIndex < 0 || implementsIndex < extendsIndex;
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

  /**
   * Returns every configured repository base class, from all
   * {@code @EnableJpaRepositories} occurrences in application source.
   */
  List<RepositoryBaseClass> springRepositoryBaseClasses() {
    Pattern attribute = Pattern.compile(
        "repositoryBaseClass\\s*=\\s*([\\w.]+)\\.class");
    List<RepositoryBaseClass> result = new ArrayList<>();
    for (SourceFile file : files()) {
      String block = file.classAnnotationBlock();
      for (AnnotationOccurrence occurrence : canonicalAnnotationOccurrences(
          file, block, "EnableJpaRepositories",
          SPRING_DATA_REPOSITORY_CONFIGURATION_ANNOTATIONS)) {
        Matcher matcher = attribute.matcher(occurrence.source());
        if (matcher.find()) {
          String configured = matcher.group(1);
          Optional<String> resolved = configured.contains(".")
              ? Optional.of(configured)
              : resolveReferenceType(configured, file);
          String qualified = resolved.orElseGet(() -> qualify(configured, file.packageName()));
          result.add(new RepositoryBaseClass(
              qualified, typeInAnalysisScope(qualified), file.displayPath()));
        }
      }
    }
    return List.copyOf(result);
  }

  /**
   * Checks whether one type is application source, or resolvable on the
   * analysis classpath (the spring-data jars carry the default backing
   * classes, and a dependency-provided base class is a bounded candidate).
   */
  private boolean typeInAnalysisScope(String qualifiedType) {
    if (files().stream().anyMatch(candidate -> candidate.typeName().equals(qualifiedType))) {
      return true;
    }
    String relative = qualifiedType.replace('.', '/') + ".class";
    for (Path entry : context.dependencyClasspath()) {
      if (dependencyContains(entry, relative)) {
        return true;
      }
    }
    return false;
  }

  private static boolean dependencyContains(Path entry, String relative) {
    if (!Files.exists(entry)) {
      return false;
    }
    if (Files.isDirectory(entry)) {
      return Files.isRegularFile(entry.resolve(relative));
    }
    if (!entry.toString().endsWith(".jar")) {
      return false;
    }
    try (ZipFile archive = new ZipFile(entry.toFile())) {
      return archive.getEntry(relative) != null;
    } catch (IOException error) {
      return false;
    }
  }
}
