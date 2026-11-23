package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
 * baseline. These cells pin the counting and the lane/baseline rules.
 */
class SurefireTestCounterTest {
  @TempDir Path temp;

  private void report(String name, int tests) throws IOException {
    Path directory = temp.resolve(name).getParent() == null
        ? temp : temp.resolve(name).getParent();
    Files.createDirectories(directory);
    Files.writeString(temp.resolve(name), """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuite name="%s" tests="%d" failures="0" errors="0" skipped="0">
        </testsuite>
        """.formatted(name, tests));
  }

  /** Count sums the tests attribute over every TEST-*.xml in the directory. */
  @Test
  void countSumsTestsAttributesAcrossReports() throws IOException {
    report("a/TEST-one.xml", 3);
    report("a/TEST-two.xml", 4);
    report("a/ignore.txt", 0);

    assertEquals(7, SurefireTestCounter.count(temp.resolve("a")));
  }

  /** A missing or empty reports directory counts as zero, never an error. */
  @Test
  void missingDirectoryCountsAsZero() {
    assertEquals(0, SurefireTestCounter.count(temp.resolve("absent")));
  }

  /** A malformed report fails loudly instead of silently undercounting. */
  @Test
  void malformedReportFailsLoudly() throws IOException {
    Files.writeString(temp.resolve("TEST-broken.xml"), "not xml");

    assertThrows(
        IllegalStateException.class, () -> SurefireTestCounter.count(temp));
  }

  /** The summary line reports every lane and the baseline. */
  @Test
  void summaryReportsEveryLaneAndBaseline() {
    List<String> summary =
        SurefireTestCounter.summaryLines(377, 1, 70, 448);

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

  /** Exit code is 0 with no error lines and 1 with any; run never throws. */
  @Test
  void exitCodeMapsErrorLinesAndRunNeverThrows() throws IOException {
    assertEquals(0, SurefireTestCounter.exitCode(List.of()));
    assertEquals(1, SurefireTestCounter.exitCode(List.of("ERROR: anything")));

    // run() is exercised against a temp base, never the live target/ directory:
    // surefire writes those reports concurrently with this parallel suite, and a
    // half-written report would turn the test into a flake.
    Path base = temp.resolve("base");
    for (String lane : new String[] {"surefire-reports", "surefire-reports-large",
        "surefire-reports-sootup"}) {
      Files.createDirectories(base.resolve(lane));
      Files.writeString(base.resolve(lane + "/TEST-one.xml"), """
          <?xml version="1.0" encoding="UTF-8"?>
          <testsuite name="one" tests="2" failures="0" errors="0" skipped="0">
          </testsuite>
          """);
    }
    assertEquals(0, SurefireTestCounter.run(new String[] {"1"}, base));
    assertEquals(1, SurefireTestCounter.run(new String[] {}, temp.resolve("empty-base")));
  }
}
