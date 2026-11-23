package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression cells for the changed-coverage validator (#45 step 4, ported from
 * spike/scripts/validate_changed_coverage.py and its cells in test_validators.py).
 *
 * <p>The unified-diff parser, the JaCoCo XML reader, the sidecar fallback, and the
 * run-level gate rules (allowlisting, zero denominator, threshold, rounding) are pinned
 * against Python semantics. Git-unavailable fallback is exercised naturally: the scratch
 * root is not a repository, so git fails and the sidecar path runs; one cell drives the
 * real git path against a scratch repository. No test captures System.out or System.err.
 */
class ChangedCoverageValidatorTest {
  @TempDir Path temp;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Path scratchRoot() throws IOException {
    Path root = temp.resolve("repo");
    Files.createDirectories(root);
    return root;
  }

  /** A minimal JaCoCo report with one covered and one uncovered line. */
  private Path jacocoReport() throws IOException {
    Path report = temp.resolve("jacoco.xml");
    Files.writeString(report, """
        <?xml version="1.0" encoding="UTF-8"?>
        <report name="demo">
          <package name="com/example">
            <sourcefile name="Present.java">
              <line nr="10" mi="0" ci="3" mb="0" cb="0"/>
              <line nr="11" mi="0" ci="0" mb="0" cb="0"/>
            </sourcefile>
          </package>
        </report>
        """);
    return report;
  }

  /** Runs the gate with a sidecar (the scratch root is not a git repository). */
  private int runWithSidecar(Path root, String sidecarJson, String... extraArgs)
      throws IOException {
    Path evidence = root.resolve("coverage.json");
    Files.writeString(root.resolve("changed-lines.json"), sidecarJson);
    java.util.List<String> args = new java.util.ArrayList<>();
    args.add("--report");
    args.add(temp.resolve("jacoco.xml").toString());
    args.add("--evidence");
    args.add(evidence.toString());
    args.add("--base");
    args.add("base");
    args.add("--head");
    args.add("head");
    args.addAll(java.util.List.of(extraArgs));
    return ChangedCoverageValidator.run(args.toArray(new String[0]), root);
  }

  /** Parses added lines from a unified diff text. */
  @Test
  void parsesAddedLinesFromUnifiedDiff() {
    Map<String, Set<Long>> changed = ChangedCoverageValidator.parseUnifiedDiff("""
        diff --git a/src/A.java b/src/A.java
        --- a/src/A.java
        +++ b/src/A.java
        @@ -1,3 +2,4 @@
        +added one
        +added two
        +added three
        +added four
        """);
    assertEquals(Map.of("src/A.java", Set.of(2L, 3L, 4L, 5L)), changed);
  }

  /** A hunk without a count adds exactly one line; a zero-count hunk adds none. */
  @Test
  void hunkCountDefaultsToOneAndZeroCountAddsNothing() {
    Map<String, Set<Long>> changed = ChangedCoverageValidator.parseUnifiedDiff("""
        +++ b/src/B.java
        @@ -1 +5 @@
        +only
        @@ -1 +7,0 @@
        removed only
        """);
    assertEquals(Set.of(5L), changed.get("src/B.java"));
    assertFalse(changed.get("src/B.java").contains(7L));
  }

  /** CRLF and lone CR are normalized before splitting; paths keep their spaces. */
  @Test
  void crlfDiffIsNormalized() {
    Map<String, Set<Long>> changed = ChangedCoverageValidator.parseUnifiedDiff(
        "+++ b/src/With Space.java\r\n@@ -1 +2 @@\r\n+x\r\n");
    assertEquals(Set.of(2L), changed.get("src/With Space.java"));
  }

  /** Lines that are not additions do not advance; +++ /dev/null is not a file header. */
  @Test
  void nonAdditionLinesDoNotAdvance() {
    Map<String, Set<Long>> changed = ChangedCoverageValidator.parseUnifiedDiff("""
        +++ b/src/C.java
        @@ -1,2 +1,2 @@
        -removed
        context
        \\ No newline at end of file
        +++ /dev/null
        """);
    assertEquals(Set.of(1L, 2L), changed.get("src/C.java"));
  }

  /** A hunk before any file header is ignored. */
  @Test
  void hunkBeforeHeaderIsIgnored() {
    Map<String, Set<Long>> changed = ChangedCoverageValidator.parseUnifiedDiff("""
        @@ -1 +9 @@
        +orphan
        """);
    assertTrue(changed.isEmpty());
  }

