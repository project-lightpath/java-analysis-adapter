package com.projectlightpath.analysis.java;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Validates JaCoCo coverage for executable lines changed between two commits (#45 step 4,
 * ported from spike/scripts/validate_changed_coverage.py, deleted).
 *
 * <p>Changed lines come from {@code git diff --no-renames --unified=0} (parsed without a
 * git dependency, so the gate is testable) with a sidecar fallback when git is
 * unavailable; JaCoCo lines are read by {@link JacocoReportReader}; the gate enforces the
 * allowlist, the non-zero denominator, and the unrounded threshold comparison; the
 * evidence receipt is emitted through {@link PythonJson} with keys in sorted order,
 * reproducing {@code json.dumps(indent=2, sort_keys=True)} byte for byte.
 *
 * <p>Deliberate divergences from the Python original, all preserving the exit code:
 * failures print one stable diagnostic instead of a traceback; argument errors print a
 * short usage line instead of argparse's block (exit code 2 either way); Python's {@code
 * int()} grammar for sidecar line numbers (surrounding whitespace, underscores between
 * digits) is replicated exactly.
 */
public final class ChangedCoverageValidator {
  static final ObjectMapper MAPPER = new ObjectMapper()
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  /** Thrown for any failed rule; the run boundary renders it as exit code 1. */
  public static final class ValidationError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ValidationError(String message) {
      super(message);
    }

