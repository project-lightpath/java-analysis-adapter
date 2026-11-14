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
abstract class BoundedFrameworkModel extends BoundedFrameworkModelSpringFacts {

  BoundedFrameworkModel(AnalysisContext.Framework framework) {
    super(framework);
  }





  private static final class InjectionScanning {
    private InjectionScanning() {}

    private record InjectionParameterRequest(
        BoundedFrameworkModel model,
        AnalysisContext context,
        SourceMetadataScanner scanner,
        List<SourceMetadataScannerState.BeanCandidate> candidates,
        String sourceMetadata,
        String registryMetadata,
        SourceMetadataScannerState.InjectionPoint injection,
        SourceMetadataScannerState.Parameter parameter,
        int parameterIndex,
        boolean uniqueInjectionSite) {}

    private record BindingSelectionRequest(
        BoundedFrameworkModel model,
        AnalysisContext context,
        SourceMetadataScanner scanner,
        SourceMetadataScannerState.InjectionPoint injection,
        SourceMetadataScannerState.Parameter parameter,
        List<SourceMetadataScannerState.BeanCandidate> matches,
        List<SourceMetadataScannerState.BeanCandidate> candidates,
        List<AnalysisContext.ResolvedBindingMetadata> selectedRows,
        List<AnalysisContext.ResolvedBindingMetadata> bindingRows) {}

    private record CertaintyRequest(
        BoundedFrameworkModel model,
        AnalysisContext context,
        SourceMetadataScanner scanner,
        String sourceMetadata,
        SourceMetadataScannerState.InjectionPoint injection,
        SourceMetadataScannerState.Parameter parameter,
        List<SourceMetadataScannerState.BeanCandidate> matches,
        List<SourceMetadataScannerState.BeanCandidate> qualifiedTypeMatches,
        List<AnalysisContext.BindingCandidateMetadata> metadataOnlyMatches,
        long eligibleCandidateCount,
        List<AnalysisContext.ResolvedBindingMetadata> selectedRows) {}

    private record SelectedFactsRequest(
        BoundedFrameworkModel model,
        AnalysisContext context,
        SourceMetadataScanner scanner,
        String registryMetadata,
        SourceMetadataScannerState.InjectionPoint injection,
        SourceMetadataScannerState.Parameter parameter,
        int parameterIndex,
        List<SourceMetadataScannerState.BeanCandidate> matches,
        int typeMatchCount,
        List<AnalysisContext.ResolvedBindingMetadata> bindingRows,
        SemanticCallFacts.Certainty certainty,
        boolean legacyIdAllowed) {}

    private static List<SemanticCallFacts.Fact> injectionFacts(
        BoundedFrameworkModel model,
        AnalysisContext context,
        SourceMetadataScanner scanner) {
      List<SourceMetadataScannerState.BeanCandidate> candidates = scanner.beanCandidates(model.framework);
      List<SemanticCallFacts.Fact> facts = new ArrayList<>();
      String sourceMetadata = model.framework == AnalysisContext.Framework.QUARKUS
          ? "quarkus-cdi-annotations"
          : "spring-bean-annotations";
      String registryMetadata = model.framework == AnalysisContext.Framework.QUARKUS
          ? "quarkus-arc-bean-index"
          : "spring-bean-definition-registry";
      List<SourceMetadataScannerState.InjectionPoint> injectionPoints =
          scanner.injectionPoints(model.framework);
      Map<String, Integer> injectionParameterCounts = new HashMap<>();
      for (SourceMetadataScannerState.InjectionPoint injection : injectionPoints) {
        injectionParameterCounts.merge(
            injection.ownerType(), injection.parameters().size(), Integer::sum);
      }
      for (SourceMetadataScannerState.InjectionPoint injection : injectionPoints) {
        facts.addAll(injectionFactsForPoint(
            model, context, scanner, candidates, sourceMetadata, registryMetadata,
            injectionParameterCounts, injection));
      }
      return facts;
    }

