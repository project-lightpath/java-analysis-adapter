package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Production integration boundary: SootUp base graph plus project-owned
 * FrameworkModel and AnalysisContext produces separate SemanticCallFacts sets.
 */
public final class JavaAnalysisAdapter {
  private final SootUpBaseGraphProvider baseGraphProvider;
  private final FrameworkModel frameworkModel;

  public JavaAnalysisAdapter() {
    this(new SootUpBaseGraphProvider(), null);
  }

  public JavaAnalysisAdapter(SootUpBaseGraphProvider baseGraphProvider) {
    this(baseGraphProvider, null);
  }

  public JavaAnalysisAdapter(SootUpBaseGraphProvider baseGraphProvider, FrameworkModel frameworkModel) {
    this.baseGraphProvider = Objects.requireNonNull(baseGraphProvider, "base graph provider");
    this.frameworkModel = frameworkModel;
  }

  /** Builds the provider graph before applying framework enrichment. */
  public SemanticCallFacts analyze(AnalysisContext context) {
    Objects.requireNonNull(context, "analysis context");
    if (context.inputMode() == AnalysisContext.InputMode.SOURCE_ARCHIVE) {
      return analyzeSourceArchive(context);
    }
    SootUpInputLocationFactory.DependencyResolution dependencies =
        SootUpInputLocationFactory.dependencyResolution(context);
    AnalysisGraph graph = baseGraphProvider.analyze(context, dependencies);
    return enrich(context, graph, () -> dependencies);
  }

  /**
   * SOURCE_ARCHIVE routing (issue #42): the archives are extracted to one
   * owner-only temp root, the extracted trees become the run's source roots
   * for the existing source/javac analysis path, and the descriptor scan
   * follows the mode routing (no binary scan of the archives themselves).
   * The final facts carry the caller's original context so the random
   * extraction root never reaches RunMetadata.
   */
  private SemanticCallFacts analyzeSourceArchive(AnalysisContext context) {
    try (ServiceArchiveExtractor.Extraction extraction =
        ServiceArchiveExtractor.extract(context.sourceArchives())) {
      AnalysisContext derived = withExtractedSourceRoots(context, extraction);
      SootUpInputLocationFactory.DependencyResolution dependencies =
          SootUpInputLocationFactory.dependencyResolution(derived);
      // An optional classpath that resolves to nothing (e.g. an empty
      // deps/* wildcard) must still reach enrichment so the scan emits
      // descriptor_metadata_unavailable_for_source_archive instead of the
      // graph provider's "no valid entries" rejection (PR #47 review CR-3).
      AnalysisContext graphContext = derived;
      if (dependencies.entries().isEmpty()
          && !derived.dependencyClasspathRequired()
          && !derived.dependencyClasspath().isEmpty()) {
        graphContext = withDependencyClasspath(derived, List.of());
      }
      AnalysisGraph graph = baseGraphProvider.analyze(graphContext, dependencies);
      SemanticCallFacts facts = enrich(derived, graph, () -> dependencies);
      return new SemanticCallFacts(
          context,
          graph,
          facts.providerFacts(),
          facts.frameworkFacts(),
          facts.links(),
          facts.diagnostics());
    }
  }

  /** Copies the context with the extracted trees as its required source roots. */
  private static AnalysisContext withExtractedSourceRoots(
      AnalysisContext context, ServiceArchiveExtractor.Extraction extraction) {
    List<AnalysisContext.SourceRoot> roots = new ArrayList<>();
    for (int index = 0; index < extraction.archiveRoots().size(); index++) {
      roots.add(AnalysisContext.SourceRoot.source(
          extraction.declaredPaths().get(index),
          extraction.archiveRoots().get(index),
          AnalysisContext.Scope.PRODUCTION,
          true,
          null));
    }
    return copyContext(context, context.dependencyClasspath(), roots);
  }

  /** Copies the context with a replaced dependency classpath. */
  private static AnalysisContext withDependencyClasspath(
      AnalysisContext context, List<java.nio.file.Path> classpath) {
    return copyContext(context, classpath, context.sourceRoots());
  }

