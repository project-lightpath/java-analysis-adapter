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

  private static final java.util.Set<String> PRIMITIVE_TYPE_NAMES = java.util.Set.of(
      "boolean", "byte", "char", "short", "int", "long", "float", "double");



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

  /**
   * The repository method signature with canonically resolved parameter
   * types behind the flag; the textual signature otherwise. The rule is
   * PER-PARAMETER and deliberately independent of member correlation: the
   * same method is declared in a repository interface and a fragment
   * interface (different files), and a member-wide rule (e.g. one blocked
   * by an unresolved cross-file return type) would give the two
   * declarations DIFFERENT identities and lose the fragment-backed
   * attribution. Per-parameter resolution is deterministic for both files;
   * an unresolvable parameter keeps its textual token so the identity
   * stays stable on both sides.
   */
  String repositoryMethodSignature(SourceMetadataScannerState.SourceFile file,
      MethodInfo method) {
    if (JavacSourceSymbolIndex.enabled()) {
      // A method type parameter must keep its TOKEN: resolving it through
      // java.lang would conflate <String extends Number> find(String key)
      // with find(java.lang.String key) — distinct overloads that would
      // otherwise share one identity and be deduplicated. An unparseable
      // clause falls back to the fully textual signature (deterministic).
      JavacTypeParameterClause clause = JavacTypeParameterClause.parse(file, method);
      if (clause.abstain()) {
        return repositoryMethodSignature(method);
      }
      List<String> parameterTypes = new ArrayList<>();
      for (Parameter parameter : parameters(method.parameters())) {
        String token = parameter.type().replaceAll("\\s+", " ").trim();
        String element = stripBalancedGenericArguments(token).trim();
        if (clause.names().contains(element)) {
          parameterTypes.add(token);
          continue;
        }
        String canonical = resolveReferenceType(element, file).orElse(token);
        // Canonicalize ONLY file-INDEPENDENT resolutions: primitives,
        // already-qualified spellings (deterministic from the spelling
        // itself), and java.lang types (implicitly imported in every
        // compilation unit). Import-dependent names keep their textual
        // token — the same method is declared in a repository interface
        // and a fragment interface, and the two files can import the same
        // simple name differently (explicit vs on-demand), so an
        // import-dependent canonical form would give the declarations
        // different identities and lose the fragment-backed attribution.
        boolean stable = PRIMITIVE_TYPE_NAMES.contains(element)
            || element.contains(".")
            || canonical.startsWith("java.lang.");
        parameterTypes.add(stable ? canonical : token);
      }
      return method.name() + "(" + String.join(",", parameterTypes) + ")";
    }
    return repositoryMethodSignature(method);
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

  /** Returns the contract-guaranteed method names for one Spring Data contract. */
  List<String> guaranteedContractMethods(String contract) {
    List<String> methods = SPRING_DATA_GUARANTEED_CONTRACT_METHODS.get(contract);
    if (methods == null) {
      return List.of();
    }
    boolean pagingContract = contract.equals("PagingAndSortingRepository")
        || contract.equals("ListPagingAndSortingRepository");
    if (pagingContract) {
      return springBootMajorVersion().map(major -> major < 3).orElse(false)
          ? methods
          : List.of();
    }
    if (contract.equals("JpaRepository")) {
      return guaranteedJpaMethods();
    }
    return methods;
  }

  /**
   * Version-pins the JPA-specific guarantees: getOne/getById were removed in
   * Spring Data 3; getById, saveAllAndFlush and deleteAllByIdInBatch exist
   * from 2.5; getReferenceById exists from 2.7. An unparsable version keeps
   * only the version-stable CRUD baseline (the safe abstention direction).
   */
  private List<String> guaranteedJpaMethods() {
    Optional<Integer> major = springBootMajorVersion();
    if (major.isEmpty()) {
      return SPRING_DATA_CRUD_METHODS;
    }
    return SPRING_DATA_JPA_METHODS.stream()
        .filter(name -> !(major.get() >= 3
            && SPRING_DATA_JPA_METHODS_REMOVED_IN_BOOT_3.contains(name)))
        .filter(name -> !(major.get() < 3
            && !springBootVersionAtLeast(2, 5)
            && SPRING_DATA_JPA_METHODS_ADDED_IN_2_5.contains(name)))
        .filter(name -> !(major.get() < 3
            && !springBootVersionAtLeast(2, 7)
            && SPRING_DATA_JPA_METHODS_ADDED_IN_2_7.contains(name)))
        .toList();
  }

  /** Reads whether the Spring Boot version is at least the given release. */
  boolean springBootVersionAtLeast(int requiredMajor, int requiredMinor) {
    Matcher matcher = Pattern.compile("^\\D*(\\d+)\\.(\\d+)")
        .matcher(context.frameworkVersion());
    if (!matcher.find()) {
      return false;
    }
    int major = Integer.parseInt(matcher.group(1));
    int minor = Integer.parseInt(matcher.group(2));
    return major > requiredMajor || (major == requiredMajor && minor >= requiredMinor);
  }
}
