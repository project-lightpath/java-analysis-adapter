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
abstract class AnalysisContextLoaderLayer04 extends AnalysisContextLoaderLayer03 {



  static void requireReplayFields(JsonNode buildNode) {
    JsonNode profiles = buildNode.get("activeMavenProfiles");
    JsonNode sources = buildNode.get("mavenProfileActivationSources");
    if (!isProfileArray(profiles) || !isSourceObject(sources)) {
      throw new IllegalArgumentException("coordinate-enabled build requires replay profile metadata");
    }
    validateReplayFingerprints(buildNode);
    List<String> profileValues = profileValues(profiles);
    validateSortedUniqueProfiles(profileValues);
    validateProfileCoverage(sources, profileValues);
    validateProfileSources(sources, profileValues);
  }

  static void validateNonWildcardPath(Path path, String label, String value) {
    if (!hasNoSymlinkComponents(path)) {
      throw new IllegalArgumentException(label + " must not traverse a symlink: " + value);
    }
  }

  static boolean existsNoFollow(Path path) {
    return Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        && hasNoSymlinkComponents(path);
  }

  static void validateHandoffMetadata(JsonNode buildNode, boolean replayExpected) {
    if (replayExpected) {
      if (buildNode == null || buildNode.isNull() || !buildNode.isObject()) {
        throw new IllegalArgumentException("coordinate-enabled context requires a build object");
      }
      rejectUnknownFields(buildNode, BUILD_FIELDS, "build");
      requireReplayFields(buildNode);
      return;
    }
    if (buildNode != null && buildNode.isObject()
        && hasAny(buildNode,
            "activeMavenProfiles",
            "mavenProfileActivationSources",
            "mavenInvocationFingerprint",
            "effectivePomFingerprint")) {
      throw new IllegalArgumentException(
          "replay metadata requires a dependency coordinate manifest");
    }
  }

  /** Validates a required existing directory path. */
  static Path requiredDirectory(String value, String label) {
    String required = required(value, label);
    Path path = parsePath(required, label).toAbsolutePath().normalize();
    if (!path.isAbsolute() || !existsNoFollow(path)) {
      throw new IllegalArgumentException(label + " is unavailable: " + required);
    }
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException(label + " must be a directory: " + path);
    }
    return path;
  }

  static void validateWildcardPath(
      Path path, String label, String value, boolean allowWildcard) {
    if (!allowWildcard || !validWildcard(path)) {
      throw new IllegalArgumentException(label + " contains an invalid wildcard: " + value);
    }
    Path directory = path.getParent();
    if (directory == null || !existsNoFollow(directory)
        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException(label + " wildcard directory is unavailable: " + value);
    }
  }

  /** Converts the serialized build handoff. */
  static AnalysisContext.Handoff handoff(
      ContextDocument document,
      JsonNode root,
      AnalysisContext.DependencyCoordinateManifest manifest) {
    ProfileDocument profile = document.profile();
    BuildDocument build = document.build();
    JsonNode buildNode = root.get("build");
    boolean replayExpected = manifest.present();
    validateHandoffMetadata(buildNode, replayExpected);
    HandoffValues values = handoffValues(document, profile, build, replayExpected);
    return new AnalysisContext.Handoff(
        values.schemaVersion(),
        values.profileId(),
        values.profileVersion(),
        values.profileDigest(),
        values.buildTool(),
        values.buildCommand(),
        values.buildArtifact(),
        values.buildFingerprint(),
        values.limitations(),
        values.activeMavenProfiles(),
        values.mavenProfileActivationSources(),
        values.mavenInvocationFingerprint(),
        values.effectivePomFingerprint());
  }

  static void validateNormalizedPath(
      Path path,
      String label,
      String value,
      Path repositoryRoot,
      boolean allowExternal,
      boolean allowWildcard) {
    boolean wildcard = isWildcard(path);
    if (wildcard) {
      validateWildcardPath(path, label, value, allowWildcard);
    } else {
      validateNonWildcardPath(path, label, value);
    }
    if (!allowExternal && repositoryRoot != null && !path.startsWith(repositoryRoot)) {
      throw new IllegalArgumentException(label + " must remain inside repository root: " + value);
    }
  }

  /** Normalizes a serialized path lexically without resolving symlinks. */
  static Path normalizedPath(
      String value,
      String label,
      Path repositoryRoot,
      boolean allowExternal,
      boolean allowWildcard) {
    String required = required(value, label);
    Path path = parsePath(required, label);
    if (!path.isAbsolute()) {
      path = resolveRelative(path, repositoryRoot, label, required);
    }
    path = path.toAbsolutePath().normalize();
    validateNormalizedPath(path, label, required, repositoryRoot, allowExternal, allowWildcard);
    return path;
  }
}