    private static List<SemanticCallFacts.Fact> injectionFactsForPoint(
        BoundedFrameworkModel model,
        AnalysisContext context,
        SourceMetadataScanner scanner,
        List<SourceMetadataScannerState.BeanCandidate> candidates,
        String sourceMetadata,
        String registryMetadata,
        Map<String, Integer> injectionParameterCounts,
        SourceMetadataScannerState.InjectionPoint injection) {
      boolean uniqueInjectionSite = injectionParameterCounts.getOrDefault(
          injection.ownerType(), 0) == 1;
      List<SemanticCallFacts.Fact> facts = new ArrayList<>();
      for (int parameterIndex = 0;
          parameterIndex < injection.parameters().size();
          parameterIndex++) {
        SourceMetadataScannerState.Parameter parameter = injection.parameters().get(parameterIndex);
        facts.addAll(injectionFactsForParameter(new InjectionParameterRequest(
            model,
            context,
            scanner,
            candidates,
            sourceMetadata,
            registryMetadata,
            injection,
            parameter,
            parameterIndex,
            uniqueInjectionSite)));
      }
      return facts;
    }

    private static List<SemanticCallFacts.Fact> injectionFactsForParameter(
        InjectionParameterRequest request) {
      BoundedFrameworkModel model = request.model();
      AnalysisContext context = request.context();
      SourceMetadataScanner scanner = request.scanner();
      List<SourceMetadataScannerState.BeanCandidate> candidates = request.candidates();
      String sourceMetadata = request.sourceMetadata();
      String registryMetadata = request.registryMetadata();
      SourceMetadataScannerState.InjectionPoint injection = request.injection();
      SourceMetadataScannerState.Parameter parameter = request.parameter();
      int parameterIndex = request.parameterIndex();
      boolean uniqueInjectionSite = request.uniqueInjectionSite();
      List<SourceMetadataScannerState.BeanCandidate> typeMatches = candidates.stream()
          .filter(candidate -> model.matches(
              context, scanner, injection.file(), parameter.type(), candidate))
          .filter(candidate -> model.profileMatches(candidate.profiles()) != Boolean.FALSE)
          .toList();
      List<SourceMetadataScannerState.BeanCandidate> matches = typeMatches.stream()
          .filter(candidate -> model.candidateMetadataActive(context, scanner, candidate) != Boolean.FALSE)
          .toList();
      matches = model.qualifierMatches(context, scanner, parameter, injection, matches);
      List<SourceMetadataScannerState.BeanCandidate> qualifiedTypeMatches = model.qualifierMatches(
          context, scanner, parameter, injection, typeMatches);
      List<AnalysisContext.BindingCandidateMetadata> metadataOnlyMatches = model.metadataOnlyCandidates(
          context,
          scanner,
          injection.file(),
          parameter.type(),
          model.mergeQualifiers(injection.qualifiers(), parameter.qualifiers()),
          candidates);
      long activeMetadataOnlyCount = metadataOnlyMatches.stream()
          .filter(row -> row.active() != Boolean.FALSE)
          .map(AnalysisContext.BindingCandidateMetadata::candidateId)
          .distinct()
          .count();
      long eligibleCandidateCount = qualifiedTypeMatches.size() + activeMetadataOnlyCount;
      List<AnalysisContext.ResolvedBindingMetadata> bindingRows = model.matchingBindings(
          context, scanner, injection, parameter, parameterIndex, candidates);
      List<AnalysisContext.ResolvedBindingMetadata> selectedRows = bindingRows.stream()
          .filter(row -> row.active() == Boolean.TRUE)
          .filter(row -> row.selected() == Boolean.TRUE)
          .toList();
      matches = applyBindingSelection(new BindingSelectionRequest(
          model,
          context,
          scanner,
          injection,
          parameter,
          matches,
          candidates,
          selectedRows,
          bindingRows));
      boolean legacyIdAllowed = uniqueInjectionSite && matches.size() == 1;
      if (matches.isEmpty()) {
        return List.of(model.injectionFact(new InjectionFactRequest(
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
            "No active bounded bean candidate matches the injection type, qualifiers, profile, or binding metadata.")));
      }
      SemanticCallFacts.Certainty certainty = certainty(new CertaintyRequest(
          model,
          context,
          scanner,
          sourceMetadata,
          injection,
          parameter,
          matches,
          qualifiedTypeMatches,
          metadataOnlyMatches,
          eligibleCandidateCount,
          selectedRows));
      return selectedFacts(new SelectedFactsRequest(
          model,
          context,
          scanner,
          registryMetadata,
          injection,
          parameter,
          parameterIndex,
          matches,
          typeMatches.size(),
          bindingRows,
          certainty,
          legacyIdAllowed));
    }

