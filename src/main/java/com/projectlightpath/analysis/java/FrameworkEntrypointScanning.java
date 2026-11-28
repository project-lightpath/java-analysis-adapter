package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Issue #84: framework-invoked entrypoint extraction for message
 * listeners, scheduled tasks, and main methods.
 *
 * <p>Follows the route/lifecycle pattern: canonical annotation resolution
 * (a user-defined same-simple-name lookalike never fabricates a fact),
 * bean-declaration gating on the declaring type, and a declaration-shape
 * check for main methods. Flag-independent by construction.
 */
final class FrameworkEntrypointScanning {
  private FrameworkEntrypointScanning() {}

  /** Spring message-listener annotations with their canonical names. */
  private static final Map<String, List<String>> SPRING_LISTENER_ANNOTATIONS = Map.of(
      "KafkaListener", List.of("org.springframework.kafka.annotation.KafkaListener"),
      "RabbitListener", List.of("org.springframework.amqp.rabbit.annotation.RabbitListener"),
      "JmsListener", List.of("org.springframework.jms.annotation.JmsListener"),
      "EventListener", List.of("org.springframework.context.event.EventListener"));

  /** Spring scheduled-task annotation with its canonical names. */
  private static final Map<String, List<String>> SPRING_SCHEDULED_ANNOTATIONS = Map.of(
      "Scheduled", List.of("org.springframework.scheduling.annotation.Scheduled"));

  /** Quarkus scheduled-task annotation with its canonical names. */
  private static final Map<String, List<String>> QUARKUS_SCHEDULED_ANNOTATIONS = Map.of(
      "Scheduled", List.of("io.quarkus.scheduler.Scheduled"));

  /** CDI observer parameter annotation with its canonical names. */
  private static final Map<String, List<String>> CDI_OBSERVES_ANNOTATIONS = Map.of(
      "Observes", List.of("jakarta.enterprise.event.Observes", "javax.enterprise.event.Observes"));

  /** Simple-receiver Quarkus runner usage inside a main body. */
  private static final Pattern QUARKUS_RUN_SIMPLE =
      Pattern.compile("(?<![\\w$.])Quarkus\\s*\\.\\s*run\\s*\\(");

  /** Qualified Quarkus runner usage inside a main body. */
  private static final Pattern QUARKUS_RUN_QUALIFIED =
      Pattern.compile("(?<![\\w$])io\\.quarkus\\.runtime\\.Quarkus\\s*\\.\\s*run\\s*\\(");

  /** Extracts Spring and Quarkus message-listener entrypoint methods. */
  static List<SourceMetadataScannerState.FrameworkEntrypointMethod> messageListenerMethods(
      SourceMetadataScanner scanner, AnalysisContext.Framework framework) {
    Map<String, List<String>> annotations = framework == AnalysisContext.Framework.SPRING_BOOT
        ? SPRING_LISTENER_ANNOTATIONS : CDI_OBSERVES_ANNOTATIONS;
    // CDI @Observes is a parameter-position annotation; the Spring
    // listener mechanisms are method-position annotations.
    boolean parameterPosition = framework != AnalysisContext.Framework.SPRING_BOOT;
    return entrypointMethods(scanner, framework, annotations, parameterPosition,
        "message_listener");
  }

  /** Extracts Spring and Quarkus scheduled-task entrypoint methods. */
  static List<SourceMetadataScannerState.FrameworkEntrypointMethod> scheduledTaskMethods(
      SourceMetadataScanner scanner, AnalysisContext.Framework framework) {
    Map<String, List<String>> annotations = framework == AnalysisContext.Framework.SPRING_BOOT
        ? SPRING_SCHEDULED_ANNOTATIONS : QUARKUS_SCHEDULED_ANNOTATIONS;
    return entrypointMethods(scanner, framework, annotations, false, "scheduled_task");
  }

  /** Shared bean-gated scan over annotated methods for one kind. */
  private static List<SourceMetadataScannerState.FrameworkEntrypointMethod> entrypointMethods(
      SourceMetadataScanner scanner,
      AnalysisContext.Framework framework,
      Map<String, List<String>> annotations,
      boolean parameterPosition,
      String kind) {
    List<SourceMetadataScannerState.FrameworkEntrypointMethod> result = new ArrayList<>();
    Set<String> registeredBeanTypes = registeredBeanTypes(scanner, framework);
    for (SourceMetadataScannerState.SourceFile file : scanner.files()) {
      for (SourceMetadataScannerState.MethodInfo method
          : SourceMetadataScanner.methods(file)) {
        String declaringType = scanner.typeContaining(
            file, SourceMetadataScanner.declarationStart(file, method.start()));
        if (!isBeanDeclared(scanner, framework, file, declaringType, registeredBeanTypes)) {
          continue;
        }
        String block = parameterPosition ? method.parameters() : method.annotationBlock();
        addMatchingMechanisms(scanner, file, method, declaringType, annotations, block,
            kind, result);
      }
    }
    return List.copyOf(result);
  }

