package com.projectlightpath.analysis.java;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Shared bounded source model for the two first-class framework inputs.
 *
 * <p>It models only evidence that is explicit in source or in the selected
 * context. Container selection, proxy generation, and startup ordering remain
 * possible or unresolved when the corresponding runtime/build metadata is not
 * captured.
 */
abstract class BoundedFrameworkModelIdentity extends BoundedFrameworkModelUtilities implements FrameworkModel {

  BoundedFrameworkModelIdentity(AnalysisContext.Framework framework) {
    super(framework);
  }


  /** Builds a stable route fact identity from its full source signature and conditions. */
  String routeFactId(SourceMetadataScannerState.Route route) {
    String identity = String.join("\u0000",
        framework.id(),
        route.file().displayPath(),
        route.file().typeName(),
        route.method().name(),
        route.method().parameters().replaceAll("\\s+", " ").trim(),
        route.httpMethod(),
        route.path(),
        String.join(",", route.consumes()),
        String.join(",", route.produces()),
        String.join(",", route.headers()),
        String.join(",", route.parameters()));
    String readable = slug(route.file().typeName() + "-" + route.method().name()
        + "-" + route.httpMethod() + "-" + route.path());
    return framework.id() + "-route-" + readable + "-" + digest(identity).substring(0, 16);
  }

  /** Builds a stable injection identity while retaining legacy fixture IDs when unique. */
  String injectionFactId(
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.InjectionPoint injection,
      SourceMetadataScannerState.Parameter parameter,
      SourceMetadataScannerState.BeanCandidate candidate,
      boolean legacyIdAllowed) {
    String legacyId = legacyInjectionFactId(scanner, injection, candidate, legacyIdAllowed);
    if (legacyId != null) {
      return legacyId;
    }
    String candidateName = candidate == null ? "unresolved" : candidate.file().simpleTypeName();
    String injectionSignature = injection.member() + "("
        + String.join(",", injection.parameters().stream()
            .map(SourceMetadataScannerState.Parameter::type)
            .toList())
        + ")";
    String identity = String.join("\u0000",
        framework.id(),
        injection.file().typeName(),
        injection.file().displayPath(),
        injection.ownerType(),
        Integer.toString(injection.sourceOffset()),
        injection.member(),
        injection.kind(),
        injectionSignature,
        parameter.name(),
        parameter.type(),
        candidate == null ? "unresolved" : candidate.candidateId(),
        candidate == null ? "" : candidate.candidateType(),
        candidate == null ? "" : candidate.producerSignature(),
        candidate == null ? "" : candidate.producerParameters());
    return framework.id() + "-injection-"
        + slug(injection.ownerType() + "-" + injection.member() + "-" + parameter.type()
            + "-" + candidateName)
        + "-" + digest(identity).substring(0, 16);
  }

  /** Preserves fixture IDs only for the original unambiguous transfer bindings. */
  String legacyInjectionFactId(
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.InjectionPoint injection,
      SourceMetadataScannerState.BeanCandidate candidate,
      boolean legacyIdAllowed) {
    if (candidate == null || candidate.producer() || !legacyIdAllowed
        || !injection.ownerType().equals(injection.file().typeName())) {
      return null;
    }
    String owner = injection.file().simpleTypeName();
    String candidateName = candidate.file().simpleTypeName();
    if (!uniqueSimpleType(scanner, owner) || !uniqueSimpleType(scanner, candidateName)) {
      return null;
    }
    if (framework == AnalysisContext.Framework.QUARKUS
        && owner.equals("TransferResource")
        && candidateName.equals("DefaultTransferService")) {
      return "quarkus-resource-to-cdi-bean";
    }
    if (framework == AnalysisContext.Framework.SPRING_BOOT
        && owner.equals("TransferController")
        && candidateName.equals("TransactionService")) {
      return "spring-controller-to-service-injection";
    }
    return null;
  }

  /** Builds a stable proxy identity that includes the full target overload signature. */
  String proxyFactId(SourceMetadataScanner scanner, SourceMetadataScannerState.ProxyTarget target) {
    if (framework == AnalysisContext.Framework.QUARKUS
        && target.file().simpleTypeName().equals("DefaultTransferService")
        && target.method().name().equals("transfer")
        && uniqueSimpleType(scanner, target.file().simpleTypeName())
        && uniqueMethodName(target.file(), target.method().name())) {
      return "quarkus-cdi-proxy-to-service";
    }
    if (framework == AnalysisContext.Framework.SPRING_BOOT
        && target.file().simpleTypeName().equals("TransactionService")
        && target.method().name().equals("transfer")
        && uniqueSimpleType(scanner, target.file().simpleTypeName())
        && uniqueMethodName(target.file(), target.method().name())) {
      return "spring-transaction-proxy-to-service";
    }
    String identity = String.join("\u0000",
        framework.id(),
        target.file().typeName(),
        target.file().displayPath(),
        target.candidateId(),
        target.method().name(),
        target.method().parameters(),
        target.method().returnType(),
        target.proxyKind());
    return framework.id() + "-proxy-"
        + slug(target.file().typeName() + "-" + target.method().name()
            + "-" + target.method().parameters())
        + "-" + digest(identity).substring(0, 16);
  }

