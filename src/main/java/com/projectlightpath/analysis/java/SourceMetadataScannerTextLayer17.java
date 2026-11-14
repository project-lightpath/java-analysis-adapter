package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerTextLayer17 extends SourceMetadataScannerTextLayer16 {

  SourceMetadataScannerTextLayer17(AnalysisContext context) {
    super(context);
  }



  /** Classifies a source bean using the bounded framework stereotype subset. */
static String beanKind(
      SourceFile file, String block, AnalysisContext.Framework framework) {
    if (framework == AnalysisContext.Framework.QUARKUS) {
      for (String annotation : List.of(
          "ApplicationScoped", "RequestScoped", "SessionScoped", "ConversationScoped",
          "Dependent", "Singleton", "Named", "Stereotype")) {
        if (hasAnnotation(block, annotation)) {
          return annotation;
        }
      }
    }
    for (String annotation : List.of(
        "Service", "Component", "Repository", "Configuration", "Controller", "RestController", "Named")) {
      if (canonicalSpringAnnotation(file, block, annotation)) {
        return annotation;
      }
    }
    return "repository_interface";
  }

  /** Returns source annotation spellings that establish one candidate's registration. */
  List<String> sourceAnnotations(BeanCandidate candidate, AnalysisContext.Framework framework) {
    if (candidate.producer()) {
      String producerParameters = candidate.producerParameters() == null
          ? ""
          : candidate.producerParameters().replaceAll("\\s+", " ").trim();
      String block = methods(candidate.file()).stream()
          .filter(method -> method.name().equals(candidate.producerMethod())
              && method.parameters().replaceAll("\\s+", " ").trim().equals(producerParameters))
          .map(MethodInfo::annotationBlock)
          .findFirst()
          .orElse("");
      return framework == AnalysisContext.Framework.QUARKUS
          ? annotationSources(block, List.of("Produces"))
          : canonicalAnnotationSources(candidate.file(), block, List.of("Bean"));
    }
    List<String> names = framework == AnalysisContext.Framework.QUARKUS
        ? List.of("ApplicationScoped", "RequestScoped", "SessionScoped", "ConversationScoped",
            "Dependent", "Singleton", "Named", "Stereotype")
        : List.of("Service", "Component", "Repository", "Configuration", "Controller",
            "RestController", "Named");
    return framework == AnalysisContext.Framework.QUARKUS
        ? annotationSources(candidate.file().classAnnotationBlock(), names)
        : canonicalAnnotationSources(candidate.file(), candidate.file().classAnnotationBlock(), names);
  }

  /** Returns source advisor or scope annotations that establish one proxy target. */
  List<String> sourceAnnotations(ProxyTarget target, AnalysisContext.Framework framework) {
    List<String> names = framework == AnalysisContext.Framework.QUARKUS
        ? List.of("ApplicationScoped", "RequestScoped", "SessionScoped", "ConversationScoped",
            "Dependent", "Singleton", "Named", "Stereotype")
        : List.of("Transactional", "Async", "Cacheable", "CachePut", "CacheEvict",
            "PreAuthorize", "PostAuthorize", "Secured", "RolesAllowed", "Retryable",
            "CircuitBreaker", "Validated", "TransactionalEventListener");
    List<String> result = framework == AnalysisContext.Framework.QUARKUS
        ? new ArrayList<>(annotationSources(target.file().classAnnotationBlock(), names))
        : new ArrayList<>(canonicalAnnotationSources(
            target.file(), target.file().classAnnotationBlock(), names));
    if (framework == AnalysisContext.Framework.QUARKUS) {
      result.addAll(annotationSources(target.method().annotationBlock(), names));
    } else {
      result.addAll(canonicalAnnotationSources(
          target.file(), target.method().annotationBlock(), names));
    }
    return List.copyOf(new LinkedHashSet<>(result));
  }

static List<String> values(String block, String annotation, String attribute) {
    return values(annotationArguments(block, annotation), attribute);
  }

  /** Extracts values only from canonically resolved Spring route annotations. */
static List<String> values(
      SourceFile file, String block, String annotation, String attribute) {
    return values(annotationArguments(file, block, annotation), attribute);
  }



  /** Returns fully qualified source owners, including nested declarations. */
  Set<String> declaredTypeNames() {
    if (declaredTypeNames != null) {
      return declaredTypeNames;
    }
    Set<String> result = new LinkedHashSet<>();
    for (SourceFile file : files()) {
      for (TypeInfo type : typeDeclarations(file)) {
        result.add(typeContaining(file, type.start()));
      }
    }
    declaredTypeNames = Set.copyOf(result);
    return declaredTypeNames;
  }

  /** Returns whether a source class declares an interface implementation contract. */
  boolean implementsInterface(String typeName) {
    for (SourceFile file : files()) {
      for (TypeInfo type : typeDeclarations(file)) {
        if (!typeContaining(file, type.start()).equals(typeName) || !type.kind().equals("class")) {
          continue;
        }
        String source = maskStringLiterals(file.structuralSource());
        int opening = typeBodyOpening(source, type.start());
        if (opening >= 0 && Pattern.compile("\\bimplements\\b")
            .matcher(source.substring(type.start(), opening)).find()) {
          return true;
        }
      }
    }
    return false;
  }

  Optional<TypeInfo> declaredType(String typeName) {
    return sourceFile(typeName).flatMap(file -> typeDeclarations(file).stream()
        .filter(type -> typeContaining(file, type.start()).equals(typeName))
        .findFirst());
  }

static boolean isInterfaceType(SourceFile file, String owner) {
    return typeDeclarations(file).stream()
        .filter(type -> typeContaining(file, type.start()).equals(owner))
        .anyMatch(type -> type.kind().equals("interface"));
  }

  TypeReference findTypeReference(String owner) {
    for (SourceFile candidate : files()) {
      for (TypeInfo type : typeDeclarations(candidate)) {
        if (typeContaining(candidate, type.start()).equals(owner)) {
          return new TypeReference(candidate, type);
        }
      }
    }
    return null;
  }

  List<String> allDeclaredTypeNames() {
    return files().stream()
        .flatMap(candidate -> typeDeclarations(candidate).stream()
            .map(type -> typeContaining(candidate, type.start())))
        .distinct()
        .toList();
  }

  List<String> nestedDeclaredTypeCandidates(
      String normalized, SourceFile sourceFile, int siteOffset, List<String> declared) {
    String owner = typeContaining(sourceFile, siteOffset);
    List<String> enclosing = enclosingDeclaredTypes(owner, normalized, declared);
    if (!enclosing.isEmpty()) {
      return List.copyOf(new LinkedHashSet<>(enclosing));
    }
    return declared.stream()
        .filter(candidate -> candidate.startsWith(sourceFile.packageName() + ".")
            || sourceFile.packageName().isBlank() && !candidate.contains("."))
        .filter(candidate -> !candidate.contains("$"))
        .filter(candidate -> simpleName(candidate).equals(normalized))
        .toList();
  }

  List<String> lexicalOwnerNames(SourceFile sourceFile, int siteOffset) {
    List<TypeInfo> containing = typeDeclarations(sourceFile).stream()
        .filter(type -> type.start() <= siteOffset && type.end() >= siteOffset)
        .sorted(Comparator.comparingInt(TypeInfo::start))
        .toList();
    List<String> result = new ArrayList<>();
    for (int index = containing.size() - 1; index >= 0; index--) {
      String owner = typeContaining(sourceFile, containing.get(index).start());
      if (!owner.equals("unknown") && !result.contains(owner)) {
        result.add(owner);
      }
    }
    return result;
  }

  List<String> directMemberTypeCandidates(String owner, String simpleName) {
    String candidate = owner + "$" + simpleName;
    return files().stream()
        .flatMap(file -> typeDeclarations(file).stream()
            .map(type -> typeContaining(file, type.start())))
        .anyMatch(candidate::equals)
        ? List.of(candidate)
        : List.of();
  }

  List<String> samePackageTypeCandidates(String requestedName, SourceFile sourceFile) {
    return files().stream()
        .flatMap(file -> typeDeclarations(file).stream()
            .map(type -> typeContaining(file, type.start())))
        .distinct()
        .filter(candidate -> !candidate.contains("$"))
        .filter(candidate -> sourceFile.packageName().isBlank()
            ? !candidate.contains(".")
            : candidate.startsWith(sourceFile.packageName() + "."))
        .filter(candidate -> simpleName(candidate).equals(requestedName))
        .toList();
  }
}