  /**
   * One context copy: the caller names the dependency classpath and the
   * source roots to install (the extracted trees for a SOURCE_ARCHIVE run,
   * or the original roots when only the classpath changes).
   */
  private static AnalysisContext copyContext(
      AnalysisContext context,
      List<java.nio.file.Path> dependencyClasspath,
      List<AnalysisContext.SourceRoot> sourceRoots) {
    AnalysisContext.Builder builder = AnalysisContext.builder()
        .id(context.id())
        .framework(context.framework())
        .frameworkVersion(context.frameworkVersion())
        .fixtureId(context.fixtureId())
        .javaRelease(context.javaRelease())
        .repositoryRoot(context.repositoryRoot())
        .applicationClasses(context.applicationClasses())
        .selectedProfile(context.selectedProfile())
        .analysisScope(context.analysisScope())
        .inputMode(context.inputMode())
        .sourceArchives(context.sourceArchives())
        .extractedSourceRoots()
        .dependencyClasspathRequired(context.dependencyClasspathRequired())
        .dependencyClasspath(dependencyClasspath)
        .dependencyCoordinateManifest(context.dependencyCoordinateManifest())
        .frameworkBindingMetadata(context.frameworkBindingMetadata())
        .provenance(context.provenance())
        .handoff(context.handoff());
    for (AnalysisContext.FrameworkMetadata metadata : context.frameworkMetadata()) {
      builder.frameworkMetadata(metadata);
    }
    for (AnalysisContext.SourceRoot root : context.generatedSourceRoots()) {
      builder.generatedSourceRoot(root.declaredPath(), root.path(), root.scope(),
          root.included(), root.required(), root.fingerprint());
    }
    for (AnalysisContext.SourceRoot root : sourceRoots) {
      builder.sourceRoot(root.declaredPath(), root.path(), root.scope(), root.required(),
          root.fingerprint());
    }
    return builder.build();
  }

  /** Caller owns graph/input consistency; enrichment uses fresh, lazily resolved inputs. */
  public SemanticCallFacts analyze(AnalysisContext context, AnalysisGraph graph) {
    Objects.requireNonNull(context, "analysis context");
    Objects.requireNonNull(graph, "analysis graph");
    if (context.inputMode() == AnalysisContext.InputMode.SOURCE_ARCHIVE) {
      // Fail closed: a prebuilt graph cannot carry the extracted source
      // roots the single-argument path derives, so silently enriching it
      // would skip the source/javac half of the run (issue #42 review F1).
      throw new IllegalArgumentException(
          "inputMode SOURCE_ARCHIVE requires the single-argument analyze path");
    }
    return enrich(context, graph, () -> SootUpInputLocationFactory.dependencyResolution(context));
  }

  private SemanticCallFacts enrich(
      AnalysisContext context,
      AnalysisGraph graph,
      Supplier<SootUpInputLocationFactory.DependencyResolution> dependencies) {
    FrameworkModel selectedModel = frameworkModel == null
        ? FrameworkModels.forContext(context.framework())
        : frameworkModel;
    List<SemanticCallFacts.Diagnostic> diagnostics = springDataDiagnostics(context);
    List<SemanticCallFacts.Fact> providerFacts = new ArrayList<>(
        ProviderFactNormalizer.normalize(context, graph, dependencies, diagnostics));
    List<SemanticCallFacts.Fact> frameworkFacts = attachExactProviderIdentities(
        selectedModel.enrich(context, graph), providerFacts);
    return new SemanticCallFacts(
        context,
        graph,
        providerFacts,
        frameworkFacts,
        links(providerFacts, frameworkFacts),
        diagnostics);
  }

  /**
   * Computes the Spring Data scan-global diagnostics once, from the (derived)
   * context: an out-of-scope repository base class blocks DEFINITE promotion
   * for the whole scan and must stay visible on the facts output.
   */
  private static List<SemanticCallFacts.Diagnostic> springDataDiagnostics(
      AnalysisContext context) {
    if (context.framework() != AnalysisContext.Framework.SPRING_BOOT) {
      return List.of();
    }
    SourceMetadataScanner scanner = new SourceMetadataScanner(context);
    List<SourceMetadataScannerState.RepositoryBaseClass> baseClasses =
        scanner.springRepositoryBaseClasses();
    List<SemanticCallFacts.Diagnostic> result = new ArrayList<>();
    for (SourceMetadataScannerState.RepositoryBaseClass baseClass : baseClasses) {
      if (!baseClass.referenceResolved()) {
        result.add(new SemanticCallFacts.Diagnostic(
            "spring_data_repository_base_class_unresolved",
            baseClass.sourcePath(),
            "repositoryBaseClass reference " + baseClass.sourceSpelling()
                + " cannot be resolved from the analyzed sources"));
      } else if (!baseClass.applicationScoped()) {
        result.add(new SemanticCallFacts.Diagnostic(
            "spring_data_backing_selection_out_of_scope",
            baseClass.sourcePath(),
            "repositoryBaseClass " + baseClass.configuredType()
                + " is outside the analysis scope"));
      }
    }
    for (Map.Entry<String, String> unresolved : scanner.unresolvedRepositoryExtensions()) {
      result.add(new SemanticCallFacts.Diagnostic(
          "spring_data_repository_fragment_unresolved",
          unresolved.getKey(),
          "repository extends-clause reference " + unresolved.getValue()
              + " resolves to no type"));
    }
    for (Map.Entry<String, String> unconfirmed : scanner.unconfirmedFragmentImplementations()) {
      result.add(new SemanticCallFacts.Diagnostic(
          "spring_data_repository_fragment_impl_unconfirmed",
          unconfirmed.getKey(),
          "the conventional Impl class does not name fragment interface "
              + unconfirmed.getValue() + " in its implements clause"));
    }
    if (baseClasses.stream()
        .map(SourceMetadataScannerState.RepositoryBaseClass::configuredType)
        .distinct().count() > 1) {
      result.add(new SemanticCallFacts.Diagnostic(
          "spring_data_backing_selection_ambiguous",
          baseClasses.get(0).sourcePath(),
          "multiple repositoryBaseClass values are configured: "
              + baseClasses.stream()
                  .map(SourceMetadataScannerState.RepositoryBaseClass::configuredType)
                  .distinct().toList()));
    }
    return result;
  }