    private static List<SourceMetadataScannerState.BeanCandidate> applyBindingSelection(
        BindingSelectionRequest request) {
      BoundedFrameworkModel model = request.model();
      AnalysisContext context = request.context();
      SourceMetadataScanner scanner = request.scanner();
      SourceMetadataScannerState.InjectionPoint injection = request.injection();
      SourceMetadataScannerState.Parameter parameter = request.parameter();
      List<SourceMetadataScannerState.BeanCandidate> matches = request.matches();
      List<SourceMetadataScannerState.BeanCandidate> candidates = request.candidates();
      List<AnalysisContext.ResolvedBindingMetadata> selectedRows = request.selectedRows();
      List<AnalysisContext.ResolvedBindingMetadata> bindingRows = request.bindingRows();
      if (!selectedRows.isEmpty()) {
        List<SourceMetadataScannerState.BeanCandidate> activeCandidates = candidates.stream()
            .filter(candidate -> model.matches(
                context, scanner, injection.file(), parameter.type(), candidate))
            .filter(candidate -> model.profileMatches(candidate.profiles()) != Boolean.FALSE)
            .filter(candidate -> model.candidateMetadataActive(context, scanner, candidate) != Boolean.FALSE)
            .toList();
        return activeCandidates.stream()
            .filter(candidate -> selectedRows.stream().anyMatch(row ->
                row.candidateId().equals(candidate.candidateId())
                    || model.matchingCandidateMetadata(context, scanner, candidate).stream()
                        .anyMatch(metadata -> row.candidateId().equals(metadata.candidateId()))))
            .filter(candidate -> model.qualifierMatches(
                context, scanner, parameter, injection, activeCandidates).contains(candidate))
            .toList();
      }
      if (!bindingRows.isEmpty() && bindingRows.stream().allMatch(row ->
          row.selected() == Boolean.FALSE || row.active() == Boolean.FALSE)) {
        return List.of();
      }
      return model.bindingPreference(context, scanner, matches);
    }

    private static SemanticCallFacts.Certainty certainty(CertaintyRequest request) {
      if (uncertainMetadata(request)) {
        return SemanticCallFacts.Certainty.UNRESOLVED;
      }
      return exactBindingSelection(request)
              || exactCandidateSelection(request)
              || exactActiveSet(request)
          ? SemanticCallFacts.Certainty.DEFINITE
          : SemanticCallFacts.Certainty.POSSIBLE;
    }

    private static boolean uncertainMetadata(CertaintyRequest request) {
      BoundedFrameworkModel model = request.model();
      List<SourceMetadataScannerState.BeanCandidate> matches = request.matches();
      boolean conditionUnknown = matches.stream().anyMatch(candidate ->
          model.hasConditions(request.context(), request.scanner(), candidate))
          && matches.stream().anyMatch(candidate ->
              model.candidateMetadataActive(request.context(), request.scanner(), candidate) == null);
      boolean profileUnknown = matches.stream().anyMatch(candidate ->
          model.profileSelectionUnknown(request.context(), request.scanner(), candidate));
      return conditionUnknown
          || profileUnknown
          || !request.context().metadataObserved(request.sourceMetadata());
    }

