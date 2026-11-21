package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerFrameworkLayer00 extends SourceMetadataScannerReflectionSupport {

  SourceMetadataScannerFrameworkLayer00(AnalysisContext context) {
    super(context);
  }



  /** Reads the Spring Boot major version carried by AnalysisContext.frameworkVersion. */
  Optional<Integer> springBootMajorVersion() {
    Matcher matcher = Pattern.compile("^\\D*(\\d+)").matcher(context.frameworkVersion());
    return matcher.find() ? Optional.of(Integer.parseInt(matcher.group(1))) : Optional.empty();
  }

  /** Resolves a repository type argument, retaining declared type variables for identity checks. */
  Optional<String> repositoryTypeToken(String type, SourceFile file) {
    String normalized = stripBalancedGenericArguments(type == null ? "" : type).trim();
    Optional<String> resolved = resolveReferenceType(normalized, file);
    if (resolved.isPresent()) {
      return resolved;
    }
    if (normalized.matches("[A-Za-z_$][\\w$]*")
        && typeVariableBounds(file, normalized, file.typeDeclarationStart()).isPresent()) {
      return Optional.of(normalized);
    }
    return Optional.empty();
  }

  /** Keeps only abstract instance methods that Spring Data can implement from an interface. */
static boolean abstractInstanceRepositoryMethod(MethodInfo method) {
    String declaration = maskAnnotationArguments(stripComments(method.annotationBlock()))
        .trim()
        .replaceFirst("^[;}\\s]+", "");
    return !Pattern.compile("(?<![\\w$])(?:default|static|private)(?![\\w$])")
            .matcher(declaration)
            .find()
        && !METHOD_BODY_STATEMENT.matcher(declaration).matches();
  }

  /** Creates a stable source-level signature without parameter names for overload identity. */
static String repositoryMethodSignature(MethodInfo method) {
    List<String> parameterTypes = parameters(method.parameters()).stream()
        .map(Parameter::type)
        .map(type -> type.replaceAll("\\s+", " ").trim())
        .toList();
    return method.name() + "(" + String.join(",", parameterTypes) + ")";
  }

  /** Resolves only known framework types, abstaining on ambiguous or unknown imports. */
  Optional<String> resolveKnownFrameworkType(
      String type, SourceFile sourceFile, Set<String> canonicalTypes) {
    String normalized = stripBalancedGenericArguments(type == null ? "" : type).trim();
    if (canonicalTypes.contains(normalized)) {
      return Optional.of(normalized);
    }
    List<String> imported = importedTypes(normalized, sourceFile);
    if (imported.size() > 1) {
      return Optional.empty();
    }
    if (imported.size() == 1 && canonicalTypes.contains(imported.get(0))) {
      return Optional.of(imported.get(0));
    }
    return resolveContractType(normalized, sourceFile).filter(canonicalTypes::contains);
  }

  /** Accepts both String... and the equivalent String[] CommandLineRunner signature. */
static boolean commandLineRunnerParameters(String source) {
    List<Parameter> parameters = parameters(source);
    if (parameters.size() != 1) {
      return false;
    }
    String type = parameters.get(0).type().replaceAll("\\s+", "");
    return type.equals("String[]") || type.equals("java.lang.String[]");
  }

  /** Preserves the exact source annotations used to establish a lifecycle candidate. */
static List<String> lifecycleAnnotationSymbols(
      SourceFile file, MethodInfo method, String declaringType) {
    List<String> result = new ArrayList<>();
    result.addAll(canonicalAnnotationSources(file, typeAnnotationBlock(file, declaringType), List.of(
        "Component", "Service", "Repository", "Configuration", "Controller",
        "RestController", "Named")));
    result.addAll(canonicalAnnotationSources(file, method.annotationBlock(), List.of("Bean")));
    return List.copyOf(new LinkedHashSet<>(result));
  }

  /** Recognizes only the canonical Spring {@code @Bean} annotation. */
static boolean springBeanAnnotation(SourceFile file, String block) {
    return canonicalAnnotation(file, block, "Bean", "org.springframework.context.annotation.Bean");
  }

  /** Scans framework proxy targets and records CDI scope eligibility. */
  List<ProxyTarget> proxyTargets(AnalysisContext.Framework framework) {
    List<ProxyTarget> result = new ArrayList<>();
    for (SourceFile file : files()) {
      String classBlock = file.classAnnotationBlock();
      boolean bean = framework == AnalysisContext.Framework.QUARKUS
          ? cdiBean(classBlock)
          : springComponent(file);
      if (!bean) {
        continue;
      }
      boolean normalScoped = CDI_NORMAL_SCOPES.stream().anyMatch(name -> hasAnnotation(classBlock, name));
      for (MethodInfo method : methods(file)) {
        boolean selected = framework == AnalysisContext.Framework.QUARKUS
            ? !method.name().equals(file.simpleTypeName())
            : SPRING_PROXY_ANNOTATIONS.stream()
                .anyMatch(name -> canonicalSpringAnnotation(file, method.annotationBlock(), name))
                || SPRING_PROXY_ANNOTATIONS.stream()
                    .anyMatch(name -> canonicalSpringAnnotation(file, classBlock, name));
        if (selected && springProxyMethod(file, method)) {
          result.add(new ProxyTarget(
              file,
              method,
              file.typeName(),
              framework.id(),
              framework != AnalysisContext.Framework.QUARKUS || normalScoped));
        }
      }
    }
    return List.copyOf(result);
  }

  /** Scans methods in generated source roots. */
  List<GeneratedMethod> generatedMethods() {
    List<GeneratedMethod> result = new ArrayList<>();
    for (SourceFile file : files()) {
      if (!file.generated()) {
        continue;
      }
      for (MethodInfo method : methods(file)) {
        result.add(new GeneratedMethod(file, method));
      }
    }
    return List.copyOf(result);
  }

  /** Returns all directly inherited supported Spring Data repository contracts. */
  List<String> springRepositoryContracts(SourceFile file) {
    String header = maskStringLiterals(typeHeader(file));
    if (!header.matches("(?s).*\\binterface\\s+\\w+\\b.*")) {
      return List.of();
    }
    int extendsIndex = topLevelKeywordIndex(header, "extends");
    if (extendsIndex < 0) {
      return List.of();
    }
    int permitsIndex = topLevelKeywordIndex(header, "permits");
    String extended = header.substring(
        extendsIndex + "extends".length(), permitsIndex >= 0 ? permitsIndex : header.length());
    return typeNames(extended).stream()
        .map(type -> resolveKnownFrameworkType(type, file, SPRING_DATA_REPOSITORY_CONTRACTS.keySet()))
        .flatMap(Optional::stream)
        .map(SPRING_DATA_REPOSITORY_CONTRACTS::get)
        .distinct()
        .toList();
  }

  /** Returns whether the selected Spring Data contract guarantees findById. */
  boolean guaranteedFindByIdContract(String contract) {
    if (!SPRING_DATA_FIND_BY_ID_CONTRACTS.contains(contract)) {
      return false;
    }
    boolean pagingContract = contract.equals("PagingAndSortingRepository")
        || contract.equals("ListPagingAndSortingRepository");
    return !pagingContract || springBootMajorVersion().map(major -> major < 3).orElse(false);
  }
}
