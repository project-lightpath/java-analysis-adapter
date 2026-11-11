package org.projectlightpath.javalint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

class JavaLintValidatorTest {
  @TempDir Path temp;

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

  @Test
  void duplicateFingerprintsUseMultisetComparison() {
    JavaLintValidator.Comparison comparison = JavaLintValidator.compareFingerprints(
        List.of("same", "same"), List.of("same"));

    assertEquals(List.of("same"), comparison.newFingerprints());
    assertEquals(List.of(), comparison.resolvedFingerprints());
  }

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

  @Test
  void processingErrorsAreReported() {
    Document report = JavaLintValidator.parseXml(
        "<pmd><error filename=\"Broken.java\">parse failed</error></pmd>");

    assertEquals(
        List.of("PMD report processing error: parse failed (filename='Broken.java')"),
        JavaLintValidator.reportProcessingErrors(report, "PMD"));
  }

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
}
