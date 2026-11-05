package com.projectlightpath.analysis.java;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The build, framework, source, dependency, and provenance inputs selected for
 * one adapter run.
 *
 * <p>The adapter deliberately does not fill missing values from analyzer
 * defaults. A caller can construct a context with incomplete framework
 * metadata, but the framework model must then emit possible or unresolved
 * facts rather than promoting them to definite facts.
 */
public final class AnalysisContext {
  public enum Framework {
    QUARKUS("quarkus"),
    SPRING_BOOT("spring-boot");

    private final String id;

    Framework(String id) {
      this.id = id;
    }

    public String id() {
      return id;
    }
  }

  public enum Scope {
    PRODUCTION("production"),
    TEST("test");

    private final String id;

    Scope(String id) {
      this.id = id;
    }

    public String id() {
      return id;
    }
  }

  public enum MetadataStatus {
    OBSERVED,
    NOT_CAPTURED
  }

  public record SourceRoot(
      String declaredPath,
      Path path,
      Scope scope,
      boolean generated,
      boolean included,
      boolean required) {
    public SourceRoot {
      if (declaredPath == null || declaredPath.isBlank()) {
        throw new IllegalArgumentException("source root path must not be blank");
      }
      Objects.requireNonNull(path, "source root path");
      Objects.requireNonNull(scope, "source root scope");
      path = path.toAbsolutePath().normalize();
    }

    public static SourceRoot source(String declaredPath, Path path, Scope scope, boolean required) {
      return new SourceRoot(declaredPath, path, scope, false, true, required);
    }

    public static SourceRoot generated(
        String declaredPath, Path path, Scope scope, boolean included, boolean required) {
      return new SourceRoot(declaredPath, path, scope, true, included, required);
    }
  }

  public record FrameworkMetadata(
      String id, String kind, MetadataStatus status, String declaredPath) {
    public FrameworkMetadata {
      if (id == null || id.isBlank()) {
        throw new IllegalArgumentException("framework metadata id must not be blank");
      }
      if (kind == null || kind.isBlank()) {
        throw new IllegalArgumentException("framework metadata kind must not be blank");
      }
      Objects.requireNonNull(status, "framework metadata status");
    }
  }