  /** Abstains open-world dynamic evidence and attaches identities for eligible targets. */
  private static List<SemanticCallFacts.Fact> attachExactProviderIdentities(
      List<SemanticCallFacts.Fact> frameworkFacts,
      List<SemanticCallFacts.Fact> providerFacts) {
    List<SemanticCallFacts.Fact> result = new ArrayList<>();
    for (SemanticCallFacts.Fact frameworkFact : frameworkFacts) {
      if (DynamicCertaintyPolicy.isOpenWorldFrameworkFact(frameworkFact)) {
        result.add(frameworkFact.abstainDynamic());
        continue;
      }
      if (frameworkFact.derivedFrom() != null) {
        boolean providerExists = providerFacts.stream()
            .anyMatch(provider -> provider.id().equals(frameworkFact.derivedFrom())
                && provider.certainty() != SemanticCallFacts.Certainty.UNRESOLVED);
        result.add(providerExists
            ? frameworkFact
            : frameworkFact.withDerivedFrom(null));
        continue;
      }
      if (frameworkFact.relationshipType().equals("framework_entrypoint")) {
        result.add(frameworkFact);
        continue;
      }
      List<SemanticCallFacts.Fact> exactTargets = providerFacts.stream()
          .filter(provider -> provider.certainty() != SemanticCallFacts.Certainty.UNRESOLVED)
          .filter(provider -> provider.callee().equals(frameworkFact.callee()))
          .filter(provider -> targetDescriptorsMatch(frameworkFact, provider))
          .filter(provider -> !provider.relationshipType().equals("reflection"))
          .filter(provider -> !provider.relationshipType().equals("proxy_dispatch")
              || frameworkFact.relationshipType().equals("proxy_dispatch"))
          .toList();
      result.add(exactTargets.size() == 1
          ? frameworkFact.withDerivedFrom(exactTargets.get(0).id())
          : frameworkFact);
    }
    return List.copyOf(result);
  }

  /** Requires matching full target descriptors before creating an implicit provider link. */
  private static boolean targetDescriptorsMatch(
      SemanticCallFacts.Fact frameworkFact,
      SemanticCallFacts.Fact providerFact) {
    Optional<String> frameworkDescriptor = evidenceAttribute(frameworkFact, "targetDescriptor");
    Optional<String> providerDescriptor = evidenceAttribute(providerFact, "calleeSignature");
    return frameworkDescriptor.isPresent()
        && providerDescriptor.isPresent()
        && frameworkDescriptor.get().equals(providerDescriptor.get());
  }

  /** Finds a nonblank identity attribute in framework or provider evidence. */
  private static Optional<String> evidenceAttribute(
      SemanticCallFacts.Fact fact,
      String attribute) {
    return fact.evidence().stream()
        .map(evidence -> evidence.attributes().get(attribute))
        .filter(value -> value != null && !value.isBlank())
        .findFirst();
  }

  /** Creates non-replacing enrichment links for exact provider matches. */
  private static List<SemanticCallFacts.Link> links(
      List<SemanticCallFacts.Fact> providerFacts,
      List<SemanticCallFacts.Fact> frameworkFacts) {
    Map<String, SemanticCallFacts.Fact> providers = new HashMap<>();
    providerFacts.forEach(fact -> providers.put(fact.id(), fact));
    List<SemanticCallFacts.Link> result = new ArrayList<>();
    for (SemanticCallFacts.Fact frameworkFact : frameworkFacts) {
      if (DynamicCertaintyPolicy.isOpenWorldFrameworkFact(frameworkFact)) {
        continue;
      }
      if (frameworkFact.derivedFrom() == null
          && !frameworkFact.relationshipType().equals("framework_entrypoint")) {
        continue;
      }
      SemanticCallFacts.Fact provider = providers.get(frameworkFact.derivedFrom());
      if (provider != null && provider.certainty() != SemanticCallFacts.Certainty.UNRESOLVED) {
        result.add(new SemanticCallFacts.Link(
            frameworkFact.id(),
            provider.id(),
            "enriches_without_replacing",
            true));
      }
    }
    return List.copyOf(result);
  }

}
