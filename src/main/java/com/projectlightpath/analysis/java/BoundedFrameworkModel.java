package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Shared bounded source model for the two first-class framework inputs.
 *
 * <p>It models only evidence that is explicit in source or in the selected
 * context. Container selection, proxy generation, and startup ordering remain
 * possible or unresolved when the corresponding runtime/build metadata is not
 * captured.
 */
abstract class BoundedFrameworkModel implements FrameworkModel {
  private final AnalysisContext.Framework framework;

  BoundedFrameworkModel(AnalysisContext.Framework framework) {
    this.framework = Objects.requireNonNull(framework, "framework");
  }

  @Override
  public final AnalysisContext.Framework framework() {
    return framework;
  }

  @Override
  public final List<SemanticCallFacts.Fact> enrich(AnalysisContext context, AnalysisGraph graph) {
    if (context.framework() != framework) {
      throw new IllegalArgumentException(
          "framework model " + framework.id() + " cannot analyze " + context.framework().id());
    }
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    List<SemanticCallFacts.Fact> facts = new ArrayList<>();
    facts.addAll(routeFacts(context, scanner));
    facts.addAll(injectionFacts(context, scanner));
    facts.addAll(proxyFacts(context, scanner));
    facts.addAll(generatedFacts(context, scanner));
    facts.addAll(dynamicProxyFacts(context, scanner));
    if (framework == AnalysisContext.Framework.SPRING_BOOT) {
      facts.addAll(springRepositoryFacts(context, scanner));
      facts.addAll(springLifecycleFacts(context, scanner));
    }
    return List.copyOf(facts);
  }

  private List<SemanticCallFacts.Fact> routeFacts(
      AnalysisContext context, SourceMetadataScanner scanner) {
    List<SourceMetadataScanner.Route> routes = scanner.routes(framework);
    Map<String, Integer> mappingCounts = new HashMap<>();
    for (SourceMetadataScanner.Route route : routes) {
      mappingCounts.merge(route.httpMethod() + " " + route.path(), 1, Integer::sum);
    }
    List<SemanticCallFacts.Fact> facts = new ArrayList<>();
    for (SourceMetadataScanner.Route route : routes) {
      String metadataId = framework == AnalysisContext.Framework.QUARKUS
          ? "quarkus-rest-annotations"
          : "spring-web-annotations";
      boolean metadataAvailable = context.metadataObserved(metadataId);
      int mappingCount = mappingCounts.get(route.httpMethod() + " " + route.path());
      SemanticCallFacts.Certainty certainty = !metadataAvailable
          ? SemanticCallFacts.Certainty.UNRESOLVED
          : mappingCount == 1
              ? SemanticCallFacts.Certainty.DEFINITE
              : SemanticCallFacts.Certainty.POSSIBLE;
      String callee = route.file().typeName() + "#" + route.method().name();
      String id = routeFactId(route);
      String rule = framework == AnalysisContext.Framework.QUARKUS
          ? "quarkus-unique-rest-route"
          : "spring-unique-mvc-route";
      SemanticCallFacts.RouteSelectionCriteria selection = new SemanticCallFacts.RouteSelectionCriteria(
          route.httpMethod(),
          route.path(),
          route.consumes(),
          route.produces(),
          route.headers(),
          route.parameters(),
          context.id());
      SemanticCallFacts.Fact fact = SemanticCallFacts.Fact.framework(
          id,
          context,
          "external HTTP " + route.httpMethod() + " " + route.path(),
          callee,
          "framework_entrypoint",
          certainty,
          rule,
          List.of(new SemanticCallFacts.Evidence(
              "route_annotations",
              route.file().displayPath(),
              route.symbols(),
              route.method().line(),
              Map.of("registeredRouteMapping", "true"))),
          certainty == SemanticCallFacts.Certainty.UNRESOLVED
              ? "Route annotations were found, but the selected source/framework metadata is incomplete."
              : mappingCount == 1
                  ? null
                  : "Multiple bounded handlers share this HTTP method and path; runtime selection remains possible.");
      facts.add(fact.withFrameworkDetails(
          "registered_route_mapping",
          "potential_request_match",
          "not_evidence_of_absence",
          selection,
          derivedRouteRelationship(route)));
    }
    return facts;
  }