  public record Provenance(
      String sourceRepository,
      String sourceReference,
      String sourceRevision,
      String sourceFingerprint,
      String generatedSourceFingerprint,
      String dependencyFingerprint,
      String buildFingerprint,
      String toolchainFingerprint,
      String analyzerFingerprint,
      String gitRevision,
      String providerRuntime,
      String profileDigest,
      String preparedAt,
      long freshnessMaxAgeSeconds) {
    /** Compatibility constructor for programmatic callers and existing tests. */
    public Provenance(
        String sourceFingerprint,
        String generatedSourceFingerprint,
        String dependencyFingerprint,
        String gitRevision,
        String providerRuntime) {
      this(
          "",
          "",
          "",
          sourceFingerprint,
          generatedSourceFingerprint,
          dependencyFingerprint,
          "",
          "",
          "",
          gitRevision,
          providerRuntime,
          "",
          "",
          0);
    }

    public Provenance {
      requireValue(sourceFingerprint, "sourceFingerprint");
      requireValue(generatedSourceFingerprint, "generatedSourceFingerprint");
      requireValue(dependencyFingerprint, "dependencyFingerprint");
      requireValue(gitRevision, "gitRevision");
      requireValue(providerRuntime, "providerRuntime");
      if (freshnessMaxAgeSeconds < 0) {
        throw new IllegalArgumentException("freshnessMaxAgeSeconds must not be negative");
      }
    }

    public static Provenance forTesting() {
      return new Provenance(
          "https://example.test/repository.git",
          "test-revision",
          "test-revision",
          "test-source",
          "test-generated",
          "test-dependencies",
          "test-build",
          "test-toolchain",
          "test-analyzer",
          "test-revision",
          "test-runtime",
          "test-profile",
          "2026-01-01T00:00:00Z",
          0);
    }

    private static void requireValue(String value, String name) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(name + " must not be blank");
      }
    }
  }

  /** Source/build metadata carried alongside the resolved provider inputs. */
  public record Handoff(
      String schemaVersion,
      String profileId,
      String profileVersion,
      String profileDigest,
      String buildTool,
      List<String> buildCommand,
      String buildArtifact,
      String buildFingerprint,
      List<String> limitations) {
    public Handoff {
      buildCommand = List.copyOf(buildCommand == null ? List.of() : buildCommand);
      limitations = List.copyOf(limitations == null ? List.of() : limitations);
    }

    public static Handoff empty() {
      return new Handoff("", "", "", "", "", List.of(), "", "", List.of());
    }
  }

  private final String id;
  private final Framework framework;
  private final String frameworkVersion;
  private final String fixtureId;
  private final int javaRelease;
  private final Path repositoryRoot;
  private final Path applicationClasses;
  private final String selectedProfile;
  private final Scope analysisScope;
  private final List<SourceRoot> sourceRoots;
  private final List<SourceRoot> generatedSourceRoots;
  private final List<Path> dependencyClasspath;
  private final boolean dependencyClasspathRequired;
  private final List<FrameworkMetadata> frameworkMetadata;
  private final Provenance provenance;
  private final Handoff handoff;

  private AnalysisContext(Builder builder) {
    id = requireValue(builder.id, "context id");
    framework = Objects.requireNonNull(builder.framework, "framework");
    frameworkVersion = requireValue(builder.frameworkVersion, "framework version");
    fixtureId = requireValue(builder.fixtureId, "fixture id");
    if (builder.javaRelease <= 0) {
      throw new IllegalArgumentException("java release must be positive");
    }
    javaRelease = builder.javaRelease;
    repositoryRoot = normalize(builder.repositoryRoot, "repository root");
    applicationClasses = normalize(builder.applicationClasses, "application classes");
    selectedProfile = requireValue(builder.selectedProfile, "selected profile");
    analysisScope = Objects.requireNonNull(builder.analysisScope, "analysis scope");
    sourceRoots = List.copyOf(builder.sourceRoots);
    generatedSourceRoots = List.copyOf(builder.generatedSourceRoots);
    dependencyClasspath = builder.dependencyClasspath.stream()
        .map(path -> normalize(path, "dependency classpath entry"))
        .toList();
    dependencyClasspathRequired = builder.dependencyClasspathRequired;
    frameworkMetadata = List.copyOf(builder.frameworkMetadata);
    provenance = Objects.requireNonNull(builder.provenance, "provenance");
    handoff = Objects.requireNonNull(builder.handoff, "handoff");

    if (sourceRoots.stream().noneMatch(root -> !root.generated() && root.required())) {
      throw new IllegalArgumentException("at least one required production/source root is required");
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public String id() {
    return id;
  }

  public Framework framework() {
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

  public Scope analysisScope() {
    return analysisScope;
  }

  public List<SourceRoot> sourceRoots() {
    return sourceRoots;
  }

  public List<SourceRoot> generatedSourceRoots() {
    return generatedSourceRoots;
  }

  public List<SourceRoot> includedSourceRoots() {
    List<SourceRoot> result = new ArrayList<>();
    result.addAll(sourceRoots.stream()
        .filter(SourceRoot::included)
        .filter(root -> root.scope() == analysisScope)
        .toList());
    result.addAll(generatedSourceRoots.stream()
        .filter(SourceRoot::included)
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

  public List<FrameworkMetadata> frameworkMetadata() {
    return frameworkMetadata;
  }

  public Provenance provenance() {
    return provenance;
  }

  public Handoff handoff() {
    return handoff;
  }

  public boolean metadataObserved(String metadataId) {
    return frameworkMetadata.stream()
        .anyMatch(item -> item.id().equals(metadataId) && item.status() == MetadataStatus.OBSERVED);
  }

  public Optional<FrameworkMetadata> metadata(String metadataId) {
    return frameworkMetadata.stream().filter(item -> item.id().equals(metadataId)).findFirst();
  }

  /** Return a stable path relative to the selected source root for provenance. */
  public String displayPath(Path file) {
    Path normalized = file.toAbsolutePath().normalize();
    for (SourceRoot root : includedSourceRoots()) {
      if (normalized.startsWith(root.path())) {
        Path relative = root.path().relativize(normalized);
        String suffix = relative.toString().replace(normalized.getFileSystem().getSeparator(), "/");
        return suffix.isEmpty() ? root.declaredPath() : root.declaredPath() + "/" + suffix;
      }
    }
    return normalized.toString();
  }

  private static String requireValue(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static Path normalize(Path path, String name) {
    return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
  }

  public static final class Builder {
    private String id;
    private Framework framework;
    private String frameworkVersion;
    private String fixtureId;
    private int javaRelease;
    private Path repositoryRoot;
    private Path applicationClasses;
    private String selectedProfile;
    private Scope analysisScope;
    private final List<SourceRoot> sourceRoots = new ArrayList<>();
    private final List<SourceRoot> generatedSourceRoots = new ArrayList<>();
    private final List<Path> dependencyClasspath = new ArrayList<>();
    private boolean dependencyClasspathRequired = true;
    private final List<FrameworkMetadata> frameworkMetadata = new ArrayList<>();
    private Provenance provenance;
    private Handoff handoff = Handoff.empty();

    public Builder id(String value) {
      id = value;
      return this;
    }

    public Builder framework(Framework value) {
      framework = value;
      return this;
    }

    public Builder frameworkVersion(String value) {
      frameworkVersion = value;
      return this;
    }

    public Builder fixtureId(String value) {
      fixtureId = value;
      return this;
    }

    public Builder javaRelease(int value) {
      javaRelease = value;
      return this;
    }

    public Builder repositoryRoot(Path value) {
      repositoryRoot = value;
      return this;
    }

    public Builder applicationClasses(Path value) {
      applicationClasses = value;
      return this;
    }

    public Builder selectedProfile(String value) {
      selectedProfile = value;
      return this;
    }

    public Builder analysisScope(Scope value) {
      analysisScope = value;
      return this;
    }

    public Builder sourceRoot(String declaredPath, Path path, Scope scope, boolean required) {
      sourceRoots.add(SourceRoot.source(declaredPath, path, scope, required));
      return this;
    }

    public Builder generatedSourceRoot(
        String declaredPath, Path path, Scope scope, boolean included, boolean required) {
      generatedSourceRoots.add(SourceRoot.generated(declaredPath, path, scope, included, required));
      return this;
    }

    public Builder dependencyClasspath(List<Path> paths) {
      dependencyClasspath.clear();
      dependencyClasspath.addAll(paths);
      return this;
    }

    public Builder addDependencyClasspath(Path path) {
      dependencyClasspath.add(path);
      return this;
    }

    public Builder dependencyClasspathRequired(boolean value) {
      dependencyClasspathRequired = value;
      return this;
    }

    public Builder frameworkMetadata(FrameworkMetadata metadata) {
      frameworkMetadata.add(metadata);
      return this;
    }

    public Builder frameworkMetadata(
        String id, String kind, MetadataStatus status, String declaredPath) {
      return frameworkMetadata(new FrameworkMetadata(id, kind, status, declaredPath));
    }

    public Builder provenance(Provenance value) {
      provenance = value;
      return this;
    }

    public Builder handoff(Handoff value) {
      handoff = value;
      return this;
    }

    public AnalysisContext build() {
      return new AnalysisContext(this);
    }
  }
}
