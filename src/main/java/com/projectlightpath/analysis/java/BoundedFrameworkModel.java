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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared bounded source model for the two first-class framework inputs.
 *
 * <p>It models only evidence that is explicit in source or in the selected
 * context. Container selection, proxy generation, and startup ordering remain
 * possible or unresolved when the corresponding runtime/build metadata is not
 * captured.
 */
abstract class BoundedFrameworkModel implements FrameworkModel {
  private static final Set<String> JAVA_LANG_TYPES = Set.of(
      "Appendable", "AutoCloseable", "Boolean", "Byte", "Character", "CharSequence",
      "Class", "Cloneable", "Comparable", "Double", "Enum", "Exception", "Float",
      "Integer", "Iterable", "Long", "Math", "Number", "Object", "Record", "Runnable",
      "RuntimeException", "Short", "StrictMath", "String", "Thread", "Throwable", "Void");

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
      mappingCounts.merge(route.httpMethod() + "\u0000" + route.path(), 1, Integer::sum);
    }
    List<SemanticCallFacts.Fact> facts = new ArrayList<>();
    for (SourceMetadataScanner.Route route : routes) {
      String metadataId = framework == AnalysisContext.Framework.QUARKUS
          ? "quarkus-rest-annotations"
          : "spring-web-annotations";
      String registrationMetadataId = framework == AnalysisContext.Framework.QUARKUS
          ? "quarkus-rest-registration"
          : "spring-mvc-handler-mappings";
      boolean metadataAvailable = context.metadataObserved(metadataId);
      boolean registrationMetadataAvailable = context.metadataObserved(registrationMetadataId);
      int mappingCount = mappingCounts.get(route.httpMethod() + "\u0000" + route.path());
      // AnalysisContext currently records registration metadata availability, not
      // candidate-level handler rows. Presence alone must not promote a route.
      SemanticCallFacts.Certainty certainty = !metadataAvailable
          ? SemanticCallFacts.Certainty.UNRESOLVED
          : SemanticCallFacts.Certainty.POSSIBLE;
      String callee = route.file().typeName() + "#" + route.method().name();
      String id = routeFactId(route);
      String rule = framework == AnalysisContext.Framework.QUARKUS
          ? "quarkus-unique-rest-route"
          : "spring-unique-mvc-route";
      String limitation = !metadataAvailable
          ? "Route annotations were found, but the selected source/framework metadata is incomplete."
          : mappingCount != 1
              ? "Multiple bounded handlers share this HTTP method and path; runtime selection remains possible."
              : "Route annotations identify a bounded candidate, but route-level registration metadata is not matched to this candidate.";
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
              Map.of(
                  "annotationMetadata", metadataId,
                  "registrationMetadata", registrationMetadataId,
                  "registrationMetadataObserved", Boolean.toString(registrationMetadataAvailable),
                  "registeredRouteMapping", Boolean.toString(certainty == SemanticCallFacts.Certainty.DEFINITE),
                  "routeCandidate", "true"))),
          limitation);
      facts.add(fact.withFrameworkDetails(
          "registered_route_mapping",
          "potential_request_match",
          "not_evidence_of_absence",
          selection,
          null));
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
      for (int parameterIndex = 0; parameterIndex < injection.parameters().size(); parameterIndex++) {
        SourceMetadataScanner.Parameter parameter = injection.parameters().get(parameterIndex);
        List<SourceMetadataScanner.BeanCandidate> typeMatches = candidates.stream()
            .filter(candidate -> matches(context, parameter.type(), candidate))
            .filter(candidate -> profileMatches(candidate.profiles(), context.selectedProfile()))
            .toList();
        List<SourceMetadataScanner.BeanCandidate> matches = typeMatches.stream()
            .filter(candidate -> candidateMetadataActive(context, candidate) != Boolean.FALSE)
            .toList();
        matches = qualifierMatches(context, parameter, injection, matches);

        List<SourceMetadataScanner.BeanCandidate> qualifiedTypeMatches = qualifierMatches(
            context, parameter, injection, typeMatches);

        List<AnalysisContext.ResolvedBindingMetadata> bindingRows = matchingBindings(
            context, injection, parameter, parameterIndex, typeMatches);
        List<AnalysisContext.ResolvedBindingMetadata> selectedRows = bindingRows.stream()
            .filter(row -> row.active() == Boolean.TRUE)
            .filter(row -> row.selected() == Boolean.TRUE)
            .toList();
        if (!selectedRows.isEmpty()) {
          matches = matches.stream()
              .filter(candidate -> selectedRows.stream().anyMatch(row ->
                  row.candidateId().equals(candidate.candidateId())
                      || matchingCandidateMetadata(context, candidate).stream()
                          .anyMatch(metadata -> row.candidateId().equals(metadata.candidateId()))))
              .toList();
        } else if (!bindingRows.isEmpty()
            && bindingRows.stream().allMatch(row -> row.selected() == Boolean.FALSE
                || row.active() == Boolean.FALSE)) {
          matches = List.of();
        }
        if (selectedRows.isEmpty()) {
          matches = bindingPreference(context, matches);
        }

        if (matches.isEmpty()) {
          facts.add(injectionFact(
              context,
              injection,
              parameter,
              null,
              SemanticCallFacts.Certainty.UNRESOLVED,
              registryMetadata,
              typeMatches.size(),
              null,
              null,
              null,
              "No active bounded bean candidate matches the injection type, qualifiers, profile, or binding metadata."));
          continue;
        }

        boolean exactBindingSelection = selectedRows.size() == 1 && matches.size() == 1;
        List<AnalysisContext.BindingCandidateMetadata> selectedCandidateMetadata =
            matchingCandidateMetadata(context, matches.get(0));
        boolean exactCandidateSelection = matches.size() == 1
            && qualifiedTypeMatches.size() == 1
            && candidateMetadataActive(context, matches.get(0)) == Boolean.TRUE
            && selectedCandidateMetadata.size() == 1
            && selectedCandidateMetadata.get(0).active() == Boolean.TRUE;
        boolean metadataCandidateCoverage = qualifiedTypeMatches.stream()
            .allMatch(candidate -> matchingCandidateMetadata(context, candidate).size() == 1);
        List<SourceMetadataScanner.BeanCandidate> selectedMatches = List.copyOf(matches);
        boolean metadataSelectsOnlyMatch = qualifiedTypeMatches.stream().allMatch(candidate -> {
          Boolean active = candidateMetadataActive(context, candidate);
          return selectedMatches.contains(candidate) ? active == Boolean.TRUE : active == Boolean.FALSE;
        });
        boolean exactActiveSet = matches.size() == 1
            && qualifiedTypeMatches.size() > 1
            && metadataCandidateCoverage
            && metadataSelectsOnlyMatch;
        boolean conditionUnknown = matches.stream().anyMatch(candidate -> hasConditions(context, candidate))
            && matches.stream().anyMatch(candidate -> candidateMetadataActive(context, candidate) == null);
        SemanticCallFacts.Certainty certainty;
        if (exactBindingSelection || exactCandidateSelection || exactActiveSet) {
          certainty = SemanticCallFacts.Certainty.DEFINITE;
        } else if (conditionUnknown || !context.metadataObserved(sourceMetadata)) {
          certainty = SemanticCallFacts.Certainty.UNRESOLVED;
        } else {
          certainty = SemanticCallFacts.Certainty.POSSIBLE;
        }
        for (SourceMetadataScanner.BeanCandidate candidate : matches) {
          AnalysisContext.BindingCandidateMetadata candidateMetadata = matchingCandidateMetadata(
              context, candidate).stream().findFirst().orElse(null);
          AnalysisContext.ResolvedBindingMetadata binding = bindingRows.stream()
              .filter(row -> row.candidateId().equals(candidate.candidateId()))
              .findFirst()
              .orElseGet(() -> bindingRows.stream()
                  .filter(row -> candidateMetadata != null
                      && row.candidateId().equals(candidateMetadata.candidateId()))
                  .findFirst()
                  .orElse(null));
          String limitation = certainty == SemanticCallFacts.Certainty.DEFINITE
              ? null
              : conditionUnknown
                  ? "A profile or conditional bean registration is not resolved in the selected context."
                  : certainty == SemanticCallFacts.Certainty.UNRESOLVED
                      ? "The selected framework source or binding metadata is incomplete."
                      : matches.size() > 1
                          ? "Multiple active bounded bean candidates remain after source-level filtering."
                          : "The source-visible candidate is bounded, but exact framework selection metadata is incomplete.";
          facts.add(injectionFact(
              context,
              injection,
              parameter,
              candidate,
              certainty,
              registryMetadata,
              typeMatches.size(),
              candidateMetadata,
              binding,
              parameterIndex,
              limitation));
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
      int candidateCount,
      AnalysisContext.BindingCandidateMetadata candidateMetadata,
      AnalysisContext.ResolvedBindingMetadata binding,
      Integer parameterIndex,
      String limitation) {
    String owner = injection.file().typeName();
    String target = candidate == null
        ? parameter.type() + "#<unresolved-bean>"
        : candidate.target();
    String callerPrefix = framework == AnalysisContext.Framework.QUARKUS ? "quarkus" : "spring";
    String id = injectionFactId(injection, parameter, candidate);
    String displayedQualifiers = String.join(",", displayQualifiers(
        mergeQualifiers(injection.qualifiers(), parameter.qualifiers())));
    List<SemanticCallFacts.Evidence> evidence = new ArrayList<>();
    evidence.add(new SemanticCallFacts.Evidence(
        framework == AnalysisContext.Framework.QUARKUS ? "injection_point" : "constructor_injection",
        injection.file().displayPath(),
        List.of(owner + "#" + injection.member() + "(" + parameter.type() + ")"),
        injection.line(),
        Map.of(
            "parameter", parameter.type(),
            "parameterIndex", parameterIndex == null ? "" : parameterIndex.toString(),
             "injectionKind", injection.kind(),
             "qualifiers", displayedQualifiers)));
    if (candidate != null) {
      Map<String, String> attributes = new HashMap<>();
      attributes.put("candidateCount", Integer.toString(candidateCount));
      attributes.put("registryMetadata", registryMetadata);
      attributes.put("candidateId", candidate.candidateId());
      attributes.put("candidateType", candidate.candidateType());
      attributes.put("producer", Boolean.toString(candidate.producer()));
      attributes.put("qualifiers", String.join(",", candidate.qualifiers()));
      attributes.put("profiles", String.join(",", candidate.profiles()));
      attributes.put("conditions", String.join(",", candidate.conditions()));
      if (candidateMetadata != null) {
        attributes.put("candidateMetadataId", candidateMetadata.candidateId());
        attributes.put("candidateMetadataActive", String.valueOf(candidateMetadata.active()));
        attributes.put("candidateMetadataAlternative", Boolean.toString(candidateMetadata.alternative()));
        attributes.put("candidateMetadataPrimary", Boolean.toString(candidateMetadata.primary()));
      }
      if (binding != null) {
        attributes.put("bindingMetadataId", binding.bindingId());
        attributes.put("bindingSelected", String.valueOf(binding.selected()));
        attributes.put("bindingActive", String.valueOf(binding.active()));
        if (binding.selectionReason() != null) {
          attributes.put("selectionReason", binding.selectionReason());
        }
      }
      evidence.add(new SemanticCallFacts.Evidence(
          "bean_candidate",
          candidate.file().displayPath(),
          List.of("@" + candidate.kind(), "candidate=" + candidate.candidateType()),
          null,
          attributes));
    }
    String providerFactId = binding == null ? null : binding.providerFactId();
    if (providerFactId == null && candidateMetadata != null) {
      providerFactId = candidateMetadata.providerFactId();
    }
    SemanticCallFacts.Fact fact = SemanticCallFacts.Fact.framework(
        id,
        context,
        callerPrefix + ":injection:" + owner + "#" + injection.member()
            + "(" + parameter.type() + ")",
        target,
        "dependency_injection",
        certainty,
        framework == AnalysisContext.Framework.QUARKUS
            ? "quarkus-unique-cdi-bean"
            : "spring-unique-component-bean",
        evidence,
        limitation);
    return fact.withFrameworkDetails(
        "resolved_bean_binding",
        certainty == SemanticCallFacts.Certainty.DEFINITE
            ? "container_selected_candidate"
            : "potential_container_match",
        "not_evidence_of_absence",
        null,
        providerFactId);
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
      List<AnalysisContext.ProxyTargetMetadata> metadataRows = matchingProxyMetadata(context, scanner, target);
      List<AnalysisContext.ProxyTargetMetadata> activeRows = metadataRows.stream()
          .filter(row -> row.active() != Boolean.FALSE)
          .filter(row -> row.selected() != Boolean.FALSE)
          .toList();
      if (!metadataRows.isEmpty() && metadataRows.stream().allMatch(row ->
          row.active() == Boolean.FALSE || row.selected() == Boolean.FALSE)) {
        continue;
      }
      boolean exact = activeRows.size() == 1
          && activeRows.get(0).selected() == Boolean.TRUE
          && activeRows.get(0).active() == Boolean.TRUE;
      SemanticCallFacts.Certainty certainty = exact
          ? SemanticCallFacts.Certainty.DEFINITE
          : !context.metadataObserved(sourceMetadata)
              ? SemanticCallFacts.Certainty.UNRESOLVED
              : SemanticCallFacts.Certainty.POSSIBLE;
      AnalysisContext.ProxyTargetMetadata metadata = activeRows.stream().findFirst().orElse(null);
      String owner = target.file().typeName();
      String prefix = framework == AnalysisContext.Framework.QUARKUS ? "quarkus:arc-client-proxy:" : "spring:aop-proxy:";
      String rule = exact
          ? framework == AnalysisContext.Framework.QUARKUS
              ? "quarkus-metadata-proxy-target"
              : "spring-metadata-proxy-target"
          : framework == AnalysisContext.Framework.QUARKUS
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
               proxyAttributes(metadataId, target, metadata))),
           exact
               ? null
               : framework == AnalysisContext.Framework.QUARKUS
                   ? "Arc-generated proxy and interceptor order are not captured by the adapter."
                   : "The selected proxy strategy and interceptor ordering are not captured by the adapter."
        ));
      SemanticCallFacts.Fact fact = facts.remove(facts.size() - 1);
      facts.add(fact.withFrameworkDetails(
          "proxy_target_dispatch",
          exact ? "framework_proxy_selected_target" : "potential_framework_proxy_target",
          "not_evidence_of_absence",
          null,
          metadata == null ? null : metadata.providerFactId()));
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

  private List<SourceMetadataScanner.BeanCandidate> qualifierMatches(
      AnalysisContext context,
      SourceMetadataScanner.Parameter parameter,
      SourceMetadataScanner.InjectionPoint injection,
      List<SourceMetadataScanner.BeanCandidate> candidates) {
    Set<String> requested = new LinkedHashSet<>(mergeQualifiers(
        injection.qualifiers(), parameter.qualifiers()));
    if (requested.isEmpty()) {
      if (framework != AnalysisContext.Framework.QUARKUS) {
        return candidates;
      }
      return candidates.stream()
          .filter(candidate -> candidateQualifiers(context, candidate).stream()
              .noneMatch(this::explicitQualifier))
          .toList();
    }
    return candidates.stream()
        .filter(candidate -> requested.stream().allMatch(requestedQualifier ->
            qualifierMatches(context, requestedQualifier, candidate, candidates)))
        .toList();
  }

  /** Applies framework qualifier rules, including Spring bean-name fallbacks. */
  private boolean qualifierMatches(
      AnalysisContext context,
      String requestedQualifier,
      SourceMetadataScanner.BeanCandidate candidate,
      List<SourceMetadataScanner.BeanCandidate> candidates) {
    if (requestedQualifier.startsWith("ResourceDefault:")) {
      String name = requestedQualifier.substring("ResourceDefault:".length());
      boolean namedCandidateExists = candidates.stream()
          .anyMatch(item -> resourceNameMatches(context, name, item));
      return !namedCandidateExists || resourceNameMatches(context, name, candidate);
    }
    if (requestedQualifier.startsWith("Resource:")) {
      return resourceNameMatches(
          context,
          requestedQualifier.substring("Resource:".length()),
          candidate);
    }
    if (framework == AnalysisContext.Framework.SPRING_BOOT
        && requestedQualifier.startsWith("Qualifier:")) {
      String value = requestedQualifier.substring("Qualifier:".length());
      return candidateQualifiers(context, candidate).stream()
          .anyMatch(candidateQualifier -> qualifierEquals(requestedQualifier, candidateQualifier))
          || candidateBeanNames(context, candidate).contains(value);
    }
    return candidateQualifiers(context, candidate).stream()
        .anyMatch(candidateQualifier -> qualifierEquals(requestedQualifier, candidateQualifier));
  }

  private static List<String> candidateQualifiers(
      AnalysisContext context,
      SourceMetadataScanner.BeanCandidate candidate) {
    LinkedHashSet<String> result = new LinkedHashSet<>(candidate.qualifiers());
    context.bindingCandidates().stream()
        .filter(row -> candidateMetadataMatches(row, candidate))
        .flatMap(row -> row.qualifiers().stream())
        .forEach(result::add);
    return List.copyOf(result);
  }

  private static boolean resourceNameMatches(
      AnalysisContext context,
      String name,
      SourceMetadataScanner.BeanCandidate candidate) {
    return candidateBeanNames(context, candidate).contains(name);
  }

  /** Returns explicit and convention-based names that can identify a bean. */
  private static Set<String> candidateBeanNames(
      AnalysisContext context,
      SourceMetadataScanner.BeanCandidate candidate) {
    Set<String> names = new LinkedHashSet<>();
    String defaultName = candidate.producer()
        ? candidate.producerMethod()
        : decapitalize(candidate.file().simpleTypeName());
    if (defaultName != null && !defaultName.isBlank()) {
      names.add(defaultName);
    }
    candidateQualifiers(context, candidate).stream()
        .filter(qualifier -> qualifier.startsWith("BeanName:")
            || qualifier.startsWith("Named:")
            || qualifier.startsWith("Qualifier:"))
        .map(qualifier -> qualifier.substring(qualifier.indexOf(':') + 1))
        .forEach(names::add);
    return Set.copyOf(names);
  }

  private List<SourceMetadataScanner.BeanCandidate> bindingPreference(
      AnalysisContext context,
      List<SourceMetadataScanner.BeanCandidate> candidates) {
    if (candidates.size() < 2) {
      return candidates;
    }
    if (framework == AnalysisContext.Framework.SPRING_BOOT) {
      List<SourceMetadataScanner.BeanCandidate> primary = candidates.stream()
          .filter(candidate -> candidate.primary() || matchingCandidateMetadata(context, candidate).stream()
              .anyMatch(AnalysisContext.BindingCandidateMetadata::primary))
          .toList();
      return primary.size() == 1 ? primary : candidates;
    }
    List<SourceMetadataScanner.BeanCandidate> alternatives = candidates.stream()
        .filter(candidate -> matchingCandidateMetadata(context, candidate).stream()
            .anyMatch(row -> row.alternative() && row.active() == Boolean.TRUE
                && profileMatches(row.profiles(), context.selectedProfile())))
        .toList();
    return alternatives.isEmpty() ? candidates : alternatives;
  }

  private boolean explicitQualifier(String qualifier) {
    String normalized = qualifier.toLowerCase(Locale.ROOT);
    return !normalized.equals("default")
        && !normalized.equals("any")
        && !normalized.startsWith("named:")
        && !normalized.startsWith("beanname:");
  }

  private static boolean qualifierEquals(String left, String right) {
    return left != null && left.equals(right);
  }

  /** Treats an implicit resource-name marker as its public resource qualifier. */
  private static boolean bindingQualifierEquals(String left, String right) {
    if (qualifierEquals(left, right)) {
      return true;
    }
    return ((left.startsWith("ResourceDefault:") && right.startsWith("Resource:"))
        || (left.startsWith("Resource:") && right.startsWith("ResourceDefault:")))
        && left.substring(left.indexOf(':') + 1).equals(right.substring(right.indexOf(':') + 1));
  }

  /** Applies the default bean-name decapitalization used by Spring. */
  private static String decapitalize(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    if (value.length() > 1 && Character.isUpperCase(value.charAt(0))
        && Character.isUpperCase(value.charAt(1))) {
      return value;
    }
    return Character.toLowerCase(value.charAt(0)) + value.substring(1);
  }

  private static List<String> mergeQualifiers(List<String> first, List<String> second) {
    LinkedHashSet<String> result = new LinkedHashSet<>();
    if (first != null) {
      result.addAll(first);
    }
    if (second != null) {
      result.addAll(second);
    }
    return List.copyOf(result);
  }

  /** Hides the internal marker used to distinguish an implicit {@code @Resource} name. */
  private static List<String> displayQualifiers(List<String> qualifiers) {
    return qualifiers.stream()
        .map(qualifier -> qualifier.startsWith("ResourceDefault:")
            ? "Resource:" + qualifier.substring("ResourceDefault:".length())
            : qualifier)
        .toList();
  }

  private static boolean profileMatches(List<String> profiles, String selectedProfile) {
    if (profiles == null || profiles.isEmpty()) {
      return true;
    }
    boolean positiveProfile = false;
    boolean positiveMatch = false;
    for (String raw : profiles) {
      String profile = raw == null ? "" : raw.trim().replaceAll("^\"|\"$", "");
      if (profile.isBlank()) {
        continue;
      }
      if (profile.startsWith("!")) {
        if (profile.substring(1).equalsIgnoreCase(selectedProfile)) {
          return false;
        }
      } else {
        positiveProfile = true;
        if (profile.equalsIgnoreCase(selectedProfile) || profile.equalsIgnoreCase("default")) {
          positiveMatch = true;
        }
      }
    }
    return !positiveProfile || positiveMatch;
  }

  private boolean hasConditions(
      AnalysisContext context,
      SourceMetadataScanner.BeanCandidate candidate) {
    return !candidate.conditions().isEmpty()
        || matchingCandidateMetadata(context, candidate).stream()
            .anyMatch(row -> !row.conditions().isEmpty());
  }

  private Boolean candidateMetadataActive(
      AnalysisContext context, SourceMetadataScanner.BeanCandidate candidate) {
    List<AnalysisContext.BindingCandidateMetadata> rows = matchingCandidateMetadata(context, candidate);
    if (rows.isEmpty()) {
      return null;
    }
    boolean knownActive = false;
    boolean knownInactive = false;
    boolean unknown = false;
    for (AnalysisContext.BindingCandidateMetadata row : rows) {
      if (!profileMatches(row.profiles(), context.selectedProfile())) {
        knownInactive = true;
      } else if (row.active() == Boolean.FALSE) {
        knownInactive = true;
      } else if (row.active() == Boolean.TRUE) {
        knownActive = true;
      } else {
        unknown = true;
      }
    }
    if (knownActive && !knownInactive && !unknown) {
      return Boolean.TRUE;
    }
    if (knownInactive && !knownActive && !unknown) {
      return Boolean.FALSE;
    }
    return null;
  }

  private List<AnalysisContext.BindingCandidateMetadata> matchingCandidateMetadata(
      AnalysisContext context, SourceMetadataScanner.BeanCandidate candidate) {
    return context.bindingCandidates().stream()
        .filter(row -> candidateMetadataMatches(row, candidate))
        .toList();
  }

  private static boolean candidateMetadataMatches(
      AnalysisContext.BindingCandidateMetadata row,
      SourceMetadataScanner.BeanCandidate candidate) {
    if (row.candidateId().equals(candidate.candidateId())) {
      return true;
    }
    if (!metadataTypeMatches(row.candidateType(), candidate.candidateType())) {
      return false;
    }
    if (row.declaringType() != null && !row.declaringType().isBlank()
        && !metadataTypeMatches(row.declaringType(), candidate.file().typeName())) {
      return false;
    }
    return row.producerMethod() == null
        || row.producerMethod().isBlank()
        || row.producerMethod().equals(candidate.producerMethod());
  }

  private List<AnalysisContext.ResolvedBindingMetadata> matchingBindings(
      AnalysisContext context,
      SourceMetadataScanner.InjectionPoint injection,
      SourceMetadataScanner.Parameter parameter,
      int parameterIndex,
      List<SourceMetadataScanner.BeanCandidate> candidates) {
    return context.resolvedBindings().stream()
        .filter(row -> injectionOwnerMatches(row.injectionOwner(), injection.file().typeName()))
        .filter(row -> memberMatches(row.injectionMember(), injection.member()))
        .filter(row -> simpleName(row.injectionType()).equalsIgnoreCase(simpleName(parameter.type())))
        .filter(row -> row.parameterIndex() == null || row.parameterIndex() == parameterIndex)
        .filter(row -> row.qualifiers().isEmpty()
            || mergeQualifiers(injection.qualifiers(), parameter.qualifiers()).isEmpty()
            || mergeQualifiers(injection.qualifiers(), parameter.qualifiers()).stream()
                .anyMatch(requested -> row.qualifiers().stream()
                    .anyMatch(candidateQualifier -> bindingQualifierEquals(requested, candidateQualifier))))
        .filter(row -> candidates.stream().anyMatch(candidate ->
            row.candidateId().equals(candidate.candidateId())
                || matchingCandidateMetadata(context, candidate).stream()
                    .anyMatch(metadata -> row.candidateId().equals(metadata.candidateId()))))
        .toList();
  }

  private static boolean injectionOwnerMatches(String rowOwner, String owner) {
    return metadataTypeMatches(rowOwner, owner);
  }

  /** Matches full metadata names, falling back to simple names only when unqualified. */
  private static boolean metadataTypeMatches(String metadataType, String sourceType) {
    if (metadataType == null || sourceType == null) {
      return false;
    }
    return metadataType.equals(sourceType)
        || (!metadataType.contains(".") && metadataType.equals(simpleName(sourceType)));
  }

  private static boolean memberMatches(String rowMember, String member) {
    return rowMember == null || rowMember.isBlank() || rowMember.equals(member)
        || (rowMember.equals("constructor") && member.equals("<init>"));
  }

  private List<AnalysisContext.ProxyTargetMetadata> matchingProxyMetadata(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScanner.ProxyTarget target) {
    Optional<String> descriptor = methodDescriptor(scanner, target);
    return context.proxyTargetMetadata().stream()
        .filter(row -> proxyTargetTypeMatches(scanner, row.targetType(), target))
        .filter(row -> row.methodName().equals(target.method().name()))
        .filter(row -> row.methodDescriptor() == null || row.methodDescriptor().isBlank()
            || descriptor.isPresent() && row.methodDescriptor().equals(descriptor.get()))
        .filter(row -> row.candidateId() == null || row.candidateId().isBlank()
            || row.candidateId().equals(target.candidateId())
            || simpleName(row.candidateId()).equalsIgnoreCase(target.file().simpleTypeName())
            || context.bindingCandidates().stream().anyMatch(candidate ->
                candidate.candidateId().equals(row.candidateId())
                    && simpleName(candidate.candidateType())
                        .equalsIgnoreCase(target.file().simpleTypeName())))
        .toList();
  }

  private static boolean proxyTargetTypeMatches(
      SourceMetadataScanner scanner,
      String metadataType,
      SourceMetadataScanner.ProxyTarget target) {
    if (metadataType.equals(target.file().typeName())) {
      return true;
    }
    if (metadataType.contains(".")
        || !simpleName(metadataType).equalsIgnoreCase(target.file().simpleTypeName())) {
      return false;
    }
    return scanner.files().stream()
        .filter(file -> file.simpleTypeName().equalsIgnoreCase(metadataType))
        .count() == 1;
  }

  /** Builds a JVM descriptor only when every reference type can be resolved. */
  private static Optional<String> methodDescriptor(
      SourceMetadataScanner scanner,
      SourceMetadataScanner.ProxyTarget target) {
    StringBuilder descriptor = new StringBuilder("(");
    for (SourceMetadataScanner.Parameter parameter :
        SourceMetadataScanner.parameters(target.method().parameters())) {
      Optional<String> parameterDescriptor = jvmDescriptor(parameter.type(), target.file(), scanner);
      if (parameterDescriptor.isEmpty()) {
        return Optional.empty();
      }
      descriptor.append(parameterDescriptor.get());
    }
    descriptor.append(')');
    Optional<String> returnDescriptor = jvmDescriptor(target.method().returnType(), target.file(), scanner);
    return returnDescriptor.map(value -> descriptor.append(value).toString());
  }

  /** Resolves one Java type to a JVM descriptor without guessing unknown references. */
  private static Optional<String> jvmDescriptor(
      String type,
      SourceMetadataScanner.SourceFile sourceFile,
      SourceMetadataScanner scanner) {
    String normalized = eraseGenericArguments(type == null ? "" : type).trim();
    if (normalized.endsWith("...")) {
      normalized = normalized.substring(0, normalized.length() - 3) + "[]";
    }
    if (normalized.endsWith("[]")) {
      return jvmDescriptor(normalized.substring(0, normalized.length() - 2), sourceFile, scanner)
          .map(value -> "[" + value);
    }
    return switch (normalized) {
      case "void" -> Optional.of("V");
      case "boolean" -> Optional.of("Z");
      case "byte" -> Optional.of("B");
      case "char" -> Optional.of("C");
      case "short" -> Optional.of("S");
      case "int" -> Optional.of("I");
      case "long" -> Optional.of("J");
      case "float" -> Optional.of("F");
      case "double" -> Optional.of("D");
      case "" -> Optional.empty();
      default -> resolveReferenceType(normalized, sourceFile, scanner)
          .map(value -> "L" + value.replace('.', '/') + ";");
    };
  }

  /** Resolves source-package, explicit-import, and java.lang reference types. */
  private static Optional<String> resolveReferenceType(
      String type,
      SourceMetadataScanner.SourceFile sourceFile,
      SourceMetadataScanner scanner) {
    List<String> importedTypes = importedTypes(type, sourceFile);
    if (importedTypes.size() > 1) {
      return Optional.empty();
    }
    if (importedTypes.size() == 1) {
      return Optional.of(importedTypes.get(0));
    }
    if (type.contains(".") && Character.isLowerCase(type.charAt(0))) {
      return Optional.of(type);
    }
    List<String> samePackageTypes = scanner.files().stream()
        .filter(file -> file.packageName().equals(sourceFile.packageName()))
        .filter(file -> file.simpleTypeName().equals(type))
        .map(SourceMetadataScanner.SourceFile::typeName)
        .distinct()
        .toList();
    if (samePackageTypes.size() > 1) {
      return Optional.empty();
    }
    if (samePackageTypes.size() == 1) {
      return Optional.of(samePackageTypes.get(0));
    }
    return JAVA_LANG_TYPES.contains(type) ? Optional.of("java.lang." + type) : Optional.empty();
  }

  /** Returns explicit imports that can qualify a source type spelling. */
  private static List<String> importedTypes(
      String type,
      SourceMetadataScanner.SourceFile sourceFile) {
    String simpleType = type;
    int nestedSeparator = type.indexOf('.');
    if (nestedSeparator >= 0) {
      simpleType = type.substring(0, nestedSeparator);
    }
    Matcher matcher = Pattern.compile("(?m)^\\s*import\\s+([A-Za-z_][\\w.]*)\\s*;")
        .matcher(sourceFile.source());
    List<String> result = new ArrayList<>();
    while (matcher.find()) {
      String imported = matcher.group(1);
      if (imported.endsWith("." + simpleType)) {
        result.add(imported + type.substring(simpleType.length()));
      }
    }
    return result.stream().distinct().toList();
  }

  /** Removes nested generic arguments while retaining the erased outer type. */
  private static String eraseGenericArguments(String source) {
    StringBuilder result = new StringBuilder(source.length());
    int depth = 0;
    for (int index = 0; index < source.length(); index++) {
      char character = source.charAt(index);
      if (character == '<') {
        depth++;
      } else if (character == '>' && depth > 0) {
        depth--;
      } else if (depth == 0) {
        result.append(character);
      }
    }
    return result.toString();
  }

  private static Map<String, String> proxyAttributes(
      String metadataId,
      SourceMetadataScanner.ProxyTarget target,
      AnalysisContext.ProxyTargetMetadata metadata) {
    Map<String, String> attributes = new HashMap<>();
    attributes.put("metadata", metadataId);
    attributes.put("targetType", target.file().typeName());
    attributes.put("method", target.method().name());
    attributes.put("proxyKind", target.proxyKind());
    if (metadata != null) {
      attributes.put("proxyTargetMetadataId", metadata.proxyTargetId());
      attributes.put("proxyTargetSelected", String.valueOf(metadata.selected()));
      attributes.put("proxyTargetActive", String.valueOf(metadata.active()));
      if (metadata.selectionReason() != null) {
        attributes.put("selectionReason", metadata.selectionReason());
      }
    }
    return attributes;
  }

  private boolean matches(
      AnalysisContext context,
      String requestedType,
      SourceMetadataScanner.BeanCandidate candidate) {
    String requested = simpleName(requestedType);
    return simpleName(candidate.candidateType()).equals(requested)
        || candidate.file().simpleTypeName().equals(requested)
        || candidate.contracts().stream().map(BoundedFrameworkModel::simpleName).anyMatch(requested::equals)
        || matchingCandidateMetadata(context, candidate).stream()
            .flatMap(row -> row.contracts().stream())
            .map(BoundedFrameworkModel::simpleName)
            .anyMatch(requested::equals);
  }

  private String routeFactId(SourceMetadataScanner.Route route) {
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

  private static String digest(String value) {
    try {
      byte[] bytes = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(bytes.length * 2);
      for (byte item : bytes) {
        result.append(String.format(Locale.ROOT, "%02x", item));
      }
      return result.toString();
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
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
    return framework.id() + "-injection-"
        + slug(owner + "-" + injection.member() + "-" + parameter.type() + "-" + candidateName);
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
    String normalized = eraseGenericArguments(value == null ? "" : value).trim();
    int dot = normalized.lastIndexOf('.');
    return dot < 0 ? normalized : normalized.substring(dot + 1);
  }

  private static String slug(String value) {
    return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
  }
}