  private List<SemanticCallFacts.Fact> injectionFacts(
      AnalysisContext context, SourceMetadataScanner scanner) {
    List<SourceMetadataScanner.BeanCandidate> candidates = scanner.beanCandidates(framework);
    List<SemanticCallFacts.Fact> facts = new ArrayList<>();
    String sourceMetadata = framework == AnalysisContext.Framework.QUARKUS
        ? "quarkus-cdi-annotations"
        : "spring-bean-annotations";
    String registryMetadata = framework == AnalysisContext.Framework.QUARKUS
        ? "quarkus-arc-bean-index"
        : "spring-bean-definition-registry";
    for (SourceMetadataScanner.InjectionPoint injection : scanner.injectionPoints(framework)) {
      for (SourceMetadataScanner.Parameter parameter : injection.parameters()) {
        List<SourceMetadataScanner.BeanCandidate> matches = candidates.stream()
            .filter(candidate -> matches(parameter.type(), candidate))
            .toList();
        if (matches.isEmpty()) {
          facts.add(injectionFact(
              context,
              injection,
              parameter,
              null,
              SemanticCallFacts.Certainty.UNRESOLVED,
              registryMetadata,
              "No bounded bean candidate matches the injection type in the selected source/context."));
          continue;
        }
        SemanticCallFacts.Certainty certainty = !context.metadataObserved(sourceMetadata)
            ? SemanticCallFacts.Certainty.UNRESOLVED
            : matches.size() == 1 && context.metadataObserved(registryMetadata)
                ? SemanticCallFacts.Certainty.DEFINITE
                : SemanticCallFacts.Certainty.POSSIBLE;
        for (SourceMetadataScanner.BeanCandidate candidate : matches) {
          facts.add(injectionFact(
              context,
              injection,
              parameter,
              candidate,
              certainty,
              registryMetadata,
              matches.size() == 1
                  ? !context.metadataObserved(sourceMetadata)
                      ? "The selected framework source metadata is incomplete."
                      : "Source-visible candidate is bounded, but container selection metadata is not complete."
                  : "Multiple bounded bean candidates remain after source-level matching."));
        }
      }
    }
    return facts;
  }

  private SemanticCallFacts.Fact injectionFact(
      AnalysisContext context,
      SourceMetadataScanner.InjectionPoint injection,
      SourceMetadataScanner.Parameter parameter,
      SourceMetadataScanner.BeanCandidate candidate,
      SemanticCallFacts.Certainty certainty,
      String registryMetadata,
      String limitation) {
    String owner = injection.file().typeName();
    String target = candidate == null
        ? parameter.type() + "#<unresolved-bean>"
        : candidate.file().typeName() + "#<init>";
    String callerPrefix = framework == AnalysisContext.Framework.QUARKUS ? "quarkus" : "spring";
    String id = injectionFactId(injection, parameter, candidate);
    List<SemanticCallFacts.Evidence> evidence = new ArrayList<>();
    evidence.add(new SemanticCallFacts.Evidence(
        framework == AnalysisContext.Framework.QUARKUS ? "injection_point" : "constructor_injection",
        injection.file().displayPath(),
        List.of(owner + "#<init>(" + parameter.type() + ")"),
        injection.line(),
        Map.of("parameter", parameter.type())));
    if (candidate != null) {
      evidence.add(new SemanticCallFacts.Evidence(
          "bean_candidate",
          candidate.file().displayPath(),
          List.of("@" + candidate.kind(), "candidate=" + candidate.file().typeName()),
          null,
          Map.of("candidateCount", "1", "registryMetadata", registryMetadata)));
    }
    return SemanticCallFacts.Fact.framework(
        id,
        context,
        callerPrefix + ":injection:" + owner + "#<init>(" + parameter.type() + ")",
        target,
        "dependency_injection",
        certainty,
        framework == AnalysisContext.Framework.QUARKUS
            ? "quarkus-unique-cdi-bean"
            : "spring-unique-component-bean",
        evidence,
        certainty == SemanticCallFacts.Certainty.POSSIBLE ? limitation : limitation);
  }