    ValidationError(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Signals that git is unavailable so the sidecar fallback can take over. */
  private static final class GitUnavailable extends RuntimeException {
    private static final long serialVersionUID = 1L;

    GitUnavailable(Throwable cause) {
      super(cause);
    }
  }

  private ChangedCoverageValidator() {}

  /**
   * Entry point: validates changed-line coverage and writes the evidence receipt.
   *
   * @param args {@code --base --head --report --evidence} (required), {@code --path}
   *     (repository path prefix), {@code --allow-missing} (repeatable), {@code
   *     --threshold} (defaults to 90.0)
   * @param cwd working directory, used as the git root and for relative paths
   * @return 0 on success, 1 on a validation failure, 2 on an argument error
   */
  public static int run(String[] args, Path cwd) {
    Arguments arguments = Arguments.parse(args);
    if (arguments == null) {
      System.err.println("usage: ChangedCoverageValidator --base SHA --head SHA"
          + " --report JACOCO_XML --evidence JSON [--path PREFIX]"
          + " [--allow-missing PATH]... [--threshold PCT]");
      return 2;
    }
    try {
      return runValidated(arguments, cwd);
    } catch (ValidationError | IOException error) {
      System.err.println(error.getMessage());
      return 1;
    }
  }

  /** Main boundary; the only System.exit. */
  public static void main(String[] args) {
    System.exit(run(args, Path.of("").toAbsolutePath()));
  }

  private static int runValidated(Arguments arguments, Path cwd) throws IOException {
    Path report = arguments.report();
    if (!Files.isRegularFile(report)) {
      throw new ValidationError("missing JaCoCo report: " + report);
    }
    Map<String, Set<Long>> changed = changedLines(cwd, arguments.base(), arguments.head(),
        ".java", arguments.evidence());
    Map<String, Set<Long>> filtered = productionChanges(changed, arguments.path());
    JacocoReportReader.JacocoCoverage coverage = JacocoReportReader.jacocoLines(report,
        sourceRoot(arguments.path()));
    Counted counted = count(filtered, coverage);
    List<String> missingFiles = missingFiles(filtered, coverage);
    List<String> allowlisted = allowlisted(missingFiles, arguments.allowMissing());
    writeEvidence(arguments, report, counted, missingFiles, allowlisted);
    return exitCode(arguments, counted, missingFiles, allowlisted);
  }

  /** Changes under the production source root with at least one line. */
  private static Map<String, Set<Long>> productionChanges(Map<String, Set<Long>> changed,
      String path) {
    String sourceRoot = sourceRoot(path);
    Map<String, Set<Long>> filtered = new LinkedHashMap<>();
    for (Map.Entry<String, Set<Long>> entry : changed.entrySet()) {
      if (!entry.getValue().isEmpty() && isProductionPath(entry.getKey(), sourceRoot)) {
        filtered.put(entry.getKey(), entry.getValue());
      }
    }
    return filtered;
  }

  /** Numerator, denominator, and per-file counts over the filtered changes. */
  private static Counted count(Map<String, Set<Long>> filtered,
      JacocoReportReader.JacocoCoverage coverage) {
    long denominator = 0;
    long numerator = 0;
    Map<String, ObjectNode> files = new TreeMap<>();
    for (Map.Entry<String, Set<Long>> entry : new TreeMap<>(filtered).entrySet()) {
      Set<Long> executableChanged = new LinkedHashSet<>(entry.getValue());
      executableChanged.retainAll(coverage.executable().getOrDefault(entry.getKey(),
          Set.of()));
      Set<Long> coveredChanged = new LinkedHashSet<>(executableChanged);
      coveredChanged.retainAll(coverage.covered().getOrDefault(entry.getKey(),
          Set.of()));
      if (!executableChanged.isEmpty()) {
        ObjectNode counts = MAPPER.createObjectNode();
        counts.put("covered", coveredChanged.size());
        counts.put("executable", executableChanged.size());
        files.put(entry.getKey(), counts);
      }
      denominator += executableChanged.size();
      numerator += coveredChanged.size();
    }
    return new Counted(numerator, denominator, files);
  }

  /** Sorted changed files absent from the JaCoCo report. */
  private static List<String> missingFiles(Map<String, Set<Long>> filtered,
      JacocoReportReader.JacocoCoverage coverage) {
    List<String> missingFiles = new ArrayList<>();
    for (String path : new TreeMap<>(filtered).keySet()) {
      if (!coverage.executable().containsKey(path)) {
        missingFiles.add(path);
      }
    }
    return missingFiles;
  }

  /** The missing files that the allowlist permits. */
  private static List<String> allowlisted(List<String> missingFiles,
      List<String> allowMissing) {
    Set<String> allowed = new LinkedHashSet<>(allowMissing);
    List<String> allowlisted = new ArrayList<>();
    for (String path : missingFiles) {
      if (allowed.contains(path)) {
        allowlisted.add(path);
      }
    }
    return allowlisted;
  }

  private static int exitCode(Arguments arguments, Counted counted,
      List<String> missingFiles, List<String> allowlisted) {
    Set<String> allowed = new LinkedHashSet<>(arguments.allowMissing());
    List<String> unexpected = new ArrayList<>();
    for (String path : missingFiles) {
      if (!allowed.contains(path)) {
        unexpected.add(path);
      }
    }
    if (!unexpected.isEmpty()) {
      System.err.println("changed files are missing from JaCoCo: "
          + String.join(", ", unexpected));
      return 1;
    }
    if (counted.denominator() == 0) {
      System.err.println("changed executable-line denominator is zero");
      return 1;
    }
    double percentage = counted.percentage();
    System.out.println("changed executable lines: " + counted.numerator() + "/"
        + counted.denominator() + " (" + PythonNumbers.format2(percentage) + "%)");
    if (percentage < arguments.threshold()) {
      System.err.println("coverage below required threshold: "
          + PythonNumbers.format2(arguments.threshold()) + "%");
      return 1;
    }
    return 0;
  }

  /** Parses added lines from a unified diff (see {@link ChangedLines}). */
  public static Map<String, Set<Long>> parseUnifiedDiff(String diffText) {
    return ChangedLines.parseUnifiedDiff(diffText);
  }

  /** Changed lines via git, falling back to the sidecar when git is unavailable. */
  public static Map<String, Set<Long>> changedLines(Path root, String base, String head,
      String suffix, Path evidence) {
    return ChangedLines.changedLines(root, base, head, suffix, evidence);
  }

  /** Runs git diff and parses it; any failure means "use the sidecar". */
  public static Map<String, Set<Long>> gitChangedLines(Path root, String base,
      String head, String suffix) {
    return ChangedLines.gitChangedLines(root, base, head, suffix);
  }

  /** True when the path lies under the production source root (prefix, not overlap). */
  public static boolean isProductionPath(String path, String sourceRoot) {
    String prefix = sourceRoot.replaceAll("/+$", "") + "/";
    return path.startsWith(prefix);
  }

  // -------------------------------------------------------------- evidence

  private static void writeEvidence(Arguments arguments, Path report, Counted counted,
      List<String> missingFiles, List<String> allowlisted) throws IOException {
    ObjectNode result = MAPPER.createObjectNode();
    // Keys inserted in sorted order, reproducing json.dumps(sort_keys=True).
    ArrayNode allowlistedNode = result.putArray("allowlistedMissingFiles");
    allowlisted.forEach(allowlistedNode::add);
    result.put("base", arguments.base());
    result.put("covered", counted.numerator());
    result.put("executable", counted.denominator());
    ObjectNode filesNode = result.putObject("files");
    counted.files().forEach(filesNode::set);
    result.put("head", arguments.head());
    ArrayNode missingNode = result.putArray("missingFiles");
    missingFiles.forEach(missingNode::add);
    result.put("percentage", PythonNumbers.round2(counted.percentage()));
    result.put("report", report.toString());
    result.put("reportSha256", FileHashes.sha256(report));
    result.put("threshold", arguments.threshold());
    Path evidence = arguments.evidence();
    if (evidence.getParent() != null) {
      Files.createDirectories(evidence.getParent());
    }
    Files.writeString(evidence, PythonJson.dumps(result) + "\n",
        java.nio.charset.StandardCharsets.UTF_8);
  }

  // -------------------------------------------------------------- helpers

  private static String sourceRoot(String path) {
    // Python: args.path.strip("/") — slashes only, no whitespace stripping.
    String stripped = path;
    while (stripped.startsWith("/")) {
      stripped = stripped.substring(1);
    }
    while (stripped.endsWith("/") && !stripped.isEmpty()) {
      stripped = stripped.substring(0, stripped.length() - 1);
    }
    return stripped.isEmpty() ? "src/main/java" : stripped + "/src/main/java";
  }

  /** Numerator, denominator, per-file counts, and the unrounded percentage. */
  private record Counted(long numerator, long denominator,
      Map<String, ObjectNode> files) {

    double percentage() {
      return denominator == 0 ? 0.0 : numerator * 100.0 / denominator;
    }
  }

  /** Parsed command line. */
  private record Arguments(String base, String head, Path report, Path evidence,
      String path, List<String> allowMissing, double threshold) {

    static Arguments parse(String[] args) {
      Map<String, List<String>> flags = ValidatorCli.parse(args,
          Set.of("allow-missing"),
          Set.of("base", "head", "report", "evidence", "path", "allow-missing",
              "threshold"));
      if (flags == null) {
        return null;
      }
      String base = ValidatorCli.single(flags, "base");
      String head = ValidatorCli.single(flags, "head");
      String report = ValidatorCli.single(flags, "report");
      String evidence = ValidatorCli.single(flags, "evidence");
      if (base == null || head == null || report == null || evidence == null) {
        return null;
      }
      String thresholdText = ValidatorCli.single(flags, "threshold");
      double threshold = 90.0;
      if (thresholdText != null) {
        Double parsed = PythonNumbers.parsePythonFloat(thresholdText);
        if (parsed == null) {
          return null;
        }
        threshold = parsed;
      }
      return new Arguments(base, head, Path.of(report), Path.of(evidence),
          valueOr(flags, "path", ""), flags.getOrDefault("allow-missing", List.of()),
          threshold);
    }

    private static String valueOr(Map<String, List<String>> flags, String name,
        String fallback) {
      String value = ValidatorCli.single(flags, name);
      return value == null ? fallback : value;
    }
  }
}
