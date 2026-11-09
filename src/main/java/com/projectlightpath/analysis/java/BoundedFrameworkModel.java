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
abstract class BoundedFrameworkModel implements FrameworkModel {
  private final AnalysisContext.Framework framework;

  /** Creates a bounded model for one supported framework. */
  BoundedFrameworkModel(AnalysisContext.Framework framework) {
    this.framework = Objects.requireNonNull(framework, "framework");
  }

  @Override
  public final AnalysisContext.Framework framework() {
    return framework;
  }

  @Override
  /** Enriches the provider graph with framework-owned facts without replacing it. */
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

  /** Resolves bounded REST route candidates and their registration certainty. */
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

  /** Resolves constructor, field, and method injection candidates. */
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
    List<SourceMetadataScanner.InjectionPoint> injectionPoints =
        scanner.injectionPoints(framework);
    Map<String, Integer> injectionParameterCounts = new HashMap<>();
    for (SourceMetadataScanner.InjectionPoint injection : injectionPoints) {
      injectionParameterCounts.merge(
          injection.ownerType(), injection.parameters().size(), Integer::sum);
    }
    for (SourceMetadataScanner.InjectionPoint injection : injectionPoints) {
      boolean uniqueInjectionSite = injectionParameterCounts.getOrDefault(
          injection.ownerType(), 0) == 1;
      for (int parameterIndex = 0;
          parameterIndex < injection.parameters().size();
          parameterIndex++) {
        SourceMetadataScanner.Parameter parameter = injection.parameters().get(parameterIndex);
        List<SourceMetadataScanner.BeanCandidate> typeMatches = candidates.stream()
            .filter(candidate -> matches(context, scanner, injection.file(), parameter.type(), candidate))
            .filter(candidate -> profileMatches(candidate.profiles()) != Boolean.FALSE)
            .toList();
        List<SourceMetadataScanner.BeanCandidate> matches = typeMatches.stream()
            .filter(candidate -> candidateMetadataActive(context, scanner, candidate) != Boolean.FALSE)
            .toList();
        matches = qualifierMatches(context, scanner, parameter, injection, matches);

        List<SourceMetadataScanner.BeanCandidate> qualifiedTypeMatches = qualifierMatches(
            context, scanner, parameter, injection, typeMatches);
        List<AnalysisContext.BindingCandidateMetadata> metadataOnlyMatches = metadataOnlyCandidates(
            context,
            scanner,
            injection.file(),
            parameter.type(),
            mergeQualifiers(injection.qualifiers(), parameter.qualifiers()),
            candidates);
        long activeMetadataOnlyCount = metadataOnlyMatches.stream()
            .filter(row -> row.active() != Boolean.FALSE)
            .map(AnalysisContext.BindingCandidateMetadata::candidateId)
            .distinct()
            .count();
        long eligibleCandidateCount = qualifiedTypeMatches.size() + activeMetadataOnlyCount;
        boolean metadataOnlyCandidatesInactive = metadataOnlyMatches.stream()
            .allMatch(row -> row.active() == Boolean.FALSE);

        List<AnalysisContext.ResolvedBindingMetadata> bindingRows = matchingBindings(
            context, scanner, injection, parameter, parameterIndex, candidates);
        List<AnalysisContext.ResolvedBindingMetadata> selectedRows = bindingRows.stream()
            .filter(row -> row.active() == Boolean.TRUE)
            .filter(row -> row.selected() == Boolean.TRUE)
            .toList();
        if (!selectedRows.isEmpty()) {
          List<SourceMetadataScanner.BeanCandidate> activeCandidates = candidates.stream()
              .filter(candidate -> profileMatches(candidate.profiles()) != Boolean.FALSE)
              .filter(candidate -> candidateMetadataActive(context, scanner, candidate) != Boolean.FALSE)
              .toList();
          matches = activeCandidates.stream()
              .filter(candidate -> selectedRows.stream().anyMatch(row ->
                  row.candidateId().equals(candidate.candidateId())
                      || matchingCandidateMetadata(context, scanner, candidate).stream()
                          .anyMatch(metadata -> row.candidateId().equals(metadata.candidateId()))))
              .filter(candidate -> qualifierMatches(
                  context, scanner, parameter, injection, activeCandidates).contains(candidate))
              .toList();
        } else if (!bindingRows.isEmpty()
            && bindingRows.stream().allMatch(row -> row.selected() == Boolean.FALSE
                || row.active() == Boolean.FALSE)) {
          matches = List.of();
        }
        if (selectedRows.isEmpty()) {
          matches = bindingPreference(context, scanner, matches);
        }
        boolean legacyIdAllowed = uniqueInjectionSite && matches.size() == 1;

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
              List.of(),
              null,
              scanner,
              legacyIdAllowed,
              "No active bounded bean candidate matches the injection type, qualifiers, profile, or binding metadata."));
          continue;
        }

        boolean exactBindingSelection = selectedRows.size() == 1 && matches.size() == 1;
        List<AnalysisContext.BindingCandidateMetadata> selectedCandidateMetadata =
            matchingCandidateMetadata(context, scanner, matches.get(0));
        boolean exactCandidateSelection = matches.size() == 1
            && eligibleCandidateCount == 1
            && candidateMetadataActive(context, scanner, matches.get(0)) == Boolean.TRUE
            && selectedCandidateMetadata.size() == 1
            && selectedCandidateMetadata.get(0).active() == Boolean.TRUE
            && typeMatchCertain(context, scanner, injection.file(), parameter.type(), matches.get(0));
        boolean metadataCandidateCoverage = qualifiedTypeMatches.stream()
            .allMatch(candidate -> matchingCandidateMetadata(context, scanner, candidate).size() == 1);
        List<SourceMetadataScanner.BeanCandidate> selectedMatches = List.copyOf(matches);
        boolean metadataSelectsOnlyMatch = qualifiedTypeMatches.stream().allMatch(candidate -> {
          Boolean active = candidateMetadataActive(context, scanner, candidate);
          return selectedMatches.contains(candidate) ? active == Boolean.TRUE : active == Boolean.FALSE;
        });
        boolean exactActiveSet = matches.size() == 1
            && eligibleCandidateCount > 1
            && metadataCandidateCoverage
            && metadataSelectsOnlyMatch
            && metadataOnlyCandidatesInactive
            && typeMatchCertain(context, scanner, injection.file(), parameter.type(), matches.get(0));
        boolean conditionUnknown = matches.stream().anyMatch(candidate -> hasConditions(context, scanner, candidate))
            && matches.stream().anyMatch(candidate -> candidateMetadataActive(context, scanner, candidate) == null);
        boolean profileUnknown = matches.stream()
            .anyMatch(candidate -> profileSelectionUnknown(context, scanner, candidate));
        SemanticCallFacts.Certainty certainty;
        if (conditionUnknown || profileUnknown || !context.metadataObserved(sourceMetadata)) {
          certainty = SemanticCallFacts.Certainty.UNRESOLVED;
        } else if (exactBindingSelection || exactCandidateSelection || exactActiveSet) {
          certainty = SemanticCallFacts.Certainty.DEFINITE;
        } else {
          certainty = SemanticCallFacts.Certainty.POSSIBLE;
        }
        for (SourceMetadataScanner.BeanCandidate candidate : matches) {
          AnalysisContext.BindingCandidateMetadata candidateMetadata = matchingCandidateMetadata(
              context, scanner, candidate).stream().findFirst().orElse(null);
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
              : conditionUnknown || profileUnknown
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
              bindingRows,
              parameterIndex,
              scanner,
              legacyIdAllowed,
              limitation));
        }
      }
    }
    return facts;
  }

  /** Builds one framework injection fact and its candidate evidence. */
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
      List<AnalysisContext.ResolvedBindingMetadata> bindingRows,
      Integer parameterIndex,
      SourceMetadataScanner scanner,
      boolean legacyIdAllowed,
      String limitation) {
    String owner = injection.ownerType();
    String target = candidate == null
        ? parameter.type() + "#<unresolved-bean>"
        : candidate.target();
    String callerPrefix = framework == AnalysisContext.Framework.QUARKUS ? "quarkus" : "spring";
    String id = injectionFactId(scanner, injection, parameter, candidate, legacyIdAllowed);
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
      if (candidate.producer()) {
        producerDescriptor(scanner, candidate)
            .ifPresent(descriptor -> attributes.put("targetDescriptor", descriptor));
      }
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
      List<String> candidateSymbols = new ArrayList<>(scanner.sourceAnnotations(candidate, framework));
      candidateSymbols.add("candidate=" + candidate.candidateType());
      evidence.add(new SemanticCallFacts.Evidence(
          "bean_candidate",
          candidate.file().displayPath(),
          candidateSymbols,
          null,
          attributes));
    }
    String providerFactId = candidate == null
        ? null
        : unambiguousInjectionProviderFactId(context, scanner, candidate, bindingRows);
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

  /** Returns a provider identity only when all active matching metadata agrees. */
  private String unambiguousInjectionProviderFactId(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScanner.BeanCandidate candidate,
      List<AnalysisContext.ResolvedBindingMetadata> bindingRows) {
    List<AnalysisContext.BindingCandidateMetadata> metadataRows = matchingCandidateMetadata(
        context, scanner, candidate).stream()
        .filter(row -> row.active() != Boolean.FALSE)
        .toList();
    Set<String> providerIds = new LinkedHashSet<>();
    int identitySources = 0;
    for (AnalysisContext.ResolvedBindingMetadata row : bindingRows.stream()
        .filter(row -> row.active() != Boolean.FALSE)
        .filter(row -> row.selected() != Boolean.FALSE)
        .filter(row -> row.candidateId().equals(candidate.candidateId())
            || metadataRows.stream().anyMatch(metadata ->
                row.candidateId().equals(metadata.candidateId())))
        .toList()) {
      identitySources++;
      if (row.providerFactId() == null || row.providerFactId().isBlank()) {
        return null;
      }
      providerIds.add(row.providerFactId());
    }
    for (AnalysisContext.BindingCandidateMetadata row : metadataRows) {
      identitySources++;
      if (row.providerFactId() == null || row.providerFactId().isBlank()) {
        return null;
      }
      providerIds.add(row.providerFactId());
    }
    return identitySources > 0 && providerIds.size() == 1
        ? providerIds.iterator().next()
        : null;
  }

  /** Resolves source and metadata-backed framework proxy targets. */
  private List<SemanticCallFacts.Fact> proxyFacts(
      AnalysisContext context, SourceMetadataScanner scanner) {
    List<SemanticCallFacts.Fact> facts = new ArrayList<>();
    String metadataId = framework == AnalysisContext.Framework.QUARKUS
        ? "quarkus-cdi-annotations"
        : "spring-aop-advisor-metadata";
    List<SourceMetadataScanner.BeanCandidate> owners = scanner.beanCandidates(framework);
    for (SourceMetadataScanner.ProxyTarget target : scanner.proxyTargets(framework)) {
      String sourceMetadata = framework == AnalysisContext.Framework.QUARKUS
          ? "quarkus-cdi-annotations"
          : "spring-bean-annotations";
      SourceMetadataScanner.BeanCandidate beanOwner = owners.stream()
          .filter(candidate -> !candidate.producer())
          .filter(candidate -> candidate.file().typeName().equals(target.file().typeName()))
          .findFirst()
          .orElse(null);
      if (beanOwner != null
          && (profileMatches(beanOwner.profiles()) == Boolean.FALSE
              || candidateMetadataActive(context, scanner, beanOwner) == Boolean.FALSE)) {
        continue;
      }
      List<AnalysisContext.ProxyTargetMetadata> metadataRows = matchingProxyMetadata(context, scanner, target);
      boolean exactMetadataTarget = metadataRows.stream()
          .anyMatch(row -> row.active() == Boolean.TRUE
              && row.selected() == Boolean.TRUE
              && !descriptorlessProxyRowAmbiguous(scanner, target, row));
      if (framework == AnalysisContext.Framework.QUARKUS
          && !target.normalScoped()
          && !exactMetadataTarget) {
        continue;
      }
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
          && activeRows.get(0).active() == Boolean.TRUE
          && !descriptorlessProxyRowAmbiguous(scanner, target, activeRows.get(0));
      boolean profileUnknown = beanOwner != null
          && profileSelectionUnknown(context, scanner, beanOwner);
      SemanticCallFacts.Certainty certainty = profileUnknown || !context.metadataObserved(sourceMetadata)
          ? SemanticCallFacts.Certainty.UNRESOLVED
          : exact
              ? SemanticCallFacts.Certainty.DEFINITE
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
      String id = proxyFactId(scanner, target);
      List<String> proxySymbols = new ArrayList<>(scanner.sourceAnnotations(target, framework));
      proxySymbols.add(target.method().name());
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
              proxySymbols,
              target.method().line(),
              proxyAttributes(
                  metadataId,
                  target,
                  metadata,
                  methodDescriptor(scanner, target).orElse(null)))),
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
          unambiguousProxyProviderFactId(activeRows, scanner, target)));
    }
    return facts;
  }

  /** Returns a proxy provider identity only when all active matching rows agree. */
  private static String unambiguousProxyProviderFactId(
      List<AnalysisContext.ProxyTargetMetadata> activeRows,
      SourceMetadataScanner scanner,
      SourceMetadataScanner.ProxyTarget target) {
    if (activeRows.isEmpty()
        || activeRows.stream().anyMatch(row -> descriptorlessProxyRowAmbiguous(scanner, target, row))
        || activeRows.stream().anyMatch(row -> row.providerFactId() == null
            || row.providerFactId().isBlank())) {
      return null;
    }
    Set<String> providerIds = activeRows.stream()
        .map(AnalysisContext.ProxyTargetMetadata::providerFactId)
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    return providerIds.size() == 1 ? providerIds.iterator().next() : null;
  }

  /** Rejects descriptor-less proxy metadata when the source method is overloaded. */
  private static boolean descriptorlessProxyRowAmbiguous(
      SourceMetadataScanner scanner,
      SourceMetadataScanner.ProxyTarget target,
      AnalysisContext.ProxyTargetMetadata row) {
    return (row.methodDescriptor() == null || row.methodDescriptor().isBlank())
        && scanner.proxyMethodOverloaded(target);
  }

  /** Emits Quarkus generated-source relationships when metadata supports them. */
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
          generatedFactId(scanner, generated),
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

  /** Preserves open-world dynamic proxy construction as unresolved evidence. */
  private List<SemanticCallFacts.Fact> dynamicProxyFacts(
      AnalysisContext context, SourceMetadataScanner scanner) {
    List<SemanticCallFacts.Fact> facts = new ArrayList<>();
    Set<String> factIds = new LinkedHashSet<>();
    for (DynamicFeature feature : scanner.dynamicFeatures()) {
      if (feature.kind() != DynamicFeature.Kind.DYNAMIC_PROXY) {
        continue;
      }
      String targetOwner = feature.target().contains("#")
          ? feature.target().substring(0, feature.target().indexOf('#'))
          : feature.target();
      String caller = framework.id() + ":dynamic-proxy:" + targetOwner;
      String factId = uniqueFactId(
          factIds,
          dynamicProxyFactId(scanner, feature),
          dynamicFeatureIdentity(feature));
      facts.add(SemanticCallFacts.Fact.framework(
          factId,
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
              feature.sourceLine() > 0 ? feature.sourceLine() : null,
              dynamicProxyEvidenceAttributes(feature))),
          feature.limitation()));
    }
    return facts;
  }

  /** Emits bounded Spring Data repository implementation candidates. */
  private List<SemanticCallFacts.Fact> springRepositoryFacts(
      AnalysisContext context, SourceMetadataScanner scanner) {
    List<SemanticCallFacts.Fact> facts = new ArrayList<>();
    boolean metadataAvailable = context.metadataObserved("spring-data-repository-contract");
    List<SourceMetadataScanner.BeanCandidate> candidates = scanner.beanCandidates(framework);
    for (SourceMetadataScanner.BeanCandidate candidate : candidates) {
      if (!candidate.kind().equals("repository_interface")) {
        continue;
      }
      SemanticCallFacts.Certainty certainty = metadataAvailable
          ? SemanticCallFacts.Certainty.POSSIBLE
          : SemanticCallFacts.Certainty.UNRESOLVED;
      String id = springRepositoryFactId(scanner, candidates, candidate);
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

  /** Emits bounded Spring lifecycle callback candidates. */
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
              lifecycleFactId(scanner, file, method, "bean"),
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
              lifecycleFactId(scanner, file, method, "runner"),
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

  /** Filters candidates by requested framework qualifier semantics. */
  private List<SourceMetadataScanner.BeanCandidate> qualifierMatches(
      AnalysisContext context,
      SourceMetadataScanner scanner,
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
          .filter(candidate -> candidateQualifiers(context, scanner, candidate).stream()
              .noneMatch(this::explicitQualifier))
          .toList();
    }
    return candidates.stream()
        .filter(candidate -> requested.stream().allMatch(requestedQualifier ->
            qualifierMatches(context, scanner, requestedQualifier, candidate, candidates)))
        .toList();
  }

  /** Applies framework qualifier rules, including Spring bean-name fallbacks. */
  private boolean qualifierMatches(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      String requestedQualifier,
      SourceMetadataScanner.BeanCandidate candidate,
      List<SourceMetadataScanner.BeanCandidate> candidates) {
    if (requestedQualifier.startsWith("ResourceDefault:")) {
      String name = requestedQualifier.substring("ResourceDefault:".length());
      boolean namedCandidateExists = candidates.stream()
          .anyMatch(item -> resourceNameMatches(context, scanner, name, item));
      return !namedCandidateExists || resourceNameMatches(context, scanner, name, candidate);
    }
    if (requestedQualifier.startsWith("Resource:")) {
      return resourceNameMatches(
          context,
          scanner,
          requestedQualifier.substring("Resource:".length()),
          candidate);
    }
    if (framework == AnalysisContext.Framework.SPRING_BOOT
        && requestedQualifier.startsWith("Qualifier:")) {
      String value = requestedQualifier.substring("Qualifier:".length());
      return candidateQualifiers(context, scanner, candidate).stream()
          .anyMatch(candidateQualifier -> qualifierEquals(requestedQualifier, candidateQualifier))
          || candidateBeanNames(context, scanner, candidate).contains(value);
    }
    return candidateQualifiers(context, scanner, candidate).stream()
        .anyMatch(candidateQualifier -> qualifierEquals(requestedQualifier, candidateQualifier));
  }

  /** Combines source and metadata qualifiers for one candidate. */
  private static List<String> candidateQualifiers(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScanner.BeanCandidate candidate) {
    LinkedHashSet<String> result = new LinkedHashSet<>(candidate.qualifiers());
    context.bindingCandidates().stream()
        .filter(row -> candidateMetadataMatches(scanner, row, candidate))
        .flatMap(row -> row.qualifiers().stream())
        .forEach(result::add);
    return List.copyOf(result);
  }

  /** Matches a Spring resource name against candidate bean names. */
  private static boolean resourceNameMatches(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      String name,
      SourceMetadataScanner.BeanCandidate candidate) {
    return candidateBeanNames(context, scanner, candidate).contains(name);
  }

  /** Returns explicit and convention-based names that can identify a bean. */
  private static Set<String> candidateBeanNames(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScanner.BeanCandidate candidate) {
    Set<String> names = new LinkedHashSet<>();
    String defaultName = candidate.producer()
        ? candidate.producerMethod()
        : decapitalize(candidate.file().simpleTypeName());
    if (defaultName != null && !defaultName.isBlank()) {
      names.add(defaultName);
    }
    candidateQualifiers(context, scanner, candidate).stream()
        .filter(qualifier -> qualifier.startsWith("BeanName:")
            || qualifier.startsWith("Named:")
            || qualifier.startsWith("Qualifier:"))
        .map(qualifier -> qualifier.substring(qualifier.indexOf(':') + 1))
        .forEach(names::add);
    return Set.copyOf(names);
  }

  /** Applies primary or enabled-alternative preference when binding metadata did not select. */
  private List<SourceMetadataScanner.BeanCandidate> bindingPreference(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      List<SourceMetadataScanner.BeanCandidate> candidates) {
    if (candidates.size() < 2) {
      return candidates;
    }
    if (framework == AnalysisContext.Framework.SPRING_BOOT) {
      List<SourceMetadataScanner.BeanCandidate> primary = candidates.stream()
          .filter(candidate -> candidate.primary() || matchingCandidateMetadata(context, scanner, candidate).stream()
              .anyMatch(AnalysisContext.BindingCandidateMetadata::primary))
          .toList();
      return primary.size() == 1 ? primary : candidates;
    }
    List<SourceMetadataScanner.BeanCandidate> alternatives = candidates.stream()
        .filter(candidate -> matchingCandidateMetadata(context, scanner, candidate).stream()
            .anyMatch(row -> row.alternative() && row.active() == Boolean.TRUE))
        .toList();
    return alternatives.isEmpty() ? candidates : alternatives;
  }

  /** Identifies qualifiers that exclude an unqualified CDI injection point. */
  private boolean explicitQualifier(String qualifier) {
    String normalized = qualifier.toLowerCase(Locale.ROOT);
    return !normalized.equals("default")
        && !normalized.equals("any")
        && !normalized.startsWith("named:")
        && !normalized.startsWith("beanname:");
  }

  /** Compares normalized qualifier kind and value without suffix matching. */
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

  /** Merges qualifier lists while preserving their first-seen order. */
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

  /** Returns known source-profile state without treating a Maven profile as a Spring profile. */
  private static Boolean profileMatches(List<String> profiles) {
    if (profiles == null || profiles.isEmpty()) {
      return Boolean.TRUE;
    }
    return null;
  }

  /** Reports source-profile uncertainty when evaluated candidate metadata is absent. */
  private boolean profileSelectionUnknown(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScanner.BeanCandidate candidate) {
    List<AnalysisContext.BindingCandidateMetadata> rows = matchingCandidateMetadata(
        context, scanner, candidate);
    if (!candidate.profiles().isEmpty()) {
      return rows.isEmpty() || rows.stream().anyMatch(row -> row.active() == null);
    }
    return rows.stream().anyMatch(row -> !row.profiles().isEmpty() && row.active() == null);
  }

  /** Reports whether source or metadata leaves condition selection to runtime. */
  private boolean hasConditions(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScanner.BeanCandidate candidate) {
    return !candidate.conditions().isEmpty()
        || matchingCandidateMetadata(context, scanner, candidate).stream()
            .anyMatch(row -> !row.conditions().isEmpty());
  }

  /** Combines active, inactive, and unknown candidate metadata states. */
  private Boolean candidateMetadataActive(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScanner.BeanCandidate candidate) {
    List<AnalysisContext.BindingCandidateMetadata> rows = matchingCandidateMetadata(context, scanner, candidate);
    if (rows.isEmpty()) {
      return null;
    }
    boolean knownActive = false;
    boolean knownInactive = false;
    boolean unknown = false;
    for (AnalysisContext.BindingCandidateMetadata row : rows) {
      if (row.active() == Boolean.FALSE) {
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

  /** Finds metadata rows belonging to one source candidate. */
  private List<AnalysisContext.BindingCandidateMetadata> matchingCandidateMetadata(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScanner.BeanCandidate candidate) {
    return context.bindingCandidates().stream()
        .filter(row -> candidateMetadataMatches(scanner, row, candidate))
        .toList();
  }

  /** Finds type-compatible metadata candidates that are not represented by scanned source. */
  private List<AnalysisContext.BindingCandidateMetadata> metadataOnlyCandidates(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScanner.SourceFile injectionFile,
      String requestedType,
      List<String> requestedQualifiers,
      List<SourceMetadataScanner.BeanCandidate> scannedCandidates) {
    return context.bindingCandidates().stream()
        .filter(row -> scannedCandidates.stream()
            .noneMatch(candidate -> candidateMetadataMatches(scanner, row, candidate)))
        .filter(row -> metadataCandidateTypeMatches(scanner, injectionFile, requestedType, row))
        .filter(row -> metadataCandidateQualifierMatches(requestedQualifiers, row))
        .toList();
  }

  /** Matches an external metadata candidate by concrete type or declared contract. */
  private static boolean metadataCandidateTypeMatches(
      SourceMetadataScanner scanner,
      SourceMetadataScanner.SourceFile injectionFile,
      String requestedType,
      AnalysisContext.BindingCandidateMetadata row) {
    Optional<String> requested = canonicalType(requestedType, injectionFile, scanner);
    if (requested.isEmpty()) {
      return false;
    }
    if (canonicalType(row.candidateType(), injectionFile, scanner)
        .map(requested.get()::equals)
        .orElse(false)) {
      return true;
    }
    return row.contracts().stream()
        .map(contract -> canonicalType(contract, injectionFile, scanner))
        .anyMatch(value -> value.isPresent() && value.get().equals(requested.get()));
  }

  /** Applies only conservative metadata qualifier filtering for external candidates. */
  private boolean metadataCandidateQualifierMatches(
      List<String> requestedQualifiers,
      AnalysisContext.BindingCandidateMetadata row) {
    if (framework != AnalysisContext.Framework.QUARKUS) {
      return true;
    }
    if (requestedQualifiers.isEmpty()) {
      return row.qualifiers().stream().noneMatch(this::explicitQualifier);
    }
    return requestedQualifiers.stream().allMatch(requested -> row.qualifiers().stream()
        .anyMatch(candidate -> bindingQualifierEquals(requested, candidate)));
  }

  /** Matches candidate identity, canonical type, declaring type, and producer method. */
  private static boolean candidateMetadataMatches(
      SourceMetadataScanner scanner,
      AnalysisContext.BindingCandidateMetadata row,
      SourceMetadataScanner.BeanCandidate candidate) {
    if (row.candidateId().equals(candidate.candidateId())) {
      return true;
    }
    if (candidate.producer() != producerMetadataRow(row)) {
      return false;
    }
    if (!metadataTypeMatches(scanner, row.candidateType(), candidate.candidateType())) {
      return false;
    }
    if (row.declaringType() != null && !row.declaringType().isBlank()
        && !metadataTypeMatches(scanner, row.declaringType(), candidate.file().typeName())) {
      return false;
    }
    if (row.producerMethod() != null && !row.producerMethod().isBlank()) {
      if (!candidate.producer() || !row.producerMethod().equals(candidate.producerMethod())) {
        return false;
      }
      return producerSignatureMatches(scanner, row, candidate);
    }
    if (row.producerSignature() != null && !row.producerSignature().isBlank()) {
      return candidate.producer() && producerSignatureMatches(scanner, row, candidate);
    }
    return !candidate.producer() || producerOverloadCount(candidate) == 1;
  }

  /** Distinguishes producer metadata from ordinary component/class registration rows. */
  private static boolean producerMetadataRow(AnalysisContext.BindingCandidateMetadata row) {
    return row.producerMethod() != null && !row.producerMethod().isBlank()
        || row.producerSignature() != null && !row.producerSignature().isBlank()
        || row.kind() != null && row.kind().toLowerCase(Locale.ROOT).contains("producer");
  }

  /** Matches a producer metadata descriptor without conflating overloaded methods. */
  private static boolean producerSignatureMatches(
      SourceMetadataScanner scanner,
      AnalysisContext.BindingCandidateMetadata row,
      SourceMetadataScanner.BeanCandidate candidate) {
    if (row.producerSignature() == null || row.producerSignature().isBlank()) {
      return producerOverloadCount(candidate) == 1;
    }
    return producerDescriptor(scanner, candidate)
        .map(row.producerSignature()::equals)
        .orElse(false);
  }

  /** Counts declarations sharing a producer method name for conservative fallback matching. */
  private static long producerOverloadCount(SourceMetadataScanner.BeanCandidate candidate) {
    return SourceMetadataScanner.methods(candidate.file()).stream()
        .filter(method -> method.name().equals(candidate.producerMethod()))
        .count();
  }

  /** Finds resolved binding rows for one source injection parameter. */
  private List<AnalysisContext.ResolvedBindingMetadata> matchingBindings(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScanner.InjectionPoint injection,
      SourceMetadataScanner.Parameter parameter,
      int parameterIndex,
      List<SourceMetadataScanner.BeanCandidate> candidates) {
    return context.resolvedBindings().stream()
        .filter(row -> injectionOwnerMatches(scanner, row.injectionOwner(), injection.ownerType()))
        .filter(row -> memberMatches(row.injectionMember(), injection.member()))
        .filter(row -> injectionTypesMatch(scanner, injection.file(), row.injectionType(), parameter.type()))
        .filter(row -> row.parameterIndex() == null || row.parameterIndex() == parameterIndex)
        .filter(row -> row.qualifiers().isEmpty()
            || mergeQualifiers(injection.qualifiers(), parameter.qualifiers()).isEmpty()
            || mergeQualifiers(injection.qualifiers(), parameter.qualifiers()).stream()
                .anyMatch(requested -> row.qualifiers().stream()
                    .anyMatch(candidateQualifier -> bindingQualifierEquals(requested, candidateQualifier))))
        .filter(row -> candidates.stream().anyMatch(candidate ->
            row.candidateId().equals(candidate.candidateId())
                || matchingCandidateMetadata(context, scanner, candidate).stream()
                    .anyMatch(metadata -> row.candidateId().equals(metadata.candidateId()))))
        .toList();
  }

  /** Matches qualified owners exactly and permits simple names only in metadata. */
  private static boolean injectionOwnerMatches(
      SourceMetadataScanner scanner,
      String rowOwner,
      String owner) {
    return metadataTypeMatches(scanner, rowOwner, owner);
  }

  /** Matches full metadata names, falling back to simple names only when unqualified. */
  private static boolean metadataTypeMatches(
      SourceMetadataScanner scanner,
      String metadataType,
      String sourceType) {
    if (metadataType == null || sourceType == null) {
      return false;
    }
    String normalizedMetadata = eraseGenericArguments(metadataType).trim();
    String normalizedSource = eraseGenericArguments(sourceType).trim();
    return normalizedMetadata.equals(normalizedSource)
        || (!normalizedMetadata.contains(".")
            && normalizedMetadata.equals(simpleName(normalizedSource))
            && scanner.files().stream()
                .filter(file -> file.simpleTypeName().equalsIgnoreCase(normalizedMetadata))
                .count() <= 1);
  }

  /** Matches constructor aliases and optional member metadata. */
  private static boolean memberMatches(String rowMember, String member) {
    return rowMember == null || rowMember.isBlank() || rowMember.equals(member)
        || (rowMember.equals("constructor") && member.equals("<init>"));
  }

  /** Finds proxy rows using target, overload descriptor, and candidate identity. */
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
                && scanner.files().stream()
                    .filter(file -> file.simpleTypeName().equalsIgnoreCase(target.file().simpleTypeName()))
                    .count() == 1
            || context.bindingCandidates().stream().anyMatch(candidate ->
                candidate.candidateId().equals(row.candidateId())
                    && simpleName(candidate.candidateType())
                        .equalsIgnoreCase(target.file().simpleTypeName())))
        .toList();
  }

  /** Matches qualified proxy types or unique unqualified source types. */
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
    return methodDescriptor(scanner, target.file(), target.method().parameters(), target.method().returnType());
  }

  /** Builds a JVM descriptor from one source method signature. */
  private static Optional<String> methodDescriptor(
      SourceMetadataScanner scanner,
      SourceMetadataScanner.SourceFile sourceFile,
      String parameters,
      String returnType) {
    StringBuilder descriptor = new StringBuilder("(");
    for (SourceMetadataScanner.Parameter parameter : SourceMetadataScanner.parameters(parameters)) {
      Optional<String> parameterDescriptor = jvmDescriptor(parameter.type(), sourceFile, scanner);
      if (parameterDescriptor.isEmpty()) {
        return Optional.empty();
      }
      descriptor.append(parameterDescriptor.get());
    }
    descriptor.append(')');
    Optional<String> returnDescriptor = jvmDescriptor(returnType, sourceFile, scanner);
    return returnDescriptor.map(value -> descriptor.append(value).toString());
  }

  /** Builds a JVM descriptor for a source producer candidate. */
  private static Optional<String> producerDescriptor(
      SourceMetadataScanner scanner,
      SourceMetadataScanner.BeanCandidate candidate) {
    return methodDescriptor(
        scanner,
        candidate.file(),
        candidate.producerParameters(),
        candidate.producerSignature());
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
      default -> scanner.resolveReferenceType(normalized, sourceFile)
          .map(value -> "L" + value.replace('.', '/') + ";");
    };
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
      AnalysisContext.ProxyTargetMetadata metadata,
      String targetDescriptor) {
    Map<String, String> attributes = new HashMap<>();
    attributes.put("metadata", metadataId);
    attributes.put("targetType", target.file().typeName());
    attributes.put("method", target.method().name());
    attributes.put("proxyKind", target.proxyKind());
    if (targetDescriptor != null && !targetDescriptor.isBlank()) {
      attributes.put("targetDescriptor", targetDescriptor);
    }
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

  /** Tests qualified injection types, producer generic arguments, and contracts. */
  private boolean matches(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScanner.SourceFile injectionFile,
      String requestedType,
      SourceMetadataScanner.BeanCandidate candidate) {
    Boolean directCompatibility = directTypeCompatibility(
        scanner, injectionFile, requestedType, candidate);
    if (directCompatibility == Boolean.TRUE) {
      return true;
    }
    if (directCompatibility == null) {
      return contractMatches(context, scanner, injectionFile, requestedType, candidate);
    }
    return directTypesHaveDifferentRawTypes(scanner, injectionFile, requestedType, candidate)
        && contractMatches(context, scanner, injectionFile, requestedType, candidate);
  }

  /** Returns whether source and metadata establish a type match strongly enough for definiteness. */
  private boolean typeMatchCertain(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScanner.SourceFile injectionFile,
      String requestedType,
      SourceMetadataScanner.BeanCandidate candidate) {
    Boolean directCompatibility = directTypeCompatibility(
        scanner, injectionFile, requestedType, candidate);
    if (directCompatibility == Boolean.TRUE) {
      return true;
    }
    if (candidate.producer() && candidate.candidateType().isBlank()) {
      return false;
    }
    return directCompatibility == Boolean.FALSE
        && directTypesHaveDifferentRawTypes(scanner, injectionFile, requestedType, candidate)
        && contractMatches(context, scanner, injectionFile, requestedType, candidate);
  }

  /** Compares erased raw types before checking known generic arguments. */
  private static Boolean directTypeCompatibility(
      SourceMetadataScanner scanner,
      SourceMetadataScanner.SourceFile injectionFile,
      String requestedType,
      SourceMetadataScanner.BeanCandidate candidate) {
    if (candidate.producer() && candidate.candidateType().isBlank()) {
      return null;
    }
    String candidateType = candidate.producer()
        ? candidate.producerSignature()
        : candidate.candidateType();
    if (candidateType == null || candidateType.isBlank()) {
      return null;
    }
    Optional<String> requestedRaw = canonicalType(
        eraseGenericArguments(requestedType), injectionFile, scanner);
    Optional<String> candidateRaw = canonicalType(
        eraseGenericArguments(candidateType), candidate.file(), scanner);
    if (requestedRaw.isEmpty() || candidateRaw.isEmpty()) {
      return null;
    }
    if (!requestedRaw.get().equals(candidateRaw.get())) {
      return Boolean.FALSE;
    }
    if (!hasGenericArguments(requestedType)) {
      return Boolean.TRUE;
    }
    if (!candidate.producer() || !hasGenericArguments(candidate.producerSignature())) {
      return null;
    }
    Optional<String> requestedCanonical = canonicalType(requestedType, injectionFile, scanner);
    Optional<String> candidateCanonical = canonicalType(
        candidate.producerSignature(), candidate.file(), scanner);
    if (requestedCanonical.isEmpty() || candidateCanonical.isEmpty()) {
      return null;
    }
    return requestedCanonical.get().equals(candidateCanonical.get());
  }

  /** Checks whether candidate and requested types have different resolved raw types. */
  private static boolean directTypesHaveDifferentRawTypes(
      SourceMetadataScanner scanner,
      SourceMetadataScanner.SourceFile injectionFile,
      String requestedType,
      SourceMetadataScanner.BeanCandidate candidate) {
    if (candidate.producer() && candidate.candidateType().isBlank()) {
      return false;
    }
    String candidateType = candidate.producer()
        ? candidate.producerSignature()
        : candidate.candidateType();
    if (candidateType == null || candidateType.isBlank()) {
      return false;
    }
    Optional<String> requestedRaw = canonicalType(
        eraseGenericArguments(requestedType), injectionFile, scanner);
    Optional<String> candidateRaw = canonicalType(
        eraseGenericArguments(candidateType), candidate.file(), scanner);
    return requestedRaw.isPresent() && candidateRaw.isPresent()
        && !requestedRaw.get().equals(candidateRaw.get());
  }

  /** Matches source and metadata contracts without using an ambiguous package suffix. */
  private boolean contractMatches(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      SourceMetadataScanner.SourceFile injectionFile,
      String requestedType,
      SourceMetadataScanner.BeanCandidate candidate) {
    Optional<String> requested = canonicalType(
        eraseGenericArguments(requestedType), injectionFile, scanner);
    if (requested.isEmpty()) {
      return false;
    }
    boolean sourceContract = candidate.contracts().stream()
        .map(contract -> canonicalType(contract, candidate.file(), scanner))
        .anyMatch(value -> value.isPresent() && value.get().equals(requested.get()));
    boolean metadataContract = matchingCandidateMetadata(context, scanner, candidate).stream()
        .flatMap(row -> row.contracts().stream())
        .map(contract -> scanner.resolveContractType(contract, candidate.file()))
        .anyMatch(value -> value.isPresent() && value.get().equals(requested.get()));
    return sourceContract || metadataContract;
  }

  /** Resolves a complete type expression, retaining canonical generic arguments. */
  private static Optional<String> canonicalType(
      String source,
      SourceMetadataScanner.SourceFile sourceFile,
      SourceMetadataScanner scanner) {
    String normalized = source == null ? "" : source.trim().replaceAll("\\s+", "");
    if (normalized.isBlank() || normalized.startsWith("?")) {
      return Optional.empty();
    }
    if (normalized.endsWith("[]")) {
      return canonicalType(normalized.substring(0, normalized.length() - 2), sourceFile, scanner)
          .map(value -> value + "[]");
    }
    int opening = normalized.indexOf('<');
    if (opening < 0) {
      return primitiveType(normalized).or(() -> scanner.resolveReferenceType(normalized, sourceFile));
    }
    int closing = matchingGenericDelimiter(normalized, opening);
    if (closing != normalized.length() - 1) {
      return Optional.empty();
    }
    Optional<String> raw = canonicalType(normalized.substring(0, opening), sourceFile, scanner);
    if (raw.isEmpty()) {
      return Optional.empty();
    }
    List<String> arguments = splitGenericArguments(
        normalized.substring(opening + 1, closing));
    List<String> canonicalArguments = new ArrayList<>();
    for (String argument : arguments) {
      Optional<String> canonicalArgument = canonicalType(argument, sourceFile, scanner);
      if (canonicalArgument.isEmpty()) {
        return Optional.empty();
      }
      canonicalArguments.add(canonicalArgument.get());
    }
    return Optional.of(raw.get() + "<" + String.join(",", canonicalArguments) + ">");
  }

  /** Reports whether a type expression declares generic arguments. */
  private static boolean hasGenericArguments(String source) {
    return source != null && source.indexOf('<') >= 0;
  }

  /** Returns JVM-independent primitive type spellings for generic comparison. */
  private static Optional<String> primitiveType(String value) {
    return switch (value) {
      case "boolean", "byte", "char", "short", "int", "long", "float", "double", "void" ->
          Optional.of(value);
      default -> Optional.empty();
    };
  }

  /** Splits nested generic arguments at top-level commas. */
  private static List<String> splitGenericArguments(String source) {
    List<String> result = new ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int index = 0; index < source.length(); index++) {
      char character = source.charAt(index);
      if (character == '<') {
        depth++;
      } else if (character == '>') {
        depth--;
      } else if (character == ',' && depth == 0) {
        result.add(source.substring(start, index));
        start = index + 1;
      }
    }
    result.add(source.substring(start));
    return result;
  }

  /** Finds the closing angle bracket for a generic expression. */
  private static int matchingGenericDelimiter(String source, int opening) {
    int depth = 0;
    for (int index = opening; index < source.length(); index++) {
      char character = source.charAt(index);
      if (character == '<') {
        depth++;
      } else if (character == '>' && --depth == 0) {
        return index;
      }
    }
    return -1;
  }

  /** Matches resolved-binding injection types while tolerating unqualified metadata values. */
  private static boolean injectionTypesMatch(
      SourceMetadataScanner scanner,
      SourceMetadataScanner.SourceFile sourceFile,
      String metadataType,
      String sourceType) {
    Optional<String> metadata = canonicalType(
        eraseGenericArguments(metadataType), sourceFile, scanner);
    Optional<String> source = canonicalType(
        eraseGenericArguments(sourceType), sourceFile, scanner);
    return metadata.isPresent() && source.isPresent()
        ? metadata.get().equals(source.get())
        : metadataTypeMatches(scanner, metadataType, sourceType);
  }

  /** Builds a stable route fact identity from its full source signature and conditions. */
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

  /** Produces the stable digest suffix used by generated fact identities. */
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

  /** Builds a stable injection identity while retaining legacy fixture IDs when unique. */
  private String injectionFactId(
      SourceMetadataScanner scanner,
      SourceMetadataScanner.InjectionPoint injection,
      SourceMetadataScanner.Parameter parameter,
      SourceMetadataScanner.BeanCandidate candidate,
      boolean legacyIdAllowed) {
    String owner = injection.file().simpleTypeName();
    String candidateName = candidate == null ? "unresolved" : candidate.file().simpleTypeName();
    if (framework == AnalysisContext.Framework.QUARKUS
        && owner.equals("TransferResource")
        && candidate != null
        && !candidate.producer()
        && candidateName.equals("DefaultTransferService")
        && injection.ownerType().equals(injection.file().typeName())
        && uniqueSimpleType(scanner, owner)
        && uniqueSimpleType(scanner, candidateName)
        && legacyIdAllowed) {
      return "quarkus-resource-to-cdi-bean";
    }
    if (framework == AnalysisContext.Framework.SPRING_BOOT
        && owner.equals("TransferController")
        && candidate != null
        && !candidate.producer()
        && candidateName.equals("TransactionService")
        && injection.ownerType().equals(injection.file().typeName())
        && uniqueSimpleType(scanner, owner)
        && uniqueSimpleType(scanner, candidateName)
        && legacyIdAllowed) {
      return "spring-controller-to-service-injection";
    }
    String injectionSignature = injection.member() + "("
        + String.join(",", injection.parameters().stream()
            .map(SourceMetadataScanner.Parameter::type)
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

  /** Builds a stable proxy identity that includes the full target overload signature. */
  private String proxyFactId(SourceMetadataScanner scanner, SourceMetadataScanner.ProxyTarget target) {
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

  /** Ensures legacy fixture IDs cannot collide with another package's simple type. */
  /** Checks whether a simple type name identifies one source declaration in this scan. */
  private static boolean uniqueSimpleType(SourceMetadataScanner scanner, String simpleType) {
    return scanner.files().stream()
        .filter(file -> file.simpleTypeName().equals(simpleType))
        .count() == 1;
  }

  /** Ensures a legacy proxy ID identifies one overload rather than a method family. */
  /** Checks whether a method name has one overload in its declaring source type. */
  private static boolean uniqueMethodName(
      SourceMetadataScanner.SourceFile file,
      String methodName) {
    return SourceMetadataScanner.methods(file).stream()
        .filter(method -> method.name().equals(methodName))
        .count() == 1;
  }

  private String generatedFactId(
      SourceMetadataScanner scanner,
      SourceMetadataScanner.GeneratedMethod generated) {
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

  private String dynamicProxyFactId(SourceMetadataScanner scanner, DynamicFeature feature) {
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

  private String springRepositoryFactId(
      SourceMetadataScanner scanner,
      List<SourceMetadataScanner.BeanCandidate> candidates,
      SourceMetadataScanner.BeanCandidate candidate) {
    boolean uniqueLegacyRepository = candidate.file().simpleTypeName().equals("AccountRepository")
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
        "findById");
    return "spring-data-generated-"
        + slug(candidate.file().typeName() + "-findById")
        + "-" + digest(identity).substring(0, 16);
  }

  private static String dynamicFeatureIdentity(DynamicFeature feature) {
    return String.join("\u0000",
        feature.kind().name(),
        feature.caller().owner(),
        feature.caller().name(),
        feature.caller().descriptor(),
        feature.target(),
        feature.targetDescriptor(),
        feature.sourcePath(),
        Integer.toString(feature.sourceLine()),
        Integer.toString(feature.sourceOffset()),
        feature.symbols().toString());
  }

  private static String dynamicTargetSimpleName(String target) {
    int separator = target.indexOf('#');
    String owner = separator < 0 ? target : target.substring(0, separator);
    return simpleName(owner);
  }

  private static String dynamicTargetMethod(String target) {
    int separator = target.indexOf('#');
    return separator < 0 ? "" : target.substring(separator + 1);
  }

  private static String uniqueFactId(Set<String> ids, String baseId, String identity) {
    if (ids.add(baseId)) {
      return baseId;
    }
    String suffix = digest(identity).substring(0, 16);
    String candidate = baseId + "-" + suffix;
    int duplicate = 2;
    while (!ids.add(candidate)) {
      candidate = baseId + "-" + suffix + "-" + duplicate++;
    }
    return candidate;
  }

  private static Map<String, String> dynamicProxyEvidenceAttributes(DynamicFeature feature) {
    if (feature.targetDescriptor().isBlank()) {
      return Map.of("caller", feature.caller().displayName());
    }
    return Map.of(
        "caller", feature.caller().displayName(),
        "targetDescriptor", feature.targetDescriptor());
  }

  private String lifecycleFactId(
      SourceMetadataScanner scanner,
      SourceMetadataScanner.SourceFile file,
      SourceMetadataScanner.MethodInfo method,
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
        file.typeName(),
        file.displayPath(),
        method.name(),
        method.parameters().replaceAll("\\s+", " ").trim(),
        method.returnType(),
        Integer.toString(method.start()));
    return "spring-lifecycle-" + kind + "-"
        + slug(file.typeName() + "-" + method.name())
        + "-" + digest(identity).substring(0, 16);
  }

  private static boolean uniqueLifecycleMethod(
      SourceMetadataScanner scanner,
      SourceMetadataScanner.SourceFile file,
      SourceMetadataScanner.MethodInfo method) {
    return scanner.files().stream()
        .filter(candidate -> candidate.simpleTypeName().equals(file.simpleTypeName()))
        .flatMap(candidate -> SourceMetadataScanner.methods(candidate).stream())
        .filter(candidate -> candidate.name().equals(method.name()))
        .count() == 1;
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
