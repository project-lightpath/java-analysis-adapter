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
    List<Descriptor> descriptors = scan.descriptors().stream()
        .filter(item -> hints.serviceTypes().contains(item.serviceType()))
        .toList();
    Set<String> providers = descriptors.stream()
        .flatMap(item -> item.providers().stream())
        .collect(Collectors.toCollection(LinkedHashSet::new));
    boolean unique = !hints.explicitClassLoader()
        && hints.callSiteBound()
        && descriptors.size() == 1
        && providers.size() == 1
        && !descriptors.get(0).malformed()
        && providers.contains(edge.callee().owner());
    SemanticCallFacts.Certainty certainty = edge.certaintyHint() == SemanticCallFacts.Certainty.UNRESOLVED
        ? SemanticCallFacts.Certainty.UNRESOLVED
        : unique ? SemanticCallFacts.Certainty.DEFINITE : SemanticCallFacts.Certainty.POSSIBLE;
    String selection = descriptors.isEmpty() ? "not_observed" : unique ? "unique" : "ambiguous";
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
    List<SemanticCallFacts.Evidence> evidence = new ArrayList<>();
    evidence.add(new SemanticCallFacts.Evidence(
        "service_loader_resolution", edge.sourcePath(), List.of(edge.callee().displayName()),
        edge.line() > 0 ? edge.line() : null, attributes));
    for (Descriptor descriptor : descriptors) {
      evidence.add(new SemanticCallFacts.Evidence(
          "service_loader_descriptor", descriptor.path(), List.of(descriptor.serviceType()), null,
          Map.of(
              "basis", "META-INF/services descriptor",
              "serviceType", descriptor.serviceType(),
              "providerCount", Integer.toString(descriptor.providers().size()),
              "providers", String.join(",", descriptor.providers()),
              "descriptorKind", descriptor.kind(),
              "descriptorFingerprint", descriptor.fingerprint(),
              "selection", selection)));
    }
    String limitation = unique ? null
        : "ServiceLoader registration or runtime classloader selection is not unique.";
    return new Resolution(certainty, List.copyOf(evidence), limitation);
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
  }
}