  /** Adds one entrypoint method per canonically resolved mechanism. */
  private static void addMatchingMechanisms(
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.SourceFile file,
      SourceMetadataScannerState.MethodInfo method,
      String declaringType,
      Map<String, List<String>> annotations,
      String block,
      String kind,
      List<SourceMetadataScannerState.FrameworkEntrypointMethod> result) {
    for (Map.Entry<String, List<String>> entry : annotations.entrySet()) {
      // Evidence symbols preserve the exact source spellings (a fully
      // qualified occurrence stays qualified), the lifecycle precedent.
      List<String> symbols = SourceMetadataScanner
          .canonicalAnnotationOccurrences(file, block, entry.getKey(),
              Set.copyOf(entry.getValue()))
          .stream()
          .map(SourceMetadataScannerState.AnnotationOccurrence::source)
          .distinct()
          .toList();
      if (!symbols.isEmpty()) {
        result.add(new SourceMetadataScannerState.FrameworkEntrypointMethod(
            file, method, declaringType, kind, entry.getKey(), symbols));
      }
    }
  }

  /** Extracts classic-protocol main methods, marking Quarkus runner usage. */
  static List<SourceMetadataScannerState.FrameworkEntrypointMethod> mainMethods(
      SourceMetadataScanner scanner) {
    List<SourceMetadataScannerState.FrameworkEntrypointMethod> result = new ArrayList<>();
    for (SourceMetadataScannerState.SourceFile file : scanner.files()) {
      for (SourceMetadataScannerState.MethodInfo method
          : SourceMetadataScanner.methods(file)) {
        if (!isClassicMain(scanner, file, method)) {
          continue;
        }
        String declaringType = scanner.typeContaining(
            file, SourceMetadataScanner.declarationStart(file, method.start()));
        boolean quarkusRunner = usesQuarkusRunner(scanner, file, method);
        result.add(new SourceMetadataScannerState.FrameworkEntrypointMethod(
            file,
            method,
            declaringType,
            "main",
            quarkusRunner ? "quarkus" : "plain",
            quarkusRunner
                ? List.of("public static void main(String[])", "Quarkus.run(")
                : List.of("public static void main(String[])")));
      }
    }
    return List.copyOf(result);
  }

  /** Bean registration evidence for the declaring type, per framework. */
  private static boolean isBeanDeclared(
      SourceMetadataScanner scanner,
      AnalysisContext.Framework framework,
      SourceMetadataScannerState.SourceFile file,
      String declaringType,
      Set<String> registeredBeanTypes) {
    if (framework == AnalysisContext.Framework.SPRING_BOOT) {
      return scanner.springComponent(file, declaringType)
          || registeredBeanTypes.contains(declaringType);
    }
    return cdiBeanDeclared(scanner, file, declaringType);
  }

  /**
   * CDI bean gate for the declaring type. Local classes cannot be CDI
   * bean classes, so they decline (the springComponent local-type filter,
   * mirrored); anonymous types resolve to no declaration and decline.
   */
  private static boolean cdiBeanDeclared(
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.SourceFile file,
      String declaringType) {
    if (file.typeName().equals(declaringType)) {
      return scanner.cdiBean(file.classAnnotationBlock());
    }
    return scanner.typeDeclaration(file, declaringType)
        .filter(type -> !scanner.isLocalType(file, type))
        .map(type -> scanner.cdiBean(scanner.typeAnnotationBlock(file, type.start())))
        .orElse(false);
  }

  /** Spring @Bean-registered types; Quarkus has no bounded equivalent here. */
  private static Set<String> registeredBeanTypes(
      SourceMetadataScanner scanner, AnalysisContext.Framework framework) {
    return framework == AnalysisContext.Framework.SPRING_BOOT
        ? scanner.registeredSpringBeanTypes()
        : Set.of();
  }

  /** Classic JVM launch protocol: public static void main(String[]-shaped). */
  private static boolean isClassicMain(
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.SourceFile file,
      SourceMetadataScannerState.MethodInfo method) {
    if (!method.name().equals("main") || !method.returnType().equals("void")) {
      return false;
    }
    if (!SourceMetadataScanner.hasModifier(method.annotationBlock(), "public")
        || !SourceMetadataScanner.hasModifier(method.annotationBlock(), "static")) {
      return false;
    }
    return scanner.commandLineRunnerParameters(method.parameters());
  }

  /** Runner evidence bounded to the main method's own body, literal-masked. */
  private static boolean usesQuarkusRunner(
      SourceMetadataScanner scanner,
      SourceMetadataScannerState.SourceFile file,
      SourceMetadataScannerState.MethodInfo method) {
    String structural = file.structuralSource();
    if (method.end() <= 0 || method.end() > structural.length()
        || structural.charAt(method.end() - 1) != '{') {
      return false;
    }
    int closing = SourceMetadataScanner.matchingDelimiter(
        structural, method.end() - 1, '{', '}');
    if (closing < 0) {
      return false;
    }
    String body = JavaSourceText.maskStringLiterals(
        structural.substring(method.end(), closing));
    // The qualified spelling is self-identifying. The simple spelling can
    // resolve to a user-defined same-simple-name class (JLS 6.5), so it
    // requires an explicit canonical import in the declaring file — the
    // same lookalike discipline the annotation mechanisms enforce.
    if (QUARKUS_RUN_QUALIFIED.matcher(body).find()) {
      return true;
    }
    if (!scanner.importedTypes("Quarkus", file).contains("io.quarkus.runtime.Quarkus")) {
      return false;
    }
    return QUARKUS_RUN_SIMPLE.matcher(body).find();
  }
}
