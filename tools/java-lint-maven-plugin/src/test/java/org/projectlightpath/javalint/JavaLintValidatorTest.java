package org.projectlightpath.javalint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

/** Regression tests for baseline normalization and effective-POM safeguards. */
class JavaLintValidatorTest {
  @TempDir Path temp;

  /** Line movement must not create a new PMD fingerprint. */
  @Test
  void pmdFingerprintIgnoresLineShifts() {
    Document first = JavaLintValidator.parseXml(
        "<pmd><file name=\"src/main/java/example/Parser.java\">"
            + "<violation beginline=\"10\" rule=\"NPathComplexity\" class=\"Parser\" "
            + "method=\"read\">The method is at line 10</violation></file></pmd>");
    Document shifted = JavaLintValidator.parseXml(
        "<pmd><file name=\"src/main/java/example/Parser.java\">"
            + "<violation beginline=\"110\" rule=\"NPathComplexity\" class=\"Parser\" "
            + "method=\"read\">The method is at line 110</violation></file></pmd>");

    assertEquals(
        JavaLintValidator.pmdFingerprint(
            (org.w3c.dom.Element) first.getElementsByTagName("file").item(0),
            (org.w3c.dom.Element) first.getElementsByTagName("violation").item(0)),
        JavaLintValidator.pmdFingerprint(
            (org.w3c.dom.Element) shifted.getElementsByTagName("file").item(0),
            (org.w3c.dom.Element) shifted.getElementsByTagName("violation").item(0)));
  }

  /** Line movement must not create a new CPD fingerprint. */
  @Test
  void cpdFingerprintIgnoresLocationShifts() {
    Document first = JavaLintValidator.parseXml(
        "<pmd-cpd><duplication tokens=\"120\"><file path=\"src/Parser.java\" line=\"10\"/>"
            + "<file path=\"src/Parser.java\" line=\"40\"/>"
            + "<codefragment>if (value) { return value; }</codefragment></duplication></pmd-cpd>");
    Document shifted = JavaLintValidator.parseXml(
        "<pmd-cpd><duplication tokens=\"120\"><file path=\"src/Parser.java\" line=\"110\"/>"
            + "<file path=\"src/Parser.java\" line=\"140\"/>"
            + "<codefragment>if (value) { return value; }</codefragment></duplication></pmd-cpd>");

    assertEquals(
        JavaLintValidator.cpdFingerprint(
            (org.w3c.dom.Element) first.getElementsByTagName("duplication").item(0)),
        JavaLintValidator.cpdFingerprint(
            (org.w3c.dom.Element) shifted.getElementsByTagName("duplication").item(0)));
  }

  /** Duplicate findings are compared with multiplicity rather than sets. */
  @Test
  void duplicateFingerprintsUseMultisetComparison() {
    JavaLintValidator.Comparison comparison = JavaLintValidator.compareFingerprints(
        List.of("same", "same"), List.of("same"));

    assertEquals(List.of("same"), comparison.newFingerprints());
    assertEquals(List.of(), comparison.resolvedFingerprints());
  }

  /** The validator reads the CPD threshold from Maven's effective configuration. */
  @Test
  void effectiveCpdThresholdIsReadFromMavenConfiguration() throws Exception {
    Path effectivePom = temp.resolve("effective-pom.xml");
    Files.writeString(effectivePom, """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <build><plugins><plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-pmd-plugin</artifactId>
            <configuration><minimumTokens>200</minimumTokens></configuration>
          </plugin></plugins></build>
        </project>
        """);

    assertEquals(200, JavaLintValidator.effectiveCpdMinimumTokens(effectivePom));
  }

  /** Processing errors embedded in a report are surfaced as gate failures. */
  @Test
  void processingErrorsAreReported() {
    Document report = JavaLintValidator.parseXml(
        "<pmd><error filename=\"Broken.java\">parse failed</error></pmd>");

    assertEquals(
        List.of("PMD report processing error: parse failed (filename='Broken.java')"),
        JavaLintValidator.reportProcessingErrors(report, "PMD"));
  }

  /** PMD configuration errors embedded in a report are surfaced as gate failures. */
  @Test
  void configurationErrorsAreReported() {
    Document report = JavaLintValidator.parseXml(
        "<pmd><configerror rule=\"MissingRule\">rule could not load</configerror></pmd>");

    assertEquals(
        List.of("PMD report configuration error: rule could not load (rule='MissingRule')"),
        JavaLintValidator.reportProcessingErrors(report, "PMD"));
  }

