package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Responsibility layer for Spring Data repository fragment composition:
 * app-declared fragment interfaces, their conventional Impl classes, and
 * the confirmation of the implements relationship.
 */
abstract class SourceMetadataScannerFragmentLayer
    extends SourceMetadataScannerConfigurationLayer {

  SourceMetadataScannerFragmentLayer(AnalysisContext context) {
    super(context);
  }

  /** Returns the repository fragment compositions visible in application source. */
  List<SourceMetadataScannerState.RepositoryFragment> springRepositoryFragments() {
    List<SourceMetadataScannerState.RepositoryFragment> result = new ArrayList<>();
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
        List<SourceMetadataScannerState.RepositoryMethod> fragmentMethods =
            methods(fragmentFile).stream()
                .filter(method -> typeContaining(
                    fragmentFile, declarationStart(fragmentFile, method.start()))
                    .equals(fragmentFile.typeName()))
                .filter(method -> abstractInstanceRepositoryMethod(method))
                .map(method -> new SourceMetadataScannerState.RepositoryMethod(
                    method.name(), repositoryMethodSignature(method), false))
                .toList();
        result.add(new SourceMetadataScannerState.RepositoryFragment(
            file.typeName(), fragmentType, implementation.typeName(), fragmentMethods));
      }
    }
    return List.copyOf(result);
  }

  /**
   * Returns fragment compositions whose conventional Impl class exists but
   * cannot be confirmed to implement the fragment interface. Each is
   * (Impl display path, fragment type).
   */
  List<Map.Entry<String, String>> unconfirmedFragmentImplementations() {
    List<Map.Entry<String, String>> result = new ArrayList<>();
    for (SourceFile file : files()) {
      if (springRepositoryContracts(file).isEmpty()) {
        continue;
      }
      for (String fragmentType : extendedFragmentInterfaces(file)) {
        SourceFile fragmentFile = files().stream()
            .filter(candidate -> candidate.typeName().equals(fragmentType))
            .findFirst()
            .orElseThrow();
        SourceFile conventional = conventionalImpl(fragmentFile);
        if (conventional != null && !implementsFragment(conventional, fragmentFile)) {
          result.add(Map.entry(conventional.displayPath(), fragmentType));
        }
      }
    }
    return List.copyOf(result);
  }

  /** Returns the Impl class that actually implements one fragment interface. */
  private SourceFile fragmentImplementation(SourceFile fragmentFile) {
    SourceFile conventional = conventionalImpl(fragmentFile);
    return conventional != null && implementsFragment(conventional, fragmentFile)
        ? conventional
        : null;
  }

  /** Returns the conventional name-matching concrete Impl candidate, unconfirmed. */
  private SourceFile conventionalImpl(SourceFile fragmentFile) {
    String implementationName = fragmentFile.simpleTypeName() + "Impl";
    return files().stream()
        .filter(candidate -> candidate.simpleTypeName().equals(implementationName))
        .filter(this::isConcreteClass)
        .findFirst()
        .orElse(null);
  }

  /** Checks that one concrete class names the fragment interface in its implements clause. */
  private boolean implementsFragment(SourceFile candidate, SourceFile fragmentFile) {
    if (implementsClauseNames(candidate, fragmentFile)) {
      return true;
    }
    // One bounded level of transitivity: Spring Data's runtime wiring checks
    // assignability, so an Impl extending a class that implements the
    // fragment is a valid composition. Deeper chains remain a documented
    // false-reject residual (visible as the unconfirmed-impl diagnostic).
    Optional<String> superclass = extendedSuperclass(candidate);
    if (superclass.isPresent()) {
      SourceFile superclassFile = files().stream()
          .filter(file -> file.typeName().equals(superclass.get()))
          .findFirst()
          .orElse(null);
      if (superclassFile != null && implementsClauseNames(superclassFile, fragmentFile)) {
        return true;
      }
    }
    return false;
  }

  /** Checks one file's own implements clause for the fragment interface. */
  private boolean implementsClauseNames(SourceFile candidate, SourceFile fragmentFile) {
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

  /** Resolves the extends-clause superclass of one concrete class, one level. */
  private Optional<String> extendedSuperclass(SourceFile candidate) {
    String header = maskStringLiterals(typeHeader(candidate));
    int extendsIndex = topLevelKeywordIndex(header, "extends");
    if (extendsIndex < 0) {
      return Optional.empty();
    }
    int implementsIndex = topLevelKeywordIndex(header, "implements");
    String extended = header.substring(
        extendsIndex + "extends".length(),
        implementsIndex >= 0 ? implementsIndex : header.length());
    List<String> names = typeNames(extended);
    if (names.size() != 1) {
      return Optional.empty();
    }
    String name = names.get(0);
    return name.contains(".") ? Optional.of(name) : resolveReferenceType(name, candidate);
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
      if (resolveKnownFrameworkType(
              type, file, SourceMetadataScannerState.SPRING_DATA_REPOSITORY_CONTRACTS.keySet())
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