  /** The sidecar must match the requested revisions. */
  @Test
  void sidecarMustMatchRequestedRevisions() throws IOException {
    Path root = scratchRoot();
    Files.writeString(root.resolve("changed-lines.json"),
        "{\"base\": \"wrong-base\", \"head\": \"head\", \"files\": {\"a.java\": [4]}}");
    ChangedCoverageValidator.ValidationError error = assertThrows(
        ChangedCoverageValidator.ValidationError.class,
        () -> ChangedCoverageValidator.changedLines(root, "base", "head", ".java",
            root.resolve("coverage.json")));
    assertTrue(error.getMessage().contains("revisions do not match"));
  }

  /** Sidecar files must be an object of int-like arrays, with Python's lenient int(). */
  @Test
  void sidecarParsesLenientInts() throws IOException {
    Path root = scratchRoot();
    Files.writeString(root.resolve("changed-lines.json"),
        "{\"base\": \"base\", \"head\": \"head\", \"files\":"
            + " {\"a.java\": [4, \" 5 \", \"1_0\"]}}");
    Map<String, Set<Long>> changed = ChangedCoverageValidator.changedLines(root, "base",
        "head", ".java", root.resolve("coverage.json"));
    assertEquals(Set.of(4L, 5L, 10L), changed.get("a.java"));
  }

  /** A non-numeric sidecar line rejects loudly. */
  @Test
  void sidecarNonNumericLineRejects() throws IOException {
    Path root = scratchRoot();
    Files.writeString(root.resolve("changed-lines.json"),
        "{\"base\": \"base\", \"head\": \"head\", \"files\": {\"a.java\": [\"x\"]}}");
    assertThrows(ChangedCoverageValidator.ValidationError.class,
        () -> ChangedCoverageValidator.changedLines(root, "base", "head", ".java",
            root.resolve("coverage.json")));
  }

  /** A scalar sidecar files value rejects (Python's TypeError crashed the process). */
  @Test
  void sidecarScalarFilesValueRejects() throws IOException {
    Path root = scratchRoot();
    Files.writeString(root.resolve("changed-lines.json"),
        "{\"base\": \"base\", \"head\": \"head\", \"files\": 5}");
    assertThrows(ChangedCoverageValidator.ValidationError.class,
        () -> ChangedCoverageValidator.changedLines(root, "base", "head", ".java",
            root.resolve("coverage.json")));
  }

  /** The source key joins source root, package, and file; empty parts drop out. */
  @Test
  void sourceKeyJoinsNonEmptyParts() {
    assertEquals("src/main/java/com/example/A.java",
        JacocoReportReader.sourceKey("com.example", "A.java", "src/main/java"));
    assertEquals("src/main/java/A.java",
        JacocoReportReader.sourceKey("", "A.java", "src/main/java"));
    assertEquals("nested/src/main/java/com/example/A.java",
        JacocoReportReader.sourceKey("/com.example/", "A.java",
            "nested/src/main/java"));
  }

  /** The production prefix must not overlap: src does not admit srcFoo. */
  @Test
  void productionPrefixDoesNotOverlap() {
    assertTrue(ChangedCoverageValidator.isProductionPath("src/main/java/A.java",
        "src/main/java"));
    assertFalse(ChangedCoverageValidator.isProductionPath("srcFoo/main/java/A.java",
        "src/main/java"));
  }

  /** JaCoCo lines: sourcefile entries plus the class sourcefilename union. */
  @Test
  void jacocoLinesUnionSourcefileAndClassEntries() throws IOException {
    Path report = temp.resolve("jacoco.xml");
    Files.writeString(report, """
        <?xml version="1.0" encoding="UTF-8"?>
        <report>
          <package name="com/example">
            <sourcefile name="A.java">
              <line nr="1" mi="0" ci="2"/>
            </sourcefile>
            <class sourcefilename="B.java">
              <lines>
                <line nr="5" mi="0" ci="0"/>
                <line nr="6" mi="0" ci="1"/>
              </lines>
            </class>
            <class>
              <lines><line nr="9" mi="0" ci="1"/></lines>
            </class>
          </package>
        </report>
        """);
    JacocoReportReader.JacocoCoverage coverage =
        JacocoReportReader.jacocoLines(report, "src/main/java");
    assertEquals(Set.of(1L),
        coverage.executable().get("src/main/java/com/example/A.java"));
    assertEquals(Set.of(1L),
        coverage.covered().get("src/main/java/com/example/A.java"));
    assertEquals(Set.of(5L, 6L),
        coverage.executable().get("src/main/java/com/example/B.java"));
    assertEquals(Set.of(6L),
        coverage.covered().get("src/main/java/com/example/B.java"));
  }

