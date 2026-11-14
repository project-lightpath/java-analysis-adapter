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
abstract class AnalysisContextLoaderLayer00 extends AnalysisContextLoaderSupport {



  static void validateSchema(ContextDocument document) {
    if (!SUPPORTED_SCHEMA_VERSION.equals(document.schemaVersion())) {
      throw new IllegalArgumentException(
          "unsupported AnalysisContext schemaVersion: " + document.schemaVersion());
    }
  }

  static int javaRelease(ContextDocument document) {
    int release = document.javaRelease() > 0
        ? document.javaRelease()
        : document.fixture() == null ? 0 : document.fixture().javaRelease();
    if (release <= 0) {
      throw new IllegalArgumentException("analysis context Java release must be positive");
    }
    return release;
  }

  static boolean dependencyClasspathRequired(JsonNode root) {
    if (!root.has("dependencyClasspathRequired")) {
      return true;
    }
    JsonNode value = root.get("dependencyClasspathRequired");
    if (value == null || value.isNull() || !value.isBoolean()) {
      throw new IllegalArgumentException("dependencyClasspathRequired must be a boolean");
    }
    return value.booleanValue();
  }

  static boolean isWildcard(Path path) {
    return path.toString().contains("*");
  }

  static void rejectManifestAliases(JsonNode root) {
    for (String alias : List.of(
        "dependencyCoordinates", "dependencyClasspathMetadata", "dependencyMetadata")) {
      if (root.has(alias)) {
        throw new IllegalArgumentException("unsupported dependency coordinate manifest alias: " + alias);
      }
    }
  }

  static void rejectUnknownFields(JsonNode node, Set<String> allowed, String label) {
    var fields = node.fieldNames();
    while (fields.hasNext()) {
      String field = fields.next();
      if (!allowed.contains(field)) {
        throw new IllegalArgumentException(label + " contains unknown field: " + field);
      }
    }
  }

  static String requiredText(JsonNode object, String field, String label) {
    JsonNode value = object.get(field);
    if (value == null || value.isNull() || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException(label + " must be a nonblank string");
    }
    return value.textValue();
  }

  static void validateFingerprint(String value, String label) {
    if (!value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(label + " must be a lowercase SHA-256 fingerprint");
    }
  }

  static JsonNode requiredObject(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || value.isNull() || !value.isObject()) {
      throw new IllegalArgumentException(field + " must be a JSON object");
    }
    return value;
  }

  static boolean hasAny(JsonNode object, String... fields) {
    for (String field : fields) {
      if (object.has(field)) {
        return true;
      }
    }
    return false;
  }

  static boolean isProfileArray(JsonNode profiles) {
    return profiles != null && !profiles.isNull() && profiles.isArray();
  }
}