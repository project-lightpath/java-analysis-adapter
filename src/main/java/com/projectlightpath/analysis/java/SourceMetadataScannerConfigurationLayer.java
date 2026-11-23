package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * Responsibility layer for the Spring Data repository configuration:
 * {@code @EnableJpaRepositories} base classes and unresolvable extends
 * references, with analysis-scope resolution across application source and
 * the dependency classpath.
 */
abstract class SourceMetadataScannerConfigurationLayer
    extends SourceMetadataScannerFrameworkLayer01 {

  SourceMetadataScannerConfigurationLayer(AnalysisContext context) {
    super(context);
  }

  /**
   * Returns every configured repository base class, from all
   * {@code @EnableJpaRepositories} occurrences in application source.
   */
  List<SourceMetadataScannerState.RepositoryBaseClass> springRepositoryBaseClasses() {
    Pattern attribute = Pattern.compile(
        "repositoryBaseClass\\s*=\\s*([\\w.]+)\\.class");
    List<SourceMetadataScannerState.RepositoryBaseClass> result = new ArrayList<>();
    for (SourceFile file : files()) {
      String block = file.classAnnotationBlock();
      for (AnnotationOccurrence occurrence : canonicalAnnotationOccurrences(
          file, block, "EnableJpaRepositories",
          SourceMetadataScannerState.SPRING_DATA_REPOSITORY_CONFIGURATION_ANNOTATIONS)) {
        Matcher matcher = attribute.matcher(occurrence.source());
        if (matcher.find()) {
          String configured = matcher.group(1);
          Optional<String> resolved = configured.contains(".")
              ? Optional.of(configured)
              : resolveReferenceType(configured, file);
          String qualified = resolved
              .orElseGet(() -> qualify(configured, file.packageName()));
          result.add(new SourceMetadataScannerState.RepositoryBaseClass(
              qualified, resolved.isPresent() && typeInAnalysisScope(qualified),
              resolved.isPresent(), file.displayPath(), configured));
        }
      }
    }
    return List.copyOf(result);
  }

  /**
   * Returns repository extends-clause references that resolve to no type at
   * all — neither a modeled Spring contract, an app interface, nor an
   * import-resolvable dependency type (explicit import, on-demand import
   * package, or the file's own package, each probed against the dependency
   * classpath). Each is (path, spelling).
   */
  List<Map.Entry<String, String>> unresolvedRepositoryExtensions() {
    List<Map.Entry<String, String>> result = new ArrayList<>();
    for (SourceFile file : files()) {
      if (springRepositoryContracts(file).isEmpty()) {
        continue;
      }
      String header = maskStringLiterals(typeHeader(file));
      int extendsIndex = topLevelKeywordIndex(header, "extends");
      if (extendsIndex < 0) {
        continue;
      }
      int permitsIndex = topLevelKeywordIndex(header, "permits");
      String extended = header.substring(
          extendsIndex + "extends".length(),
          permitsIndex >= 0 ? permitsIndex : header.length());
      for (String type : typeNames(extended)) {
        if (resolveKnownFrameworkType(
                type, file, SourceMetadataScannerState.SPRING_DATA_REPOSITORY_CONTRACTS.keySet())
            .isPresent()) {
          continue;
        }
        if (type.contains(".") || resolveReferenceType(type, file).isPresent()) {
          continue;
        }
        if (resolvableOnDependencyClasspath(type, file)) {
          continue;
        }
        result.add(Map.entry(file.displayPath(), type));
      }
    }
    return List.copyOf(result);
  }

  /**
   * Probes the dependency classpath for one simple type name under the
   * file's on-demand import packages and its own package, so dependency
   * fragments referenced without a single-type import do not raise false
   * unresolved diagnostics.
   */
  private boolean resolvableOnDependencyClasspath(String simpleType, SourceFile file) {
    Set<String> packages = new java.util.LinkedHashSet<>();
    packages.add(file.packageName());
    Matcher onDemand = Pattern.compile("import\\s+([\\w.]+)\\.\\*\\s*;").matcher(
        maskStringLiterals(file.structuralSource()));
    while (onDemand.find()) {
      packages.add(onDemand.group(1));
    }
    for (String packageName : packages) {
      if (packageName.isBlank()) {
        continue;
      }
      String relative = packageName.replace('.', '/') + "/" + simpleType + ".class";
      for (Path entry : context.dependencyClasspath()) {
        if (dependencyContains(entry, relative)) {
          return true;
        }
      }
    }
    return false;
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
    if (!entry.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
      return false;
    }
    try (ZipFile archive = new ZipFile(entry.toFile())) {
      return archive.getEntry(relative) != null;
    } catch (IOException error) {
      return false;
    }
  }
}
