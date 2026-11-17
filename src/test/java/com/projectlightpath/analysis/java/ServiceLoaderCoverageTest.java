package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Assumptions;
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
        "import java.util.ServiceLoader;\n"
            + "ServiceLoader.load(demo.Provider.class, loader(foo[0], new Object() { }));");
    assertEquals(1, sites.size());
    assertTrue(sites.get(0).explicitClassLoader());
    assertEquals("demo.Provider", sites.get(0).serviceType());

    List<ServiceLoaderCallSite> genericSites = ServiceLoaderCallSiteParser.parse(
        "import java.util.ServiceLoader;\n"
            + "ServiceLoader.load(demo.Provider.class, Factory.<String, Integer>loader());");
    assertEquals(1, genericSites.size());
    assertTrue(genericSites.get(0).explicitClassLoader());

    assertTrue(ServiceLoaderCallSiteParser.parse(
        "import java.util.ServiceLoader;\n"
            + "ServiceLoader.load(demo.Provider.class, first, second);").isEmpty());
  }

  @Test
  void parserIgnoresCommentsStringsAndTextBlocks() {
    String source = "import java.util.ServiceLoader;\n"
        + "// ServiceLoader.load(comment.Provider.class).record();\n"
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
    String source = "import java.util.ServiceLoader;\n"
        + "return ServiceLoader.load(demo.Provider.class)"
        + ".map(demo.Provider::record); }";
    ServiceLoaderCallSite site = ServiceLoaderCallSiteParser.parse(source).get(0);

    assertFalse(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(source, site, null));
    assertFalse(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(source, site, ""));
    assertFalse(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(source, site, "<init>"));
     assertFalse(ServiceLoaderCallSiteSource.invokeIsLexicallyBound(source, site, "record"));
    String unrelatedReference = "import java.util.ServiceLoader;\n"
        + "return ServiceLoader.load(demo.Provider.class)"
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

    String separate = "import java.util.ServiceLoader;\n"
        + "ServiceLoader.load(demo.Provider.class).findFirst(); other.record();";
    ServiceLoaderCallSite separateSite = ServiceLoaderCallSiteParser.parse(separate).get(0);
    assertFalse(ServiceLoaderCallSiteSource.sameStatement(separate, separateSite, 1));
    String closing = "import java.util.ServiceLoader;\n"
        + "ServiceLoader.load(demo.Provider.class).findFirst(); }";
    ServiceLoaderCallSite closingSite = ServiceLoaderCallSiteParser.parse(closing).get(0);
    assertTrue(ServiceLoaderCallSiteSource.sameStatement(closing, closingSite, 2));
  }

  @Test
  void sameStatementIgnoresTrailingCommentDelimiters() {
    String source = "import java.util.ServiceLoader;\n"
        + "ServiceLoader.load(demo.Provider.class).findFirst(); // comment has ; and }\n";
    ServiceLoaderCallSite site = ServiceLoaderCallSiteParser.parse(source).get(0);

    assertTrue(ServiceLoaderCallSiteSource.sameStatement(source, site, 2),
        "comment delimiters must not terminate the source statement");
  }

  @Test
  void sourceOwnershipIgnoresCommentsStringsAndTextBlocks() {
    String source = "import java.util.ServiceLoader;\n"
        + "return ServiceLoader.load(demo.Provider.class)"
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
    Files.writeString(services.resolve("demo.ÉService"), "demo.ÉProvider\n");
    Files.writeString(services.resolve("bad-name"), "ignored");

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState();
    Set<String> visited = new java.util.HashSet<>();
    ServiceLoaderDescriptorSource.scanClasspathEntry(root, descriptors, visited, state);
    assertEquals(2, descriptors.size());
    assertEquals(List.of("demo.DefaultProvider"), descriptors.get(0).providers());
    assertTrue(descriptors.stream()
        .anyMatch(item -> item.serviceType().equals("demo.ÉService")
            && item.providers().equals(List.of("demo.ÉProvider"))),
        descriptors.toString());
    ServiceLoaderDescriptorSource.scanClasspathEntry(root, descriptors, visited, state);
    assertEquals(2, descriptors.size());

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
  void explodedDirectoriesIncludeModuleInfoProviders() throws IOException {
    Path moduleSource = temp.resolve("module-src");
    Path moduleClasses = temp.resolve("module-classes");
    Files.createDirectories(moduleClasses);
    Files.createDirectories(moduleSource.resolve("demo"));
    Files.writeString(moduleSource.resolve("module-info.java"),
        "module demo.provider { provides demo.Provider with demo.DefaultProvider; }\n");
    Files.writeString(moduleSource.resolve("demo/Provider.java"),
        "package demo; public interface Provider { void record(); }\n");
    Files.writeString(moduleSource.resolve("demo/DefaultProvider.java"),
        "package demo; public final class DefaultProvider implements Provider {"
            + " public void record() {} }\n");

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    Assumptions.assumeTrue(compiler != null, "a JDK compiler is required for module metadata");
    try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
      List<Path> sources = Files.walk(moduleSource)
          .filter(path -> path.toString().endsWith(".java"))
          .sorted()
          .toList();
      var units = files.getJavaFileObjectsFromFiles(sources.stream().map(Path::toFile).toList());
      assertTrue(compiler.getTask(
          null, files, null, List.of("--release", "17", "-d", moduleClasses.toString()), null,
          units).call());
    }

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorSource.scanClasspathEntry(
        moduleClasses, descriptors, new java.util.HashSet<>(), state);

    assertEquals(1, descriptors.size(), descriptors.toString());
    assertEquals("module_info", descriptors.get(0).kind());
    assertEquals("demo.Provider", descriptors.get(0).serviceType());
    assertEquals(List.of("demo.DefaultProvider"), descriptors.get(0).providers());

    // Directory module scanning is independent of an incomplete services listing.
    Path services = Files.createDirectories(moduleClasses.resolve("META-INF/services"));
    for (int index = 0; index <= ENUMERATION_LIMIT; index++) {
      Files.writeString(services.resolve("noise" + index), "");
    }
    List<ServiceLoaderDescriptorScanner.Descriptor> boundedDirectory = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState directoryState = new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorSource.scanClasspathEntry(
        moduleClasses, boundedDirectory, new java.util.HashSet<>(), directoryState);
    assertEquals(List.of("module_info"), boundedDirectory.stream()
        .map(ServiceLoaderDescriptorScanner.Descriptor::kind).toList());
    assertTrue(directoryState.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_enumeration_limit")));

    // Archive candidates, including module-info, require complete archive enumeration.
    Path archive = temp.resolve("module-overflow.jar");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      output.putNextEntry(new ZipEntry("module-info.class"));
      output.write(Files.readAllBytes(moduleClasses.resolve("module-info.class")));
      output.closeEntry();
      put(output, "META-INF/services/demo.Provider", "demo.DefaultProvider\n");
      for (int index = 0; index <= ENUMERATION_LIMIT; index++) {
        put(output, "noise/entry-" + index, "");
      }
    }
    List<ServiceLoaderDescriptorScanner.Descriptor> boundedArchive = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState archiveState = new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorArchiveSource.scan(archive, boundedArchive, archiveState);
    assertTrue(boundedArchive.isEmpty(), boundedArchive.toString());
    assertTrue(archiveState.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_enumeration_limit")));
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
    Path unicode = declaredDirectory.resolve("demo.ÉInside");
    Files.writeString(unicode, "demo.ÉInsideProvider\n\u2003\n"
        + "demo.ÉInsideProvider # duplicate\nbad/name\n");
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
    assertEquals(3, scan.descriptors().size(), scan.descriptors().toString());
    assertTrue(scan.descriptors().stream()
        .anyMatch(item -> item.serviceType().equals("demo.Output")));
    assertTrue(scan.descriptors().stream().anyMatch(item -> item.path().equals("declared-services/demo.Inside")));
    assertTrue(scan.descriptors().stream()
        .anyMatch(item -> item.serviceType().equals("demo.ÉInside")
            && item.providers().equals(List.of("demo.ÉInsideProvider"))
            && item.malformedEntries().equals(List.of("bad/name"))),
        scan.descriptors().toString());
    assertTrue(scan.descriptors().stream().noneMatch(item -> item.path().equals(outside.toString())));
    assertTrue(scan.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_path_outside_context")));
    assertTrue(scan.diagnostics().stream()
        .anyMatch(item -> item.code().equals("archive_decompression_limit")));
  }

  @Test
  void readerRejectsRepositoryRootAsAnUnselectedMetadataInput() throws IOException {
    Path sourceRoot = temp.resolve("root-authorization/src/main/java");
    Files.createDirectories(sourceRoot);
    Path applicationClasses = temp.resolve("root-authorization/classes");
    Files.createDirectories(applicationClasses);
    Files.writeString(temp.resolve("demo.Unselected"), "demo.UnselectedProvider\n");

    AnalysisContext context = AnalysisContext.builder()
        .id("root-authorization")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("root-authorization")
        .javaRelease(17)
        .repositoryRoot(temp)
        .applicationClasses(applicationClasses)
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
            "repository-root", "service_loader_descriptor", AnalysisContext.MetadataStatus.OBSERVED,
            temp.toString()))
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();

    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorReader.scan(context);

    assertTrue(scan.descriptors().isEmpty(), scan.descriptors().toString());
    assertTrue(scan.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_path_outside_context")),
        scan.diagnostics().toString());
  }

  @Test
  void descriptorIoFailuresBecomeDiagnosticsInsteadOfAbortingAnalysis() throws IOException {
    Path sourceRoot = temp.resolve("io-failures/src/main/java");
    Files.createDirectories(sourceRoot);
    Path applicationClasses = temp.resolve("io-failures/classes");
    Files.createDirectories(applicationClasses);
    Path unreadableDirectory = temp.resolve("unreadable-services");
    Files.createDirectories(unreadableDirectory);
    Path unreadableFile = temp.resolve("demo.Unreadable");
    Files.writeString(unreadableFile, "demo.UnreadableProvider\n");

    Assumptions.assumeTrue(Files.getFileAttributeView(
        unreadableDirectory, java.nio.file.attribute.PosixFileAttributeView.class) != null);
    Set<java.nio.file.attribute.PosixFilePermission> directoryPermissions =
        Files.getPosixFilePermissions(unreadableDirectory);
    Set<java.nio.file.attribute.PosixFilePermission> filePermissions =
        Files.getPosixFilePermissions(unreadableFile);
    try {
      Files.setPosixFilePermissions(unreadableDirectory, Set.of());
      Files.setPosixFilePermissions(unreadableFile, Set.of());
      try (var ignored = Files.newDirectoryStream(unreadableDirectory)) {
        Assumptions.assumeTrue(false, "test user can still enumerate unreadable directory");
      } catch (IOException expected) {
        // The fixture must exercise the descriptor directory failure path.
      }
      try (var ignored = Files.newInputStream(unreadableFile)) {
        Assumptions.assumeTrue(false, "test user can still read unreadable descriptor");
      } catch (IOException expected) {
        // The fixture must exercise the descriptor file failure path.
      }

      AnalysisContext context = AnalysisContext.builder()
          .id("io-failures")
          .framework(AnalysisContext.Framework.QUARKUS)
          .frameworkVersion("3.40.1")
          .fixtureId("io-failures")
          .javaRelease(17)
          .repositoryRoot(temp)
          .applicationClasses(applicationClasses)
          .selectedProfile("test")
          .analysisScope(AnalysisContext.Scope.PRODUCTION)
          .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
          .dependencyClasspathRequired(false)
          .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
              "directory", "service_loader_descriptor", AnalysisContext.MetadataStatus.OBSERVED,
              unreadableDirectory.toString()))
          .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
              "file", "service_loader_descriptor", AnalysisContext.MetadataStatus.OBSERVED,
              unreadableFile.toString()))
          .provenance(AnalysisContext.Provenance.forTesting())
          .build();

      ServiceLoaderDescriptorScanner.Scan scan = assertDoesNotThrow(
          () -> ServiceLoaderDescriptorReader.scan(context));

      assertTrue(scan.descriptors().isEmpty(), scan.descriptors().toString());
      assertTrue(scan.diagnostics().stream()
          .anyMatch(item -> item.code().equals("descriptor_directory_read_error")),
          scan.diagnostics().toString());
      assertTrue(scan.diagnostics().stream()
          .anyMatch(item -> item.code().equals("descriptor_read_error")),
          scan.diagnostics().toString());
    } finally {
      Files.setPosixFilePermissions(unreadableDirectory, directoryPermissions);
      Files.setPosixFilePermissions(unreadableFile, filePermissions);
    }
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
  void sharedEnumerationLimitReportsEverySkippedArchive() throws IOException {
    ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState();
    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    List<Path> archives = List.of(temp.resolve("first.jar"), temp.resolve("second.jar"),
        temp.resolve("third.jar"));
    for (Path archive : archives) {
      try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
        put(output, "META-INF/services/demo.Provider", "demo.DefaultProvider\n");
        for (int index = 0; index < ENUMERATION_LIMIT / 2; index++) {
          put(output, "noise/entry-" + index, "ignored");
        }
      }
      ServiceLoaderDescriptorArchiveSource.scan(archive, descriptors, state);
    }

    assertEquals(ENUMERATION_LIMIT, enumeratedEntries(state));
    assertEquals(1, descriptors.size(), descriptors.toString());
    for (Path archive : archives.subList(1, archives.size())) {
      assertTrue(state.diagnostics().stream()
          .anyMatch(item -> item.code().equals("descriptor_enumeration_limit")
              && item.path().contains(archive.toString())), state.diagnostics().toString());
    }
  }

  @Test
  void moduleInfoWithMultipleProvidesSharesOneFingerprint() throws IOException {
    Path moduleSource = temp.resolve("fingerprint-module-src");
    Path moduleClasses = Files.createDirectories(temp.resolve("fingerprint-module-classes"));
    Files.createDirectories(moduleSource.resolve("demo"));
    StringBuilder module = new StringBuilder("module demo.provider {\n");
    for (String name : List.of("One", "Two", "Three")) {
      Files.writeString(moduleSource.resolve("demo/" + name + ".java"),
          "package demo; public interface " + name + " { void record(); }\n");
      Files.writeString(moduleSource.resolve("demo/" + name + "Impl.java"),
          "package demo; public final class " + name + "Impl implements " + name
              + " { public void record() {} }\n");
      module.append("  provides demo.").append(name).append(" with demo.")
          .append(name).append("Impl;\n");
    }
    Files.writeString(moduleSource.resolve("module-info.java"), module + "}\n");

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    Assumptions.assumeTrue(compiler != null, "a JDK compiler is required for module metadata");
    try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
      List<Path> sources = Files.walk(moduleSource)
          .filter(path -> path.toString().endsWith(".java"))
          .sorted()
          .toList();
      var units = files.getJavaFileObjectsFromFiles(sources.stream().map(Path::toFile).toList());
      assertTrue(compiler.getTask(
          null, files, null, List.of("--release", "17", "-d", moduleClasses.toString()), null,
          units).call());
    }

    byte[] bytes = Files.readAllBytes(moduleClasses.resolve("module-info.class"));
    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorSource.addModuleBytes(bytes, "fingerprint-module-info", descriptors,
        state);
    assertTrue(state.diagnostics().isEmpty(), state.diagnostics().toString());
    assertEquals(3, descriptors.size(), descriptors.toString());
    String fingerprint = ServiceLoaderDescriptorSource.sha256(bytes);
    assertTrue(descriptors.stream()
        .allMatch(item -> item.fingerprint().equals(fingerprint)), descriptors.toString());
  }

  @Test
  void binaryClassNameValidationSemanticsArePreserved() throws IOException {
    // Provider-line semantics: accepted, duplicated, and rejected provider lines.
    ServiceLoaderDescriptorSource.ProviderEntries parsed = ServiceLoaderDescriptorSource.providers(
        ("demo.Provider\n$a$b.C$d\n\u00C9nicode.Provider\n\n.leading\ntrailing.\nbad/name\n")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(List.of("demo.Provider", "$a$b.C$d", "\u00C9nicode.Provider"), parsed.providers());
    assertEquals(List.of(".leading", "trailing.", "bad/name"), parsed.malformedEntries());

    // Filename semantics at the serviceFileCandidate/addServiceFile sites.
    Path root = temp.resolve("filename-classes");
    Path services = Files.createDirectories(
        root.resolve(ServiceLoaderDescriptorSource.SERVICES_DIRECTORY));
    Files.writeString(services.resolve("demo.Filename"), "demo.FilenameProvider\n");
    Files.writeString(services.resolve("$dollar.Name"), "demo.DollarProvider\n");
    Files.writeString(services.resolve("bad-name"), "ignored");
    Files.writeString(services.resolve("trailing.dot."), "ignored");
    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorSource.scanClasspathEntry(
        root, descriptors, new java.util.HashSet<>(), state);
    assertEquals(Set.of("demo.Filename", "$dollar.Name"), Set.copyOf(
        descriptors.stream().map(ServiceLoaderDescriptorScanner.Descriptor::serviceType).toList()),
        descriptors.toString());
    assertTrue(state.diagnostics().isEmpty(), state.diagnostics().toString());
  }

  @Test
  void oversizedArchiveIsRejectedBeforeCentralDirectoryIndex() throws IOException {
    Path archive = temp.resolve("oversized.jar");
    try (RandomAccessFile sparse = new RandomAccessFile(archive.toFile(), "rw")) {
      sparse.setLength(512L * 1024 * 1024 + 1);
    }

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorArchiveSource.scan(archive, descriptors, state);

    assertEquals(1, state.diagnostics().size(), state.diagnostics().toString());
    assertEquals("archive_size_limit", state.diagnostics().get(0).code());
    assertTrue(descriptors.isEmpty(), descriptors.toString());
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
