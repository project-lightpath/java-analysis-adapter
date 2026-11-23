package com.projectlightpath.analysis.java;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Changed-line extraction for the changed-coverage validator (#45 step 4): the unified
 * diff parser, the git subprocess, and the sidecar fallback. Ported from
 * validate_changed_coverage.py's git_changed_lines, changed_lines, and the HUNK loop.
 */
final class ChangedLines {
  /** Python's HUNK pattern; prefix-anchored like re.match. */
  private static final Pattern HUNK =
      Pattern.compile("@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");

  /** Python's int() grammar for strings: optional sign, digits with single underscores. */
  private static final Pattern LENIENT_INT = Pattern.compile("[+-]?\\d+(_\\d+)*");

  /** Signals that git is unavailable so the sidecar fallback can take over. */
  private static final class GitUnavailable extends RuntimeException {
    private static final long serialVersionUID = 1L;

    GitUnavailable(Throwable cause) {
      super(cause);
    }
  }

  private ChangedLines() {}

  /** Changed lines via git, falling back to the sidecar when git is unavailable. */
  static Map<String, Set<Long>> changedLines(Path root, String base, String head,
      String suffix, Path evidence) {
    try {
      return gitChangedLines(root, base, head, suffix);
    } catch (GitUnavailable error) {
      return sidecarChangedLines(evidence.getParent().resolve("changed-lines.json"),
          base, head);
    }
  }

  /** Runs git diff and parses it; any failure means "use the sidecar". */
  static Map<String, Set<Long>> gitChangedLines(Path root, String base,
      String head, String suffix) {
    ProcessBuilder builder = new ProcessBuilder("git", "-C", root.toString(), "diff",
        "--no-renames", "--unified=0", base, head, "--", "*" + suffix);
    builder.redirectError(ProcessBuilder.Redirect.INHERIT);
    try {
      Process process = builder.start();
      String output = new String(process.getInputStream().readAllBytes(),
          StandardCharsets.UTF_8);
      if (process.waitFor() != 0) {
        throw new GitUnavailable(null);
      }
      return parseUnifiedDiff(output);
    } catch (IOException error) {
      throw new GitUnavailable(error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new GitUnavailable(error);
    }
  }

  /**
   * Parses added lines from a unified diff: every line in each hunk's added range counts;
   * {@code +} content lines only advance the running position, exactly like the Python
   * original. CRLF and lone CR are normalized first (Python's universal newlines).
   */
  static Map<String, Set<Long>> parseUnifiedDiff(String diffText) {
    String normalized = diffText.replace("\r\n", "\n").replace("\r", "\n");
    Map<String, Set<Long>> changed = new LinkedHashMap<>();
    String current = null;
    Long nextLine = null;
    for (String line : normalized.split("\n", -1)) {
      if (line.startsWith("+++ b/")) {
        current = line.substring(6);
        nextLine = null;
        changed.computeIfAbsent(current, key -> new LinkedHashSet<>());
        continue;
      }
      Matcher hunk = HUNK.matcher(line);
      if (hunk.lookingAt() && current != null) {
        long start = Long.parseLong(hunk.group(1));
        long count = hunk.group(2) != null ? Long.parseLong(hunk.group(2)) : 1;
        for (long added = start; added < start + count; added++) {
          changed.get(current).add(added);
        }
        nextLine = start + count;
        continue;
      }
      if (current != null && nextLine != null && line.startsWith("+")
          && !line.startsWith("+++")) {
        nextLine++;
      }
    }
    return changed;
  }

  /** The sidecar shape: {base, head, files: {path: [lines]}} with lenient ints. */
  private static Map<String, Set<Long>> sidecarChangedLines(Path sidecar, String base,
      String head) {
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
    if (!raw.isObject() || !base.equals(raw.path("base").asText(null))
        || !head.equals(raw.path("head").asText(null))) {
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
        changed.put(entry.getKey(), sidecarLines(entry.getValue(), entry.getKey())));
    return changed;
  }

  /** One sidecar file's line set; values must be arrays of int-like entries. */
  private static Set<Long> sidecarLines(JsonNode lines, String path) {
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

  /** Python int(): numbers pass through; strings allow whitespace and inner underscores. */
  private static Long lenientLong(JsonNode node) {
    if (node.isNumber()) {
      return node.longValue();
    }
    if (!node.isTextual()) {
      return null;
    }
    String value = node.asText().strip();
    return LENIENT_INT.matcher(value).matches()
        ? Long.parseLong(value.replace("_", "")) : null;
  }
}