    private static boolean exactBindingSelection(CertaintyRequest request) {
      return request.selectedRows().size() == 1 && request.matches().size() == 1;
    }

    private static boolean exactCandidateSelection(CertaintyRequest request) {
      if (request.matches().size() != 1 || request.eligibleCandidateCount() != 1) {
        return false;
      }
      SourceMetadataScannerState.BeanCandidate selected = request.matches().get(0);
      List<AnalysisContext.BindingCandidateMetadata> metadata =
          request.model().matchingCandidateMetadata(request.context(), request.scanner(), selected);
      return request.model().candidateMetadataActive(request.context(), request.scanner(), selected)
              == Boolean.TRUE
          && metadata.size() == 1
          && metadata.get(0).active() == Boolean.TRUE
          && request.model().typeMatchCertain(
              request.context(), request.scanner(), request.injection().file(),
              request.parameter().type(), selected);
    }

    private static boolean exactActiveSet(CertaintyRequest request) {
      if (request.matches().size() != 1 || request.eligibleCandidateCount() <= 1) {
        return false;
      }
      return metadataCoversTypeMatches(request)
          && metadataSelectsOnlyMatch(request)
          && metadataOnlyCandidatesInactive(request)
          && request.model().typeMatchCertain(
              request.context(), request.scanner(), request.injection().file(),
              request.parameter().type(), request.matches().get(0));
    }

    private static boolean metadataCoversTypeMatches(CertaintyRequest request) {
      return request.qualifiedTypeMatches().stream().allMatch(candidate ->
          request.model().matchingCandidateMetadata(
              request.context(), request.scanner(), candidate).size() == 1);
    }

    private static boolean metadataSelectsOnlyMatch(CertaintyRequest request) {
      List<SourceMetadataScannerState.BeanCandidate> selectedMatches =
          List.copyOf(request.matches());
      return request.qualifiedTypeMatches().stream().allMatch(candidate -> {
        Boolean active = request.model().candidateMetadataActive(
            request.context(), request.scanner(), candidate);
        return selectedMatches.contains(candidate) ? active == Boolean.TRUE : active == Boolean.FALSE;
      });
    }

    private static boolean metadataOnlyCandidatesInactive(CertaintyRequest request) {
      return request.metadataOnlyMatches().stream()
          .allMatch(row -> row.active() == Boolean.FALSE);
    }

    private static List<SemanticCallFacts.Fact> selectedFacts(SelectedFactsRequest request) {
      BoundedFrameworkModel model = request.model();
      AnalysisContext context = request.context();
      SourceMetadataScanner scanner = request.scanner();
      String registryMetadata = request.registryMetadata();
      SourceMetadataScannerState.InjectionPoint injection = request.injection();
      SourceMetadataScannerState.Parameter parameter = request.parameter();
      int parameterIndex = request.parameterIndex();
      List<SourceMetadataScannerState.BeanCandidate> matches = request.matches();
      int typeMatchCount = request.typeMatchCount();
      List<AnalysisContext.ResolvedBindingMetadata> bindingRows = request.bindingRows();
      SemanticCallFacts.Certainty certainty = request.certainty();
      boolean legacyIdAllowed = request.legacyIdAllowed();
      boolean conditionUnknown = matches.stream().anyMatch(candidate ->
          model.hasConditions(context, scanner, candidate))
          && matches.stream().anyMatch(candidate ->
              model.candidateMetadataActive(context, scanner, candidate) == null);
      boolean profileUnknown = matches.stream().anyMatch(candidate ->
          model.profileSelectionUnknown(context, scanner, candidate));
      List<SemanticCallFacts.Fact> facts = new ArrayList<>();
      for (SourceMetadataScannerState.BeanCandidate candidate : matches) {
        AnalysisContext.BindingCandidateMetadata candidateMetadata =
            model.matchingCandidateMetadata(context, scanner, candidate).stream().findFirst().orElse(null);
        AnalysisContext.ResolvedBindingMetadata binding = bindingForCandidate(
            candidate, candidateMetadata, bindingRows);
        String limitation = limitation(
            certainty, conditionUnknown, profileUnknown, matches.size());
        facts.add(model.injectionFact(new InjectionFactRequest(
            context,
            injection,
            parameter,
            candidate,
            certainty,
            registryMetadata,
            typeMatchCount,
            candidateMetadata,
            binding,
            bindingRows,
            parameterIndex,
            scanner,
            legacyIdAllowed,
            limitation)));
      }
      return facts;
    }

