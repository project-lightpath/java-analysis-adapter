package com.projectlightpath.analysis.java;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Fail-visible printer for a VPE preparation report (#40).
 *
 * <p>The manual integration runner aborts with a bare non-zero exit status when the VPE
 * prepare-manual step fails; the actual diagnostics are only in the preparation report
 * JSON. This printer makes those failures visible: it renders the report status and
 * every diagnostic. It never exits non-zero, so it never masks the runner's own failure
 * exit code.
 */
public final class PrintPreparationDiagnostics {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private PrintPreparationDiagnostics() {}

  /**
   * Renders fail-visible diagnostic lines for a preparation report.
   *
   * <p>Every malformed shape (missing file, directory, unreadable bytes, non-object
   * JSON, malformed diagnostics field) renders an actionable message rather than
   * throwing, so the caller can always print something.
   *
   * @param reportPath path to the preparation report JSON
   * @return message lines, in print order, without trailing newlines
   */
  public static List<String> render(Path reportPath) {
    if (!Files.isRegularFile(reportPath)) {
      return List.of("preparation report not found or not a file: " + reportPath);
    }
    JsonNode report;
    try {
      report = MAPPER.readTree(Files.readAllBytes(reportPath));
    } catch (IOException error) {
      return List.of("preparation report is unreadable: " + reportPath
          + " (" + error.getMessage() + ")");
    }
    if (report == null || !report.isObject()) {
      return List.of("preparation report is unreadable: " + reportPath
          + " (expected a JSON object)");
    }
    List<String> lines = new ArrayList<>();
    StringBuilder header = new StringBuilder("preparation report: ").append(reportPath)
        .append(" (status: ").append(textOrString(report.path("status"), "unknown"));
    if (report.has("profileId")) {
      header.append(", profile: ").append(textOrString(report.path("profileId"), ""));
    }
    header.append(")");
    lines.add(header.toString());
    if (!report.has("diagnostics")) {
      lines.add("no diagnostics were recorded in the report");
      return lines;
    }
    JsonNode diagnostics = report.path("diagnostics");
    if (!diagnostics.isArray()) {
      lines.add("diagnostics field is malformed (not a list): " + diagnostics);
      return lines;
    }
    if (diagnostics.isEmpty()) {
      lines.add("no diagnostics were recorded in the report");
      return lines;
    }
    for (JsonNode diagnostic : diagnostics) {
      if (!diagnostic.isObject()) {
        lines.add("  - " + diagnostic);
        continue;
      }
      lines.add("  - [" + textOrString(diagnostic.path("severity"), "unknown_severity")
          + "] " + textOrString(diagnostic.path("code"), "unknown_code")
          + " (phase: " + textOrString(diagnostic.path("phase"), "unknown_phase")
          + "): " + textOrString(diagnostic.path("message"), ""));
    }
    return lines;
  }

  /**
   * Prints report diagnostics to stderr; never exits non-zero.
   *
   * @param args exactly one argument: the preparation report path
   */
  public static void main(String[] args) {
    printLines(mainLines(args), System.err);
  }

  /**
   * Lines main prints for the given arguments: usage on argv misuse, otherwise the
   * rendered report diagnostics.
   *
   * @param args command-line arguments
   * @return message lines, in print order
   */
  static List<String> mainLines(String[] args) {
    return args.length == 1 ? render(Path.of(args[0])) : usageLines();
  }

  /** Usage lines printed when the argument count is wrong. */
  static List<String> usageLines() {
    return List.of("usage: PrintPreparationDiagnostics PREPARATION_REPORT_JSON");
  }

  /** Prints the lines to the stream, one per line. */
  static void printLines(List<String> lines, PrintStream stream) {
    for (String line : lines) {
      stream.println(line);
    }
  }

  private static String textOrString(JsonNode node, String fallback) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return fallback;
    }
    return node.isTextual() ? node.asText() : node.toString();
  }
}
