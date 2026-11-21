package com.projectlightpath.analysis.java;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Shared immutable state and accessors for one analysis context. */
abstract class AnalysisContextAccessors {
  final String id;
  final AnalysisContext.Framework framework;
  final String frameworkVersion;
  final String fixtureId;
  final int javaRelease;
  final Path repositoryRoot;
  final Path applicationClasses;
  final String selectedProfile;
  final AnalysisContext.Scope analysisScope;
  final List<AnalysisContext.SourceRoot> sourceRoots;
  final List<AnalysisContext.SourceRoot> generatedSourceRoots;
  final List<Path> dependencyClasspath;
  final boolean dependencyClasspathRequired;
  final AnalysisContext.DependencyCoordinateManifest dependencyCoordinateManifest;
  final List<AnalysisContext.FrameworkMetadata> frameworkMetadata;
  final AnalysisContext.FrameworkBindingMetadata frameworkBindingMetadata;
  final AnalysisContext.Provenance provenance;
  final AnalysisContext.Handoff handoff;

  AnalysisContextAccessors(AnalysisContext.Builder builder) {
    id = AnalysisContext.requireValue(builder.id, "context id");
    framework = java.util.Objects.requireNonNull(builder.framework, "framework");
    frameworkVersion = AnalysisContext.requireValue(builder.frameworkVersion, "framework version");
    fixtureId = AnalysisContext.requireValue(builder.fixtureId, "fixture id");
    if (builder.javaRelease <= 0) {
      throw new IllegalArgumentException("java release must be positive");
    }
    javaRelease = builder.javaRelease;
    repositoryRoot = AnalysisContext.normalize(builder.repositoryRoot, "repository root");
    applicationClasses = AnalysisContext.normalize(builder.applicationClasses, "application classes");
    selectedProfile = AnalysisContext.requireValue(builder.selectedProfile, "selected profile");
    analysisScope = java.util.Objects.requireNonNull(builder.analysisScope, "analysis scope");
    sourceRoots = List.copyOf(builder.sourceRoots);
    generatedSourceRoots = List.copyOf(builder.generatedSourceRoots);
    dependencyClasspath = builder.dependencyClasspath.stream()
        .map(path -> AnalysisContext.normalize(path, "dependency classpath entry"))
        .toList();
    dependencyClasspathRequired = builder.dependencyClasspathRequired;
    dependencyCoordinateManifest = java.util.Objects.requireNonNull(
        builder.dependencyCoordinateManifest, "dependency coordinate manifest");
    frameworkMetadata = List.copyOf(builder.frameworkMetadata);
    frameworkBindingMetadata = java.util.Objects.requireNonNull(
        builder.frameworkBindingMetadata, "framework binding metadata");
    provenance = java.util.Objects.requireNonNull(builder.provenance, "provenance");
    handoff = java.util.Objects.requireNonNull(builder.handoff, "handoff");
  }

  public String id() {
    return id;
  }

  public AnalysisContext.Framework framework() {
    return framework;
  }

  public String frameworkVersion() {
    return frameworkVersion;
  }

  public String fixtureId() {
    return fixtureId;
  }

  public int javaRelease() {
    return javaRelease;
  }

  public Path repositoryRoot() {
    return repositoryRoot;
  }

  public Path applicationClasses() {
    return applicationClasses;
  }

  public String selectedProfile() {
    return selectedProfile;
  }

  public AnalysisContext.Scope analysisScope() {
    return analysisScope;
  }

  public List<AnalysisContext.SourceRoot> sourceRoots() {
    return sourceRoots;
  }

  public List<AnalysisContext.SourceRoot> generatedSourceRoots() {
    return generatedSourceRoots;
  }

  /** Returns source roots explicitly included in this analysis. */
  public List<AnalysisContext.SourceRoot> includedSourceRoots() {
    List<AnalysisContext.SourceRoot> result = new ArrayList<>();
    result.addAll(sourceRoots.stream()
        .filter(AnalysisContext.SourceRoot::included)
        .filter(root -> root.scope() == analysisScope)
        .toList());
    result.addAll(generatedSourceRoots.stream()
        .filter(AnalysisContext.SourceRoot::included)
        .filter(root -> root.scope() == analysisScope)
        .toList());
    return List.copyOf(result);
  }

  public List<Path> dependencyClasspath() {
    return dependencyClasspath;
  }

  public boolean dependencyClasspathRequired() {
    return dependencyClasspathRequired;
  }

  public AnalysisContext.DependencyCoordinateManifest dependencyCoordinateManifest() {
    return dependencyCoordinateManifest;
  }

  public List<AnalysisContext.FrameworkMetadata> frameworkMetadata() {
    return frameworkMetadata;
  }

  public AnalysisContext.FrameworkBindingMetadata frameworkBindingMetadata() {
    return frameworkBindingMetadata;
  }

  public List<AnalysisContext.BindingCandidateMetadata> bindingCandidates() {
    return frameworkBindingMetadata.candidates();
  }

  public List<AnalysisContext.ResolvedBindingMetadata> resolvedBindings() {
    return frameworkBindingMetadata.resolvedBindings();
  }

  public List<AnalysisContext.ProxyTargetMetadata> proxyTargetMetadata() {
    return frameworkBindingMetadata.proxyTargets();
  }

  public AnalysisContext.Provenance provenance() {
    return provenance;
  }

  public AnalysisContext.Handoff handoff() {
    return handoff;
  }

  /** Reports whether a framework metadata artifact was observed. */
  public boolean metadataObserved(String metadataId) {
    return frameworkMetadata.stream()
        .anyMatch(item -> item.id().equals(metadataId)
            && item.status() == AnalysisContext.MetadataStatus.OBSERVED);
  }

  /** Looks up one framework metadata declaration by ID. */
  public Optional<AnalysisContext.FrameworkMetadata> metadata(String metadataId) {
    return frameworkMetadata.stream()
        .filter(item -> item.id().equals(metadataId))
        .findFirst();
  }

  /** Returns a stable path relative to the selected source root for provenance. */
  public String displayPath(Path file) {
    Path normalized = file.toAbsolutePath().normalize();
    Optional<AnalysisContext.SourceRoot> root = AnalysisContext.mostSpecificRoot(
        normalized, includedSourceRoots());
    if (root.isPresent()) {
      Path relative = root.get().path().relativize(normalized);
      String suffix = relative.toString().replace(
          normalized.getFileSystem().getSeparator(), "/");
      return suffix.isEmpty()
          ? root.get().declaredPath()
          : root.get().declaredPath() + "/" + suffix;
    }
    return normalized.toString();
  }
}