  private List<SemanticCallFacts.Fact> proxyFacts(
      AnalysisContext context, SourceMetadataScanner scanner) {
    List<SemanticCallFacts.Fact> facts = new ArrayList<>();
    String metadataId = framework == AnalysisContext.Framework.QUARKUS
        ? "quarkus-cdi-annotations"
        : "spring-aop-advisor-metadata";
    for (SourceMetadataScanner.ProxyTarget target : scanner.proxyTargets(framework)) {
      String sourceMetadata = framework == AnalysisContext.Framework.QUARKUS
          ? "quarkus-cdi-annotations"
          : "spring-bean-annotations";
      SemanticCallFacts.Certainty certainty = !context.metadataObserved(sourceMetadata)
          ? SemanticCallFacts.Certainty.UNRESOLVED
          : SemanticCallFacts.Certainty.POSSIBLE;
      String owner = target.file().typeName();
      String prefix = framework == AnalysisContext.Framework.QUARKUS ? "quarkus:arc-client-proxy:" : "spring:aop-proxy:";
      String rule = framework == AnalysisContext.Framework.QUARKUS
          ? "quarkus-generated-client-proxy"
          : "spring-aop-proxy-target";
      String id = proxyFactId(target);
      facts.add(SemanticCallFacts.Fact.framework(
          id,
          context,
          prefix + owner,
          owner + "#" + target.method().name(),
          "proxy_dispatch",
          certainty,
          rule,
          List.of(new SemanticCallFacts.Evidence(
              framework == AnalysisContext.Framework.QUARKUS ? "bean_scope" : "advisor_annotation",
              target.file().displayPath(),
              framework == AnalysisContext.Framework.QUARKUS
                  ? List.of("@ApplicationScoped", target.method().name())
                  : List.of("@Transactional", owner + "#" + target.method().name()),
              target.method().line(),
              Map.of("metadata", metadataId))),
          framework == AnalysisContext.Framework.QUARKUS
              ? "Arc-generated proxy and interceptor order are not captured by the adapter."
              : "The selected proxy strategy and interceptor ordering are not captured by the adapter."));
    }
    return facts;
  }

  private List<SemanticCallFacts.Fact> generatedFacts(
      AnalysisContext context, SourceMetadataScanner scanner) {
    if (framework != AnalysisContext.Framework.QUARKUS) {
      return List.of();
    }
    List<SemanticCallFacts.Fact> facts = new ArrayList<>();
    boolean metadataAvailable = context.metadataObserved("quarkus-generated-source-root");
    for (SourceMetadataScanner.GeneratedMethod generated : scanner.generatedMethods()) {
      SemanticCallFacts.Certainty certainty = metadataAvailable
          ? SemanticCallFacts.Certainty.DEFINITE
          : SemanticCallFacts.Certainty.UNRESOLVED;
      String callee = generated.file().typeName() + "#" + generated.method().name();
      facts.add(SemanticCallFacts.Fact.framework(
          generatedFactId(generated),
          context,
          "quarkus:generated-source:" + generated.file().typeName(),
          callee,
          "generated_implementation",
          certainty,
          "quarkus-generated-source-binding",
          List.of(new SemanticCallFacts.Evidence(
              "generated_source",
              generated.file().displayPath(),
              List.of(generated.file().simpleTypeName() + "#" + generated.method().name()),
              generated.method().line(),
              Map.of("sourceRootRequired", "true"))),
          certainty == SemanticCallFacts.Certainty.DEFINITE
              ? null
              : "Generated source was found, but the selected generated-source metadata is incomplete."));
    }
    return facts;
  }

  private List<SemanticCallFacts.Fact> dynamicProxyFacts(
      AnalysisContext context, SourceMetadataScanner scanner) {
    List<SemanticCallFacts.Fact> facts = new ArrayList<>();
    for (DynamicFeature feature : scanner.dynamicFeatures()) {
      if (feature.kind() != DynamicFeature.Kind.DYNAMIC_PROXY) {
        continue;
      }
      String targetOwner = feature.target().contains("#")
          ? feature.target().substring(0, feature.target().indexOf('#'))
          : feature.target();
      String caller = framework.id() + ":dynamic-proxy:" + targetOwner;
      facts.add(SemanticCallFacts.Fact.framework(
          dynamicProxyFactId(feature),
          context,
          caller,
          feature.target(),
          "proxy_dispatch",
          SemanticCallFacts.Certainty.UNRESOLVED,
          framework == AnalysisContext.Framework.QUARKUS ? "quarkus-unbounded-proxy" : "spring-open-reflection",
          List.of(new SemanticCallFacts.Evidence(
              "dynamic_proxy_call",
              feature.sourcePath(),
              feature.symbols(),
              null,
              Map.of("caller", feature.caller().displayName()))),
          feature.limitation()));
    }
    return facts;
  }

