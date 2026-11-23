package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression cells for the surefire test-count gate (#45 step 1, ported from
 * spike/scripts/check_test_count.py).
 *
 * <p>The adapter suite runs in three surefire executions (default, large-archive, sootup),
 * each with its own reports directory; the gate sums them and enforces the recorded
 * baseline. These cells pin the counting, the lane/baseline rules, and every malformed
 * input shape.
 */
class SurefireTestCounterTest {
  @TempDir Path temp;

  private Path lane(String name) throws IOException {
    Path directory = temp.resolve(name);
    Files.createDirectories(directory);
    return directory;
  }

  private void report(Path directory, String file, String tests) throws IOException {
    Files.writeString(directory.resolve(file), """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuite name="%s" tests="%s" failures="0" errors="0" skipped="0">
        </testsuite>
        """.formatted(file, tests));
  }

  /** Count sums the tests attribute over every TEST-*.xml in the directory. */
  @Test
  void countSumsTestsAttributesAcrossReports() throws IOException {
    Path directory = lane("a");
    report(directory, "TEST-one.xml", "3");
    report(directory, "TEST-two.xml", "4");
    Files.writeString(directory.resolve("ignore.txt"), "not a report");

    assertEquals(7, SurefireTestCounter.count(directory));
  }

  /** A missing or empty reports directory counts as zero, never an error. */
  @Test
  void missingDirectoryCountsAsZero() {
    assertEquals(0, SurefireTestCounter.count(temp.resolve("absent")));
  }

  /** A regular file at the reports path fails loudly, naming the path. */
  @Test
  void fileAtReportsPathFailsLoudlyNamingThePath() throws IOException {
    Path file = temp.resolve("surefire-reports");
    Files.writeString(file, "not a directory");

    IllegalStateException error = assertThrows(
        IllegalStateException.class, () -> SurefireTestCounter.count(file));
    assertTrue(error.getMessage().contains("surefire-reports"));
    assertTrue(error.getMessage().contains("not a directory"));
  }

  /** Counting uses long arithmetic; two huge reports must not wrap. */
  @Test
  void countSumsBeyondIntRangeWithoutWrapping() throws IOException {
    Path directory = lane("huge");
    report(directory, "TEST-one.xml", "2000000000");
    report(directory, "TEST-two.xml", "2000000000");

    assertEquals(4_000_000_000L, SurefireTestCounter.count(directory));
  }

