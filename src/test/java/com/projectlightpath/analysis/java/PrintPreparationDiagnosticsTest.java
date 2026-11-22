package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression cells for the fail-visible preparation-diagnostics printer (#40).
 *
 * <p>Each cell pins the rendered message for one report shape so a regression in the
 * fail-visible handling of missing, malformed, or diagnostic-free reports is caught.
 */
class PrintPreparationDiagnosticsTest {
  @TempDir Path temp;

  private final PrintStream originalErr = System.err;
  private ByteArrayOutputStream capturedErr;

  @BeforeEach
  void captureStderr() {
    capturedErr = new ByteArrayOutputStream();
    System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));
  }

  @AfterEach
  void restoreStderr() {
    System.setErr(originalErr);
  }

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

  /** Main prints usage for argv misuse and exits without throwing. */
  @Test
  void mainPrintsUsageForArgvMisuse() throws IOException {
    PrintPreparationDiagnostics.main(new String[0]);
    assertTrue(capturedErr.toString(StandardCharsets.UTF_8).contains("usage:"));

    PrintPreparationDiagnostics.main(new String[] {"a", "b"});
    assertTrue(capturedErr.toString(StandardCharsets.UTF_8).contains("usage:"));
  }

  /** Main prints the rendered diagnostics for a report path and never throws. */
  @Test
  void mainPrintsDiagnosticsForReportPath() throws IOException {
    PrintPreparationDiagnostics.main(new String[] {
        report("{\"status\": \"failed\", \"diagnostics\": []}").toString()});
    String stderr = capturedErr.toString(StandardCharsets.UTF_8);
    assertTrue(stderr.contains("status: failed"));
    assertTrue(stderr.contains("no diagnostics were recorded"));
  }
}