  /** Both annotation and source-marker suppressions enter the review inventory. */
  @Test
  void suppressionInventoryRecognizesAnnotationsAndNopmd() {
    assertTrue(JavaLintValidator.containsSuppressionMarker("// NOPMD: parser boundary"));
    assertTrue(JavaLintValidator.containsSuppressionMarker("@SuppressWarnings(\"unused\")"));
    assertFalse(JavaLintValidator.containsSuppressionMarker("// no suppression"));
  }

  /** Effective ruleset, priority, test, source, and CPD settings are normalized. */
  @Test
  void effectivePmdConfigurationReadsReviewedRuleAndSourceScope() throws Exception {
    Path effectivePom = temp.resolve("effective-pom.xml");
    String ruleset = temp.resolve("config/pmd-production.xml").toString();
    String source = temp.resolve("src/main/java").toString();
    Files.writeString(effectivePom, """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <build>
            <sourceDirectory>%s</sourceDirectory>
            <plugins><plugin>
              <artifactId>maven-pmd-plugin</artifactId>
              <configuration>
                <rulesets><ruleset>%s</ruleset></rulesets>
                <includeTests>false</includeTests>
                <minimumPriority>3</minimumPriority>
                <minimumTokens>100</minimumTokens>
              </configuration>
            </plugin></plugins>
          </build>
        </project>
        """.formatted(source, ruleset));

    JavaLintValidator.EffectivePmdConfiguration configuration =
        JavaLintValidator.effectivePmdConfiguration(effectivePom, temp);

    assertEquals(List.of("config/pmd-production.xml"), configuration.rulesets());
    assertEquals(3, configuration.minimumPriority());
    assertFalse(configuration.includeTests());
    assertEquals(List.of("src/main/java"), configuration.sourceScope());
    assertEquals(100, configuration.minimumTokens());
  }

  /** Maven defaults and build source directories remain visible when paths are not configured. */
  @Test
  void effectivePmdConfigurationUsesDefaultBuildSourceScopes() throws Exception {
    Path effectivePom = temp.resolve("effective-pom.xml");
    Files.writeString(effectivePom, """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <build>
            <sourceDirectory>src/main/java</sourceDirectory>
            <testSourceDirectory>src/test/java</testSourceDirectory>
            <plugins><plugin>
              <artifactId>maven-pmd-plugin</artifactId>
              <configuration><includeTests>true</includeTests></configuration>
            </plugin></plugins>
          </build>
        </project>
        """);

    JavaLintValidator.EffectivePmdConfiguration configuration =
        JavaLintValidator.effectivePmdConfiguration(effectivePom, temp);

    assertEquals(List.of(), configuration.rulesets());
    assertEquals(5, configuration.minimumPriority());
    assertTrue(configuration.includeTests());
    assertEquals(List.of("src/main/java", "src/test/java"), configuration.sourceScope());
    assertEquals(100, configuration.minimumTokens());
  }

  /** Invalid effective Maven settings fail closed rather than silently changing the policy. */
  @Test
  void effectivePmdConfigurationRejectsInvalidSettings() throws Exception {
    Path effectivePom = temp.resolve("effective-pom.xml");
    Files.writeString(effectivePom, effectivePomWithConfiguration(
        "<minimumPriority>0</minimumPriority>"));
    IllegalArgumentException nonPositive = assertThrows(
        IllegalArgumentException.class,
        () -> JavaLintValidator.effectivePmdConfiguration(effectivePom, temp));
    assertTrue(nonPositive.getMessage().contains("must be positive"));

    Files.writeString(effectivePom, effectivePomWithConfiguration(
        "<minimumPriority>not-a-number</minimumPriority>"));
    IllegalArgumentException nonInteger = assertThrows(
        IllegalArgumentException.class,
        () -> JavaLintValidator.effectivePmdConfiguration(effectivePom, temp));
    assertTrue(nonInteger.getMessage().contains("is not an integer"));

    Files.writeString(effectivePom, effectivePomWithConfiguration(
        "<includeTests>sometimes</includeTests>"));
    IllegalArgumentException nonBoolean = assertThrows(
        IllegalArgumentException.class,
        () -> JavaLintValidator.effectivePmdConfiguration(effectivePom, temp));
    assertTrue(nonBoolean.getMessage().contains("is not boolean"));
  }