    private static AnalysisContext.ResolvedBindingMetadata bindingForCandidate(
        SourceMetadataScannerState.BeanCandidate candidate,
        AnalysisContext.BindingCandidateMetadata candidateMetadata,
        List<AnalysisContext.ResolvedBindingMetadata> rows) {
      return rows.stream()
          .filter(row -> row.candidateId().equals(candidate.candidateId()))
          .findFirst()
          .orElseGet(() -> candidateMetadata == null
              ? null
              : rows.stream()
                  .filter(row -> row.candidateId().equals(candidateMetadata.candidateId()))
                  .findFirst()
                  .orElse(null));
    }

    private static String limitation(
        SemanticCallFacts.Certainty certainty,
        boolean conditionUnknown,
        boolean profileUnknown,
        int matchCount) {
      if (certainty == SemanticCallFacts.Certainty.DEFINITE) {
        return null;
      }
      if (conditionUnknown || profileUnknown) {
        return "A profile or conditional bean registration is not resolved in the selected context.";
      }
      if (certainty == SemanticCallFacts.Certainty.UNRESOLVED) {
        return "The selected framework source or binding metadata is incomplete.";
      }
      return matchCount > 1
          ? "Multiple active bounded bean candidates remain after source-level filtering."
          : "The source-visible candidate is bounded, but exact framework selection metadata is incomplete.";
    }

    private static SemanticCallFacts.Fact injectionFact(
        BoundedFrameworkModel model, InjectionFactRequest request) {
      AnalysisContext context = request.context();
      SourceMetadataScannerState.InjectionPoint injection = request.injection();
      SourceMetadataScannerState.Parameter parameter = request.parameter();
      SourceMetadataScannerState.BeanCandidate candidate = request.candidate();
      SemanticCallFacts.Certainty certainty = request.certainty();
      String registryMetadata = request.registryMetadata();
      int candidateCount = request.candidateCount();
      AnalysisContext.BindingCandidateMetadata candidateMetadata = request.candidateMetadata();
      AnalysisContext.ResolvedBindingMetadata binding = request.binding();
      List<AnalysisContext.ResolvedBindingMetadata> bindingRows = request.bindingRows();
      Integer parameterIndex = request.parameterIndex();
      SourceMetadataScanner scanner = request.scanner();
      boolean legacyIdAllowed = request.legacyIdAllowed();
      String limitation = request.limitation();
      String owner = injection.ownerType();
      String target = candidate == null ? parameter.type() + "#<unresolved-bean>" : candidate.target();
      String callerPrefix = model.framework == AnalysisContext.Framework.QUARKUS ? "quarkus" : "spring";
      String id = model.injectionFactId(scanner, injection, parameter, candidate, legacyIdAllowed);
      String displayedQualifiers = String.join(",", model.displayQualifiers(
          model.mergeQualifiers(injection.qualifiers(), parameter.qualifiers())));
      List<SemanticCallFacts.Evidence> evidence = new ArrayList<>();
      evidence.add(new SemanticCallFacts.Evidence(
          model.framework == AnalysisContext.Framework.QUARKUS ? "injection_point" : "constructor_injection",
          injection.file().displayPath(),
          List.of(owner + "#" + injection.member() + "(" + parameter.type() + ")"),
          injection.line(),
          Map.of(
              "parameter", parameter.type(),
              "parameterIndex", parameterIndex == null ? "" : parameterIndex.toString(),
              "injectionKind", injection.kind(),
              "qualifiers", displayedQualifiers)));
      if (candidate != null) {
        evidence.add(candidateEvidence(model, scanner, candidate, candidateCount, registryMetadata,
            candidateMetadata, binding));
      }
      String providerFactId = candidate == null
          ? null
          : model.unambiguousInjectionProviderFactId(context, scanner, candidate, bindingRows);
      SemanticCallFacts.Fact fact = SemanticCallFacts.Fact.framework(
          id,
          context,
          new SemanticCallFacts.Fact.FrameworkInput(
              callerPrefix + ":injection:" + owner + "#" + injection.member()
                  + "(" + parameter.type() + ")",
              target,
              "dependency_injection",
              certainty,
              model.framework == AnalysisContext.Framework.QUARKUS
                  ? "quarkus-unique-cdi-bean"
                  : "spring-unique-component-bean",
              evidence,
              limitation));
      return fact.withFrameworkDetails(
          "resolved_bean_binding",
          certainty == SemanticCallFacts.Certainty.DEFINITE
              ? "container_selected_candidate"
              : "potential_container_match",
          "not_evidence_of_absence",
          null,
          providerFactId);
    }

