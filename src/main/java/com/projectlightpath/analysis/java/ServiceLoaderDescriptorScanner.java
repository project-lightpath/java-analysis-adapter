package com.projectlightpath.analysis.java;

import java.nio.file.Path;
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

  static Scan scan(
      AnalysisContext context, SootUpInputLocationFactory.DependencyResolution dependencies) {
    return ServiceLoaderDescriptorReader.scan(context, dependencies);
  }

  static Scan scan(
      AnalysisContext context,
      SootUpInputLocationFactory.DependencyResolution dependencies,
      ServiceLoaderDescriptorSource.ScanState state) {
    return ServiceLoaderDescriptorReader.scan(context, dependencies, state);
  }

  /**
   * Adds the files javac rejected to the scan diagnostics. This is scan-global,
   * as required for descriptor diagnostics: one source error downgrades every
   * ServiceLoader edge in the scan to POSSIBLE.
   */
  static Scan withSourceDiagnostics(Scan scan, SourceMetadataScanner scanner) {
    Map<Path, String> errors = JavacServiceLoaderSiteResolver.errors(scanner);
    if (errors.isEmpty()) {
      return scan;
    }
    List<Diagnostic> diagnostics = new ArrayList<>(scan.diagnostics());
    errors.forEach((path, detail) -> diagnostics.add(new Diagnostic(
        "javac_source_error", diagnosticPath(scanner, path), detail)));
    return new Scan(scan.descriptors(), diagnostics);
  }

  private static String diagnosticPath(SourceMetadataScanner scanner, Path path) {
    Path root = scanner.context.repositoryRoot().toAbsolutePath().normalize();
    return path.startsWith(root) ? root.relativize(path).toString() : path.toString();
  }

  static Resolution resolve(
      AnalysisContext context,
      CallEdge edge,
      Scan scan,
      SourceMetadataScanner scanner) {
    return resolve(context, edge, scan, scanner, new ServiceLoaderDescriptorEvidenceCache());
  }

  static Resolution resolve(
      AnalysisContext context,
      CallEdge edge,
      Scan scan,
      SourceMetadataScanner scanner,
      ServiceLoaderDescriptorEvidenceCache cache) {
    Scan effective = withSourceDiagnostics(scan, scanner);
    ServiceLoaderCallSiteReader.ServiceLoadHints hints =
        ServiceLoaderCallSiteReader.read(context, edge, effective, scanner);
    List<Descriptor> descriptors = selectedDescriptors(hints, effective);
    Set<String> providers = providerNames(descriptors);
    boolean moduleVisibilityProven = descriptors.stream().noneMatch(Descriptor::moduleInfo);
    boolean unique =
        isUnique(hints, edge, effective, descriptors, providers, moduleVisibilityProven);
    String selection = selection(descriptors, unique);
    return new Resolution(
        certainty(edge, unique),
        evidence(edge, hints, effective, descriptors, providers, selection, cache),
        limitation(unique, effective, descriptors, moduleVisibilityProven));
  }

  private static List<Descriptor> selectedDescriptors(
      ServiceLoaderCallSiteReader.ServiceLoadHints hints, Scan scan) {
    java.util.Map<DescriptorIdentity, Descriptor> selected = new java.util.LinkedHashMap<>();
    scan.descriptors().stream()
        .filter(item -> hints.serviceTypes().contains(item.serviceType()))
        .forEach(item -> selected.merge(
            DescriptorIdentity.from(item), item,
            (first, next) -> first.classpathBacked() || !next.classpathBacked()
                ? first : next));
    return List.copyOf(selected.values());
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
        && providers.size() == 1
        && descriptors.stream().noneMatch(Descriptor::malformed)
        && descriptors.stream().anyMatch(Descriptor::classpathBacked)
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
      String selection,
      ServiceLoaderDescriptorEvidenceCache cache) {
    List<SemanticCallFacts.Evidence> evidence = new ArrayList<>();
    evidence.add(resolutionEvidence(edge, hints, descriptors, providers, selection));
    descriptors.stream()
        .map(descriptor -> cache.descriptor(descriptor, selection))
        .forEach(evidence::add);
    scan.diagnostics().stream()
        .map(cache::diagnostic)
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
    attributes.put(
        "classpathBackedDescriptorCount",
        Integer.toString((int) descriptors.stream().filter(Descriptor::classpathBacked).count()));
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

  static SemanticCallFacts.Evidence descriptorEvidence(
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

  static SemanticCallFacts.Evidence diagnosticEvidence(Diagnostic diagnostic) {
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

    /** True when the descriptor comes only from a source-root resource tree. */
    boolean sourceOnly() {
      return "source_meta_inf_services".equals(kind);
    }

    /** True when the descriptor is backed by output, a dependency, or a selection. */
    boolean classpathBacked() {
      return !sourceOnly();
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
      List<String> malformedEntries) {
    static DescriptorIdentity from(Descriptor descriptor) {
      return new DescriptorIdentity(
          descriptor.serviceType(),
          descriptor.providers(),
          descriptor.fingerprint(),
          descriptor.malformedEntries());
    }
  }
}
