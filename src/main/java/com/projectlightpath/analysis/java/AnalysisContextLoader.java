package com.projectlightpath.analysis.java;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Loads and validates the VPE AnalysisContext JSON handoff. */
public final class AnalysisContextLoader {
  public static final String SUPPORTED_SCHEMA_VERSION = "0.1.0";

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
      .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);

  private AnalysisContextLoader() {}

  /** Read one immutable context from a VPE-emitted JSON file. */
  public static AnalysisContext load(Path filename) throws IOException {
    Objects.requireNonNull(filename, "context filename");
    return fromDocument(MAPPER.readValue(filename.toFile(), ContextDocument.class));
  }

  private static AnalysisContext fromDocument(ContextDocument document) {
    require(document, "analysis context");
    if (!SUPPORTED_SCHEMA_VERSION.equals(document.schemaVersion())) {
      throw new IllegalArgumentException(
          "unsupported AnalysisContext schemaVersion: " + document.schemaVersion());
    }
    String id = required(document.id(), "context id");
    String frameworkId = required(document.framework(), "framework").toLowerCase(Locale.ROOT);
    AnalysisContext.Framework framework = switch (frameworkId) {
      case "quarkus" -> AnalysisContext.Framework.QUARKUS;
      case "spring-boot" -> AnalysisContext.Framework.SPRING_BOOT;
      default -> throw new IllegalArgumentException("unsupported Java framework: " + frameworkId);
    };
    String fixtureId = firstNonBlank(
        document.fixtureId(),
        document.fixture() == null ? null : document.fixture().id());
    int javaRelease = document.javaRelease() > 0
        ? document.javaRelease()
        : document.fixture() == null ? 0 : document.fixture().javaRelease();
    if (javaRelease <= 0) {
      throw new IllegalArgumentException("analysis context Java release must be positive");
    }

    Path repositoryRoot = requiredDirectory(document.repositoryRoot(), "repository root");
    Path applicationClasses = requiredDirectory(document.applicationClasses(), "application classes");
    boolean dependencyClasspathRequired = document.dependencyClasspathRequired() == null
        || document.dependencyClasspathRequired();
    AnalysisContext.Builder builder = AnalysisContext.builder()
        .id(id)
        .framework(framework)
        .frameworkVersion(required(document.frameworkVersion(), "framework version"))
        .fixtureId(required(fixtureId, "fixture id"))
        .javaRelease(javaRelease)
        .repositoryRoot(repositoryRoot)
        .applicationClasses(applicationClasses)
        .selectedProfile(required(document.selectedProfile(), "selected profile"))
        .analysisScope(parseScope(document.analysisScope()))
        .dependencyClasspathRequired(dependencyClasspathRequired)
        .provenance(provenance(document.provenance()));

    for (ResolvedRootDocument root : nonNull(document.sourceRoots())) {
      addRoot(builder, root, false, repositoryRoot);
    }
    for (ResolvedRootDocument root : nonNull(document.generatedSourceRoots())) {
      addRoot(builder, root, true, repositoryRoot);
    }
    List<Path> dependencyClasspath = nonNull(document.dependencyClasspath()).stream()
        .map(value -> existingPath(value, "dependency classpath entry"))
        .toList();
    if (dependencyClasspathRequired && dependencyClasspath.isEmpty()) {
      throw new IllegalArgumentException("required dependency classpath is empty");
    }
    builder.dependencyClasspath(dependencyClasspath);
    for (FrameworkMetadataDocument metadata : nonNull(document.frameworkMetadata())) {
      builder.frameworkMetadata(
          required(metadata.id(), "framework metadata id"),
          required(metadata.kind(), "framework metadata kind"),
          parseMetadataStatus(metadata.status()),
          metadata.path());
    }
    builder.frameworkBindingMetadata(bindingMetadata(document));
    builder.handoff(handoff(document));
    return builder.build();
  }

  private static AnalysisContext.FrameworkBindingMetadata bindingMetadata(ContextDocument document) {
    FrameworkBindingMetadataDocument envelope = document.frameworkBindingMetadata();
    List<BindingCandidateDocument> candidates = envelope == null
        ? nonNull(document.bindingCandidates())
        : nonNull(envelope.candidates());
    List<ResolvedBindingDocument> bindings = envelope == null
        ? nonNull(document.resolvedBindings())
        : nonNull(envelope.resolvedBindings());
    List<ProxyTargetDocument> proxyTargets = envelope == null
        ? nonNull(document.proxyTargets())
        : nonNull(envelope.proxyTargets());
    if (envelope == null && candidates.isEmpty() && bindings.isEmpty() && proxyTargets.isEmpty()) {
      return AnalysisContext.FrameworkBindingMetadata.empty();
    }
    String schemaVersion = envelope == null || envelope.schemaVersion() == null
        ? "0.1.0"
        : required(envelope.schemaVersion(), "framework binding metadata schemaVersion");
    if (!"0.1.0".equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "unsupported framework binding metadata schemaVersion: " + schemaVersion);
    }
    return new AnalysisContext.FrameworkBindingMetadata(
        schemaVersion,
        candidates.stream().map(AnalysisContextLoader::bindingCandidate).toList(),
        bindings.stream().map(AnalysisContextLoader::resolvedBinding).toList(),
        proxyTargets.stream().map(AnalysisContextLoader::proxyTarget).toList());
  }

  private static AnalysisContext.BindingCandidateMetadata bindingCandidate(
      BindingCandidateDocument document) {
    return new AnalysisContext.BindingCandidateMetadata(
        required(document.id(), "binding candidate id"),
        required(document.type(), "binding candidate type"),
        nonNull(document.contracts()),
        nonNull(document.qualifiers()),
        required(document.kind(), "binding candidate kind"),
        document.declaringType(),
        document.producerMethod(),
        document.producerSignature(),
        nonNull(document.profiles()),
        nonNull(document.conditions()),
        document.active(),
        document.alternative(),
        document.primary(),
        document.path(),
        document.providerFactId());
  }

  private static AnalysisContext.ResolvedBindingMetadata resolvedBinding(
      ResolvedBindingDocument document) {
    return new AnalysisContext.ResolvedBindingMetadata(
        required(document.id(), "resolved binding id"),
        required(document.injectionOwner(), "resolved binding injectionOwner"),
        nullToEmpty(document.injectionMember()),
        required(document.injectionType(), "resolved binding injectionType"),
        document.parameterIndex(),
        nonNull(document.qualifiers()),
        required(document.candidateId(), "resolved binding candidateId"),
        document.selected(),
        document.active(),
        document.selectionReason(),
        document.path(),
        document.providerFactId());
  }

  private static AnalysisContext.ProxyTargetMetadata proxyTarget(
      ProxyTargetDocument document) {
    return new AnalysisContext.ProxyTargetMetadata(
        required(document.id(), "proxy target id"),
        document.candidateId(),
        required(document.targetType(), "proxy target type"),
        required(document.methodName(), "proxy target methodName"),
        document.methodDescriptor(),
        required(document.proxyKind(), "proxy target proxyKind"),
        document.selected(),
        document.active(),
        document.selectionReason(),
        document.path(),
        document.providerFactId());
  }

  private static void addRoot(
      AnalysisContext.Builder builder,
      ResolvedRootDocument root,
      boolean generatedCollection,
      Path repositoryRoot) {
    String declaredPath = required(root.declaredPath(), "source root declaredPath");
    Path resolved = normalizedPath(root.path(), "source root " + declaredPath);
    if (!resolved.startsWith(repositoryRoot)) {
      throw new IllegalArgumentException("source root must remain inside repository root: " + declaredPath);
    }
    if (!Files.exists(resolved)
        && (!generatedCollection || root.required() || root.included())) {
      throw new IllegalArgumentException("source root is unavailable: " + declaredPath);
    }
    AnalysisContext.Scope scope = parseScope(root.scope());
    boolean generated = generatedCollection || root.generated();
    if (generated) {
      builder.generatedSourceRoot(declaredPath, resolved, scope, root.included(), root.required());
    } else {
      if (root.required() && !root.included()) {
        throw new IllegalArgumentException("required source root must be included: " + declaredPath);
      }
      builder.sourceRoot(declaredPath, resolved, scope, root.required());
    }
  }

  private static AnalysisContext.Provenance provenance(ProvenanceDocument value) {
    require(value, "provenance");
    return new AnalysisContext.Provenance(
        required(value.sourceRepository(), "provenance sourceRepository"),
        required(value.sourceReference(), "provenance sourceReference"),
        required(value.sourceRevision(), "provenance sourceRevision"),
        required(value.sourceFingerprint(), "provenance sourceFingerprint"),
        required(value.generatedSourceFingerprint(), "provenance generatedSourceFingerprint"),
        required(value.dependencyFingerprint(), "provenance dependencyFingerprint"),
        required(value.buildFingerprint(), "provenance buildFingerprint"),
        required(value.toolchainFingerprint(), "provenance toolchainFingerprint"),
        required(value.analyzerFingerprint(), "provenance analyzerFingerprint"),
        required(value.gitRevision(), "provenance gitRevision"),
        required(value.providerRuntime(), "provenance providerRuntime"),
        required(value.profileDigest(), "provenance profileDigest"),
        required(value.preparedAt(), "provenance preparedAt"),
        value.freshness() == null ? 0 : value.freshness().maxAgeSeconds());
  }

  private static AnalysisContext.Handoff handoff(ContextDocument document) {
    ProfileDocument profile = document.profile();
    BuildDocument build = document.build();
    return new AnalysisContext.Handoff(
        required(document.schemaVersion(), "schemaVersion"),
        profile == null ? "" : nullToEmpty(profile.id()),
        profile == null ? "" : nullToEmpty(profile.version()),
        profile == null ? "" : nullToEmpty(profile.digest()),
        build == null ? "" : nullToEmpty(build.tool()),
        build == null ? List.of() : nonNull(build.command()),
        build == null ? "" : nullToEmpty(build.artifact()),
        build == null ? "" : nullToEmpty(build.fingerprint()),
        nonNull(document.limitations()));
  }

  private static AnalysisContext.Scope parseScope(String value) {
    String normalized = required(value, "analysis scope").toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "PRODUCTION" -> AnalysisContext.Scope.PRODUCTION;
      case "TEST" -> AnalysisContext.Scope.TEST;
      default -> throw new IllegalArgumentException("unsupported analysis scope: " + value);
    };
  }

  private static AnalysisContext.MetadataStatus parseMetadataStatus(String value) {
    String normalized = required(value, "framework metadata status").toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "OBSERVED" -> AnalysisContext.MetadataStatus.OBSERVED;
      case "NOT_CAPTURED" -> AnalysisContext.MetadataStatus.NOT_CAPTURED;
      default -> throw new IllegalArgumentException("unsupported framework metadata status: " + value);
    };
  }

  private static Path requiredDirectory(String value, String label) {
    Path path = existingPath(value, label);
    if (!Files.isDirectory(path)) {
      throw new IllegalArgumentException(label + " must be a directory: " + path);
    }
    return path;
  }

  private static Path existingPath(String value, String label) {
    Path path = normalizedPath(value, label);
    if (!Files.exists(path)) {
      throw new IllegalArgumentException(label + " is unavailable: " + value);
    }
    return path;
  }

  private static Path normalizedPath(String value, String label) {
    String required = required(value, label);
    Path path = Path.of(required);
    if (!path.isAbsolute()) {
      throw new IllegalArgumentException(label + " must be absolute: " + required);
    }
    return path.normalize();
  }

  private static String required(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value;
  }

  private static String firstNonBlank(String first, String second) {
    return first != null && !first.isBlank() ? first : second;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static <T> List<T> nonNull(List<T> values) {
    return values == null ? List.of() : values;
  }

  private static void require(Object value, String label) {
    if (value == null) {
      throw new IllegalArgumentException(label + " must not be null");
    }
  }

  /** Root context shape is intentionally kept separate from the domain builder. */
  private record ContextDocument(
      String schemaVersion,
      String id,
      String fixtureId,
      FixtureDocument fixture,
      ProfileDocument profile,
      String framework,
      String frameworkVersion,
      int javaRelease,
      String repositoryRoot,
      String sourceRevision,
      String selectedProfile,
      String analysisScope,
      List<ResolvedRootDocument> sourceRoots,
      List<ResolvedRootDocument> generatedSourceRoots,
      List<String> dependencyClasspath,
      Boolean dependencyClasspathRequired,
      String applicationClasses,
      List<FrameworkMetadataDocument> frameworkMetadata,
      @JsonAlias({"bindingMetadata", "frameworkBindings"})
      FrameworkBindingMetadataDocument frameworkBindingMetadata,
      List<BindingCandidateDocument> bindingCandidates,
      List<ResolvedBindingDocument> resolvedBindings,
      List<ProxyTargetDocument> proxyTargets,
      BuildDocument build,
      ProvenanceDocument provenance,
      List<String> limitations) {}

  private record FixtureDocument(String id, int javaRelease) {}

  private record ProfileDocument(String id, String version, String digest) {}

  private record ResolvedRootDocument(
      String declaredPath,
      String path,
      String scope,
      boolean generated,
      boolean included,
      boolean required,
      String fingerprint) {}

  private record FrameworkMetadataDocument(String id, String kind, String status, String path) {}

  private record FrameworkBindingMetadataDocument(
      String schemaVersion,
      @JsonAlias("beanCandidates")
      List<BindingCandidateDocument> candidates,
      @JsonAlias("bindings")
      List<ResolvedBindingDocument> resolvedBindings,
      @JsonAlias("targets")
      List<ProxyTargetDocument> proxyTargets) {}

  private record BindingCandidateDocument(
      @JsonAlias("candidateId")
      String id,
      @JsonAlias({"candidateType", "beanType"}) String type,
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
      @JsonAlias({"sourcePath", "declaredPath"}) String path,
      String providerFactId) {}

  private record ResolvedBindingDocument(
      @JsonAlias("bindingId")
      String id,
      String injectionOwner,
      String injectionMember,
      String injectionType,
      Integer parameterIndex,
      List<String> qualifiers,
      String candidateId,
      Boolean selected,
      Boolean active,
      String selectionReason,
      @JsonAlias({"sourcePath", "declaredPath"}) String path,
      String providerFactId) {}

  private record ProxyTargetDocument(
      @JsonAlias("proxyTargetId")
      String id,
      String candidateId,
      String targetType,
      String methodName,
      String methodDescriptor,
      String proxyKind,
      Boolean selected,
      Boolean active,
      String selectionReason,
      @JsonAlias({"sourcePath", "declaredPath"}) String path,
      String providerFactId) {}

  private record BuildDocument(String tool, List<String> command, String artifact, String fingerprint) {}

  private record FreshnessDocument(long maxAgeSeconds) {}

  private record ProvenanceDocument(
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
      FreshnessDocument freshness) {}
}