    private static SemanticCallFacts.Evidence candidateEvidence(
        BoundedFrameworkModel model,
        SourceMetadataScanner scanner,
        SourceMetadataScannerState.BeanCandidate candidate,
        int candidateCount,
        String registryMetadata,
        AnalysisContext.BindingCandidateMetadata candidateMetadata,
        AnalysisContext.ResolvedBindingMetadata binding) {
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
        model.producerDescriptor(scanner, candidate)
            .ifPresent(descriptor -> attributes.put("targetDescriptor", descriptor));
      }
      addCandidateMetadata(attributes, candidateMetadata);
      addBindingMetadata(attributes, binding);
      List<String> symbols = new ArrayList<>(
          scanner.sourceAnnotations(candidate, model.framework));
      symbols.add("candidate=" + candidate.candidateType());
      return new SemanticCallFacts.Evidence(
          "bean_candidate",
          candidate.file().displayPath(),
          symbols,
          null,
          attributes);
    }

    private static void addCandidateMetadata(
        Map<String, String> attributes, AnalysisContext.BindingCandidateMetadata metadata) {
      if (metadata == null) {
        return;
      }
      attributes.put("candidateMetadataId", metadata.candidateId());
      attributes.put("candidateMetadataActive", String.valueOf(metadata.active()));
      attributes.put("candidateMetadataAlternative", Boolean.toString(metadata.alternative()));
      attributes.put("candidateMetadataPrimary", Boolean.toString(metadata.primary()));
    }

