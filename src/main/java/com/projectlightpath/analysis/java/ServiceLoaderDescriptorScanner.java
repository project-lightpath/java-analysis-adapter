package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Resolves ServiceLoader edges against selected descriptor metadata. */
final class ServiceLoaderDescriptorScanner {
  private ServiceLoaderDescriptorScanner() {}

  static Scan scan(AnalysisContext context) {
    return ServiceLoaderDescriptorReader.scan(context);
  }

  static Resolution resolve(
      AnalysisContext context,
      CallEdge edge,
      Scan scan,
      SourceMetadataScanner scanner) {
    ServiceLoaderCallSiteReader.ServiceLoadHints hints =
        ServiceLoaderCallSiteReader.read(context, edge, scan, scanner);
    List<Descriptor> descriptors = selectedDescriptors(hints, scan);
    Set<String> providers = providerNames(descriptors);
    boolean moduleVisibilityProven = descriptors.stream().noneMatch(Descriptor::moduleInfo);
    boolean unique = isUnique(hints, edge, scan, descriptors, providers, moduleVisibilityProven);
    String selection = selection(descriptors, unique);
    return new Resolution(
        certainty(edge, unique),
        evidence(edge, hints, scan, descriptors, providers, selection),
        limitation(unique, scan, descriptors, moduleVisibilityProven));
  }

  private static List<Descriptor> selectedDescriptors(
      ServiceLoaderCallSiteReader.ServiceLoadHints hints, Scan scan) {
    Set<DescriptorIdentity> seen = new LinkedHashSet<>();
    return scan.descriptors().stream()
        .filter(item -> hints.serviceTypes().contains(item.serviceType()))
        .filter(item -> seen.add(DescriptorIdentity.from(item)))
        .toList();
  }

