package com.projectlightpath.analysis.java;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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

  /** Presence state for the optional dependency-coordinate handoff. */
  public enum DependencyCoordinateManifestPresence {
    ABSENT,
    PRESENT_EMPTY,
    PRESENT_NONEMPTY
  }

  public record SourceRoot(
      String declaredPath,
      Path path,
      Scope scope,
      boolean generated,
      boolean included,
      boolean required,
      String fingerprint) {
    /** Compatibility constructor for callers that do not provide a root fingerprint. */
    public SourceRoot(
        String declaredPath,
        Path path,
        Scope scope,
        boolean generated,
        boolean included,
        boolean required) {
      this(declaredPath, path, scope, generated, included, required, null);
    }

    /** Validates and normalizes one source root declaration. */
    public SourceRoot {
      if (declaredPath == null || declaredPath.isBlank()) {
        throw new IllegalArgumentException("source root path must not be blank");
      }
      Objects.requireNonNull(path, "source root path");
      Objects.requireNonNull(scope, "source root scope");
      path = path.toAbsolutePath().normalize();
    }

    /** Creates an included application source root. */
    public static SourceRoot source(String declaredPath, Path path, Scope scope, boolean required) {
      return source(declaredPath, path, scope, required, null);
    }

    public static SourceRoot source(
        String declaredPath, Path path, Scope scope, boolean required, String fingerprint) {
      return new SourceRoot(declaredPath, path, scope, false, true, required, fingerprint);
    }

    public static SourceRoot generated(
        String declaredPath, Path path, Scope scope, boolean included, boolean required) {
      return generated(declaredPath, path, scope, included, required, null);
    }

    public static SourceRoot generated(
        String declaredPath,
        Path path,
        Scope scope,
        boolean included,
        boolean required,
        String fingerprint) {
      return new SourceRoot(declaredPath, path, scope, true, included, required, fingerprint);
    }
  }

  public record FrameworkMetadata(
      String id, String kind, MetadataStatus status, String declaredPath) {
    /** Validates one framework metadata declaration. */
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

  /** Versioned upstream metadata binding dependency paths to supplied Maven coordinates. */
  public record DependencyCoordinateManifest(
      String schemaVersion,
      String owner,
      String fingerprint,
      List<DependencyCoordinateEntry> entries,
      DependencyCoordinateManifestPresence presence) {
    public static final String SUPPORTED_SCHEMA_VERSION = "0.1.0";
    public static final String OWNER =
        "vulnerability-prioritization-engine/analysiscontext.Coordinator.Prepare";

    /** Creates a present manifest and derives its empty/non-empty state. */
    public DependencyCoordinateManifest(
        String schemaVersion,
        String owner,
        String fingerprint,
        List<DependencyCoordinateEntry> entries) {
      this(
          schemaVersion,
          owner,
          fingerprint,
          entries,
          entries == null || entries.isEmpty()
              ? DependencyCoordinateManifestPresence.PRESENT_EMPTY
              : DependencyCoordinateManifestPresence.PRESENT_NONEMPTY);
    }

    /** Compatibility constructor for the historical three-argument model. */
    public DependencyCoordinateManifest(
        String schemaVersion, String fingerprint, List<DependencyCoordinateEntry> entries) {
      this(schemaVersion, OWNER, fingerprint, entries);
    }

    /** Creates a backward-compatible absent manifest state. */
    public static DependencyCoordinateManifest absent() {
      return new DependencyCoordinateManifest(
          SUPPORTED_SCHEMA_VERSION,
          OWNER,
          null,
          List.of(),
          DependencyCoordinateManifestPresence.ABSENT);
    }

    /** Historical name for the absent, backward-compatible state. */
    public static DependencyCoordinateManifest empty() {
      return absent();
    }

    /** Creates the explicit present-empty state used for required no-dependency runs. */
    public static DependencyCoordinateManifest presentEmpty() {
      return new DependencyCoordinateManifest(
          SUPPORTED_SCHEMA_VERSION,
          OWNER,
          null,
          List.of(),
          DependencyCoordinateManifestPresence.PRESENT_EMPTY);
    }

    /** Validates and freezes the dependency coordinate handoff. */
    public DependencyCoordinateManifest {
      if (!SUPPORTED_SCHEMA_VERSION.equals(schemaVersion)) {
        throw new IllegalArgumentException(
            "unsupported dependency coordinate manifest schemaVersion: " + schemaVersion);
      }
      if (!OWNER.equals(owner)) {
        throw new IllegalArgumentException("unsupported dependency coordinate manifest owner: " + owner);
      }
      presence = Objects.requireNonNull(presence, "dependency coordinate manifest presence");
      entries = List.copyOf(entries == null ? List.of() : entries);
      fingerprint = optionalFingerprint(fingerprint, "dependency coordinate manifest fingerprint");
      Set<Path> paths = new HashSet<>();
      for (DependencyCoordinateEntry entry : entries) {
        Objects.requireNonNull(entry, "dependency coordinate manifest entry");
        if (!paths.add(entry.classpathEntry())) {
          throw new IllegalArgumentException(
              "duplicate dependency coordinate manifest path: " + entry.classpathEntry());
        }
      }
      if (presence == DependencyCoordinateManifestPresence.ABSENT && (!entries.isEmpty() || fingerprint != null)) {
        throw new IllegalArgumentException("absent dependency coordinate manifest must be empty");
      }
      if (presence == DependencyCoordinateManifestPresence.PRESENT_EMPTY && !entries.isEmpty()) {
        throw new IllegalArgumentException("present-empty dependency coordinate manifest must have no entries");
      }
      if (presence == DependencyCoordinateManifestPresence.PRESENT_EMPTY && fingerprint != null) {
        throw new IllegalArgumentException(
            "present-empty dependency coordinate manifest must omit fingerprint");
      }
      if (presence == DependencyCoordinateManifestPresence.PRESENT_NONEMPTY && entries.isEmpty()) {
        throw new IllegalArgumentException("present-nonempty dependency coordinate manifest must have entries");
      }
      if (presence == DependencyCoordinateManifestPresence.PRESENT_NONEMPTY && fingerprint == null) {
        throw new IllegalArgumentException(
            "dependency coordinate manifest fingerprint is required for non-empty entries");
      }
    }

    /** Finds metadata for one normalized dependency path without discovering any metadata. */
    public Optional<DependencyCoordinateEntry> entryFor(Path path) {
      Path normalized = normalize(path, "dependency coordinate lookup path");
      return entries.stream()
          .filter(entry -> entry.classpathEntry().equals(normalized))
          .findFirst();
    }

    public boolean present() {
      return presence != DependencyCoordinateManifestPresence.ABSENT;
    }

    public boolean hasEntries() {
      return !entries.isEmpty();
    }
  }

  /** One upstream-owned dependency path, fingerprint, and Maven coordinate tuple. */
  public record DependencyCoordinateEntry(
      Path classpathEntry,
      String fingerprint,
      String groupId,
      String artifactId,
      String version,
      String classifier) {
    /** Validates and normalizes one dependency coordinate row. */
    public DependencyCoordinateEntry {
      classpathEntry = normalize(classpathEntry, "dependency coordinate classpath entry");
      if (classpathEntry.toString().contains("*")) {
        throw new IllegalArgumentException("dependency coordinate classpathEntry must be concrete");
      }
      requireValue(groupId, "dependency coordinate groupId");
      requireValue(artifactId, "dependency coordinate artifactId");
      requireValue(version, "dependency coordinate version");
      fingerprint = optionalFingerprint(fingerprint, "dependency coordinate entry fingerprint");
      classifier = optionalValue(classifier, "dependency coordinate classifier");
    }

    /** Convenience constructor for callers using serialized path strings. */
    public DependencyCoordinateEntry(
        String classpathEntry,
        String fingerprint,
        String groupId,
        String artifactId,
        String version,
        String classifier) {
      this(Path.of(classpathEntry), fingerprint, groupId, artifactId, version, classifier);
    }

  }

  /** Versioned, candidate-level framework evidence used for binding resolution. */
  public record FrameworkBindingMetadata(
      String schemaVersion,
      List<BindingCandidateMetadata> candidates,
      List<ResolvedBindingMetadata> resolvedBindings,
      List<ProxyTargetMetadata> proxyTargets) {
    /** Validates and freezes the versioned binding metadata payload. */
    public FrameworkBindingMetadata {
      schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? "0.1.0" : schemaVersion;
      if (!"0.1.0".equals(schemaVersion)) {
        throw new IllegalArgumentException(
            "unsupported framework binding metadata schemaVersion: " + schemaVersion);
      }
      candidates = List.copyOf(candidates == null ? List.of() : candidates);
      resolvedBindings = List.copyOf(resolvedBindings == null ? List.of() : resolvedBindings);
      proxyTargets = List.copyOf(proxyTargets == null ? List.of() : proxyTargets);
    }

    /** Creates an empty version-compatible binding metadata payload. */
    public static FrameworkBindingMetadata empty() {
      return new FrameworkBindingMetadata("0.1.0", List.of(), List.of(), List.of());
    }
  }

  /** One active or inactive CDI/Spring bean registration candidate. */
  public record BindingCandidateMetadata(
      String candidateId,
      String candidateType,
      List<String> contracts,
      List<String> qualifiers,
      String kind,
      String declaringType,
      String producerMethod,
      String producerSignature,
      List<String> profiles,
      List<String> conditions,
      Boolean active,
      boolean alternative,
      boolean primary,
      String sourcePath,
      String providerFactId) {
    /** Validates and freezes one binding candidate row. */
    public BindingCandidateMetadata {
      requireValue(candidateId, "binding candidate id");
      requireValue(candidateType, "binding candidate type");
      requireValue(kind, "binding candidate kind");
      contracts = List.copyOf(contracts == null ? List.of() : contracts);
      qualifiers = List.copyOf(qualifiers == null ? List.of() : qualifiers);
      profiles = List.copyOf(profiles == null ? List.of() : profiles);
      conditions = List.copyOf(conditions == null ? List.of() : conditions);
    }

    /** Convenience constructor for the common class/component candidate shape. */
    public BindingCandidateMetadata(
        String candidateId,
        String candidateType,
        List<String> contracts,
        List<String> qualifiers,
        String kind,
        Boolean active,
        boolean alternative,
        boolean primary) {
      this(
          candidateId,
          candidateType,
          contracts,
          qualifiers,
          kind,
          null,
          null,
          null,
          List.of(),
          List.of(),
          active,
          alternative,
          primary,
          null,
          null);
    }
  }

  /** Framework's resolved view of one injection point and candidate. */
  public record ResolvedBindingMetadata(
      String bindingId,
      String injectionOwner,
      String injectionMember,
      String injectionType,
      Integer parameterIndex,
      List<String> qualifiers,
      String candidateId,
      Boolean selected,
      Boolean active,
      String selectionReason,
      String sourcePath,
      String providerFactId) {
    /** Validates and freezes one resolved binding row. */
    public ResolvedBindingMetadata {
      requireValue(bindingId, "resolved binding id");
      requireValue(injectionOwner, "resolved binding injection owner");
      requireValue(injectionType, "resolved binding injection type");
      requireValue(candidateId, "resolved binding candidate id");
      if (parameterIndex != null && parameterIndex < 0) {
        throw new IllegalArgumentException("resolved binding parameterIndex must not be negative");
      }
      qualifiers = List.copyOf(qualifiers == null ? List.of() : qualifiers);
    }

    /** Convenience constructor for a selected constructor parameter binding. */
    public ResolvedBindingMetadata(
        String bindingId,
        String injectionOwner,
        String injectionType,
        int parameterIndex,
        String candidateId) {
      this(
          bindingId,
          injectionOwner,
          "<init>",
          injectionType,
          parameterIndex,
          List.of(),
          candidateId,
          true,
          true,
          "exact framework selection",
          null,
          null);
    }
  }

  /** Framework-generated proxy/interceptor target evidence for one bean method. */
  public record ProxyTargetMetadata(
      String proxyTargetId,
      String candidateId,
      String targetType,
      String methodName,
      String methodDescriptor,
      String proxyKind,
      Boolean selected,
      Boolean active,
      String selectionReason,
      String sourcePath,
      String providerFactId) {
    /** Validates one framework proxy target row. */
    public ProxyTargetMetadata {
      requireValue(proxyTargetId, "proxy target id");
      requireValue(targetType, "proxy target type");
      requireValue(methodName, "proxy target method");
      requireValue(proxyKind, "proxy target kind");
    }

    /** Convenience constructor for an exact target row without a bytecode descriptor. */
    public ProxyTargetMetadata(
        String proxyTargetId,
        String targetType,
        String methodName,
        String proxyKind,
        Boolean selected,
        Boolean active) {
      this(
          proxyTargetId,
          null,
          targetType,
          methodName,
          null,
          proxyKind,
          selected,
          active,
          "exact framework proxy target",
          null,
          null);
    }
  }

  public record Provenance(
      String sourceRepository,
      String sourceReference,
      String sourceRevision,
      String sourceFingerprint,
      String generatedSourceFingerprint,
      String dependencyFingerprint,
      String dependencyClasspathOrderFingerprint,
      String dependencyCoordinateFingerprint,
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
           null,
           null,
           "",
           "",
           "",
          gitRevision,
          providerRuntime,
          "",
          "",
           0);
    }

    /** Compatibility constructor for serialized contexts predating ordered fingerprints. */
    public Provenance(
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
      this(
          sourceRepository,
          sourceReference,
          sourceRevision,
          sourceFingerprint,
          generatedSourceFingerprint,
          dependencyFingerprint,
          null,
          null,
          buildFingerprint,
          toolchainFingerprint,
          analyzerFingerprint,
          gitRevision,
          providerRuntime,
          profileDigest,
          preparedAt,
          freshnessMaxAgeSeconds);
    }

    /** Validates provenance fingerprints and freshness policy. */
    public Provenance {
      requireValue(sourceFingerprint, "sourceFingerprint");
      requireValue(generatedSourceFingerprint, "generatedSourceFingerprint");
      requireValue(dependencyFingerprint, "dependencyFingerprint");
      dependencyClasspathOrderFingerprint = optionalFingerprint(
          dependencyClasspathOrderFingerprint, "dependencyClasspathOrderFingerprint");
      dependencyCoordinateFingerprint = optionalFingerprint(
          dependencyCoordinateFingerprint, "dependencyCoordinateFingerprint");
      requireValue(gitRevision, "gitRevision");
      requireValue(providerRuntime, "providerRuntime");
      if (freshnessMaxAgeSeconds < 0) {
        throw new IllegalArgumentException("freshnessMaxAgeSeconds must not be negative");
      }
    }

    /** Creates deterministic provenance for unit tests. */
    public static Provenance forTesting() {
      return new Provenance(
          "https://example.test/repository.git",
          "test-revision",
          "test-revision",
           "test-source",
           "test-generated",
           "test-dependencies",
           null,
           null,
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
      List<String> limitations,
      List<String> activeMavenProfiles,
      Map<String, String> mavenProfileActivationSources,
      String mavenInvocationFingerprint,
      String effectivePomFingerprint) {
    /** Compatibility constructor for handoffs without replay metadata. */
    public Handoff(
        String schemaVersion,
        String profileId,
        String profileVersion,
        String profileDigest,
        String buildTool,
        List<String> buildCommand,
        String buildArtifact,
        String buildFingerprint,
        List<String> limitations) {
      this(
          schemaVersion,
          profileId,
          profileVersion,
          profileDigest,
          buildTool,
          buildCommand,
          buildArtifact,
          buildFingerprint,
          limitations,
          null,
          null,
          null,
          null);
    }

    /** Freezes build handoff lists for downstream consumers. */
    public Handoff {
      buildCommand = List.copyOf(buildCommand == null ? List.of() : buildCommand);
      limitations = List.copyOf(limitations == null ? List.of() : limitations);
      activeMavenProfiles = activeMavenProfiles == null ? null : List.copyOf(activeMavenProfiles);
      mavenProfileActivationSources = mavenProfileActivationSources == null
          ? null
          : Map.copyOf(mavenProfileActivationSources);
      mavenInvocationFingerprint = optionalFingerprint(
          mavenInvocationFingerprint, "mavenInvocationFingerprint");
      effectivePomFingerprint = optionalFingerprint(effectivePomFingerprint, "effectivePomFingerprint");
      boolean replayMetadataPresent = activeMavenProfiles != null
          || mavenProfileActivationSources != null
          || mavenInvocationFingerprint != null
          || effectivePomFingerprint != null;
      boolean replayMetadataComplete = activeMavenProfiles != null
          && mavenProfileActivationSources != null
          && mavenInvocationFingerprint != null
          && effectivePomFingerprint != null;
      if (replayMetadataPresent && !replayMetadataComplete) {
        throw new IllegalArgumentException(
            "Maven replay metadata must provide profiles, activation sources, and both fingerprints");
      }
      if (replayMetadataComplete) {
        if (activeMavenProfiles.stream().anyMatch(value -> value == null || value.isBlank())
            || !activeMavenProfiles.stream().sorted().toList().equals(activeMavenProfiles)
            || new HashSet<>(activeMavenProfiles).size() != activeMavenProfiles.size()) {
          throw new IllegalArgumentException("activeMavenProfiles must be sorted and unique");
        }
        Set<String> profiles = new HashSet<>(activeMavenProfiles);
        if (mavenProfileActivationSources.size() != profiles.size()
            || !mavenProfileActivationSources.keySet().equals(profiles)
            || mavenProfileActivationSources.values().stream()
                .anyMatch(value -> !"explicit".equals(value))) {
          throw new IllegalArgumentException(
              "mavenProfileActivationSources must explicitly cover activeMavenProfiles");
        }
      }
    }

    /** Creates an empty build handoff for programmatic contexts. */
    public static Handoff empty() {
      return new Handoff("", "", "", "", "", List.of(), "", "", List.of());
    }

    /** Returns whether replay metadata was present in the input handoff. */
    public boolean hasReplayMetadata() {
      return activeMavenProfiles != null
          || mavenProfileActivationSources != null
          || mavenInvocationFingerprint != null
          || effectivePomFingerprint != null;
    }
  }

  /** Freshness identities used to compare a loaded context with a later handoff. */
  public record FreshnessSnapshot(
      String dependencyFingerprint,
      String dependencyClasspathOrderFingerprint,
      String dependencyCoordinateFingerprint,
      String mavenInvocationFingerprint,
      String effectivePomFingerprint) {
    public static FreshnessSnapshot from(AnalysisContext context) {
      Objects.requireNonNull(context, "analysis context");
      return new FreshnessSnapshot(
          context.provenance().dependencyFingerprint(),
          context.provenance().dependencyClasspathOrderFingerprint(),
          context.provenance().dependencyCoordinateFingerprint(),
          context.handoff().mavenInvocationFingerprint(),
          context.handoff().effectivePomFingerprint());
    }

    /** Compares required identities and skips only metadata unknown to the older context. */
    public boolean staleAgainst(FreshnessSnapshot current) {
      Objects.requireNonNull(current, "current freshness snapshot");
      return !Objects.equals(dependencyFingerprint, current.dependencyFingerprint)
          || differsWhenKnown(dependencyClasspathOrderFingerprint,
              current.dependencyClasspathOrderFingerprint)
          || differsWhenKnown(dependencyCoordinateFingerprint,
              current.dependencyCoordinateFingerprint)
          || differsWhenKnown(mavenInvocationFingerprint, current.mavenInvocationFingerprint)
          || differsWhenKnown(effectivePomFingerprint, current.effectivePomFingerprint);
    }

    private static boolean differsWhenKnown(String previous, String current) {
      return previous != null && !previous.equals(current);
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
  private final DependencyCoordinateManifest dependencyCoordinateManifest;
  private final List<FrameworkMetadata> frameworkMetadata;
  private final FrameworkBindingMetadata frameworkBindingMetadata;
  private final Provenance provenance;
  private final Handoff handoff;

  /** Validates and freezes one analysis context from its builder. */
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
    dependencyCoordinateManifest = Objects.requireNonNull(
        builder.dependencyCoordinateManifest, "dependency coordinate manifest");
    frameworkMetadata = List.copyOf(builder.frameworkMetadata);
    frameworkBindingMetadata = Objects.requireNonNull(
        builder.frameworkBindingMetadata, "framework binding metadata");
    provenance = Objects.requireNonNull(builder.provenance, "provenance");
    handoff = Objects.requireNonNull(builder.handoff, "handoff");

    if (sourceRoots.stream().noneMatch(root -> !root.generated() && root.required())) {
      throw new IllegalArgumentException("at least one required production/source root is required");
    }
    DependencyCoordinateManifestValidator.validate(this);
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

  /** Returns source roots explicitly included in this analysis. */
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

  public DependencyCoordinateManifest dependencyCoordinateManifest() {
    return dependencyCoordinateManifest;
  }

  public List<FrameworkMetadata> frameworkMetadata() {
    return frameworkMetadata;
  }

  public FrameworkBindingMetadata frameworkBindingMetadata() {
    return frameworkBindingMetadata;
  }

  public List<BindingCandidateMetadata> bindingCandidates() {
    return frameworkBindingMetadata.candidates();
  }

  public List<ResolvedBindingMetadata> resolvedBindings() {
    return frameworkBindingMetadata.resolvedBindings();
  }

  public List<ProxyTargetMetadata> proxyTargetMetadata() {
    return frameworkBindingMetadata.proxyTargets();
  }

  public Provenance provenance() {
    return provenance;
  }

  public Handoff handoff() {
    return handoff;
  }

  public FreshnessSnapshot freshnessSnapshot() {
    return FreshnessSnapshot.from(this);
  }

  /** Reports whether a framework metadata artifact was observed. */
  public boolean metadataObserved(String metadataId) {
    return frameworkMetadata.stream()
        .anyMatch(item -> item.id().equals(metadataId) && item.status() == MetadataStatus.OBSERVED);
  }

  /** Looks up one framework metadata declaration by ID. */
  public Optional<FrameworkMetadata> metadata(String metadataId) {
    return frameworkMetadata.stream().filter(item -> item.id().equals(metadataId)).findFirst();
  }

  /** Return a stable path relative to the selected source root for provenance. */
  public String displayPath(Path file) {
    Path normalized = file.toAbsolutePath().normalize();
    Optional<SourceRoot> root = mostSpecificRoot(normalized, includedSourceRoots());
    if (root.isPresent()) {
      Path relative = root.get().path().relativize(normalized);
      String suffix = relative.toString().replace(normalized.getFileSystem().getSeparator(), "/");
      return suffix.isEmpty() ? root.get().declaredPath() : root.get().declaredPath() + "/" + suffix;
    }
    return normalized.toString();
  }

  /** Orders overlapping roots from most-specific to least-specific provenance. */
  static List<SourceRoot> rootsBySpecificity(List<SourceRoot> roots) {
    return roots.stream()
        .sorted(Comparator
            .comparingInt((SourceRoot root) -> root.path().getNameCount())
            .reversed()
            .thenComparing(SourceRoot::generated, Comparator.reverseOrder())
            .thenComparing(SourceRoot::declaredPath))
        .toList();
  }

  /** Finds the most-specific root containing one normalized source path. */
  static Optional<SourceRoot> mostSpecificRoot(Path file, List<SourceRoot> roots) {
    Path normalized = file.toAbsolutePath().normalize();
    return rootsBySpecificity(roots).stream()
        .filter(root -> normalized.startsWith(root.path()))
        .findFirst();
  }

  private static String requireValue(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static String optionalValue(String value, String name) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value;
  }

  private static String optionalFingerprint(String value, String name) {
    if (value == null || value.isBlank()) {
      return null;
    }
    if (!value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be a lowercase SHA-256 fingerprint");
    }
    return value;
  }

  /** Normalizes a required context path and rejects missing values. */
  private static Path normalize(Path path, String name) {
    return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
  }

  /** Mutable construction surface for one immutable analysis context. */
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
    private DependencyCoordinateManifest dependencyCoordinateManifest =
        DependencyCoordinateManifest.absent();
    private final List<FrameworkMetadata> frameworkMetadata = new ArrayList<>();
    private FrameworkBindingMetadata frameworkBindingMetadata = FrameworkBindingMetadata.empty();
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

    /** Adds an included application source root. */
    public Builder sourceRoot(String declaredPath, Path path, Scope scope, boolean required) {
      return sourceRoot(declaredPath, path, scope, required, null);
    }

    /** Adds an included application source root with its handoff fingerprint. */
    public Builder sourceRoot(
        String declaredPath, Path path, Scope scope, boolean required, String fingerprint) {
      sourceRoots.add(SourceRoot.source(declaredPath, path, scope, required, fingerprint));
      return this;
    }

    /** Adds an included or excluded generated source root. */
    public Builder generatedSourceRoot(
        String declaredPath, Path path, Scope scope, boolean included, boolean required) {
      return generatedSourceRoot(declaredPath, path, scope, included, required, null);
    }

    /** Adds an included or excluded generated source root with its handoff fingerprint. */
    public Builder generatedSourceRoot(
        String declaredPath,
        Path path,
        Scope scope,
        boolean included,
        boolean required,
        String fingerprint) {
      generatedSourceRoots.add(
          SourceRoot.generated(declaredPath, path, scope, included, required, fingerprint));
      return this;
    }

    /** Replaces the dependency classpath inputs. */
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

    /** Sets the optional upstream dependency coordinate handoff. */
    public Builder dependencyCoordinateManifest(DependencyCoordinateManifest value) {
      dependencyCoordinateManifest = Objects.requireNonNull(
          value, "dependency coordinate manifest");
      return this;
    }

    /** Adds one framework metadata declaration. */
    public Builder frameworkMetadata(FrameworkMetadata metadata) {
      frameworkMetadata.add(metadata);
      return this;
    }

    public Builder frameworkMetadata(
        String id, String kind, MetadataStatus status, String declaredPath) {
      return frameworkMetadata(new FrameworkMetadata(id, kind, status, declaredPath));
    }

    /** Sets candidate-level CDI/Spring binding evidence. */
    public Builder frameworkBindingMetadata(FrameworkBindingMetadata value) {
      frameworkBindingMetadata = Objects.requireNonNull(value, "framework binding metadata");
      return this;
    }

    public Builder provenance(Provenance value) {
      provenance = value;
      return this;
    }

    public Builder handoff(Handoff value) {
      handoff = value;
      return this;
    }

    /** Validates and creates the immutable analysis context. */
    public AnalysisContext build() {
      return new AnalysisContext(this);
    }
  }
}
