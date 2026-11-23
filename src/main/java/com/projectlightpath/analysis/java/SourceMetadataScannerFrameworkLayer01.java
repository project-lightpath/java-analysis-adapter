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
    return springRepositoryTypeArgument(file, 1);
  }

  /** Resolves one type argument of the first supported Spring Data contract declaration. */
  Optional<String> springRepositoryTypeArgument(SourceFile file, int argumentIndex) {
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
      if (parameters.size() > argumentIndex) {
        Optional<String> resolved = repositoryTypeToken(parameters.get(argumentIndex), file);
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
}