  /** A malformed report fails loudly, naming the file. */
  @Test
  void malformedReportFailsLoudlyNamingTheFile() throws IOException {
    Path directory = lane("broken");
    Files.writeString(directory.resolve("TEST-broken.xml"), "not xml");

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> SurefireTestCounter.count(directory));
    assertTrue(error.getMessage().contains("TEST-broken.xml"));
  }

  /** A report without a numeric tests attribute fails loudly, naming the file. */
  @Test
  void missingOrNonNumericTestsAttributeFailsLoudlyNamingTheFile() throws IOException {
    Path missing = lane("missing-attr");
    Files.writeString(missing.resolve("TEST-missing.xml"), """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuite name="missing"></testsuite>
        """);
    IllegalStateException error = assertThrows(IllegalStateException.class,
        () -> SurefireTestCounter.count(missing));
    assertTrue(error.getMessage().contains("TEST-missing.xml"));

    Path nonNumeric = lane("non-numeric");
    report(nonNumeric, "TEST-nonnumeric.xml", "7x");
    error = assertThrows(IllegalStateException.class,
        () -> SurefireTestCounter.count(nonNumeric));
    assertTrue(error.getMessage().contains("TEST-nonnumeric.xml"));
  }

  /** A directory named TEST-*.xml is not a report and fails loudly, naming it. */
  @Test
  void directoryNamedLikeAReportFailsLoudlyNamingIt() throws IOException {
    Path directory = lane("dir-report");
    Files.createDirectories(directory.resolve("TEST-dir.xml"));

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> SurefireTestCounter.count(directory));
    assertTrue(error.getMessage().contains("TEST-dir.xml"));
  }

  /** The summary line reports every lane and the baseline. */
  @Test
  void summaryReportsEveryLaneAndBaseline() {
    List<String> summary = SurefireTestCounter.summaryLines(377, 1, 70, 448);

    assertTrue(String.join("\n", summary).contains("default=377"));
    assertTrue(String.join("\n", summary).contains("large=1"));
    assertTrue(String.join("\n", summary).contains("sootup=70"));
    assertTrue(String.join("\n", summary).contains("total=448"));
    assertTrue(String.join("\n", summary).contains("baseline 448"));
  }

  /** Every lane must run tests and the total must meet the baseline. */
  @Test
  void verifyRejectsEmptyLanesAndCountsBelowBaseline() {
    assertTrue(
        SurefireTestCounter.verifyLines(377, 1, 70, 448).isEmpty(),
        "all lanes non-empty at the baseline passes");

    assertTrue(
        SurefireTestCounter.verifyLines(377, 0, 70, 448)
            .toString().contains("large-archive"),
        "an empty large lane fails");
    assertTrue(
        SurefireTestCounter.verifyLines(377, 1, 0, 448)
            .toString().contains("sootup"),
        "an empty sootup lane fails");
    assertTrue(
        SurefireTestCounter.verifyLines(370, 1, 70, 448)
            .toString().contains("baseline"),
        "a total below the baseline fails");
  }

  /**
   * The no-argument baseline defaults to exactly 302: a total of 302 with all lanes
   * non-empty passes, and a total of 301 fails on the baseline rule.
   */
  @Test
  void noArgumentBaselineDefaultsToExactly302() throws IOException {
    Path baseAtBoundary = lane("base-302");
    Path baseBelow = lane("base-301");
    for (String lane : new String[] {"surefire-reports", "surefire-reports-large",
        "surefire-reports-sootup"}) {
      report(Files.createDirectories(baseAtBoundary.resolve(lane)),
          "TEST-one.xml", "100");
      report(Files.createDirectories(baseBelow.resolve(lane)),
          "TEST-one.xml", "100");
    }
    report(baseAtBoundary.resolve("surefire-reports"), "TEST-two.xml", "2");
    report(baseBelow.resolve("surefire-reports"), "TEST-two.xml", "1");

    assertEquals(0, SurefireTestCounter.run(new String[] {}, baseAtBoundary));
    assertEquals(1, SurefireTestCounter.run(new String[] {}, baseBelow));
  }

  /** A non-numeric baseline argument is an error line and exit 1, never a crash. */
  @Test
  void nonNumericBaselineArgumentIsAnErrorLineNotACrash() throws IOException {
    Path base = passingBase();

    assertEquals(1, SurefireTestCounter.run(new String[] {"abc"}, base));
  }

  /** A stray extra argument is an error line and exit 1, not silently ignored. */
  @Test
  void extraBaselineArgumentIsAnErrorLineNotIgnored() throws IOException {
    Path base = passingBase();

    assertEquals(1, SurefireTestCounter.run(new String[] {"1", "999"}, base));
  }

  /**
   * A base that passes the gate under every plausible baseline misread: lanes total
   * 1000, above the default 302 and above any single argument the argv cells pass
   * (1, 999), so an exit 1 in those cells can only come from the argv error itself.
   */
  private Path passingBase() throws IOException {
    Path base = temp.resolve("passing-base");
    report(Files.createDirectories(base.resolve("surefire-reports")),
        "TEST-one.xml", "400");
    report(Files.createDirectories(base.resolve("surefire-reports-large")),
        "TEST-one.xml", "300");
    report(Files.createDirectories(base.resolve("surefire-reports-sootup")),
        "TEST-one.xml", "300");
    return base;
  }

  /** Exit code is 0 with no error lines and 1 with any; run never throws. */
  @Test
  void exitCodeMapsErrorLines() {
    assertEquals(0, SurefireTestCounter.exitCode(List.of()));
    assertEquals(1, SurefireTestCounter.exitCode(List.of("ERROR: anything")));
  }
}