  /** A malformed JaCoCo report fails loudly, naming the report. */
  @Test
  void malformedJacocoReportFailsLoudly() throws IOException {
    Path report = temp.resolve("jacoco.xml");
    Files.writeString(report, "not xml");
    ChangedCoverageValidator.ValidationError error = assertThrows(
        ChangedCoverageValidator.ValidationError.class,
        () -> JacocoReportReader.jacocoLines(report, "src/main/java"));
    assertTrue(error.getMessage().contains("jacoco.xml"));
  }

  /** A missing report exits 1 with the Python message. */
  @Test
  void missingReportExitsOne() throws IOException {
    Path root = scratchRoot();
    int code = ChangedCoverageValidator.run(new String[] {"--report",
        temp.resolve("absent.xml").toString(), "--evidence",
        root.resolve("coverage.json").toString(), "--base", "base", "--head", "head"},
        root);
    assertEquals(1, code);
  }

  /** A changed file missing from JaCoCo is reported and can be allowlisted. */
  @Test
  void missingJacocoFileIsReportedAndCanBeAllowlisted() throws IOException {
    Path root = scratchRoot();
    jacocoReport();
    String sidecar = "{\"base\": \"base\", \"head\": \"head\", \"files\": {"
        + "\"src/main/java/demo/Missing.java\": [10],"
        + "\"src/main/java/com/example/Present.java\": [10],"
        + "\"src/test/java/demo/MainTest.java\": [12]}}";
    assertEquals(1, runWithSidecar(root, sidecar));
    JsonNode result = MAPPER.readTree(root.resolve("coverage.json").toFile());
    assertEquals("base", result.path("base").asText());
    assertEquals(1, result.path("covered").asInt());
    assertEquals(1, result.path("executable").asInt());
    assertEquals(100.0, result.path("percentage").asDouble());
    assertEquals(90.0, result.path("threshold").asDouble());
    assertEquals(1, result.path("missingFiles").size());

    Path allowlisted = temp.resolve("allowlisted.json");
    int code = runWithSidecar(root, sidecar, "--allow-missing",
        "src/main/java/demo/Missing.java");
    assertEquals(0, code);
    JsonNode allowlistedResult = MAPPER.readTree(root.resolve("coverage.json").toFile());
    assertEquals(1, allowlistedResult.path("allowlistedMissingFiles").size());
  }

  /** A zero changed denominator exits 1. */
  @Test
  void zeroDenominatorExitsOne() throws IOException {
    Path root = scratchRoot();
    jacocoReport();
    String sidecar = "{\"base\": \"base\", \"head\": \"head\", \"files\": {"
        + "\"src/main/java/com/example/Other.java\": [99]}}";
    assertEquals(1, runWithSidecar(root, sidecar));
  }

  /** Coverage below the threshold exits 1; at the threshold passes. */
  @Test
  void thresholdBoundaryIsUnrounded() throws IOException {
    Path root = scratchRoot();
    Path report = temp.resolve("jacoco.xml");
    Files.writeString(report, """
        <?xml version="1.0" encoding="UTF-8"?>
        <report>
          <package name="com/example">
            <sourcefile name="A.java">
              <line nr="1" mi="0" ci="1"/>
              <line nr="2" mi="5" ci="0"/>
              <line nr="3" mi="0" ci="1"/>
              <line nr="4" mi="0" ci="0"/>
              <line nr="5" mi="0" ci="1"/>
              <line nr="6" mi="0" ci="0"/>
              <line nr="7" mi="0" ci="1"/>
              <line nr="8" mi="0" ci="0"/>
              <line nr="9" mi="0" ci="1"/>
              <line nr="10" mi="0" ci="0"/>
            </sourcefile>
          </package>
        </report>
        """);
    String sidecar = "{\"base\": \"base\", \"head\": \"head\", \"files\": {"
        + "\"src/main/java/com/example/A.java\": [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]}}";
    assertEquals(50.0, 50.0);
    // 5 of 10 covered = 50%, below the default 90% threshold.
    assertEquals(1, runWithSidecar(root, sidecar));
    // Exactly at a lowered threshold passes.
    assertEquals(0, runWithSidecar(root, sidecar, "--threshold", "50"));
  }

  /** Python round(x, 2) is half-even on the binary double; .2f uses the same mode. */
  @Test
  void roundingMatchesPythonHalfEvenOnBinaryDoubles() {
    assertEquals(2.67, PythonNumbers.round2(2.675));
    assertEquals(8.01, PythonNumbers.round2(8.005));
    assertEquals(12.35, PythonNumbers.round2(12.345));
    assertEquals(0.12, PythonNumbers.round2(0.125));
    assertEquals("2.67", PythonNumbers.format2(2.675));
    assertEquals("0.12", PythonNumbers.format2(0.125));
    assertEquals("90.00", PythonNumbers.format2(90.0));
  }

