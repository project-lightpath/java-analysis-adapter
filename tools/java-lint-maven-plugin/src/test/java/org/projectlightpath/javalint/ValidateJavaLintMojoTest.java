package org.projectlightpath.javalint;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression tests for the Maven goal's pass, failure, and report-only paths. */
class ValidateJavaLintMojoTest {
  private static final String RULESET = """
      <ruleset>
        CognitiveComplexity CyclomaticComplexity NPathComplexity NcssCount
        ExcessiveParameterList AvoidCatchingGenericException PreserveStackTrace
        UnusedPrivateMethod CommentRequired
      </ruleset>
      """;

  @TempDir Path temp;

  @Test
  void executePassesForAReviewedReport() throws Exception {
    writeProject(temp, "<pmd version=\"7.17.0\"/>", "// NOPMD: fixture inventory\nclass Fixture {}\n");

    assertDoesNotThrow(() -> configure(temp, true).execute());
  }

  @Test
  void executeFailsWhenEnforcedReportContainsANewFinding() throws Exception {
    writeProject(temp, newFindingReport(), "class Fixture {}\n");

    MojoFailureException failure = assertThrows(
        MojoFailureException.class,
        () -> configure(temp, true).execute());

    assertEquals(
        "Java lint gate failed: 1 new finding(s), 0 report/configuration error(s)",
        failure.getMessage());
  }

  @Test
  void executeReportsNewFindingsWithoutFailingInReportOnlyMode() throws Exception {
    writeProject(temp, newFindingReport(), "class Fixture {}\n");

    assertDoesNotThrow(() -> configure(temp, false).execute());
  }

  @Test
  void executeWrapsUnexpectedValidationErrors() throws Exception {
    MojoExecutionException failure = assertThrows(
        MojoExecutionException.class,
        () -> configure(temp, true).execute());

    assertTrue(failure.getMessage().contains("Java lint report validation failed"));
    assertInstanceOf(java.io.IOException.class, failure.getCause());
  }

  private static ValidateJavaLintMojo configure(Path root, boolean enforce) throws Exception {
    ValidateJavaLintMojo mojo = new ValidateJavaLintMojo();
    setField(mojo, "baseDirectory", root.toFile());
    setField(mojo, "enforce", enforce);
    return mojo;
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static void writeProject(Path root, String pmdReport, String source) throws Exception {
    Files.createDirectories(root.resolve("config"));
    Files.createDirectories(root.resolve("target"));
    Files.createDirectories(root.resolve("src/main/java"));
    Files.writeString(root.resolve("config/pmd-production.xml"), RULESET);
    Files.writeString(root.resolve("config/java-lint-baseline.json"), baseline());
    Files.writeString(root.resolve("target/pmd.xml"), pmdReport);
    Files.writeString(root.resolve("target/cpd.xml"), "<pmd-cpd pmdVersion=\"7.17.0\"/>");
    Files.writeString(root.resolve("target/java-lint-effective-pom.xml"), effectivePom());
    Files.writeString(root.resolve("src/main/java/Fixture.java"), source);
  }

  private static String baseline() throws Exception {
    return """
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
        """.formatted(sha256(RULESET));
  }

  private static String effectivePom() {
    return """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <build>
            <sourceDirectory>src/main/java</sourceDirectory>
            <plugins><plugin>
              <groupId>org.apache.maven.plugins</groupId>
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
        """;
  }

  private static String newFindingReport() {
    return """
        <pmd version="7.17.0">
          <file name="src/main/java/Fixture.java">
            <violation beginline="1" rule="CyclomaticComplexity" class="Fixture"
                method="run">new finding</violation>
          </file>
        </pmd>
        """;
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