    private static void addBindingMetadata(
        Map<String, String> attributes, AnalysisContext.ResolvedBindingMetadata binding) {
      if (binding == null) {
        return;
      }
      attributes.put("bindingMetadataId", binding.bindingId());
      attributes.put("bindingSelected", String.valueOf(binding.selected()));
      attributes.put("bindingActive", String.valueOf(binding.active()));
      if (binding.selectionReason() != null) {
        attributes.put("selectionReason", binding.selectionReason());
      }
    }
  }

  private static final class ProxyScanning {
    private ProxyScanning() {}

    private static List<SemanticCallFacts.Fact> proxyFacts(
        BoundedFrameworkModel model,
        AnalysisContext context,
        SourceMetadataScanner scanner) {
      List<SemanticCallFacts.Fact> facts = new ArrayList<>();
      String metadataId = model.framework == AnalysisContext.Framework.QUARKUS
          ? "quarkus-cdi-annotations"
          : "spring-aop-advisor-metadata";
      List<SourceMetadataScannerState.BeanCandidate> owners = scanner.beanCandidates(model.framework);
      for (SourceMetadataScannerState.ProxyTarget target : scanner.proxyTargets(model.framework)) {
        SemanticCallFacts.Fact fact = proxyFact(model, context, scanner, metadataId, owners, target);
        if (fact != null) {
          facts.add(fact);
        }
      }
      return facts;
    }

    private static SemanticCallFacts.Fact proxyFact(
        BoundedFrameworkModel model,
        AnalysisContext context,
        SourceMetadataScanner scanner,
        String metadataId,
        List<SourceMetadataScannerState.BeanCandidate> owners,
        SourceMetadataScannerState.ProxyTarget target) {
      String sourceMetadata = model.framework == AnalysisContext.Framework.QUARKUS
          ? "quarkus-cdi-annotations"
          : "spring-bean-annotations";
      SourceMetadataScannerState.BeanCandidate beanOwner = owners.stream()
          .filter(candidate -> !candidate.producer())
          .filter(candidate -> candidate.file().typeName().equals(target.file().typeName()))
          .findFirst()
          .orElse(null);
      if (inactiveOwner(model, context, scanner, beanOwner)) {
        return null;
      }
      List<AnalysisContext.ProxyTargetMetadata> metadataRows =
          model.matchingProxyMetadata(context, scanner, target);
      boolean exactMetadataTarget = metadataRows.stream().anyMatch(row ->
          row.active() == Boolean.TRUE && row.selected() == Boolean.TRUE
              && !model.descriptorlessProxyRowAmbiguous(scanner, target, row));
      if (skipNonNormalQuarkusTarget(model, target, exactMetadataTarget)
          || allMetadataRowsInactive(metadataRows)) {
        return null;
      }
      List<AnalysisContext.ProxyTargetMetadata> activeRows = metadataRows.stream()
          .filter(row -> row.active() != Boolean.FALSE)
          .filter(row -> row.selected() != Boolean.FALSE)
          .toList();
      boolean exact = exactTarget(model, scanner, target, activeRows);
      boolean profileUnknown = beanOwner != null
          && model.profileSelectionUnknown(context, scanner, beanOwner);
      SemanticCallFacts.Certainty certainty = profileUnknown || !context.metadataObserved(sourceMetadata)
          ? SemanticCallFacts.Certainty.UNRESOLVED
          : exact ? SemanticCallFacts.Certainty.DEFINITE : SemanticCallFacts.Certainty.POSSIBLE;
      AnalysisContext.ProxyTargetMetadata metadata = activeRows.stream().findFirst().orElse(null);
      String owner = target.file().typeName();
      String prefix = model.framework == AnalysisContext.Framework.QUARKUS
          ? "quarkus:arc-client-proxy:" : "spring:aop-proxy:";
      String rule = proxyRule(model, exact);
      SemanticCallFacts.Evidence evidence = new SemanticCallFacts.Evidence(
          model.framework == AnalysisContext.Framework.QUARKUS ? "bean_scope" : "advisor_annotation",
          target.file().displayPath(),
          proxySymbols(model, scanner, target),
          target.method().line(),
          model.proxyAttributes(metadataId, target, metadata,
              model.methodDescriptor(scanner, target).orElse(null)));
      SemanticCallFacts.Fact fact = SemanticCallFacts.Fact.framework(
          model.proxyFactId(scanner, target),
          context,
          new SemanticCallFacts.Fact.FrameworkInput(
              prefix + owner,
              owner + "#" + target.method().name(),
              "proxy_dispatch",
              certainty,
              rule,
              List.of(evidence),
              proxyLimitation(model, exact)));
      return fact.withFrameworkDetails(
          "proxy_target_dispatch",
          exact ? "framework_proxy_selected_target" : "potential_framework_proxy_target",
          "not_evidence_of_absence",
          null,
          model.unambiguousProxyProviderFactId(activeRows, scanner, target));
    }

    private static boolean inactiveOwner(
        BoundedFrameworkModel model,
        AnalysisContext context,
        SourceMetadataScanner scanner,
        SourceMetadataScannerState.BeanCandidate owner) {
      return owner != null
          && (model.profileMatches(owner.profiles()) == Boolean.FALSE
              || model.candidateMetadataActive(context, scanner, owner) == Boolean.FALSE);
    }

    private static boolean skipNonNormalQuarkusTarget(
        BoundedFrameworkModel model,
        SourceMetadataScannerState.ProxyTarget target,
        boolean exactMetadataTarget) {
      return model.framework == AnalysisContext.Framework.QUARKUS
          && !target.normalScoped() && !exactMetadataTarget;
    }

    private static boolean allMetadataRowsInactive(
        List<AnalysisContext.ProxyTargetMetadata> rows) {
      return !rows.isEmpty() && rows.stream().allMatch(row ->
          row.active() == Boolean.FALSE || row.selected() == Boolean.FALSE);
    }

    private static boolean exactTarget(
        BoundedFrameworkModel model,
        SourceMetadataScanner scanner,
        SourceMetadataScannerState.ProxyTarget target,
        List<AnalysisContext.ProxyTargetMetadata> rows) {
      return rows.size() == 1
          && rows.get(0).selected() == Boolean.TRUE
          && rows.get(0).active() == Boolean.TRUE
          && !model.descriptorlessProxyRowAmbiguous(scanner, target, rows.get(0));
    }

    private static List<String> proxySymbols(
        BoundedFrameworkModel model,
        SourceMetadataScanner scanner,
        SourceMetadataScannerState.ProxyTarget target) {
      List<String> symbols = new ArrayList<>(scanner.sourceAnnotations(target, model.framework));
      symbols.add(target.method().name());
      return symbols;
    }

    private static String proxyRule(BoundedFrameworkModel model, boolean exact) {
      if (model.framework == AnalysisContext.Framework.QUARKUS) {
        return exact ? "quarkus-metadata-proxy-target" : "quarkus-generated-client-proxy";
      }
      return exact ? "spring-metadata-proxy-target" : "spring-aop-proxy-target";
    }

    private static String proxyLimitation(BoundedFrameworkModel model, boolean exact) {
      if (exact) {
        return null;
      }
      return model.framework == AnalysisContext.Framework.QUARKUS
          ? "Arc-generated proxy and interceptor order are not captured by the adapter."
          : "The selected proxy strategy and interceptor ordering are not captured by the adapter.";
    }
  }

  /** Named inputs for one emitted injection fact, avoiding positional arguments. */
  private record InjectionFactRequest(
      AnalysisContext context,
      SourceMetadataScannerState.InjectionPoint injection,
      SourceMetadataScannerState.Parameter parameter,
      SourceMetadataScannerState.BeanCandidate candidate,
      SemanticCallFacts.Certainty certainty,
      String registryMetadata,
      int candidateCount,
      AnalysisContext.BindingCandidateMetadata candidateMetadata,
      AnalysisContext.ResolvedBindingMetadata binding,
      List<AnalysisContext.ResolvedBindingMetadata> bindingRows,
      Integer parameterIndex,
      SourceMetadataScanner scanner,
      boolean legacyIdAllowed,
      String limitation) {}

  /** Enriches the provider graph with framework-owned facts without replacing it. */
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
  private List<SemanticCallFacts.Fact> injectionFacts(
      AnalysisContext context, SourceMetadataScanner scanner) {
    return InjectionScanning.injectionFacts(this, context, scanner);
  }

  private SemanticCallFacts.Fact injectionFact(InjectionFactRequest request) {
    return InjectionScanning.injectionFact(this, request);
  }
  private List<SemanticCallFacts.Fact> proxyFacts(
      AnalysisContext context, SourceMetadataScanner scanner) {
    return ProxyScanning.proxyFacts(this, context, scanner);
  }
}
