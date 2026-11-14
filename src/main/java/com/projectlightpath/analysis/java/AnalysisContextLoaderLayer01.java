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
abstract class AnalysisContextLoaderLayer01 extends AnalysisContextLoaderLayer00 {



  static boolean isSourceObject(JsonNode sources) {
    return sources != null && !sources.isNull() && sources.isObject();
  }

  static List<String> profileValues(JsonNode profiles) {
    List<String> result = new java.util.ArrayList<>();
    for (JsonNode profile : profiles) {
      if (profile == null || profile.isNull() || !profile.isTextual() || profile.textValue().isBlank()) {
        throw new IllegalArgumentException("activeMavenProfiles must contain nonblank strings");
      }
      result.add(profile.textValue());
    }
    return result;
  }

  static void validateSortedUniqueProfiles(List<String> profileValues) {
    List<String> sorted = profileValues.stream().sorted().toList();
    if (!profileValues.equals(sorted)
        || new HashSet<>(profileValues).size() != profileValues.size()) {
      throw new IllegalArgumentException("activeMavenProfiles must be sorted and unique");
    }
  }

  static void validateProfileCoverage(JsonNode sources, List<String> profileValues) {
    if (sources.size() != profileValues.size()) {
      throw new IllegalArgumentException(
          "mavenProfileActivationSources must cover activeMavenProfiles exactly");
    }
  }

  static void validateProfileSources(JsonNode sources, List<String> profileValues) {
    for (String profile : profileValues) {
      JsonNode source = sources.get(profile);
      if (source == null || !source.isTextual() || !"explicit".equals(source.textValue())) {
        throw new IllegalArgumentException(
            "maven profile activation source must be explicit for " + profile);
      }
    }
    var sourceNames = sources.fieldNames();
    while (sourceNames.hasNext()) {
      if (!profileValues.contains(sourceNames.next())) {
        throw new IllegalArgumentException(
            "mavenProfileActivationSources contains an inactive profile");
      }
    }
  }

  static Path parsePath(String value, String label) {
    try {
      return Path.of(value);
    } catch (InvalidPathException error) {
      throw new IllegalArgumentException(label + " is not a valid path: " + value, error);
    }
  }

  static Path resolveRelative(Path path, Path repositoryRoot, String label, String value) {
    if (repositoryRoot == null) {
      throw new IllegalArgumentException(label + " must be absolute: " + value);
    }
    return repositoryRoot.resolve(path);
  }

  static boolean validWildcard(Path path) {
    String value = path.toString();
    return path.getFileName() != null
        && "*".equals(path.getFileName().toString())
        && value.indexOf('*') == value.lastIndexOf('*');
  }

  /** macOS exposes temporary directories through the trusted /var alias. */
  static boolean isTrustedPrefixAlias(Path path) {
    String value = path.toAbsolutePath().normalize().toString();
    return "/var".equals(value) || "/tmp".equals(value);
  }

  /** Requires a nonblank serialized scalar. */
  static String required(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value;
  }
}