package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceLoaderDescriptorSourceTest {
  @TempDir Path temp;

  @Test
  void scanBudgetsBoundArchivesAndDecompressedBytes() {
    ServiceLoaderDescriptorSource.ScanState state =
        new ServiceLoaderDescriptorSource.ScanState();
    for (int index = 0; index < ServiceLoaderDescriptorSource.MAX_ARCHIVES; index++) {
      assertTrue(state.reserveArchive(temp.resolve("archive-" + index + ".jar")));
    }
    assertFalse(state.reserveArchive(temp.resolve("overflow.jar")));
    assertTrue(state.reserveDecompressed(
        ServiceLoaderDescriptorSource.MAX_ARCHIVE_DECOMPRESSED_BYTES, "archive!/entry"));
    assertFalse(state.reserveDecompressed(1, "archive!/overflow"));

    Set<String> codes = state.diagnostics().stream()
        .map(ServiceLoaderDescriptorScanner.Diagnostic::code)
        .collect(java.util.stream.Collectors.toSet());
    assertTrue(codes.contains("archive_limit"));
    assertTrue(codes.contains("archive_decompression_limit"));
  }

  @Test
  void serviceDirectoryEntryLimitProducesIncompleteDiagnostic() throws IOException {
    Path directory = temp.resolve(ServiceLoaderDescriptorSource.SERVICES_DIRECTORY);
    Files.createDirectories(directory);
    for (int index = 0; index <= ServiceLoaderDescriptorSource.MAX_SERVICE_DIRECTORY_ENTRIES; index++) {
      Files.writeString(directory.resolve("demo.Service" + index), "demo.Provider\n");
    }
    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state =
        new ServiceLoaderDescriptorSource.ScanState();

    ServiceLoaderDescriptorSource.scanServiceDirectory(
        directory, "resources", descriptors, new HashSet<>(), state);

    assertTrue(state.diagnostics().stream()
        .anyMatch(item -> item.code().equals("service_directory_entry_limit")));
    assertTrue(descriptors.size() <= ServiceLoaderDescriptorSource.MAX_SERVICE_DIRECTORY_ENTRIES);
  }

  @Test
  void descriptorPathsOutsideInputsAndSymlinksAreRejected() throws IOException {
    Path trusted = temp.resolve("trusted");
    Path external = temp.resolve("external");
    Path externalDescriptor = external.resolve("demo.AuditProvider");
    Path directory = trusted.resolve(ServiceLoaderDescriptorSource.SERVICES_DIRECTORY);
    Files.createDirectories(directory);
    Files.createDirectories(external);
    Files.writeString(externalDescriptor, "secret.Provider\n");

    Path externalDirectory = external.resolve(ServiceLoaderDescriptorSource.SERVICES_DIRECTORY);
    Files.createDirectories(externalDirectory);
    Files.writeString(externalDirectory.resolve("demo.OtherProvider"), "other.Provider\n");
    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state =
        new ServiceLoaderDescriptorSource.ScanState(List.of(trusted));

    ServiceLoaderDescriptorSource.scanServiceDirectory(
        externalDirectory, "external", descriptors, new HashSet<>(), state);
    assertTrue(descriptors.isEmpty());
    assertTrue(state.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_path_outside_inputs")));

    Path symlink = directory.resolve("demo.AuditProvider");
    try {
      Files.createSymbolicLink(symlink, externalDescriptor);
    } catch (UnsupportedOperationException error) {
      Assumptions.assumeTrue(false, "symbolic links are not supported");
    }
    ServiceLoaderDescriptorSource.scanServiceDirectory(
        directory, "trusted", descriptors, new HashSet<>(), state);
    assertTrue(descriptors.isEmpty());
    assertTrue(state.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_symlink_rejected")));
  }

  @Test
  void descriptorReaderRestrictsMetadataAndResourcePaths() throws IOException {
    Path repository = temp.resolve("repository");
    Path sourceRoot = repository.resolve("src/main/java");
    Path resources = repository.resolve("src/main/resources")
        .resolve(ServiceLoaderDescriptorSource.SERVICES_DIRECTORY);
    Path externalServices = temp.resolve("external/META-INF/services");
    Path externalDescriptor = temp.resolve("external/secret-provider");
    Files.createDirectories(sourceRoot);
    Files.createDirectories(repository.resolve("classes"));
    Files.createDirectories(resources);
    Files.createDirectories(externalServices);
    Files.writeString(externalDescriptor, "secret.Provider\n");
    Files.writeString(externalServices.resolve("demo.ExternalProvider"), "external.Provider\n");
    try {
      Files.createSymbolicLink(resources.resolve("demo.InternalProvider"), externalDescriptor);
    } catch (UnsupportedOperationException error) {
      Assumptions.assumeTrue(false, "symbolic links are not supported");
    }

    AnalysisContext context = AnalysisContext.builder()
        .id("quarkus-production")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("java21-quarkus")
        .javaRelease(21)
        .repositoryRoot(repository)
        .applicationClasses(repository.resolve("classes"))
        .selectedProfile("production")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
            "external-service-loader-resources",
            "service_loader_descriptor",
            AnalysisContext.MetadataStatus.OBSERVED,
            externalServices.toString()))
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();

    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorReader.scan(context);
    assertTrue(scan.descriptors().isEmpty());
    assertTrue(scan.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_path_outside_inputs")));
    assertTrue(scan.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_symlink_rejected")));
  }

  @Test
  void validDescriptorPreservesUniqueProvidersAndMalformedEntries() throws IOException {
    Path directory = temp.resolve(ServiceLoaderDescriptorSource.SERVICES_DIRECTORY);
    Path descriptor = directory.resolve("demo.AuditProvider");
    Files.createDirectories(directory);
    Files.writeString(descriptor,
        "\uFEFFdemo.Provider # provider\n"
            + "demo.Provider\n"
            + "not-a-provider!\n");
    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();

    ServiceLoaderDescriptorSource.scanServiceDirectory(
        directory, "resources", descriptors, new HashSet<>());

    assertEquals(1, descriptors.size());
    ServiceLoaderDescriptorScanner.Descriptor result = descriptors.get(0);
    assertEquals(List.of("demo.Provider"), result.providers());
    assertEquals(List.of("not-a-provider!"), result.malformedEntries());
    assertTrue(result.malformed());
    assertTrue(result.fingerprint().matches("[0-9a-f]{64}"));
    assertTrue(result.path().endsWith("META-INF/services/demo.AuditProvider"));
  }

  @Test
  void archiveEntryLimitProducesIncompleteDiagnostic() throws IOException {
    Path archive = temp.resolve("many-entries.jar");
    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive))) {
      for (int index = 0; index <= ServiceLoaderDescriptorSource.MAX_ARCHIVE_ENTRIES; index++) {
        output.putNextEntry(new JarEntry("META-INF/services/demo.Service" + index));
        output.write("demo.Provider\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.closeEntry();
      }
    }
    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state =
        new ServiceLoaderDescriptorSource.ScanState();

    ServiceLoaderDescriptorSource.scanClasspathEntry(
        archive, descriptors, new HashSet<>(), state);

    assertTrue(state.diagnostics().stream()
        .anyMatch(item -> item.code().equals("archive_entry_limit")));
    assertTrue(descriptors.size() <= ServiceLoaderDescriptorSource.MAX_ARCHIVE_ENTRIES);
  }

  @Test
  void malformedModuleAndReadFailuresBecomeDiagnostics() throws IOException {
    Path classes = temp.resolve("classes");
    Files.createDirectories(classes);
    Files.writeString(classes.resolve("module-info.class"), "not a module");
    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state =
        new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorSource.scanClassDirectory(
        classes, "classes", descriptors, new HashSet<>(), state);
    ServiceLoaderDescriptorSource.addServiceFile(
        temp.resolve("missing-service"), "classes", descriptors, state, new HashSet<>());

    assertTrue(state.diagnostics().stream()
        .anyMatch(item -> item.code().equals("malformed_module_info")));
    assertTrue(state.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_read_error")));
  }

  @Test
  void invalidArchiveBecomesDiagnosticInsteadOfThrowing() throws IOException {
    Path archive = temp.resolve("invalid.jar");
    Files.writeString(archive, "not a zip archive");
    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state =
        new ServiceLoaderDescriptorSource.ScanState();

    ServiceLoaderDescriptorSource.scanClasspathEntry(
        archive, descriptors, new HashSet<>(), state);

    assertTrue(state.diagnostics().stream()
        .anyMatch(item -> item.code().equals("invalid_archive")));
    assertTrue(descriptors.isEmpty());
  }

  @Test
  void archiveDirectoryEntriesAreIgnoredWhileServiceFilesAreRead() throws IOException {
    Path archive = temp.resolve("directory-entry.jar");
    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive))) {
      output.putNextEntry(new JarEntry("META-INF/services/"));
      output.closeEntry();
      output.putNextEntry(new JarEntry("META-INF/services/demo.AuditProvider"));
      output.write("demo.Provider\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      output.closeEntry();
    }
    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();

    ServiceLoaderDescriptorSource.scanClasspathEntry(
        archive, descriptors, new HashSet<>());

    assertEquals(1, descriptors.size());
    assertEquals(List.of("demo.Provider"), descriptors.get(0).providers());
  }

  @Test
  void archiveDecompressionBudgetStopsBeforeReadingLaterEntries() throws IOException {
    Path archive = temp.resolve("bounded-work.jar");
    String contents = "demo.Provider\n";
    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive))) {
      for (int index = 0; index < 2; index++) {
        output.putNextEntry(new JarEntry("META-INF/services/demo.Service" + index));
        output.write(contents.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.closeEntry();
      }
    }
    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state =
        new ServiceLoaderDescriptorSource.ScanState(List.of(), contents.length());

    ServiceLoaderDescriptorSource.scanClasspathEntry(
        archive, descriptors, new HashSet<>(), state);

    assertEquals(1, descriptors.size());
    assertTrue(state.diagnostics().stream()
        .anyMatch(item -> item.code().equals("archive_decompression_limit")));
  }
}
