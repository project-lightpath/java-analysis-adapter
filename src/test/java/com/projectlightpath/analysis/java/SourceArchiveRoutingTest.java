package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Equivalence-class tests for descriptor-scan routing by explicit input
 * mode (issue #42 scope 2-5): the source-archive diagnostic, binary
 * authority in mixed mode, the SOURCE_TREE limitation, default-mode
 * parity, and the wildcard-classpath conflict.
 */
class SourceArchiveRoutingTest {
  @TempDir Path temp;

  private AnalysisContext.Builder baseBuilder() throws IOException {
    Path classes = temp.resolve("classes");
    Files.createDirectories(classes);
    return AnalysisContext.builder()
        .id("source-archive-routing")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("source-archive-routing")
        .javaRelease(17)
        .repositoryRoot(temp)
        .applicationClasses(classes)
        .selectedProfile("production")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .provenance(AnalysisContext.Provenance.forTesting());
  }

  private AnalysisContext.Builder sourceTreeBuilder() throws IOException {
    Path sourceRoot = temp.resolve("src/main/java");
    Files.createDirectories(sourceRoot);
    return baseBuilder().sourceRoot(
        "src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true);
  }

  private Path sourcesArchive(String name) throws IOException {
    Path archive = temp.resolve(name);
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      output.putNextEntry(new ZipEntry("demo/"));
      output.closeEntry();
      output.putNextEntry(new ZipEntry("demo/App.java"));
      output.write("package demo; class App {}\n"
          .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      output.closeEntry();
    }
    return archive;
  }

  private Path binaryArchive(String name, String serviceType, String provider)
      throws IOException {
    Path archive = temp.resolve(name);
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      output.putNextEntry(new ZipEntry("META-INF/services/" + serviceType));
      output.write((provider + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      output.closeEntry();
    }
    return archive;
  }

  // --- SOURCE_ARCHIVE routing ---------------------------------------------

  @Test
  void sourceArchiveWithoutBinariesEmitsTheScanGlobalDiagnostic() throws IOException {
    Path archive = sourcesArchive("app-sources.jar");
    AnalysisContext context = baseBuilder()
        .inputMode(AnalysisContext.InputMode.SOURCE_ARCHIVE)
        .dependencyClasspathRequired(false)
        .sourceArchive(archive)
        .build();

    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(context);

    assertTrue(scan.descriptors().isEmpty());
    assertEquals(1, scan.diagnostics().size());
    assertEquals("descriptor_metadata_unavailable_for_source_archive",
        scan.diagnostics().get(0).code());
    assertEquals(archive.toAbsolutePath().normalize().toString(),
        scan.diagnostics().get(0).path());
  }

  @Test
  void sourceArchiveWithBinariesKeepsBinaryDescriptorsAuthoritative() throws IOException {
    Path archive = sourcesArchive("mixed-sources.jar");
    Path binary = binaryArchive(
        "mixed-application-1.0.0.jar", "demo.Service", "demo.ServiceProvider");
    AnalysisContext context = baseBuilder()
        .inputMode(AnalysisContext.InputMode.SOURCE_ARCHIVE)
        .dependencyClasspathRequired(false)
        .sourceArchive(archive)
        .addDependencyClasspath(binary)
        .build();

    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(context);

    assertTrue(scan.diagnostics().isEmpty());
    assertEquals(1, scan.descriptors().size());
    assertEquals("demo.Service", scan.descriptors().get(0).serviceType());
    assertEquals("meta_inf_services", scan.descriptors().get(0).kind());
    assertTrue(scan.descriptors().get(0).classpathBacked());
  }

  @Test
  void wildcardExpansionContainingASourceArchiveIsDiagnosed() throws IOException {
    Path archive = sourcesArchive("conflicted-sources.jar");
    Path libraryDirectory = Files.createDirectories(temp.resolve("lib"));
    Files.move(archive, libraryDirectory.resolve("conflicted-sources.jar"));
    AnalysisContext context = baseBuilder()
        .inputMode(AnalysisContext.InputMode.SOURCE_ARCHIVE)
        .dependencyClasspathRequired(false)
        .sourceArchive(libraryDirectory.resolve("conflicted-sources.jar"))
        .addDependencyClasspath(libraryDirectory.resolve("*"))
        .build();

    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(context);

    assertTrue(scan.descriptors().isEmpty());
    assertTrue(scan.diagnostics().stream()
        .anyMatch(item -> item.code().equals("source_archive_on_dependency_classpath")));
  }

  // --- SOURCE_TREE limitation ----------------------------------------------

  @Test
  void explicitSourceTreeWithoutBinariesRecordsTheLimitation() throws IOException {
    Path resources = temp.resolve("src/main/resources/META-INF/services");
    Files.createDirectories(resources);
    Files.writeString(resources.resolve("demo.CheckedInService"), "demo.CheckedInProvider\n");
    Path sourceRoot = temp.resolve("src/main/java");
    Files.createDirectories(sourceRoot);
    AnalysisContext context = baseBuilder()
        .inputMode(AnalysisContext.InputMode.SOURCE_TREE)
        .dependencyClasspathRequired(false)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .build();

    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(context);

    assertTrue(scan.diagnostics().isEmpty());
    assertEquals(1, scan.descriptors().size());
    assertEquals("source_meta_inf_services", scan.descriptors().get(0).kind());
    assertEquals(1, scan.limitations().size());
    assertTrue(scan.limitations().get(0).contains("dependency binaries"));
  }

  @Test
  void defaultModeWithoutBinariesStaysSilentForParity() throws IOException {
    Path sourceRoot = temp.resolve("src/main/java");
    Files.createDirectories(sourceRoot);
    AnalysisContext context = sourceTreeBuilder()
        .dependencyClasspathRequired(false)
        .build();

    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(context);

    assertTrue(scan.diagnostics().isEmpty());
    assertTrue(scan.limitations().isEmpty());
  }

  // --- BINARY_ARCHIVE parity -----------------------------------------------

  @Test
  void binaryArchiveModeMatchesTheLegacyScan() throws IOException {
    Path binary = binaryArchive("legacy-application-1.0.0.jar",
        "demo.BinaryService", "demo.BinaryProvider");
    AnalysisContext legacy = sourceTreeBuilder()
        .addDependencyClasspath(binary)
        .build();
    AnalysisContext binaryMode = baseBuilder()
        .inputMode(AnalysisContext.InputMode.BINARY_ARCHIVE)
        .dependencyClasspathRequired(false)
        .addDependencyClasspath(binary)
        .build();

    ServiceLoaderDescriptorScanner.Scan legacyScan =
        ServiceLoaderDescriptorScanner.scan(legacy);
    ServiceLoaderDescriptorScanner.Scan binaryScan =
        ServiceLoaderDescriptorScanner.scan(binaryMode);

    assertEquals(legacyScan.descriptors(), binaryScan.descriptors());
    assertEquals(legacyScan.diagnostics(), binaryScan.diagnostics());
    assertEquals(1, binaryScan.descriptors().size());
    assertEquals("demo.BinaryService", binaryScan.descriptors().get(0).serviceType());
  }

  @Test
  void binaryArchiveWithCheckedInServicesKeepsSourceProvenance() throws IOException {
    // Mixed mode: checked-in source-tree descriptors plus binary truth.
    Path resources = temp.resolve("src/main/resources/META-INF/services");
    Files.createDirectories(resources);
    Files.writeString(resources.resolve("demo.MixedService"), "demo.MixedProvider\n");
    Path binary = binaryArchive(
        "mixed-application-1.0.0.jar", "demo.MixedService", "demo.BinaryMixedProvider");
    AnalysisContext context = sourceTreeBuilder()
        .inputMode(AnalysisContext.InputMode.SOURCE_TREE)
        .addDependencyClasspath(binary)
        .build();

    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(context);

    assertTrue(scan.diagnostics().isEmpty());
    assertTrue(scan.limitations().isEmpty());
    assertEquals(2, scan.descriptors().size());
    assertTrue(scan.descriptors().stream()
        .anyMatch(item -> item.kind().equals("meta_inf_services")
            && item.providers().contains("demo.BinaryMixedProvider")));
    assertTrue(scan.descriptors().stream()
        .anyMatch(item -> item.kind().equals("source_meta_inf_services")
            && item.providers().contains("demo.MixedProvider")));
  }
}
