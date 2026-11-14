package com.projectlightpath.analysis.java;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Responsibility layer for the AnalysisContext JSON handoff. */
abstract class AnalysisContextLoaderSupport {


  static final String SUPPORTED_SCHEMA_VERSION = "0.1.0";

  protected static final Set<String> MANIFEST_FIELDS = Set.of(
      "schemaVersion", "owner", "entries", "fingerprint");
  protected static final Set<String> ENTRY_FIELDS = Set.of(
      "classpathEntry", "groupId", "artifactId", "version", "fingerprint", "classifier");
  protected static final Set<String> BUILD_FIELDS = Set.of(
      "tool", "command", "artifact", "fingerprint", "activeMavenProfiles",
      "mavenProfileActivationSources", "mavenInvocationFingerprint", "effectivePomFingerprint");
  protected static final ObjectMapper MAPPER = new ObjectMapper(
      JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
      .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);


  record ContextInputs(
      String id,
      AnalysisContext.Framework framework,
      String fixtureId,
      int javaRelease,
      Path repositoryRoot,
      Path applicationClasses,
      boolean dependencyClasspathRequired,
      AnalysisContext.DependencyCoordinateManifest manifest,
      AnalysisContext.Provenance provenance) {}

  record HandoffValues(
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
      String effectivePomFingerprint) {}

  enum ProfileField { ID, VERSION, DIGEST }

  enum BuildField { TOOL, ARTIFACT, FINGERPRINT }

  /** Root context shape is intentionally kept separate from the domain builder. */
  record ContextDocument(
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

  record FixtureDocument(String id, int javaRelease) {}

  record ProfileDocument(String id, String version, String digest) {}

  record ResolvedRootDocument(
      String declaredPath,
      String path,
      String scope,
      boolean generated,
      boolean included,
      boolean required,
      String fingerprint) {}

  record FrameworkMetadataDocument(String id, String kind, String status, String path) {}

  record FrameworkBindingMetadataDocument(
      String schemaVersion,
      @JsonAlias("beanCandidates")
      List<BindingCandidateDocument> candidates,
      @JsonAlias("bindings")
      List<ResolvedBindingDocument> resolvedBindings,
      @JsonAlias("targets")
      List<ProxyTargetDocument> proxyTargets) {}

  record BindingCandidateDocument(
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

  record ResolvedBindingDocument(
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

  record ProxyTargetDocument(
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

  record BuildDocument(
      String tool,
      List<String> command,
      String artifact,
      String fingerprint,
      List<String> activeMavenProfiles,
      Map<String, String> mavenProfileActivationSources,
      String mavenInvocationFingerprint,
      String effectivePomFingerprint) {}

  record FreshnessDocument(long maxAgeSeconds) {}

  record ProvenanceDocument(
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
      FreshnessDocument freshness) {}
}