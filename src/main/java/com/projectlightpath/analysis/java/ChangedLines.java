package com.projectlightpath.analysis.java;

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
      // Python: Path(name).parent is "." for a bare filename — never null.
      Path parent = evidence.getParent();
      return ChangedLinesSidecar.read(
          parent == null ? Path.of("changed-lines.json")
              : parent.resolve("changed-lines.json"),
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

}