  /** A configuration error prevents validate from returning a passing report. */
  @Test
  void validateFailsOnConfigurationErrors() throws Exception {
    Path config = temp.resolve("config");
    Path target = temp.resolve("target");
    Files.createDirectories(config);
    Files.createDirectories(target);
    String ruleset = """
        <ruleset>
          CognitiveComplexity CyclomaticComplexity NPathComplexity NcssCount
          ExcessiveParameterList AvoidCatchingGenericException PreserveStackTrace
          UnusedPrivateMethod CommentRequired
        </ruleset>
        """;
    Path rulesetFile = config.resolve("pmd-production.xml");
    Files.writeString(rulesetFile, ruleset);
    Files.writeString(config.resolve("java-lint-baseline.json"), """
        {
          "schemaVersion": 1,
          "pmd": {
            "findings": [],
            "includeTests": false,
            "minimumPriority": 3,
            "pmdVersion": "7.17.0",
            "rulesetSha256": "%s",
            "rulesets": ["config/pmd-production.xml"],
            "sourceScope": ["src/main/java"]
          },
          "cpd": {"findings": [], "minimumTokens": 100, "pmdVersion": "7.17.0"}
        }
        """.formatted(sha256(ruleset)));
    Files.writeString(target.resolve("pmd.xml"),
        "<pmd version=\"7.17.0\"><configerror rule=\"MissingRule\">broken</configerror></pmd>");
    Files.writeString(target.resolve("cpd.xml"), "<pmd-cpd pmdVersion=\"7.17.0\"/>");
    Files.writeString(target.resolve("java-lint-effective-pom.xml"), """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <build>
            <sourceDirectory>%s</sourceDirectory>
            <plugins><plugin>
              <artifactId>maven-pmd-plugin</artifactId>
              <configuration>
                <rulesets><ruleset>%s</ruleset></rulesets>
                <includeTests>false</includeTests>
                <minimumPriority>3</minimumPriority>
                <minimumTokens>100</minimumTokens>
              </configuration>
            </plugin></plugins>
          </build>
        </project>
        """.formatted(temp.resolve("src/main/java"), rulesetFile));

    JavaLintValidator.ValidationReport report = new JavaLintValidator(temp).validate(true);

    assertFalse(report.passed());
    assertEquals(1, report.configurationErrorCount());
    assertTrue(report.messages().stream().anyMatch(message -> message.contains("configuration error")));
  }

  /** An absent findings baseline is treated as an empty inventory. */
  @Test
  void emptyInventoryPassesWithoutBaseline() throws Exception {
    Path config = temp.resolve("config");
    Path target = temp.resolve("target");
    Files.createDirectories(config);
    Files.createDirectories(target);
    String ruleset = """
        <ruleset>
          CognitiveComplexity CyclomaticComplexity NPathComplexity NcssCount
          ExcessiveParameterList AvoidCatchingGenericException PreserveStackTrace
          UnusedPrivateMethod CommentRequired
        </ruleset>
        """;
    Files.writeString(config.resolve("pmd-production.xml"), ruleset);
    Files.writeString(target.resolve("pmd.xml"), "<pmd version=\"7.17.0\"/>");
    Files.writeString(target.resolve("cpd.xml"), "<pmd-cpd pmdVersion=\"7.17.0\"/>");
    Files.writeString(target.resolve("java-lint-effective-pom.xml"), """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <build>
            <sourceDirectory>src/main/java</sourceDirectory>
            <plugins><plugin>
              <artifactId>maven-pmd-plugin</artifactId>
              <configuration>
                <rulesets><ruleset>config/pmd-production.xml</ruleset></rulesets>
                <includeTests>false</includeTests>
                <minimumPriority>3</minimumPriority>
                <minimumTokens>100</minimumTokens>
              </configuration>
            </plugin></plugins>
          </build>
        </project>
        """);

    JavaLintValidator.ValidationReport report = new JavaLintValidator(temp).validate(true);

    assertTrue(report.passed());
    assertEquals(0, report.newFindingCount());
    assertEquals(0, report.configurationErrorCount());
  }

  /** A threshold change is observable even when the baseline has no CPD findings. */
  @Test
  void effectiveThresholdChangeIsDetectedAgainstBaseline() throws Exception {
    Path effectivePom = temp.resolve("effective-pom.xml");
    Files.writeString(effectivePom, """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <build><plugins><plugin>
            <artifactId>maven-pmd-plugin</artifactId>
            <configuration><minimumTokens>200</minimumTokens></configuration>
          </plugin></plugins></build>
        </project>
        """);

    assertTrue(JavaLintValidator.effectiveCpdMinimumTokens(effectivePom) != 100);
  }

  /** Computes the digest format used by the baseline fixture. */
  private static String sha256(String value) throws Exception {
    return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static String effectivePomWithConfiguration(String configuration) {
    return """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <build><plugins><plugin>
            <artifactId>maven-pmd-plugin</artifactId>
            <configuration>%s</configuration>
          </plugin></plugins></build>
        </project>
        """.formatted(configuration);
  }
}