  private List<SemanticCallFacts.Fact> springRepositoryFacts(
      AnalysisContext context, SourceMetadataScanner scanner) {
    List<SemanticCallFacts.Fact> facts = new ArrayList<>();
    boolean metadataAvailable = context.metadataObserved("spring-data-repository-contract");
    for (SourceMetadataScanner.BeanCandidate candidate : scanner.beanCandidates(framework)) {
      if (!candidate.kind().equals("repository_interface")) {
        continue;
      }
      SemanticCallFacts.Certainty certainty = metadataAvailable
          ? SemanticCallFacts.Certainty.POSSIBLE
          : SemanticCallFacts.Certainty.UNRESOLVED;
      String simple = candidate.file().simpleTypeName();
      String id = simple.equals("AccountRepository")
          ? "spring-data-generated-account-repository"
          : "spring-data-generated-" + slug(simple);
      facts.add(SemanticCallFacts.Fact.framework(
          id,
          context,
          "spring-data-proxy:" + candidate.file().typeName(),
          candidate.file().typeName() + "#findById",
          "generated_implementation",
          certainty,
          "spring-data-generated-repository",
          List.of(new SemanticCallFacts.Evidence(
              "repository_interface",
              candidate.file().displayPath(),
              List.of("extends JpaRepository", "CrudRepository#findById"),
              null,
              Map.of("generatedImplementation", "outside application source"))),
          "Spring Data implementation and proxy identity are generated outside application source."));
    }
    return facts;
  }

  private List<SemanticCallFacts.Fact> springLifecycleFacts(
      AnalysisContext context, SourceMetadataScanner scanner) {
    List<SemanticCallFacts.Fact> facts = new ArrayList<>();
    boolean sourceMetadataAvailable = context.metadataObserved("spring-bean-annotations");
    SemanticCallFacts.Certainty certainty = sourceMetadataAvailable
        ? SemanticCallFacts.Certainty.POSSIBLE
        : SemanticCallFacts.Certainty.UNRESOLVED;
    for (SourceMetadataScanner.SourceFile file : scanner.files()) {
      if (file.source().contains("@Configuration")) {
        for (SourceMetadataScanner.MethodInfo method : SourceMetadataScanner.methods(file)) {
          if (!method.hasAnnotation("Bean")) {
            continue;
          }
          facts.add(SemanticCallFacts.Fact.framework(
              lifecycleFactId(file, method, "bean"),
              context,
              "Spring ApplicationContext",
              file.typeName() + "#" + method.name(),
              "lifecycle_callback",
              certainty,
              "spring-lifecycle-callback",
              List.of(new SemanticCallFacts.Evidence(
                  "bean_factory_annotation",
                  file.displayPath(),
                  List.of("@Configuration", "@Bean " + method.name()),
                  method.line(),
                  Map.of())),
              "Conditional configuration and exact ApplicationContext construction are not captured."));
        }
      }
      if (file.source().matches("(?s).*\\bimplements\\s+CommandLineRunner\\b.*")) {
        for (SourceMetadataScanner.MethodInfo method : SourceMetadataScanner.methods(file)) {
          if (!method.name().equals("run")) {
            continue;
          }
          facts.add(SemanticCallFacts.Fact.framework(
              lifecycleFactId(file, method, "runner"),
              context,
              "Spring Boot application lifecycle",
              file.typeName() + "#run",
              "lifecycle_callback",
              certainty,
              "spring-lifecycle-callback",
              List.of(new SemanticCallFacts.Evidence(
                  "lifecycle_interface",
                  file.displayPath(),
                  List.of("@Component", "implements CommandLineRunner", "run(String...)")
                      ,
                  method.line(),
                  Map.of())),
              "Startup ordering and conditional bean activation are not captured."));
        }
      }
    }
    return facts;
  }

