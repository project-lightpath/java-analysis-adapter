package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Resolves ServiceLoader edges against selected descriptor metadata. */
final class ServiceLoaderDescriptorScanner {
  private ServiceLoaderDescriptorScanner() {}

  /** Scans only the selected application and dependency inputs. */
  static Scan scan(AnalysisContext context) {
    return ServiceLoaderDescriptorReader.scan(context);
  }

  /** Resolves one graph edge without promoting an unresolved provider claim. */
  static Resolution resolve(
      AnalysisContext context,
      CallEdge edge,
      Scan scan,
      SourceMetadataScanner scanner) {
    ServiceLoaderCallSiteReader.ServiceLoadHints loadHints =
        ServiceLoaderCallSiteReader.read(context, edge, scan, scanner);
    Set<String> hints = loadHints.serviceTypes();
    List<Descriptor> descriptors = matchingDescriptors(scan.descriptors(), hints);
    Set<String> providers = descriptors.stream()
        .flatMap(item -> item.providers().stream())
        .collect(Collectors.toCollection(LinkedHashSet::new));
    boolean malformed = descriptors.stream().anyMatch(Descriptor::malformed);
    boolean blockingDiagnostics = scan.diagnostics().stream().anyMatch(Diagnostic::blocking);
    boolean unique = !loadHints.explicitClassLoader()
        && loadHints.callSiteBound()
        && descriptors.size() == 1
        && providers.size() == 1
        && !malformed
        && !blockingDiagnostics
        && providers.contains(edge.callee().owner());
    SemanticCallFacts.Certainty certainty = certainty(edge, unique);
    String selection = selection(descriptors, unique);
    Map<String, String> attributes = resolutionAttributes(
        descriptors, providers, hints, selection, loadHints, scan);
    List<SemanticCallFacts.Evidence> evidence = new ArrayList<>();
    evidence.add(new SemanticCallFacts.Evidence(
        "service_loader_resolution",
        edge.sourcePath(),
        List.of(edge.callee().displayName()),
        edge.line() > 0 ? edge.line() : null,
        attributes));
    for (Descriptor descriptor : descriptors) {
      evidence.add(descriptorEvidence(descriptor, selection));
    }
    for (Diagnostic diagnostic : scan.diagnostics()) {
      evidence.add(diagnosticEvidence(diagnostic));
    }
    return new Resolution(
        certainty,
        List.copyOf(evidence),
        limitation(certainty, unique, descriptors, loadHints, scan));
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

  private static Map<String, String> resolutionAttributes(
      List<Descriptor> descriptors,
      Set<String> providers,
      Set<String> hints,
      String selection,
      ServiceLoaderCallSiteReader.ServiceLoadHints loadHints,
      Scan scan) {
    Map<String, String> attributes = new LinkedHashMap<>();
    attributes.put("basis", "selected classpath ServiceLoader metadata");
    attributes.put("descriptorStatus", descriptorStatus(descriptors, scan));
    attributes.put("descriptorCount", Integer.toString(descriptors.size()));
    attributes.put("descriptorSources", descriptors.stream()
        .map(Descriptor::path)
        .sorted()
        .collect(Collectors.joining("|")));
    attributes.put("providerCount", Integer.toString(providers.size()));
    attributes.put("providerCandidates", String.join(",", providers.stream().sorted().toList()));
    attributes.put("selection", selection);
    attributes.put("callSiteBound", Boolean.toString(loadHints.callSiteBound()));
    attributes.put("classLoaderSelection",
        loadHints.explicitClassLoader() ? "explicit" : "default_context");
    attributes.put("scanStatus", scan.diagnostics().isEmpty() ? "complete" : "incomplete");
    attributes.put("scanDiagnosticCount", Integer.toString(scan.diagnostics().size()));
    attributes.put("scanDiagnostics", scan.diagnostics().stream()
        .map(Diagnostic::code)
        .distinct()
        .sorted()
        .collect(Collectors.joining("|")));
    String serviceType = serviceType(descriptors, hints);
    if (serviceType != null) {
      attributes.put("serviceType", serviceType);
    }
    return attributes;
  }

  private static String descriptorStatus(List<Descriptor> descriptors, Scan scan) {
    if (scan.diagnostics().stream().anyMatch(item -> item.code().equals("malformed_module_info"))) {
      return "malformed";
    }
    if (descriptors.isEmpty()) {
      return scan.diagnostics().isEmpty() ? "not_observed" : "incomplete";
    }
    if (descriptors.stream().anyMatch(Descriptor::malformed)) {
      return "malformed";
    }
    return scan.diagnostics().isEmpty() ? "observed" : "incomplete";
  }

  private static String limitation(
      SemanticCallFacts.Certainty certainty,
      boolean unique,
      List<Descriptor> descriptors,
      ServiceLoaderCallSiteReader.ServiceLoadHints loadHints,
      Scan scan) {
    if (certainty == SemanticCallFacts.Certainty.UNRESOLVED) {
      return DynamicCertaintyPolicy.LIMITATION;
    }
    if (descriptors.stream().anyMatch(Descriptor::malformed)) {
      return "Selected ServiceLoader descriptor contains malformed provider metadata; runtime selection is not proven unique.";
    }
    if (unique) {
      return scanLimitation(scan);
    }
    String scanLimitation = scanLimitation(scan);
    if (scanLimitation != null) {
      return scanLimitation;
    }
    if (loadHints.explicitClassLoader()) {
      return "ServiceLoader uses an explicit classloader whose provider selection is not proven unique.";
    }
    if (!loadHints.callSiteBound()) {
      return "The ServiceLoader call site or exact service type is not uniquely identified.";
    }
    return descriptors.isEmpty()
        ? "No selected classpath ServiceLoader descriptor identifies the runtime provider."
        : "ServiceLoader registration or runtime classloader selection is not unique.";
  }

  private static String scanLimitation(Scan scan) {
    if (scan.diagnostics().isEmpty()) {
      return null;
    }
    return "Selected ServiceLoader metadata scan reported: " + scan.diagnostics().stream()
        .map(Diagnostic::code)
        .distinct()
        .sorted()
        .collect(Collectors.joining(", ")) + ".";
  }

  private static List<Descriptor> matchingDescriptors(
      List<Descriptor> descriptors, Set<String> hints) {
    return descriptors.stream()
        .filter(descriptor -> hints.contains(descriptor.serviceType()))
        .toList();
  }

  private static SemanticCallFacts.Evidence descriptorEvidence(
      Descriptor descriptor, String selection) {
    Map<String, String> attributes = new LinkedHashMap<>();
    attributes.put("basis", descriptor.kind().equals("module_info")
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
    return new SemanticCallFacts.Evidence(
        "service_loader_descriptor",
        descriptor.path(),
        List.of(descriptor.serviceType()),
        null,
        attributes);
  }

  private static SemanticCallFacts.Evidence diagnosticEvidence(Diagnostic diagnostic) {
    Map<String, String> attributes = new LinkedHashMap<>();
    attributes.put("diagnosticCode", diagnostic.code());
    attributes.put("diagnostic", diagnostic.code());
    attributes.put("status", diagnostic.status());
    attributes.put("detail", diagnostic.detail());
    attributes.put("scanStatus", "incomplete");
    return new SemanticCallFacts.Evidence(
        "service_loader_scan_diagnostic", diagnostic.path(), List.of(), null, attributes);
  }

  private static String serviceType(List<Descriptor> descriptors, Set<String> hints) {
    Set<String> types = descriptors.stream()
        .map(Descriptor::serviceType)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    if (types.size() == 1) {
      return types.iterator().next();
    }
    return hints.size() == 1 ? hints.iterator().next() : null;
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

  record Diagnostic(String code, String path, String detail) {
    Diagnostic {
      if (code == null || code.isBlank()) {
        throw new IllegalArgumentException("ServiceLoader diagnostic code must not be blank");
      }
      if (path == null || path.isBlank()) {
        throw new IllegalArgumentException("ServiceLoader diagnostic path must not be blank");
      }
      detail = detail == null || detail.isBlank() ? code : detail;
    }

    String status() {
      return code.equals("malformed_module_info") ? "malformed" : "incomplete";
    }

    boolean blocking() {
      return !code.equals("malformed_module_info");
    }
  }

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
