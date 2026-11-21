package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.ZipOutputStream;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Item T: dependency inputs dropped by resolution must be visible. */
class DependencyResolutionDiagnosticsTest {
  @TempDir Path temp;

  @Test
  void missingWildcardDirectoryIsDiagnosed() throws IOException {
    AnalysisContext context = context("missing-wildcard",
        List.of(temp.resolve("absent-deps/*")));

    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(context);

    assertTrue(scan.diagnostics().stream()
        .anyMatch(item -> item.code().equals("dependency_wildcard_directory_unavailable")),
        scan.diagnostics().toString());
  }

  @Test
  void nonJarArchiveInWildcardDirectoryIsDiagnosed() throws IOException {
    Path deps = Files.createDirectories(temp.resolve("deps"));
    jar(deps.resolve("provider.jar"));
    Files.writeString(deps.resolve("provider.war"), "archive placeholder");
    AnalysisContext context = context("non-jar-archive", List.of(deps.resolve("*")));

    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(context);

    assertTrue(scan.diagnostics().stream()
        .anyMatch(item -> item.code().equals("dependency_wildcard_archive_skipped")
            && item.path().endsWith("provider.war")),
        scan.diagnostics().toString());
    assertEquals(List.of(deps.resolve("provider.jar").toAbsolutePath().normalize()),
        SootUpInputLocationFactory.resolvedDependencyEntries(context));
  }

  @Test
  void explicitNonArchiveDependencyIsDiagnosed() throws IOException {
    Path notes = temp.resolve("notes.txt");
    Files.writeString(notes, "not an archive");
    AnalysisContext context = context("non-archive", List.of(notes));

    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(context);

    assertTrue(scan.diagnostics().stream()
        .anyMatch(item -> item.code().equals("dependency_classpath_entry_skipped")
            && item.path().endsWith("notes.txt")),
        scan.diagnostics().toString());
  }

  @Test
  void symlinkedWildcardJarIsDiagnosed() throws IOException {
    Path deps = Files.createDirectories(temp.resolve("linked-deps"));
    Path real = deps.resolve("real.jar");
    jar(real);
    Files.createSymbolicLink(deps.resolve("linked.jar"), real);
    AnalysisContext context = context("linked-jar", List.of(deps.resolve("*")));

    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(context);

    assertTrue(scan.diagnostics().stream()
        .anyMatch(item -> item.code().equals("dependency_classpath_entry_skipped")
            && item.path().endsWith("linked.jar")),
        scan.diagnostics().toString());
  }

  @Test
  void nestedWildcardChainIsNotExpanded() throws IOException {
    Path deps = Files.createDirectories(temp.resolve("nested-star"));
    jar(deps.resolve("top.jar"));
    Files.createDirectories(deps.resolve("nested"));
    jar(deps.resolve("nested/deep.jar"));
    AnalysisContext context = context("nested-star-cp", List.of(deps.resolve("*/*")));

    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(context);

    assertTrue(scan.diagnostics().stream()
        .anyMatch(item -> item.code().equals("dependency_wildcard_directory_unavailable")),
        scan.diagnostics().toString());
    assertTrue(SootUpInputLocationFactory.resolvedDependencyEntries(context).isEmpty(),
        SootUpInputLocationFactory.resolvedDependencyEntries(context).toString());
  }

  @Test
  void wildcardInAncestorComponentStillExpands() throws IOException {
    Path deps = Files.createDirectories(temp.resolve("we*ird/deps"));
    Path only = deps.resolve("only.jar");
    jar(only);
    AnalysisContext context = context("ancestor-star", List.of(deps.resolve("*")));

    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(context);

    assertTrue(scan.diagnostics().isEmpty(), scan.diagnostics().toString());
    assertEquals(List.of(only.toAbsolutePath().normalize()),
        SootUpInputLocationFactory.resolvedDependencyEntries(context));
  }

  @Test
  void partialStarElementIsNotAPrefixExpansion() throws IOException {
    Path deps = Files.createDirectories(temp.resolve("prefix-star"));
    jar(deps.resolve("a.jar"));
    jar(deps.resolve("b.jar"));
    AnalysisContext context = context("prefix-star-cp", List.of(deps.resolve("foo*")));

    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(context);

    assertTrue(scan.diagnostics().stream()
        .anyMatch(item -> item.code().equals("dependency_classpath_entry_skipped")
            && item.path().endsWith("foo*")),
        scan.diagnostics().toString());
    assertTrue(SootUpInputLocationFactory.resolvedDependencyEntries(context).isEmpty(),
        SootUpInputLocationFactory.resolvedDependencyEntries(context).toString());
  }

  @Test
  void ordinaryClasspathStaysDiagnosticFree() throws IOException {
    Path deps = Files.createDirectories(temp.resolve("clean-deps"));
    jar(deps.resolve("one.jar"));
    jar(deps.resolve("two.jar"));
    Files.writeString(deps.resolve("README.txt"), "ignored");
    Files.createDirectories(deps.resolve("classes.dir"));
    AnalysisContext context = context("clean-classpath", List.of(deps.resolve("*")));

    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(context);

    assertTrue(scan.diagnostics().isEmpty(), scan.diagnostics().toString());
  }

  private static void jar(Path path) throws IOException {
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
      // Empty archive: a valid input location with no entries.
    }
  }

  private AnalysisContext context(String id, List<Path> dependencies) throws IOException {
    Path root = temp.resolve(id);
    Path sourceRoot = Files.createDirectories(root.resolve("src/main/java"));
    Files.createDirectories(root.resolve("target/classes"));
    return AnalysisContext.builder()
        .id(id)
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId(id)
        .javaRelease(17)
        .repositoryRoot(root)
        .applicationClasses(root.resolve("target/classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspath(dependencies)
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
  }
}
