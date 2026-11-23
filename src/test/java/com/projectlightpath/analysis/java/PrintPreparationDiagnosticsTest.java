package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression cells for the fail-visible preparation-diagnostics printer (#40).
 *
 * <p>Each cell pins the rendered message for one report shape so a regression in the
 * fail-visible handling of missing, malformed, or diagnostic-free reports is caught. The
 * suite runs test methods in parallel, so no test captures the shared System.err: main
 * delegates to injectable pieces (render, usageLines, printLines) that are pinned
 * directly.
 */
class PrintPreparationDiagnosticsTest {
  @TempDir Path temp;

  private Path report(String content) throws IOException {
    Path file = temp.resolve("preparation-report.json");
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file;
  }

  private static String joined(List<String> lines) {
    return String.join("\n", lines);
  }

  /** Prints the report status, profile, and every diagnostic. */
  @Test
  void printsStatusAndEachDiagnostic() throws IOException {
    Path file = report("""
        {
          "status": "failed",
          "profileId": "fsi-spring-boot-production",
          "diagnostics": [
            {
              "code": "host_execution_forbidden",
              "phase": "preparation",
              "severity": "error",
              "message": "prepare-manual is disabled"
            },
            {
              "code": "acquisition_failed",
              "phase": "acquisition",
              "severity": "error",
              "message": "local source path is unavailable"
            }
          ]
        }
        """);
    String rendered = joined(PrintPreparationDiagnostics.render(file));

    assertTrue(rendered.contains("status: failed"));
    assertTrue(rendered.contains("profile: fsi-spring-boot-production"));
    assertTrue(rendered.contains("host_execution_forbidden"));
    assertTrue(rendered.contains("prepare-manual is disabled"));
    assertTrue(rendered.contains("acquisition_failed"));
    assertTrue(rendered.contains("local source path is unavailable"));
  }

  /** A missing report file and a directory at the report path are both actionable. */
  @Test
  void missingOrNonFileReportIsActionable() {
    String missing = joined(PrintPreparationDiagnostics.render(temp.resolve("absent.json")));
    assertTrue(missing.contains("not found or not a file"));
    assertTrue(missing.contains("absent.json"));

    String directory = joined(PrintPreparationDiagnostics.render(temp));
    assertTrue(directory.contains("not found or not a file"));
  }

  /** An absent key and an empty list both mean no diagnostics were recorded. */
  @Test
  void absentOrEmptyDiagnosticsSaySo() throws IOException {
    String absent = joined(PrintPreparationDiagnostics.render(
        report("{\"status\": \"failed\"}")));
    assertTrue(absent.contains("no diagnostics were recorded"));

    String empty = joined(PrintPreparationDiagnostics.render(
        report("{\"status\": \"failed\", \"diagnostics\": []}")));
    assertTrue(empty.contains("no diagnostics were recorded"));
  }

  /** A present non-list diagnostics field (including null) is malformed, not absent. */
  @Test
  void nonListDiagnosticsIsMalformedNotAbsent() throws IOException {
    String nullField = joined(PrintPreparationDiagnostics.render(
        report("{\"status\": \"failed\", \"diagnostics\": null}")));
    assertTrue(nullField.contains("malformed"));
    assertFalse(nullField.contains("no diagnostics"));

    String stringField = joined(PrintPreparationDiagnostics.render(
        report("{\"status\": \"failed\", \"diagnostics\": \"host_execution_forbidden\"}")));
    assertTrue(stringField.contains("malformed"));
    assertFalse(stringField.contains("no diagnostics"));
  }

  /** Non-dict diagnostic entries and non-string field values still print. */
  @Test
  void nonDictAndNonStringDiagnosticsStillPrint() throws IOException {
    String rendered = joined(PrintPreparationDiagnostics.render(report("""
        {
          "status": "failed",
          "diagnostics": [
            "plain-string",
            {"code": 123, "severity": null, "phase": "p", "message": {"nested": "obj"}}
          ]
        }
        """)));
    assertTrue(rendered.contains("plain-string"));
    assertTrue(rendered.contains("123"));
    assertTrue(rendered.contains("nested"));
  }

  /** A falsy but present profile id is still shown. */
  @Test
  void falsyProfileIdIsStillShown() throws IOException {
    String rendered = joined(PrintPreparationDiagnostics.render(
        report("{\"status\": \"failed\", \"profileId\": \"\"}")));
    assertTrue(rendered.contains("profile:"));
  }

  /** A non-object top-level JSON report is actionable. */
  @Test
  void nonDictTopLevelJsonIsActionable() throws IOException {
    String rendered = joined(PrintPreparationDiagnostics.render(report("[1, 2]")));
    assertTrue(rendered.contains("expected a JSON object"));
  }

  /** Malformed JSON and invalid UTF-8 are actionable, never a traceback. */
  @Test
  void malformedReportIsActionable() throws IOException {
    String malformed = joined(PrintPreparationDiagnostics.render(report("not json")));
    assertTrue(malformed.contains("unreadable"));

    Path binary = temp.resolve("binary-report.json");
    Files.write(binary, new byte[] {(byte) 0xff, (byte) 0xfe, 0x00, 0x01});
    String invalid = joined(PrintPreparationDiagnostics.render(binary));
    assertTrue(invalid.contains("unreadable"));
  }

  /** MainLines pins argv misuse and report-path content; main never throws. */
  @Test
  void mainLinesPinArgvMisuseAndReportContent() throws IOException {
    assertTrue(joined(PrintPreparationDiagnostics.mainLines(new String[0]))
        .contains("usage:"));
    assertTrue(joined(PrintPreparationDiagnostics.mainLines(new String[] {"a", "b"}))
        .contains("usage:"));

    String reportPath =
        report("{\"status\": \"failed\", \"diagnostics\": []}").toString();
    String rendered = joined(
        PrintPreparationDiagnostics.mainLines(new String[] {reportPath}));
    assertTrue(rendered.contains("status: failed"));
    assertTrue(rendered.contains("no diagnostics were recorded"));

    assertDoesNotThrow(() -> PrintPreparationDiagnostics.main(new String[0]));
    assertDoesNotThrow(
        () -> PrintPreparationDiagnostics.main(new String[] {reportPath}));
  }

  /** PrintLines writes exactly one line per rendered line to the target stream. */
  @Test
  void printLinesWritesRenderedLinesToTargetStream() throws IOException {
    Path file = report("{\"status\": \"failed\", \"diagnostics\": []}");
    ByteArrayOutputStream sink = new ByteArrayOutputStream();

    List<String> rendered = PrintPreparationDiagnostics.render(file);
    PrintPreparationDiagnostics.printLines(rendered, new PrintStream(sink, true));

    String printed = sink.toString(StandardCharsets.UTF_8);
    assertTrue(printed.contains("status: failed"));
    assertTrue(printed.contains("no diagnostics were recorded"));
    String[] printedLines = printed.split("\n", -1);
    assertEquals(rendered.size(), printedLines.length - 1);
    for (int i = 0; i < rendered.size(); i++) {
      assertEquals(rendered.get(i), printedLines[i]);
    }
    assertTrue(printed.endsWith("\n"));
  }
}
