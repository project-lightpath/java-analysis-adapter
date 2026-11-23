package com.projectlightpath.analysis.java;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The git-unavailable sidecar for the changed-coverage validator (#45 step 4): the shape
 * {@code {"base": ..., "head": ..., "files": {path: [lines]}}} with revision binding and
 * Python's int() coercion for line numbers. Ported from the fallback half of
 * validate_changed_coverage.py's changed_lines.
 */
final class ChangedLinesSidecar {
  /** Python's int() grammar for strings: optional sign, digits with single underscores. */
  private static final Pattern LENIENT_INT = Pattern.compile("[+-]?\\d+(_\\d+)*");

  /** Stand-in for a line number too large for long: it never matches a JaCoCo line. */
  private static final long UNMATCHABLE_LINE = Long.MIN_VALUE;

  private ChangedLinesSidecar() {}

  /** Reads the sidecar, enforcing the requested revisions and per-file line arrays. */
  static Map<String, Set<Long>> read(Path sidecar, String base, String head) {
    if (!Files.isRegularFile(sidecar)) {
      throw new ChangedCoverageValidator.ValidationError(
          "git unavailable and changed-line sidecar is missing: " + sidecar);
    }
    JsonNode raw;
    try {
      raw = ChangedCoverageValidator.MAPPER.readTree(sidecar.toFile());
    } catch (IOException error) {
      throw new ChangedCoverageValidator.ValidationError("cannot parse " + sidecar
          + ": " + error.getMessage(), error);
    }
    JsonNode sidecarBase = raw.path("base");
    JsonNode sidecarHead = raw.path("head");
    if (!raw.isObject() || !sidecarBase.isTextual() || !sidecarHead.isTextual()
        || !base.equals(sidecarBase.asText()) || !head.equals(sidecarHead.asText())) {
      throw new ChangedCoverageValidator.ValidationError(
          "changed-line sidecar revisions do not match the requested base and head");
    }
    JsonNode files = raw.path("files");
    if (!files.isObject()) {
      throw new ChangedCoverageValidator.ValidationError(
          "changed-line sidecar files must be an object");
    }
    Map<String, Set<Long>> changed = new LinkedHashMap<>();
    files.fields().forEachRemaining(entry ->
        changed.put(entry.getKey(), fileLines(entry.getValue(), entry.getKey())));
    return changed;
  }

  /** One sidecar file's line set; values must be arrays of int-like entries. */
  private static Set<Long> fileLines(JsonNode lines, String path) {
    if (!lines.isArray()) {
      throw new ChangedCoverageValidator.ValidationError(
          "changed-line sidecar files values must be arrays: " + path);
    }
    Set<Long> values = new LinkedHashSet<>();
    for (JsonNode line : lines) {
      Long value = lenientLong(line);
      if (value == null) {
        throw new ChangedCoverageValidator.ValidationError(
            "changed-line sidecar has a non-numeric line: " + path);
      }
      values.add(value);
    }
    return values;
  }

  /**
   * Python int(): numbers pass through and strings allow whitespace and inner
   * underscores. Python keeps arbitrary-precision integers, so an out-of-long-range
   * line number (numeric or textual) never narrows: it maps to a sentinel that can
   * never equal a JaCoCo line number, exactly like a Python big int that never
   * intersects the report's lines.
   */
  private static Long lenientLong(JsonNode node) {
    if (node.isNumber()) {
      return node.canConvertToLong() ? node.longValue() : UNMATCHABLE_LINE;
    }
    if (!node.isTextual()) {
      return null;
    }
    String value = node.asText().strip();
    if (!LENIENT_INT.matcher(value).matches()) {
      return null;
    }
    try {
      return Long.parseLong(value.replace("_", ""));
    } catch (NumberFormatException error) {
      return UNMATCHABLE_LINE;
    }
  }
}
