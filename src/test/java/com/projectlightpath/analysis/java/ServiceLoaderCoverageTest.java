package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceLoaderCoverageTest {
  private static final int ENUMERATION_LIMIT = 1024;

  @TempDir Path temp;

  @Test
  void parserRejectsMalformedCallsAndHandlesNestedArguments() {
    assertTrue(ServiceLoaderCallSiteParser.parse(null).isEmpty());
    assertTrue(ServiceLoaderCallSiteParser.parse("ServiceLoader.load(").isEmpty());
    assertTrue(ServiceLoaderCallSiteParser.parse("ServiceLoader.load()").isEmpty());
    assertTrue(ServiceLoaderCallSiteParser.parse("ServiceLoader.load(provider)").isEmpty());

    List<ServiceLoaderCallSite> sites = ServiceLoaderCallSiteParser.parse(
        "ServiceLoader.load(demo.Provider.class, loader(foo[0], new Object() { }));");
    assertEquals(1, sites.size());
    assertTrue(sites.get(0).explicitClassLoader());
    assertEquals("demo.Provider", sites.get(0).serviceType());
  }

  @Test
  void parserIgnoresCommentsStringsAndTextBlocks() {
    String source = "// ServiceLoader.load(comment.Provider.class).record();\n"
        + "String literal = \"ServiceLoader.load(string.Provider.class);\";\n"
        + "String textBlock = \"\"\"ServiceLoader.load(text.Provider.class);\"\"\";\n"
        + "/* ServiceLoader.load(block.Provider.class); */\n"
        + "return ServiceLoader.load(demo.Provider.class).findFirst();";

    List<ServiceLoaderCallSite> sites = ServiceLoaderCallSiteParser.parse(source);

    assertEquals(1, sites.size());
    assertEquals("demo.Provider", sites.get(0).serviceType());
    assertEquals(source.lastIndexOf("ServiceLoader.load"), sites.get(0).offset());
  }

  @Test
  void sourceOwnershipHandlesInvalidTargetsAndStatementBoundaries() {
    String source = "return ServiceLoader.load(demo.Provider.class)"
        + ".map(demo.Provider::record); }";
    ServiceLoaderCallSite site = ServiceLoaderCallSiteParser.parse(source).get(0);

    assertFalse(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(source, site, null));
    assertFalse(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(source, site, ""));
    assertFalse(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(source, site, "<init>"));
     assertFalse(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(source, site, "record"));
    String unrelatedReference = "return ServiceLoader.load(demo.Provider.class)"
        + ".map(other.Provider::record);";
    ServiceLoaderCallSite unrelatedSite = ServiceLoaderCallSiteParser.parse(unrelatedReference).get(0);
    assertFalse(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
        unrelatedReference, unrelatedSite, "record"));
    assertFalse(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(
        source, new ServiceLoaderCallSite("demo.Provider", "demo.Provider", false, source.length(), 1),
        "record"));
    assertFalse(ServiceLoaderCallSiteSource.sameStatement(source, site, 0));
    assertFalse(ServiceLoaderCallSiteSource.sameStatement(source, site, 99));
    assertFalse(ServiceLoaderCallSiteSource.sameStatement(source, site, site.line() + 1));

    String separate = "ServiceLoader.load(demo.Provider.class).findFirst(); other.record();";
    ServiceLoaderCallSite separateSite = ServiceLoaderCallSiteParser.parse(separate).get(0);
    assertFalse(ServiceLoaderCallSiteSource.sameStatement(separate, separateSite, 1));
    assertTrue(ServiceLoaderCallSiteSource.sameStatement(
        "ServiceLoader.load(demo.Provider.class).findFirst(); }", separateSite, 1));
  }

  @Test
  void sourceOwnershipIgnoresCommentsStringsAndTextBlocks() {
    String source = "return ServiceLoader.load(demo.Provider.class)"
        + ".map(provider -> { /* provider.record(); */"
        + " String text = \"provider.record()\";"
        + " String block = \"\"\"provider.record()\"\"\"; return provider; });";
    ServiceLoaderCallSite site = ServiceLoaderCallSiteParser.parse(source).get(0);

    assertFalse(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(source, site, "record"));
  }

  @Test
  void descriptorSourcesHandleMalformedEntriesAndBudgetGuards() throws IOException {
    Path root = temp.resolve("classes");
    Path services = root.resolve(ServiceLoaderDescriptorSource.SERVICES_DIRECTORY);
    Files.createDirectories(services);
    Files.writeString(services.resolve("demo.Provider"),
        "# comment\ndemo.DefaultProvider\nbad-name\ndemo.DefaultProvider\n");
    Files.writeString(services.resolve("bad-name"), "ignored");

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState();
    Set<String> visited = new java.util.HashSet<>();
    ServiceLoaderDescriptorSource.scanClasspathEntry(root, descriptors, visited, state);
    assertEquals(1, descriptors.size());
    assertEquals(List.of("demo.DefaultProvider"), descriptors.get(0).providers());
    ServiceLoaderDescriptorSource.scanClasspathEntry(root, descriptors, visited, state);
    assertEquals(1, descriptors.size());

    ServiceLoaderDescriptorSource.ScanState limited = new ServiceLoaderDescriptorSource.ScanState(1);
    assertFalse(limited.consumeDecompressed(-1, "memory"));
    assertFalse(limited.consumeDecompressed(2, "memory"));
    assertThrows(IllegalArgumentException.class,
        () -> new ServiceLoaderDescriptorSource.ScanState(0));
    assertTrue(ServiceLoaderDescriptorSource.message(new Exception()).contains("Exception"));
    assertTrue(ServiceLoaderDescriptorSource.message(new Exception(" ")).contains("Exception"));
    assertEquals("detail", ServiceLoaderDescriptorSource.message(new Exception("detail")));

    List<ServiceLoaderDescriptorScanner.Descriptor> modules = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState moduleState = new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorSource.addModuleBytes(new byte[] {1, 2, 3}, "bad-module", modules,
        moduleState);
    assertTrue(moduleState.diagnostics().stream()
        .anyMatch(item -> item.code().equals("malformed_module_info")));
  }

  @Test
  void archiveSourcesHandleInvalidEntriesAndExhaustedBudgets() throws IOException {
    Path invalid = temp.resolve("invalid.jar");
    Files.writeString(invalid, "not a zip");
    List<ServiceLoaderDescriptorScanner.Descriptor> invalidDescriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState invalidState = new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorArchiveSource.scan(invalid, invalidDescriptors, invalidState);
    assertTrue(invalidState.diagnostics().stream()
        .anyMatch(item -> item.code().equals("invalid_archive")));

    Path archive = temp.resolve("entries.jar");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      put(output, "META-INF/services/not-valid", "ignored");
      put(output, "META-INF/services/a.Provider", "x");
      put(output, "META-INF/services/b.Provider", "y");
    }
    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState(1);
    ServiceLoaderDescriptorArchiveSource.scan(archive, descriptors, state);
    assertTrue(state.diagnostics().stream()
        .anyMatch(item -> item.code().equals("archive_decompression_limit")));

    Path exact = temp.resolve("exact.jar");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(exact))) {
      put(output, "META-INF/services/a.Provider", "x");
    }
    List<ServiceLoaderDescriptorScanner.Descriptor> exactDescriptors = new ArrayList<>();
    ServiceLoaderDescriptorArchiveSource.scan(
        exact, exactDescriptors, new ServiceLoaderDescriptorSource.ScanState(1));
    assertEquals(1, exactDescriptors.size());
  }

  @Test
  void readerUsesObservedFilesInsideAndOutsideRepository() throws IOException {
    Path sourceRoot = temp.resolve("src/main/java");
    Files.createDirectories(sourceRoot);
    Path applicationClasses = temp.resolve("classes");
    Path applicationServices = applicationClasses.resolve(ServiceLoaderDescriptorSource.SERVICES_DIRECTORY);
    Files.createDirectories(applicationServices);
    Files.writeString(applicationServices.resolve("demo.Output"), "demo.OutputProvider\n");
    Path declaredDirectory = temp.resolve("declared-services");
    Files.createDirectories(declaredDirectory);
    Path inside = declaredDirectory.resolve("demo.Inside");
    Files.writeString(inside, "demo.InsideProvider\n");
    Path oversized = declaredDirectory.resolve("large.Provider");
    Files.write(oversized, new byte[(int) ServiceLoaderDescriptorSource.MAX_ARCHIVE_DECOMPRESSED_BYTES + 1]);
    Path outside = Files.createTempFile("service-loader-outside", ".Provider");
    Files.writeString(outside, "demo.OutsideProvider\n");

    AnalysisContext context = AnalysisContext.builder()
        .id("coverage")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("coverage")
        .javaRelease(17)
        .repositoryRoot(temp)
        .applicationClasses(applicationClasses)
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
            "file", "service_loader_descriptor", AnalysisContext.MetadataStatus.OBSERVED,
            outside.toString()))
        .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
            "directory", "service_loader_descriptor", AnalysisContext.MetadataStatus.OBSERVED,
            declaredDirectory.toString()))
        .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
            "ignored", "service_loader_descriptor", AnalysisContext.MetadataStatus.NOT_CAPTURED,
            outside.toString()))
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();

    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorReader.scan(context);
    assertEquals(2, scan.descriptors().size(), scan.descriptors().toString());
    assertTrue(scan.descriptors().stream()
        .anyMatch(item -> item.serviceType().equals("demo.Output")));
    assertTrue(scan.descriptors().stream().anyMatch(item -> item.path().equals("declared-services/demo.Inside")));
    assertTrue(scan.descriptors().stream().noneMatch(item -> item.path().equals(outside.toString())));
    assertTrue(scan.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_path_outside_context")));
    assertTrue(scan.diagnostics().stream()
        .anyMatch(item -> item.code().equals("archive_decompression_limit")));
  }

  @Test
  void descriptorEnumerationStopsForDirectoriesAndArchives() throws IOException {
    Path classes = temp.resolve("bounded-classes");
    Path services = classes.resolve(ServiceLoaderDescriptorSource.SERVICES_DIRECTORY);
    Files.createDirectories(services);
    for (int index = 0; index <= ENUMERATION_LIMIT; index++) {
      Files.writeString(services.resolve("demo.Provider" + index), "demo.ProviderImpl\n");
    }
    List<ServiceLoaderDescriptorScanner.Descriptor> directoryDescriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState directoryState =
        new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorSource.scanClasspathEntry(
        classes, directoryDescriptors, new java.util.HashSet<>(), directoryState);
    assertTrue(directoryState.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_enumeration_limit")));
    assertTrue(directoryDescriptors.isEmpty(), directoryDescriptors.toString());

    Path archive = temp.resolve("bounded.jar");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      put(output, "META-INF/services/demo.Archive", "demo.ArchiveProvider\n");
      for (int index = 0; index <= ENUMERATION_LIMIT; index++) {
        put(output, "noise/entry-" + index, "ignored");
      }
    }
    List<ServiceLoaderDescriptorScanner.Descriptor> archiveDescriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState archiveState =
        new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorArchiveSource.scan(archive, archiveDescriptors, archiveState);
    assertTrue(archiveState.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_enumeration_limit")));
    assertTrue(archiveDescriptors.isEmpty(), archiveDescriptors.toString());
    assertEquals(ENUMERATION_LIMIT, enumeratedEntries(directoryState));
    assertEquals(ENUMERATION_LIMIT, enumeratedEntries(archiveState));
  }

  @Test
  void readerRejectsSymlinkTraversalAndMalformedDeclaredPaths() throws IOException {
    Path sourceRoot = temp.resolve("src/main/java");
    Files.createDirectories(sourceRoot);
    Path applicationClasses = temp.resolve("classes");
    Files.createDirectories(applicationClasses);
    Path outside = Files.createTempDirectory("service-loader-outside");
    Files.writeString(outside.resolve("demo.Symlink"), "demo.SymlinkProvider\n");
    Path link = temp.resolve("linked-services");
    Files.createSymbolicLink(link, outside);

    AnalysisContext context = AnalysisContext.builder()
        .id("unsafe-paths")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("unsafe-paths")
        .javaRelease(17)
        .repositoryRoot(temp)
        .applicationClasses(applicationClasses)
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
            "symlink", "service_loader_descriptor", AnalysisContext.MetadataStatus.OBSERVED,
            link.resolve("demo.Symlink").toString()))
        .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
            "invalid", "service_loader_descriptor", AnalysisContext.MetadataStatus.OBSERVED,
            "\u0000invalid"))
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();

    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorReader.scan(context);

    assertTrue(scan.descriptors().isEmpty(), scan.descriptors().toString());
    assertTrue(scan.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_path_outside_context")));
    assertTrue(scan.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_path_invalid")));
  }

  @Test
  void wildcardDependencyExpansionIsBoundedAndDiagnosed() throws IOException {
    Path dependencies = temp.resolve("dependencies");
    Files.createDirectories(dependencies);
    for (int index = 0; index <= ENUMERATION_LIMIT; index++) {
      Files.write(dependencies.resolve("dependency-" + index + ".jar"), new byte[0]);
    }
    Path sourceRoot = temp.resolve("wildcard-src");
    Files.createDirectories(sourceRoot);
    Path classes = temp.resolve("wildcard-classes");
    Files.createDirectories(classes);
    AnalysisContext context = AnalysisContext.builder()
        .id("wildcard-dependencies")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("wildcard-dependencies")
        .javaRelease(17)
        .repositoryRoot(temp)
        .applicationClasses(classes)
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("wildcard-src", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspath(List.of(dependencies.resolve("*")))
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();

    List<Path> resolved = SootUpInputLocationFactory.resolvedDependencyEntries(context);
    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorReader.scan(context);

    assertTrue(resolved.isEmpty(), resolved.toString());
    assertTrue(scan.diagnostics().stream()
        .anyMatch(item -> item.code().equals("dependency_enumeration_limit")));
  }

  @Test
  void symlinkedDependencyArchivesFollowTheDescriptorScanPolicy() throws IOException {
    Path dependencies = temp.resolve("symlink-dependencies");
    Files.createDirectories(dependencies);
    Path realArchive = dependencies.resolve("real.jar");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(realArchive))) {
      put(output, "META-INF/services/demo.Provider", "demo.ProviderImpl\n");
    }
    Path linkedArchive = dependencies.resolve("linked.jar");
    Files.createSymbolicLink(linkedArchive, realArchive.getFileName());
    Path sourceRoot = temp.resolve("symlink-src");
    Files.createDirectories(sourceRoot);
    Path classes = temp.resolve("symlink-classes");
    Files.createDirectories(classes);
    AnalysisContext context = AnalysisContext.builder()
        .id("symlink-dependencies")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("symlink-dependencies")
        .javaRelease(17)
        .repositoryRoot(temp)
        .applicationClasses(classes)
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("symlink-src", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspath(List.of(dependencies.resolve("*")))
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();

    List<Path> resolved = SootUpInputLocationFactory.resolvedDependencyEntries(context);
    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorReader.scan(context);

    assertTrue(resolved.contains(realArchive.toAbsolutePath().normalize()));
    assertTrue(resolved.stream().noneMatch(path -> path.equals(linkedArchive.toAbsolutePath().normalize())));
    assertTrue(scan.descriptors().stream()
        .noneMatch(item -> item.path().contains("linked.jar")));
  }

  private static int enumeratedEntries(ServiceLoaderDescriptorSource.ScanState state) {
    try {
      Field field = ServiceLoaderDescriptorSource.ScanState.class
          .getDeclaredField("enumeratedEntries");
      field.setAccessible(true);
      return field.getInt(state);
    } catch (ReflectiveOperationException error) {
      throw new AssertionError("scan budget state is not observable", error);
    }
  }

  private static void put(ZipOutputStream output, String name, String value) throws IOException {
    output.putNextEntry(new ZipEntry(name));
    output.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    output.closeEntry();
  }
}