  /** Checks whether a simple type name identifies one source declaration in this scan. */
  static boolean uniqueSimpleType(SourceMetadataScanner scanner, String simpleType) {
    return scanner.files().stream()
        .filter(file -> file.simpleTypeName().equals(simpleType))
        .count() == 1;
  }

  /** Checks whether a method name has one overload in its declaring source type. */
  static boolean uniqueMethodName(
      SourceMetadataScannerState.SourceFile file,
      String methodName) {
    return SourceMetadataScanner.methods(file).stream()
        .filter(method -> method.name().equals(methodName))
        .count() == 1;
  }

  String generatedFactId(
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.GeneratedMethod generated) {
    if (generated.file().simpleTypeName().equals("GeneratedTransferMapper")
        && generated.method().name().equals("toResponse")
        && uniqueSimpleType(scanner, generated.file().simpleTypeName())
        && uniqueMethodName(generated.file(), generated.method().name())
        && scanner.generatedMethods().stream()
            .filter(item -> item.file().simpleTypeName().equals(generated.file().simpleTypeName()))
            .filter(item -> item.method().name().equals(generated.method().name()))
            .count() == 1) {
      return "quarkus-generated-mapper-binding";
    }
    String identity = String.join("\u0000",
        framework.id(),
        generated.file().typeName(),
        generated.file().displayPath(),
        generated.method().name(),
        generated.method().parameters().replaceAll("\\s+", " ").trim(),
        generated.method().returnType(),
        Integer.toString(generated.method().start()));
    return framework.id() + "-generated-"
        + slug(generated.file().typeName() + "-" + generated.method().name())
        + "-" + digest(identity).substring(0, 16);
  }

  String dynamicProxyFactId(SourceMetadataScanner scanner, DynamicFeature feature) {
    if (framework == AnalysisContext.Framework.QUARKUS
        && dynamicTargetSimpleName(feature.target()).equals("AuthCallback")
        && dynamicTargetMethod(feature.target()).equals("onAuthenticated")
        && scanner.dynamicFeatures().stream()
            .filter(item -> item.kind() == DynamicFeature.Kind.DYNAMIC_PROXY)
            .filter(item -> dynamicTargetSimpleName(item.target()).equals("AuthCallback"))
            .filter(item -> dynamicTargetMethod(item.target()).equals("onAuthenticated"))
            .count() == 1) {
      return "quarkus-unbounded-proxy-abstention";
    }
    return framework.id() + "-dynamic-proxy-" + slug(feature.target())
        + "-" + digest(dynamicFeatureIdentity(feature)).substring(0, 16);
  }

  String springRepositoryFactId(
      SourceMetadataScanner scanner,
      List<SourceMetadataScannerState.BeanCandidate> candidates,
      SourceMetadataScannerState.BeanCandidate candidate,
      String repositoryMethod) {
    boolean uniqueLegacyRepository = repositoryMethod.equals("findById")
        && candidate.file().simpleTypeName().equals("AccountRepository")
        && uniqueSimpleType(scanner, candidate.file().simpleTypeName())
        && candidates.stream()
            .filter(item -> item.kind().equals("repository_interface"))
            .filter(item -> item.file().simpleTypeName().equals("AccountRepository"))
            .count() == 1
        && SourceMetadataScanner.methods(candidate.file()).stream()
            .filter(method -> method.name().equals("findById"))
            .count() <= 1;
    if (uniqueLegacyRepository) {
      return "spring-data-generated-account-repository";
    }
    String identity = String.join("\u0000",
        framework.id(),
        candidate.file().typeName(),
        candidate.file().displayPath(),
        candidate.candidateId(),
        candidate.candidateType(),
        candidate.kind(),
        repositoryMethod);
    return "spring-data-generated-"
        + slug(candidate.file().typeName() + "-" + repositoryMethod)
        + "-" + digest(identity).substring(0, 16);
  }

  String lifecycleFactId(
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.SourceFile file,
      SourceMetadataScannerState.MethodInfo method,
      String declaringType,
      String kind) {
    if (file.simpleTypeName().equals("SecurityConfig")
        && method.name().equals("securityFilterChain")
        && uniqueLifecycleMethod(scanner, file, method)) {
      return "spring-bean-factory-to-security-config";
    }
    if (file.simpleTypeName().equals("DataSeeder")
        && method.name().equals("run")
        && uniqueLifecycleMethod(scanner, file, method)) {
      return "spring-boot-to-data-seeder";
    }
    String identity = String.join("\u0000",
        framework.id(),
        kind,
        declaringType,
        file.displayPath(),
        method.name(),
        method.parameters().replaceAll("\\s+", " ").trim(),
        method.returnType(),
        Integer.toString(method.start()));
    return "spring-lifecycle-" + kind + "-"
        + slug(declaringType + "-" + method.name())
        + "-" + digest(identity).substring(0, 16);
  }

  static boolean uniqueLifecycleMethod(
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.SourceFile file,
      SourceMetadataScannerState.MethodInfo method) {
    return scanner.files().stream()
        .filter(candidate -> candidate.simpleTypeName().equals(file.simpleTypeName()))
        .flatMap(candidate -> SourceMetadataScanner.methods(candidate).stream())
        .filter(candidate -> candidate.name().equals(method.name()))
        .count() == 1;
  }
}