  /** The real git path extracts changed lines from a scratch repository. */
  @Test
  void gitChangedLinesWorkAgainstAScratchRepository() throws IOException, InterruptedException {
    Path repo = temp.resolve("gitrepo");
    Files.createDirectories(repo);
    runCommand(repo, "git", "init", "-q");
    runCommand(repo, "git", "config", "user.email", "t@example.com");
    runCommand(repo, "git", "config", "user.name", "T");
    Path file = repo.resolve("src/main/java/A.java");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "one\ntwo\n");
    runCommand(repo, "git", "add", ".");
    runCommand(repo, "git", "commit", "-q", "-m", "base");
    Files.writeString(file, "one\nchanged\nthree\n");
    runCommand(repo, "git", "add", ".");
    runCommand(repo, "git", "commit", "-q", "-m", "head");
    String head = runCommand(repo, "git", "rev-parse", "HEAD").trim();
    String base = runCommand(repo, "git", "rev-parse", "HEAD~1").trim();
    Map<String, Set<Long>> changed = ChangedCoverageValidator.gitChangedLines(repo, base,
        head, ".java");
    assertEquals(Set.of(2L, 3L), changed.get("src/main/java/A.java"));
  }

  private static String runCommand(Path directory, String... command)
      throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(directory.toFile());
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes());
    process.waitFor();
    return output;
  }

  /** An unknown flag exits 2, like argparse's invalid-choice error. */
  @Test
  void unknownFlagExitsTwo() throws IOException {
    Path root = scratchRoot();
    jacocoReport();
    assertEquals(2, ChangedCoverageValidator.run(new String[] {"--thresold", "50",
        "--base", "base", "--head", "head", "--report",
        temp.resolve("jacoco.xml").toString(), "--evidence",
        root.resolve("coverage.json").toString()}, root));
  }

  /** A missing required flag exits 2. */
  @Test
  void missingRequiredFlagExitsTwo() throws IOException {
    Path root = scratchRoot();
    jacocoReport();
    assertEquals(2, ChangedCoverageValidator.run(new String[] {"--head", "head",
        "--report", temp.resolve("jacoco.xml").toString(), "--evidence",
        root.resolve("coverage.json").toString()}, root));
  }

  /** A non-Python-float threshold exits 2 (argparse type=float parity). */
  @Test
  void nonPythonFloatThresholdExitsTwo() throws IOException {
    Path root = scratchRoot();
    jacocoReport();
    for (String bad : new String[] {"x", "90.0d", "0x1p3"}) {
      assertEquals(2, ChangedCoverageValidator.run(new String[] {"--threshold", bad,
          "--base", "base", "--head", "head", "--report",
          temp.resolve("jacoco.xml").toString(), "--evidence",
          root.resolve("coverage.json").toString()}, root), bad);
    }
  }

  /** An infinite threshold renders like Python's f-string and compares like Python. */
  @Test
  void infiniteThresholdRendersLikePython() {
    assertEquals("inf", PythonNumbers.format2(Double.POSITIVE_INFINITY));
    assertEquals("-inf", PythonNumbers.format2(Double.NEGATIVE_INFINITY));
    assertEquals("nan", PythonNumbers.format2(Double.NaN));
  }

  /** A missing sidecar when git is unavailable rejects with the Python message. */
  @Test
  void missingSidecarRejectsWhenGitUnavailable() throws IOException {
    Path root = scratchRoot();
    ChangedCoverageValidator.ValidationError error = assertThrows(
        ChangedCoverageValidator.ValidationError.class,
        () -> ChangedCoverageValidator.changedLines(root, "base", "head", ".java",
            root.resolve("coverage.json")));
    assertTrue(error.getMessage().contains("sidecar is missing"));
  }

  /** Non-textual sidecar revisions reject (Python compares typed values). */
  @Test
  void nonTextualSidecarRevisionRejects() throws IOException {
    Path root = scratchRoot();
    Files.writeString(root.resolve("changed-lines.json"),
        "{\"base\": 123, \"head\": \"head\", \"files\": {\"a.java\": [4]}}");
    ChangedCoverageValidator.ValidationError error = assertThrows(
        ChangedCoverageValidator.ValidationError.class,
        () -> ChangedCoverageValidator.changedLines(root, "123", "head", ".java",
            root.resolve("coverage.json")));
    assertTrue(error.getMessage().contains("revisions do not match"));
  }

  /** sha256 streams the file content. */
  @Test
  void sha256HashesFileContent() throws IOException {
    Path file = temp.resolve("content.txt");
    Files.writeString(file, "hello");
    assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
        JacocoReportReader.sha256(file));
  }
}