  private static Set<String> providerNames(List<Descriptor> descriptors) {
    return descriptors.stream()
        .flatMap(item -> item.providers().stream())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static boolean isUnique(
      ServiceLoaderCallSiteReader.ServiceLoadHints hints,
      CallEdge edge,
      Scan scan,
      List<Descriptor> descriptors,
      Set<String> providers,
      boolean moduleVisibilityProven) {
    return !hints.explicitClassLoader()
        && hints.callSiteBound()
        && descriptors.size() == 1
        && providers.size() == 1
        && !descriptors.get(0).malformed()
        && scan.diagnostics().isEmpty()
        && moduleVisibilityProven
        && providers.contains(edge.callee().owner());
  }

  private static SemanticCallFacts.Certainty certainty(CallEdge edge, boolean unique) {
    if (edge.certaintyHint() == SemanticCallFacts.Certainty.UNRESOLVED) {
      return SemanticCallFacts.Certainty.UNRESOLVED;
    }
    return unique ? SemanticCallFacts.Certainty.DEFINITE : SemanticCallFacts.Certainty.POSSIBLE;
  }

  private static String selection(List<Descriptor> descriptors, boolean unique) {
    if (descriptors.isEmpty()) {
      return "not_observed";
    }
    return unique ? "unique" : "ambiguous";
  }

  private static List<SemanticCallFacts.Evidence> evidence(
      CallEdge edge,
      ServiceLoaderCallSiteReader.ServiceLoadHints hints,
      Scan scan,
      List<Descriptor> descriptors,
      Set<String> providers,
      String selection) {
    List<SemanticCallFacts.Evidence> evidence = new ArrayList<>();
    evidence.add(resolutionEvidence(edge, hints, descriptors, providers, selection));
    descriptors.stream()
        .map(descriptor -> descriptorEvidence(descriptor, selection))
        .forEach(evidence::add);
    scan.diagnostics().stream()
        .map(ServiceLoaderDescriptorScanner::diagnosticEvidence)
        .forEach(evidence::add);
    return List.copyOf(evidence);
  }

  private static SemanticCallFacts.Evidence resolutionEvidence(
      CallEdge edge,
      ServiceLoaderCallSiteReader.ServiceLoadHints hints,
      List<Descriptor> descriptors,
      Set<String> providers,
      String selection) {
    Map<String, String> attributes = new java.util.LinkedHashMap<>();
    attributes.put("basis", "selected classpath ServiceLoader metadata");
    attributes.put("descriptorStatus", descriptors.isEmpty() ? "not_observed" : "observed");
    attributes.put("descriptorCount", Integer.toString(descriptors.size()));
    attributes.put("providerCount", Integer.toString(providers.size()));
    attributes.put("providerCandidates", String.join(",", providers));
    attributes.put("selection", selection);
    attributes.put("callSiteBound", Boolean.toString(hints.callSiteBound()));
    attributes.put("classLoaderSelection", hints.explicitClassLoader() ? "explicit" : "default_context");
    String serviceType = descriptors.stream().map(Descriptor::serviceType).distinct().findFirst()
        .orElse(hints.serviceTypes().size() == 1 ? hints.serviceTypes().iterator().next() : null);
    if (serviceType != null) {
      attributes.put("serviceType", serviceType);
    }
    return new SemanticCallFacts.Evidence(
        "service_loader_resolution", edge.sourcePath(), List.of(edge.callee().displayName()),
        edge.line() > 0 ? edge.line() : null, attributes);
  }

  private static SemanticCallFacts.Evidence descriptorEvidence(
      Descriptor descriptor, String selection) {
    Map<String, String> attributes = new java.util.LinkedHashMap<>();
    attributes.put(
        "basis", descriptor.moduleInfo()
            ? "module-info provides declaration" : "META-INF/services descriptor");
    attributes.put("serviceType", descriptor.serviceType());
    attributes.put("providerCount", Integer.toString(descriptor.providers().size()));
    attributes.put("providers", String.join(",", descriptor.providers()));
    attributes.put("descriptorKind", descriptor.kind());
    attributes.put("descriptorFingerprint", descriptor.fingerprint());
    attributes.put("descriptorStatus", descriptor.malformed() ? "malformed" : "observed");
    if (!descriptor.malformedEntries().isEmpty()) {
      attributes.put("malformedProviderEntries", String.join("|", descriptor.malformedEntries()));
    }
    attributes.put("selection", selection);
    if (descriptor.moduleInfo()) {
      attributes.put("moduleVisibility", "not_proven");
    }
    return new SemanticCallFacts.Evidence(
        "service_loader_descriptor", descriptor.path(), List.of(descriptor.serviceType()), null,
        attributes);
  }

  private static SemanticCallFacts.Evidence diagnosticEvidence(Diagnostic diagnostic) {
    return new SemanticCallFacts.Evidence(
        "service_loader_scan_diagnostic", diagnostic.path(), List.of(), null,
        Map.of(
            "diagnosticCode", diagnostic.code(),
            "detail", diagnostic.detail(),
            "scanStatus", "incomplete"));
  }

  private static String limitation(
      boolean unique,
      Scan scan,
      List<Descriptor> descriptors,
      boolean moduleVisibilityProven) {
    if (unique) {
      return null;
    }
    if (!scan.diagnostics().isEmpty()) {
      return "Selected ServiceLoader metadata scan reported: " + scan.diagnostics().stream()
          .map(Diagnostic::code).distinct().sorted().collect(Collectors.joining(", ")) + ".";
    }
    if (descriptors.stream().anyMatch(Descriptor::malformed)) {
      return "Selected ServiceLoader descriptor contains malformed provider metadata; runtime selection is not proven unique.";
    }
    if (!moduleVisibilityProven) {
      return "module-info provides evidence is possible; selected loader module visibility is not explicit.";
    }
    return "ServiceLoader registration or runtime classloader selection is not unique.";
  }

  record Scan(List<Descriptor> descriptors, List<Diagnostic> diagnostics) {
    Scan {
      descriptors = List.copyOf(descriptors);
      diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
    }

    Scan(List<Descriptor> descriptors) {
      this(descriptors, List.of());
    }
  }

  record Diagnostic(String code, String path, String detail) {}

  record Resolution(
      SemanticCallFacts.Certainty certainty,
      List<SemanticCallFacts.Evidence> evidence,
      String limitation) {}

  record Descriptor(
      String serviceType,
      List<String> providers,
      String path,
      String fingerprint,
      String kind,
      List<String> malformedEntries) {
    Descriptor {
      providers = List.copyOf(providers);
      malformedEntries = List.copyOf(malformedEntries);
    }

    boolean malformed() {
      return !malformedEntries.isEmpty();
    }

    boolean moduleInfo() {
      return kind.equals("module_info");
    }
  }

  private record DescriptorIdentity(
      String serviceType,
      List<String> providers,
      String fingerprint,
      String kind,
      List<String> malformedEntries) {
    static DescriptorIdentity from(Descriptor descriptor) {
      return new DescriptorIdentity(
          descriptor.serviceType(),
          descriptor.providers(),
          descriptor.fingerprint(),
          descriptor.kind(),
          descriptor.malformedEntries());
    }
  }
}
