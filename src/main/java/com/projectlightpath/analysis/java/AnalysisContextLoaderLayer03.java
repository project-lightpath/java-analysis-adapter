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
abstract class AnalysisContextLoaderLayer03 extends AnalysisContextLoaderLayer02 {



  static String buildValue(BuildDocument build, BuildField field) {
    if (build == null) {
      return "";
    }
    return switch (field) {
      case TOOL -> nullToEmpty(build.tool());
      case ARTIFACT -> nullToEmpty(build.artifact());
      case FINGERPRINT -> nullToEmpty(build.fingerprint());
    };
  }

  /** Parses a serialized production or test scope. */
  static AnalysisContext.Scope parseScope(String value) {
    String normalized = required(value, "analysis scope").toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "PRODUCTION" -> AnalysisContext.Scope.PRODUCTION;
      case "TEST" -> AnalysisContext.Scope.TEST;
      default -> throw new IllegalArgumentException("unsupported analysis scope: " + value);
    };
  }

  static String manifestFingerprint(
      JsonNode node, List<AnalysisContext.DependencyCoordinateEntry> entries) {
    if (!entries.isEmpty()) {
      String fingerprint = requiredText(node, "fingerprint", "manifest fingerprint");
      validateFingerprint(fingerprint, "manifest fingerprint");
      return fingerprint;
    }
    if (node.has("fingerprint")) {
      throw new IllegalArgumentException(
          "present-empty dependency coordinate manifest must omit fingerprint");
    }
    return null;
  }

  static String optionalText(JsonNode object, String field, String label) {
    if (!object.has(field)) {
      return null;
    }
    return requiredText(object, field, label);
  }

  static String optionalFingerprint(JsonNode object, String field) {
    if (!object.has(field)) {
      return null;
    }
    String value = requiredText(object, field, "provenance " + field);
    validateFingerprint(value, "provenance " + field);
    return value;
  }

  static void validateReplayFingerprints(JsonNode buildNode) {
    String invocation = requiredText(
        buildNode, "mavenInvocationFingerprint", "build mavenInvocationFingerprint");
    String pom = requiredText(
        buildNode, "effectivePomFingerprint", "build effectivePomFingerprint");
    validateFingerprint(invocation, "build mavenInvocationFingerprint");
    validateFingerprint(pom, "build effectivePomFingerprint");
  }

  /** Parses an observed or not-captured metadata status. */
  static AnalysisContext.MetadataStatus parseMetadataStatus(String value) {
    String normalized = required(value, "framework metadata status").toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "OBSERVED" -> AnalysisContext.MetadataStatus.OBSERVED;
      case "NOT_CAPTURED" -> AnalysisContext.MetadataStatus.NOT_CAPTURED;
      default -> throw new IllegalArgumentException("unsupported framework metadata status: " + value);
    };
  }

  static boolean hasNoSymlinkComponents(Path path) {
    Path normalized = path.toAbsolutePath().normalize();
    Path current = normalized.getRoot();
    if (current == null) {
      return false;
    }
    for (Path component : normalized) {
      current = current.resolve(component);
      if (Files.isSymbolicLink(current) && !isTrustedPrefixAlias(current)) {
        return false;
      }
    }
    return true;
  }

  static AnalysisContext.Builder baseBuilder(
      ContextDocument document, ContextInputs inputs) {
    return AnalysisContext.builder()
        .id(inputs.id())
        .framework(inputs.framework())
        .frameworkVersion(required(document.frameworkVersion(), "framework version"))
        .fixtureId(required(inputs.fixtureId(), "fixture id"))
        .javaRelease(inputs.javaRelease())
        .repositoryRoot(inputs.repositoryRoot())
        .applicationClasses(inputs.applicationClasses())
        .selectedProfile(required(document.selectedProfile(), "selected profile"))
        .analysisScope(parseScope(document.analysisScope()))
        .dependencyClasspathRequired(inputs.dependencyClasspathRequired())
        .dependencyCoordinateManifest(inputs.manifest())
        .provenance(inputs.provenance());
  }

  static void addFrameworkMetadata(
      AnalysisContext.Builder builder, ContextDocument document) {
    for (FrameworkMetadataDocument metadata : nonNull(document.frameworkMetadata())) {
      builder.frameworkMetadata(
          required(metadata.id(), "framework metadata id"),
          required(metadata.kind(), "framework metadata kind"),
          parseMetadataStatus(metadata.status()),
          metadata.path());
    }
  }

  /** Converts serialized provenance while preserving freshness fields. */
  static AnalysisContext.Provenance provenance(
      ProvenanceDocument value, JsonNode root) {
    require(value, "provenance");
    JsonNode provenanceNode = requiredObject(root, "provenance");
    return new AnalysisContext.Provenance(
        required(value.sourceRepository(), "provenance sourceRepository"),
        required(value.sourceReference(), "provenance sourceReference"),
        required(value.sourceRevision(), "provenance sourceRevision"),
        required(value.sourceFingerprint(), "provenance sourceFingerprint"),
        required(value.generatedSourceFingerprint(), "provenance generatedSourceFingerprint"),
        required(value.dependencyFingerprint(), "provenance dependencyFingerprint"),
        optionalFingerprint(provenanceNode, "dependencyClasspathOrderFingerprint"),
        optionalFingerprint(provenanceNode, "dependencyCoordinateFingerprint"),
        required(value.buildFingerprint(), "provenance buildFingerprint"),
        required(value.toolchainFingerprint(), "provenance toolchainFingerprint"),
        required(value.analyzerFingerprint(), "provenance analyzerFingerprint"),
        required(value.gitRevision(), "provenance gitRevision"),
        required(value.providerRuntime(), "provenance providerRuntime"),
        required(value.profileDigest(), "provenance profileDigest"),
        required(value.preparedAt(), "provenance preparedAt"),
        value.freshness() == null ? 0 : value.freshness().maxAgeSeconds());
  }

  static HandoffValues handoffValues(
      ContextDocument document,
      ProfileDocument profile,
      BuildDocument build,
      boolean replayExpected) {
    String profileId = profileValue(profile, ProfileField.ID);
    String profileVersion = profileValue(profile, ProfileField.VERSION);
    String profileDigest = profileValue(profile, ProfileField.DIGEST);
    String buildTool = buildValue(build, BuildField.TOOL);
    List<String> buildCommand = build == null ? List.of() : nonNull(build.command());
    String buildArtifact = buildValue(build, BuildField.ARTIFACT);
    String buildFingerprint = buildValue(build, BuildField.FINGERPRINT);
    return new HandoffValues(
        required(document.schemaVersion(), "schemaVersion"),
        profileId,
        profileVersion,
        profileDigest,
        buildTool,
        buildCommand,
        buildArtifact,
        buildFingerprint,
        nonNull(document.limitations()),
        replayExpected ? List.copyOf(build.activeMavenProfiles()) : null,
        replayExpected ? Map.copyOf(build.mavenProfileActivationSources()) : null,
        replayExpected ? required(build.mavenInvocationFingerprint(), "build mavenInvocationFingerprint") : null,
        replayExpected ? required(build.effectivePomFingerprint(), "build effectivePomFingerprint") : null);
  }
}