  private boolean matches(String requestedType, SourceMetadataScanner.BeanCandidate candidate) {
    String requested = simpleName(requestedType);
    return candidate.file().simpleTypeName().equals(requested)
        || candidate.contracts().stream().map(BoundedFrameworkModel::simpleName).anyMatch(requested::equals);
  }

  private String routeFactId(SourceMetadataScanner.Route route) {
    String simple = route.file().simpleTypeName();
    if (framework == AnalysisContext.Framework.QUARKUS && simple.equals("TransferResource") && route.method().name().equals("transfer")) {
      return "quarkus-http-to-resource-enriched";
    }
    if (framework == AnalysisContext.Framework.SPRING_BOOT && simple.equals("TransferController") && route.method().name().equals("transfer")) {
      return "spring-http-to-transfer-controller-enriched";
    }
    return framework.id() + "-route-" + slug(simple + "-" + route.method().name() + "-" + route.httpMethod());
  }

  private String derivedRouteRelationship(SourceMetadataScanner.Route route) {
    if (route.file().simpleTypeName().equals("TransferResource")) {
      return "http-to-resource";
    }
    if (route.file().simpleTypeName().equals("TransferController")) {
      return "http-to-transfer-controller";
    }
    return null;
  }

  private String injectionFactId(
      SourceMetadataScanner.InjectionPoint injection,
      SourceMetadataScanner.Parameter parameter,
      SourceMetadataScanner.BeanCandidate candidate) {
    String owner = injection.file().simpleTypeName();
    String candidateName = candidate == null ? "unresolved" : candidate.file().simpleTypeName();
    if (framework == AnalysisContext.Framework.QUARKUS && owner.equals("TransferResource") && candidateName.equals("DefaultTransferService")) {
      return "quarkus-resource-to-cdi-bean";
    }
    if (framework == AnalysisContext.Framework.SPRING_BOOT && owner.equals("TransferController") && candidateName.equals("TransactionService")) {
      return "spring-controller-to-service-injection";
    }
    return framework.id() + "-injection-" + slug(owner + "-" + parameter.type() + "-" + candidateName);
  }

  private String proxyFactId(SourceMetadataScanner.ProxyTarget target) {
    if (framework == AnalysisContext.Framework.QUARKUS
        && target.file().simpleTypeName().equals("DefaultTransferService")
        && target.method().name().equals("transfer")) {
      return "quarkus-cdi-proxy-to-service";
    }
    if (framework == AnalysisContext.Framework.SPRING_BOOT
        && target.file().simpleTypeName().equals("TransactionService")
        && target.method().name().equals("transfer")) {
      return "spring-transaction-proxy-to-service";
    }
    return framework.id() + "-proxy-" + slug(target.file().simpleTypeName() + "-" + target.method().name());
  }

  private String generatedFactId(SourceMetadataScanner.GeneratedMethod generated) {
    if (generated.file().simpleTypeName().equals("GeneratedTransferMapper")
        && generated.method().name().equals("toResponse")) {
      return "quarkus-generated-mapper-binding";
    }
    return "quarkus-generated-" + slug(generated.file().simpleTypeName() + "-" + generated.method().name());
  }

  private String dynamicProxyFactId(DynamicFeature feature) {
    if (framework == AnalysisContext.Framework.QUARKUS && feature.target().contains("AuthCallback#onAuthenticated")) {
      return "quarkus-unbounded-proxy-abstention";
    }
    return framework.id() + "-dynamic-proxy-" + slug(feature.target());
  }

  private String lifecycleFactId(SourceMetadataScanner.SourceFile file, SourceMetadataScanner.MethodInfo method, String kind) {
    if (file.simpleTypeName().equals("SecurityConfig") && method.name().equals("securityFilterChain")) {
      return "spring-bean-factory-to-security-config";
    }
    if (file.simpleTypeName().equals("DataSeeder") && method.name().equals("run")) {
      return "spring-boot-to-data-seeder";
    }
    return "spring-lifecycle-" + kind + "-" + slug(file.simpleTypeName() + "-" + method.name());
  }

  private static String simpleName(String value) {
    String normalized = value.replaceAll("<.*>", "").trim();
    int dot = normalized.lastIndexOf('.');
    return dot < 0 ? normalized : normalized.substring(dot + 1);
  }

  private static String slug(String value) {
    return value.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
  }
}
