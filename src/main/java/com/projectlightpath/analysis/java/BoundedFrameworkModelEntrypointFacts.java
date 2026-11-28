package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Issue #84: emits framework_entrypoint facts for message listeners,
 * scheduled tasks, and main methods — the framework-invoked roots the
 * #71 CVE-reachability reverse walk starts from.
 *
 * <p>Certainty ladder (documented in PLAN-84 §2.2 and RESOLUTION-84):
 * annotation families are POSSIBLE when their source-annotation metadata
 * family is observed and UNRESOLVED otherwise; no family ever reaches
 * DEFINITE because no registration metadata exists for these mechanisms
 * in the bounded contract. Main methods are always POSSIBLE bounded
 * candidates: the declaration is Java-language evidence, and the missing
 * input (deployed launcher selection) is stated in the limitation.
 */
abstract class BoundedFrameworkModelEntrypointFacts extends BoundedFrameworkModelSpringFacts
    implements FrameworkModel {

  BoundedFrameworkModelEntrypointFacts(AnalysisContext.Framework framework) {
    super(framework);
  }

  /** Metadata family that gates each entrypoint kind, per framework. */
  private static final Map<String, List<String>> LISTENER_METADATA =
      Map.of(
          "spring-boot", List.of("spring-listener-annotations"),
          "quarkus", List.of("quarkus-cdi-observer-annotations"));
  private static final Map<String, List<String>> SCHEDULED_METADATA =
      Map.of(
          "spring-boot", List.of("spring-scheduling-annotations"),
          "quarkus", List.of("quarkus-scheduler-annotations"));

  /** Emits listener, scheduled, and main entrypoint facts for the framework. */
  List<SemanticCallFacts.Fact> entrypointFacts(
      AnalysisContext context, SourceMetadataScanner scanner) {
    List<SemanticCallFacts.Fact> facts = new ArrayList<>();
    facts.addAll(listenerFacts(context, scanner));
    facts.addAll(scheduledFacts(context, scanner));
    facts.addAll(mainFacts(context, scanner));
    return List.copyOf(facts);
  }

  /** Emits message-listener entrypoint facts with the metadata-gated ladder. */
  private List<SemanticCallFacts.Fact> listenerFacts(
      AnalysisContext context, SourceMetadataScanner scanner) {
    List<SemanticCallFacts.Fact> facts = new ArrayList<>();
    boolean metadataAvailable = LISTENER_METADATA.get(framework.id()).stream()
        .anyMatch(context::metadataObserved);
    for (SourceMetadataScannerState.FrameworkEntrypointMethod listener
        : scanner.messageListenerMethods(framework)) {
      String rule = framework == AnalysisContext.Framework.QUARKUS
          ? "cdi-observer-method"
          : "spring-message-listener";
      String caller = listener.mechanism().equals("Observes")
          ? "CDI event dispatcher"
          : listenerCaller(listener.mechanism());
      facts.add(entrypointFact(context, new EntrypointEmission(
          listener,
          metadataAvailable,
          rule,
          caller,
          framework == AnalysisContext.Framework.QUARKUS
              ? "observer_parameter_annotation"
              : "listener_annotation",
          framework == AnalysisContext.Framework.QUARKUS
              ? "quarkus-cdi-observer-annotations"
              : "spring-listener-annotations",
          "Message listener registration is runtime container state;"
              + " no build-time registry is captured for this mechanism.")));
    }
    return facts;
  }

  /** Maps one Spring listener mechanism to its framework caller identity. */
  private static String listenerCaller(String mechanism) {
    return switch (mechanism) {
      case "KafkaListener" -> "external Kafka listener";
      case "RabbitListener" -> "external RabbitMQ listener";
      case "JmsListener" -> "external JMS listener";
      case "EventListener" -> "Spring ApplicationContext event";
      default -> "Spring message listener container";
    };
  }

  /** Emits scheduled-task entrypoint facts with the metadata-gated ladder. */
  private List<SemanticCallFacts.Fact> scheduledFacts(
      AnalysisContext context, SourceMetadataScanner scanner) {
    List<SemanticCallFacts.Fact> facts = new ArrayList<>();
    String metadataId = SCHEDULED_METADATA.get(framework.id()).get(0);
    boolean metadataAvailable = context.metadataObserved(metadataId);
    for (SourceMetadataScannerState.FrameworkEntrypointMethod scheduled
        : scanner.scheduledTaskMethods(framework)) {
      facts.add(entrypointFact(context, new EntrypointEmission(
          scheduled,
          metadataAvailable,
          framework == AnalysisContext.Framework.QUARKUS
              ? "quarkus-scheduled-task" : "spring-scheduled-task",
          framework == AnalysisContext.Framework.QUARKUS
              ? "Quarkus scheduler" : "Spring task scheduler",
          "scheduling_annotation",
          metadataId,
          "Task scheduling registration and activation conditions are"
              + " runtime container state; no build-time registry is captured.")));
    }
    return facts;
  }

  /** Emits main-method entrypoint facts (always POSSIBLE bounded candidates). */
  private List<SemanticCallFacts.Fact> mainFacts(
      AnalysisContext context, SourceMetadataScanner scanner) {
    List<SemanticCallFacts.Fact> facts = new ArrayList<>();
    for (SourceMetadataScannerState.FrameworkEntrypointMethod main
        : scanner.mainMethods()) {
      boolean quarkusRunner = main.mechanism().equals("quarkus");
      facts.add(entrypointFact(context, new EntrypointEmission(
          main,
          true,
          quarkusRunner ? "quarkus-main-runner" : "jvm-main-entrypoint",
          quarkusRunner ? "Quarkus application launcher" : "external JVM launcher",
          quarkusRunner ? "quarkus_runner_usage" : "main_declaration",
          null,
          "A declared main method does not prove the deployed launcher;"
              + " the runtime entry class selection is not captured.")));
    }
    return facts;
  }

  /** Named inputs for one entrypoint fact, so callers cannot swap fields. */
  private record EntrypointEmission(
      SourceMetadataScannerState.FrameworkEntrypointMethod entrypoint,
      boolean metadataAvailable,
      String rule,
      String caller,
      String evidenceKind,
      String metadataId,
      String limitation) {}

  /** Builds one framework_entrypoint fact from an extracted root method. */
  private SemanticCallFacts.Fact entrypointFact(
      AnalysisContext context, EntrypointEmission emission) {
    SemanticCallFacts.Certainty certainty = emission.metadataAvailable()
        ? SemanticCallFacts.Certainty.POSSIBLE
        : SemanticCallFacts.Certainty.UNRESOLVED;
    SourceMetadataScannerState.FrameworkEntrypointMethod entrypoint = emission.entrypoint();
    String callee = entrypoint.declaringType() + "#" + entrypoint.method().name();
    Map<String, String> attributes = new LinkedHashMap<>();
    attributes.put("mechanism", entrypoint.mechanism());
    attributes.put("kind", entrypoint.kind());
    if (emission.metadataId() != null) {
      attributes.put("annotationMetadata", emission.metadataId());
      attributes.put("registrationMetadataObserved", "false");
    }
    return SemanticCallFacts.Fact.framework(
        entrypointFactId(entrypoint),
        context,
        new SemanticCallFacts.Fact.FrameworkInput(
            emission.caller(),
            callee,
            "framework_entrypoint",
            certainty,
            emission.rule(),
            List.of(new SemanticCallFacts.Evidence(
                emission.evidenceKind(),
                entrypoint.file().displayPath(),
                entrypoint.symbols(),
                entrypoint.method().line(),
                attributes)),
            certainty == SemanticCallFacts.Certainty.UNRESOLVED
                ? "Entrypoint declarations were found, but the selected"
                    + " source/framework metadata is incomplete."
                : emission.limitation()));
  }

  /** Stable identity for one entrypoint root: framework, kind, mechanism, member. */
  private String entrypointFactId(
      SourceMetadataScannerState.FrameworkEntrypointMethod entrypoint) {
    String identity = String.join("\u0000",
        framework.id(),
        entrypoint.kind(),
        entrypoint.mechanism(),
        entrypoint.declaringType(),
        entrypoint.file().displayPath(),
        entrypoint.method().name(),
        entrypoint.method().parameters().replaceAll("\\s+", " ").trim(),
        entrypoint.method().returnType(),
        Integer.toString(entrypoint.method().start()));
    String readable = slug(entrypoint.declaringType() + "-" + entrypoint.method().name()
        + "-" + entrypoint.kind());
    return framework.id() + "-entrypoint-" + readable + "-" + digest(identity).substring(0, 16);
  }
}
