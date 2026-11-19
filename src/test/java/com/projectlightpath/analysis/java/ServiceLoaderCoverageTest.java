package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceLoaderCoverageTest {

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
  void vanishedSelectedDependencyIsDiagnosed() {
    Path missing = temp.resolve("vanished/dependency.jar");
    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorSource.scanClasspathEntry(
        missing, descriptors, new java.util.HashSet<>(), state, true);
    assertTrue(descriptors.isEmpty(), descriptors.toString());
    assertTrue(state.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_entry_unavailable")),
        state.diagnostics().toString());
  }

  @Test
  void applicationClassesPathStaysSilentForMissingDirectories() {
    Path missing = temp.resolve("missing/application-classes");
    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorSource.scanClasspathEntry(
        missing, descriptors, new java.util.HashSet<>(), state, false);
    assertTrue(descriptors.isEmpty(), descriptors.toString());
    assertTrue(state.diagnostics().isEmpty(), state.diagnostics().toString());
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
    for (int index = 0; index <= 1024; index++) {
      Files.writeString(services.resolve("noise" + index), "");
    }
    List<ServiceLoaderDescriptorScanner.Descriptor> boundedDirectory = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState directoryState =
        ServiceLoaderDescriptorSource.ScanState.withBudgets(
            ServiceLoaderDescriptorSource.MAX_ARCHIVE_DECOMPRESSED_BYTES,
            300,
            ServiceLoaderDescriptorBudget.MAX_RETAINED_DESCRIPTOR_BYTES);
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
      for (int index = 0; index <= 1024; index++) {
        put(output, "noise/entry-" + index, "");
      }
    }
    List<ServiceLoaderDescriptorScanner.Descriptor> boundedArchive = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState archiveState =
        ServiceLoaderDescriptorSource.ScanState.withBudgets(
            ServiceLoaderDescriptorSource.MAX_ARCHIVE_DECOMPRESSED_BYTES,
            300,
            ServiceLoaderDescriptorBudget.MAX_RETAINED_DESCRIPTOR_BYTES);
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
            && item.malformedEntries().equals(List.of("\u2003", "bad/name"))),
        scan.descriptors().toString());
    assertTrue(scan.descriptors().stream().noneMatch(item -> item.path().equals(outside.toString())));
    assertTrue(scan.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_path_outside_context")));
    assertTrue(scan.diagnostics().stream()
        .anyMatch(item -> item.code().equals("archive_decompression_limit")));
  }

  @Test
  void explicitDescriptorPathWithDotSegmentsIsAuthorizedAndRead() throws IOException {
    Path sourceRoot = temp.resolve("dot-segments/src/main/java");
    Files.createDirectories(sourceRoot);
    Path applicationClasses = temp.resolve("dot-segments/classes");
    Files.createDirectories(applicationClasses);
    Path descriptors = temp.resolve("dot-segments/descriptors");
    Files.createDirectories(descriptors);
    Files.writeString(descriptors.resolve("demo.Inside"), "demo.InsideProvider\n");
    String declared = temp.resolve("dot-segments/missing/../descriptors/demo.Inside").toString();

    AnalysisContext context = AnalysisContext.builder()
        .id("dot-segments")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("dot-segments")
        .javaRelease(17)
        .repositoryRoot(temp.resolve("dot-segments"))
        .applicationClasses(applicationClasses)
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
            "file", "service_loader_descriptor", AnalysisContext.MetadataStatus.OBSERVED, declared))
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();

    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorReader.scan(context);

    assertTrue(scan.descriptors().stream()
        .anyMatch(item -> item.serviceType().equals("demo.Inside")),
        scan.descriptors() + " " + scan.diagnostics());
  }

  @Test
  void missingExplicitDescriptorPathIsReportedUnavailable() throws IOException {
    Path sourceRoot = temp.resolve("missing-declared/src/main/java");
    Files.createDirectories(sourceRoot);
    Path applicationClasses = temp.resolve("missing-declared/classes");
    Files.createDirectories(applicationClasses);

    AnalysisContext context = AnalysisContext.builder()
        .id("missing-declared")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("missing-declared")
        .javaRelease(17)
        .repositoryRoot(temp.resolve("missing-declared"))
        .applicationClasses(applicationClasses)
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
            "file", "service_loader_descriptor", AnalysisContext.MetadataStatus.OBSERVED,
            "descriptors/absent.Descriptor"))
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();

    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorReader.scan(context);

    assertTrue(scan.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_entry_unavailable")),
        scan.diagnostics().toString());
    assertTrue(scan.diagnostics().stream()
        .noneMatch(item -> item.code().equals("descriptor_path_outside_context")),
        scan.diagnostics().toString());
  }

  @Test
  void explicitlySelectedDescriptorWithInvalidFilenameIsDiagnosed() throws IOException {
    Path sourceRoot = temp.resolve("invalid-name/src/main/java");
    Files.createDirectories(sourceRoot);
    Path applicationClasses = temp.resolve("invalid-name/classes");
    Files.createDirectories(applicationClasses);
    Path selected = temp.resolve("invalid-name/provider-list");
    Files.writeString(selected, "demo.Provider\n");

    AnalysisContext context = AnalysisContext.builder()
        .id("invalid-name")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("invalid-name")
        .javaRelease(17)
        .repositoryRoot(temp.resolve("invalid-name"))
        .applicationClasses(applicationClasses)
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
            "file", "service_loader_descriptor", AnalysisContext.MetadataStatus.OBSERVED,
            selected.toString()))
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();

    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorReader.scan(context);

    assertTrue(scan.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_filename_invalid")),
        scan.diagnostics().toString());
  }

  @Test
  void sourceOnlyDescriptorAloneIsNotClasspathBacked() throws IOException {
    Path root = temp.resolve("source-only");
    Path sourceRoot = root.resolve("src/main/java");
    Path resourceServices = root.resolve("src/main/resources/META-INF/services");
    Files.createDirectories(sourceRoot.resolve("demo"));
    Files.createDirectories(resourceServices);
    Files.createDirectories(root.resolve("target/classes"));
    Files.writeString(sourceRoot.resolve("demo/App.java"), "package demo;\n"
        + "import java.util.ServiceLoader;\n"
        + "class App { void audit() { ServiceLoader.load(Provider.class).findFirst()"
        + ".ifPresent(value -> value.record()); } }\n");
    Files.writeString(sourceRoot.resolve("demo/Provider.java"),
        "package demo; interface Provider { void record(); }\n");
    Files.writeString(resourceServices.resolve("demo.Provider"), "demo.DefaultProvider\n");

    AnalysisContext context = AnalysisContext.builder()
        .id("source-only")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("source-only")
        .javaRelease(17)
        .repositoryRoot(root)
        .applicationClasses(root.resolve("target/classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();

    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(context);
    assertEquals(1, scan.descriptors().size(), scan.descriptors().toString());
    assertEquals("source_meta_inf_services", scan.descriptors().get(0).kind());
    CallEdge edge = new CallEdge(
        new MethodSymbol("demo.App", "audit", "()V"),
        new MethodSymbol("demo.DefaultProvider", "record", "()V"),
        3,
        "src/main/java/demo/App.java",
        SemanticCallFacts.Certainty.DEFINITE,
        "service_loader");
    ServiceLoaderDescriptorScanner.Resolution resolution = ServiceLoaderDescriptorScanner.resolve(
        context, edge, scan, new SourceMetadataScanner(context));
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, resolution.certainty());
  }

  @Test
  void explicitlySelectedResourceDirectoryRemainsClasspathBacked() throws IOException {
    Path root = temp.resolve("selected-resources");
    Path sourceRoot = root.resolve("src/main/java");
    Path resourceServices = root.resolve("src/main/resources/META-INF/services");
    Files.createDirectories(sourceRoot.resolve("demo"));
    Files.createDirectories(resourceServices);
    Files.createDirectories(root.resolve("target/classes"));
    Files.writeString(sourceRoot.resolve("demo/App.java"), "package demo;\n"
        + "import java.util.ServiceLoader;\n"
        + "class App { void audit() { ServiceLoader.load(Provider.class).findFirst()"
        + ".ifPresent(value -> value.record()); } }\n");
    Files.writeString(sourceRoot.resolve("demo/Provider.java"),
        "package demo; interface Provider { void record(); }\n");
    Files.writeString(resourceServices.resolve("demo.Provider"), "demo.DefaultProvider\n");

    AnalysisContext context = AnalysisContext.builder()
        .id("selected-resources")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("selected-resources")
        .javaRelease(17)
        .repositoryRoot(root)
        .applicationClasses(root.resolve("target/classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
            "resources", "service_loader_descriptor", AnalysisContext.MetadataStatus.OBSERVED,
            "src/main/resources/META-INF/services"))
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();

    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(context);
    assertEquals(1, scan.descriptors().size(), scan.descriptors().toString());
    assertEquals("meta_inf_services", scan.descriptors().get(0).kind());
    CallEdge edge = new CallEdge(
        new MethodSymbol("demo.App", "audit", "()V"),
        new MethodSymbol("demo.DefaultProvider", "record", "()V"),
        3,
        "src/main/java/demo/App.java",
        SemanticCallFacts.Certainty.DEFINITE,
        "service_loader");
    ServiceLoaderDescriptorScanner.Resolution resolution = ServiceLoaderDescriptorScanner.resolve(
        context, edge, scan, new SourceMetadataScanner(context));
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, resolution.certainty());
  }

  @Test
  void identicalSourceAndOutputDescriptorsAreMergedPreferringClasspath() throws IOException {
    Path root = temp.resolve("identical-copies");
    Path sourceRoot = root.resolve("src/main/java");
    Path resources = root.resolve("src/main/resources/META-INF/services");
    Path classes = root.resolve("target/classes/META-INF/services");
    Files.createDirectories(sourceRoot.resolve("demo"));
    Files.createDirectories(resources);
    Files.createDirectories(classes);
    Files.writeString(sourceRoot.resolve("demo/App.java"), "package demo;\n"
        + "import java.util.ServiceLoader;\n"
        + "class App { void audit() { ServiceLoader.load(Provider.class).findFirst()"
        + ".ifPresent(value -> value.record()); } }\n");
    Files.writeString(sourceRoot.resolve("demo/Provider.java"),
        "package demo; interface Provider { void record(); }\n");
    Files.writeString(resources.resolve("demo.Provider"), "demo.DefaultProvider\n");
    Files.writeString(classes.resolve("demo.Provider"), "demo.DefaultProvider\n");

    AnalysisContext context = AnalysisContext.builder()
        .id("identical-copies")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("identical-copies")
        .javaRelease(17)
        .repositoryRoot(root)
        .applicationClasses(root.resolve("target/classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();

    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(context);
    CallEdge edge = new CallEdge(
        new MethodSymbol("demo.App", "audit", "()V"),
        new MethodSymbol("demo.DefaultProvider", "record", "()V"),
        3,
        "src/main/java/demo/App.java",
        SemanticCallFacts.Certainty.DEFINITE,
        "service_loader");
    ServiceLoaderDescriptorScanner.Resolution resolution = ServiceLoaderDescriptorScanner.resolve(
        context, edge, scan, new SourceMetadataScanner(context));

    assertEquals(2, scan.descriptors().size(), scan.descriptors().toString());
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, resolution.certainty());
    assertTrue(resolution.evidence().stream()
        .filter(evidence -> evidence.kind().equals("service_loader_resolution"))
        .anyMatch(evidence -> evidence.attributes().get("descriptorCount").equals("1")),
        resolution.evidence().toString());
    assertTrue(resolution.evidence().stream()
        .filter(evidence -> evidence.kind().equals("service_loader_descriptor"))
        .allMatch(evidence -> evidence.attributes().get("descriptorKind")
            .equals("meta_inf_services")),
        resolution.evidence().toString());
  }

  @Test
  void observedServicesDirectoryIsEnumeratedOnce() throws IOException {
    Path sourceRoot = temp.resolve("src/main/java");
    Files.createDirectories(sourceRoot);
    Path applicationClasses = temp.resolve("classes");
    Path applicationServices = Files.createDirectories(
        applicationClasses.resolve(ServiceLoaderDescriptorSource.SERVICES_DIRECTORY));
    for (int index = 0; index < 600; index++) {
      Files.writeString(applicationServices.resolve("demo.Service" + index),
          "demo.Provider" + index + "\n");
    }
    Path dependencies = temp.resolve("deps");
    Files.createDirectories(dependencies);
    Path dependency = dependencies.resolve("dependency.jar");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(dependency))) {
      put(output, "META-INF/services/demo.Dependency", "demo.DependencyProvider\n");
    }

    AnalysisContext context = AnalysisContext.builder()
        .id("services-dedup")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("services-dedup")
        .javaRelease(17)
        .repositoryRoot(temp)
        .applicationClasses(applicationClasses)
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspath(List.of(dependency))
        .dependencyClasspathRequired(false)
        .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
            "directory", "service_loader_descriptor", AnalysisContext.MetadataStatus.OBSERVED,
            applicationServices.toString()))
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();

    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorReader.scan(context);
    assertTrue(scan.diagnostics().stream().noneMatch(
        item -> item.code().equals("descriptor_enumeration_limit")), scan.diagnostics().toString());
    assertTrue(scan.descriptors().stream()
        .anyMatch(item -> item.serviceType().equals("demo.Dependency")), scan.descriptors().toString());
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
  void descriptorEnumerationStopsAtByteBudgetForDirectoriesAndArchives() throws IOException {
    Path classes = temp.resolve("bounded-classes");
    Path services = classes.resolve(ServiceLoaderDescriptorSource.SERVICES_DIRECTORY);
    Files.createDirectories(services);
    for (int index = 0; index <= 1024; index++) {
      Files.writeString(services.resolve("demo.Provider" + index), "demo.ProviderImpl\n");
    }
    List<ServiceLoaderDescriptorScanner.Descriptor> directoryDescriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState directoryState =
        ServiceLoaderDescriptorSource.ScanState.withBudgets(
            ServiceLoaderDescriptorSource.MAX_ARCHIVE_DECOMPRESSED_BYTES,
            300,
            ServiceLoaderDescriptorBudget.MAX_RETAINED_DESCRIPTOR_BYTES);
    ServiceLoaderDescriptorSource.scanClasspathEntry(
        classes, directoryDescriptors, new java.util.HashSet<>(), directoryState);
    assertTrue(directoryState.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_enumeration_limit")));
    assertTrue(directoryDescriptors.isEmpty(), directoryDescriptors.toString());

    Path archive = temp.resolve("bounded.jar");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      put(output, "META-INF/services/demo.Archive", "demo.ArchiveProvider\n");
      for (int index = 0; index <= 1024; index++) {
        put(output, "noise/entry-" + index, "ignored");
      }
    }
    List<ServiceLoaderDescriptorScanner.Descriptor> archiveDescriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState archiveState =
        ServiceLoaderDescriptorSource.ScanState.withBudgets(
            ServiceLoaderDescriptorSource.MAX_ARCHIVE_DECOMPRESSED_BYTES,
            300,
            ServiceLoaderDescriptorBudget.MAX_RETAINED_DESCRIPTOR_BYTES);
    ServiceLoaderDescriptorArchiveSource.scan(archive, archiveDescriptors, archiveState);
    assertTrue(archiveState.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_enumeration_limit")));
    assertTrue(archiveDescriptors.isEmpty(), archiveDescriptors.toString());
    assertEquals(2 * ServiceLoaderDescriptorBudget.MEASURED_RAW_BYTES_PER_ENTRY,
        directoryState.enumeratedBytes());
    assertEquals(2 * ServiceLoaderDescriptorBudget.MEASURED_RAW_BYTES_PER_ENTRY,
        archiveState.enumeratedBytes());
  }

  @Test
  void sharedByteBudgetReportsEverySkippedArchive() throws IOException {
    long firstArchiveBytes =
        513 * ServiceLoaderDescriptorBudget.MEASURED_RAW_BYTES_PER_ENTRY;
    ServiceLoaderDescriptorSource.ScanState state =
        ServiceLoaderDescriptorSource.ScanState.withEnumerationBudget(firstArchiveBytes);
    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    List<Path> archives = List.of(temp.resolve("first.jar"), temp.resolve("second.jar"),
        temp.resolve("third.jar"));
    for (Path archive : archives) {
      try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
        put(output, "META-INF/services/demo.Provider", "demo.DefaultProvider\n");
        for (int index = 0; index < 512; index++) {
          put(output, "noise/entry-" + index, "ignored");
        }
      }
      ServiceLoaderDescriptorArchiveSource.scan(archive, descriptors, state);
    }

    assertEquals(1, descriptors.size(), descriptors.toString());
    for (Path archive : archives.subList(1, archives.size())) {
      assertTrue(state.diagnostics().stream()
          .anyMatch(item -> item.code().equals("descriptor_enumeration_limit")
              && item.path().contains(archive.toString())), state.diagnostics().toString());
    }
    assertTrue(state.diagnostics().stream()
        .allMatch(item -> item.detail().contains("bytes")), state.diagnostics().toString());
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
  void unicodeWhitespaceOnlyLineIsMalformed() {
    ServiceLoaderDescriptorSource.ProviderEntries parsed = ServiceLoaderDescriptorSource.providers(
        "\u2003\ndemo.Provider\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    assertEquals(List.of("demo.Provider"), parsed.providers());
    assertEquals(List.of("\u2003"), parsed.malformedEntries());
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
    for (int index = 0;
        index <= SootUpInputLocationFactory.MAX_DEPENDENCY_ENTRIES; index++) {
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

  @Test
  void versionedOnlyModuleInfoIsDiagnosed() throws IOException {
    Path archive = temp.resolve("versioned-only-module.jar");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      put(output, "META-INF/services/demo.Provider", "demo.VersionedProvider\n");
      output.putNextEntry(new ZipEntry("META-INF/versions/9/module-info.class"));
      output.write(new byte[] {0, 0, 0, 61});
      output.closeEntry();
    }

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorArchiveSource.scan(archive, descriptors, state);
    assertTrue(descriptors.stream()
        .anyMatch(item -> item.serviceType().equals("demo.Provider")), descriptors.toString());
    assertTrue(descriptors.stream()
        .noneMatch(ServiceLoaderDescriptorScanner.Descriptor::moduleInfo), descriptors.toString());
    assertTrue(state.diagnostics().stream()
        .anyMatch(item -> item.code().equals("versioned_module_info_unsupported")),
        state.diagnostics().toString());

    // Control: a root module-info plus a versioned one still uses the root
    // one with no versioned-only diagnostic.
    Path moduleSource = temp.resolve("versioned-module-src");
    Path moduleClasses = temp.resolve("versioned-module-classes");
    Files.createDirectories(moduleClasses);
    Files.createDirectories(moduleSource.resolve("demo"));
    Files.writeString(moduleSource.resolve("module-info.java"),
        "module demo.versioned { provides demo.Provider with demo.VersionedProvider; }\n");
    Files.writeString(moduleSource.resolve("demo/Provider.java"),
        "package demo; public interface Provider { void record(); }\n");
    Files.writeString(moduleSource.resolve("demo/VersionedProvider.java"),
        "package demo; public final class VersionedProvider implements Provider {"
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
    Path rooted = temp.resolve("rooted-with-versioned-module.jar");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(rooted))) {
      put(output, "META-INF/services/demo.Provider", "demo.RootedProvider\n");
      output.putNextEntry(new ZipEntry("module-info.class"));
      output.write(Files.readAllBytes(moduleClasses.resolve("module-info.class")));
      output.closeEntry();
      output.putNextEntry(new ZipEntry("META-INF/versions/9/module-info.class"));
      output.write(new byte[] {0, 0, 0, 61});
      output.closeEntry();
    }
    List<ServiceLoaderDescriptorScanner.Descriptor> rootedDescriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState rootedState =
        new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorArchiveSource.scan(rooted, rootedDescriptors, rootedState);
    assertEquals(List.of("demo.VersionedProvider"), rootedDescriptors.stream()
        .filter(ServiceLoaderDescriptorScanner.Descriptor::moduleInfo)
        .map(ServiceLoaderDescriptorScanner.Descriptor::providers).findFirst().orElseThrow(),
        rootedDescriptors.toString());
    assertTrue(rootedState.diagnostics().stream()
        .noneMatch(item -> item.code().equals("versioned_module_info_unsupported")),
        rootedState.diagnostics().toString());
  }

  @Test
  void malformedVersionedModulePathDoesNotAbortScan() throws IOException {
    // Boundary probe set per AGENTS.md: prefix/suffix overlap, empty version
    // component, and the valid single-digit form.
    Path overlap = temp.resolve("versioned-overlap-module.jar");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(overlap))) {
      put(output, "META-INF/services/demo.Provider", "demo.OverlapProvider\n");
      output.putNextEntry(new ZipEntry("META-INF/versions/module-info.class"));
      output.write(new byte[] {0, 0, 0, 61});
      output.closeEntry();
    }
    List<ServiceLoaderDescriptorScanner.Descriptor> overlapDescriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState overlapState =
        new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorArchiveSource.scan(overlap, overlapDescriptors, overlapState);
    assertTrue(overlapDescriptors.stream()
        .anyMatch(item -> item.serviceType().equals("demo.Provider")),
        overlapDescriptors.toString());
    assertTrue(overlapDescriptors.stream()
        .noneMatch(ServiceLoaderDescriptorScanner.Descriptor::moduleInfo),
        overlapDescriptors.toString());
    assertTrue(overlapState.diagnostics().stream()
        .noneMatch(item -> item.code().equals("versioned_module_info_unsupported")),
        overlapState.diagnostics().toString());

    Path emptyComponent = temp.resolve("versioned-empty-module.jar");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(emptyComponent))) {
      put(output, "META-INF/services/demo.Provider", "demo.EmptyProvider\n");
      output.putNextEntry(new ZipEntry("META-INF/versions//module-info.class"));
      output.write(new byte[] {0, 0, 0, 61});
      output.closeEntry();
    }
    List<ServiceLoaderDescriptorScanner.Descriptor> emptyDescriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState emptyState =
        new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorArchiveSource.scan(emptyComponent, emptyDescriptors, emptyState);
    assertTrue(emptyDescriptors.stream()
        .anyMatch(item -> item.serviceType().equals("demo.Provider")),
        emptyDescriptors.toString());
    assertTrue(emptyState.diagnostics().stream()
        .noneMatch(item -> item.code().equals("versioned_module_info_unsupported")),
        emptyState.diagnostics().toString());

    Path valid = temp.resolve("versioned-valid-module.jar");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(valid))) {
      put(output, "META-INF/services/demo.Provider", "demo.ValidProvider\n");
      output.putNextEntry(new ZipEntry("META-INF/versions/9/module-info.class"));
      output.write(new byte[] {0, 0, 0, 61});
      output.closeEntry();
    }
    List<ServiceLoaderDescriptorScanner.Descriptor> validDescriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState validState =
        new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorArchiveSource.scan(valid, validDescriptors, validState);
    assertTrue(validState.diagnostics().stream()
        .anyMatch(item -> item.code().equals("versioned_module_info_unsupported")),
        validState.diagnostics().toString());
  }

  @Test
  void directoryEnumerationScalesPastFormerEntryCap() throws IOException {
    Path classes = temp.resolve("scale-directory-classes");
    Path services = Files.createDirectories(
        classes.resolve(ServiceLoaderDescriptorSource.SERVICES_DIRECTORY));
    for (int index = 0; index < 1600; index++) {
      Files.writeString(services.resolve("demo.Provider" + index), "demo.ProviderImpl\n");
    }

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state =
        new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorSource.scanClasspathEntry(
        classes, descriptors, new java.util.HashSet<>(), state);

    assertEquals(1600, descriptors.size());
    assertTrue(state.diagnostics().isEmpty(), state.diagnostics().toString());
    assertTrue(state.enumeratedBytes() > 0);
  }

  @Test
  void archiveEnumerationScalesPastFormerEntryCap() throws IOException {
    Path archive = temp.resolve("scale-archive.jar");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      for (int index = 0; index < 1600; index++) {
        put(output, "META-INF/services/demo.Provider" + index, "demo.ProviderImpl\n");
      }
    }

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state =
        new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorArchiveSource.scan(archive, descriptors, state);

    assertEquals(1600, descriptors.size());
    assertTrue(state.diagnostics().isEmpty(), state.diagnostics().toString());
  }

  @Test
  void sharedByteBudgetCrossesManyArchives() throws IOException {
    ServiceLoaderDescriptorSource.ScanState state =
        new ServiceLoaderDescriptorSource.ScanState();
    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    List<Path> archives = new ArrayList<>();
    for (int archiveIndex = 0; archiveIndex < 4; archiveIndex++) {
      Path archive = temp.resolve("shared-" + archiveIndex + ".jar");
      archives.add(archive);
      try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
        put(output, "META-INF/services/demo.Provider" + archiveIndex, "demo.ProviderImpl\n");
        for (int index = 0; index < 699; index++) {
          put(output, "noise/entry-" + index, "ignored");
        }
      }
      ServiceLoaderDescriptorArchiveSource.scan(archive, descriptors, state);
    }

    assertEquals(4, descriptors.size(), descriptors.toString());
    assertTrue(state.diagnostics().isEmpty(), state.diagnostics().toString());
  }

  @Test
  void classicZipAtEntryCountBoundaryIsFullyEnumerated() throws IOException {
    List<String> names = new ArrayList<>();
    names.add("META-INF/services/demo.Provider");
    for (int index = 0; index < 65534; index++) {
      names.add("noise/e-" + index);
    }
    Path archive = temp.resolve("classic-65535.jar");
    writeClassicZip(archive, names, "demo.ProviderImpl\n");

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state =
        new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorArchiveSource.scan(archive, descriptors, state);

    assertEquals(1, descriptors.size(), descriptors.toString());
    assertEquals("demo.Provider", descriptors.get(0).serviceType());
    assertEquals(List.of("demo.ProviderImpl"), descriptors.get(0).providers());
    assertTrue(state.diagnostics().isEmpty(), state.diagnostics().toString());

    byte[] bytes = Files.readAllBytes(archive);
    int end = lastIndexOf(bytes, new byte[] {0x50, 0x4b, 0x05, 0x06});
    assertEquals(bytes.length - 22, end);
    assertEquals(0xffff, (bytes[end + 10] & 0xff) | ((bytes[end + 11] & 0xff) << 8));
    assertEquals(-1, lastIndexOf(bytes, new byte[] {0x50, 0x4b, 0x06, 0x07}));
  }

  @Test
  @Tag("large-archive")
  void zip64MillionEntryArchiveIsFullyEnumerated() throws IOException {
    Path archive = temp.resolve("zip64-million.jar");
    try (ZipOutputStream output = new ZipOutputStream(
        new BufferedOutputStream(Files.newOutputStream(archive)))) {
      put(output, "META-INF/services/demo.Provider", "demo.ProviderImpl\n");
      for (int index = 0; index < 1_000_000; index++) {
        putStored(output, "noise/e-" + index);
      }
    }

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state =
        new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorArchiveSource.scan(archive, descriptors, state);

    assertEquals(1, descriptors.size(), descriptors.toString());
    assertEquals("demo.Provider", descriptors.get(0).serviceType());
    assertTrue(state.diagnostics().isEmpty(), state.diagnostics().toString());
  }

  @Test
  void byteBudgetExhaustionRollsBackArchiveAndReportsBytes() throws IOException {
    Path archive = temp.resolve("bounded-bytes.jar");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      put(output, "META-INF/services/demo.Provider", "demo.ProviderImpl\n");
      put(output, "noise/a", "ignored");
      put(output, "noise/b", "ignored");
    }

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state =
        ServiceLoaderDescriptorSource.ScanState.withEnumerationBudget(200);
    ServiceLoaderDescriptorArchiveSource.scan(archive, descriptors, state);

    assertTrue(descriptors.isEmpty(), descriptors.toString());
    assertEquals(1, state.diagnostics().size(), state.diagnostics().toString());
    ServiceLoaderDescriptorScanner.Diagnostic diagnostic = state.diagnostics().get(0);
    assertEquals("descriptor_enumeration_limit", diagnostic.code());
    assertTrue(diagnostic.detail().contains("bytes"), diagnostic.detail());
    assertTrue(diagnostic.detail().contains("200"), diagnostic.detail());
    assertTrue(state.diagnostics().stream()
        .noneMatch(item -> item.code().equals("archive_decompression_limit")));
  }

  @Test
  void byteBudgetExhaustionRollsBackDirectoryAndReportsBytes() throws IOException {
    Path classes = temp.resolve("bounded-directory-classes");
    Path services = Files.createDirectories(
        classes.resolve(ServiceLoaderDescriptorSource.SERVICES_DIRECTORY));
    for (String name : List.of("demo.A", "demo.B", "demo.C")) {
      Files.writeString(services.resolve(name), "demo.ProviderImpl\n");
    }

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state =
        ServiceLoaderDescriptorSource.ScanState.withEnumerationBudget(200);
    ServiceLoaderDescriptorSource.scanClasspathEntry(
        classes, descriptors, new java.util.HashSet<>(), state);

    assertTrue(descriptors.isEmpty(), descriptors.toString());
    assertEquals(1, state.diagnostics().size(), state.diagnostics().toString());
    assertEquals("descriptor_enumeration_limit", state.diagnostics().get(0).code());
    assertTrue(state.diagnostics().get(0).detail().contains("bytes"));
  }

  @Test
  void archiveExhaustionAfterCollectingDescriptorStillRollsBack() throws IOException {
    Path archive = temp.resolve("bounded-after-collect.jar");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      put(output, "META-INF/services/demo.Provider", "demo.ProviderImpl\n");
      put(output, "noise/a", "ignored");
      put(output, "noise/b", "ignored");
    }

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state =
        ServiceLoaderDescriptorSource.ScanState.withEnumerationBudget(122);
    ServiceLoaderDescriptorArchiveSource.scan(archive, descriptors, state);

    assertTrue(descriptors.isEmpty(), descriptors.toString());
    assertTrue(state.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_enumeration_limit")));
  }

  @Test
  void byteBudgetBoundaryIsExactForArchiveAndDirectory() throws IOException {
    Path archive = temp.resolve("boundary.jar");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      put(output, "META-INF/services/demo.A", "demo.ProviderImpl\n");
      put(output, "noise/a", "ignored");
    }
    long archiveBytes;
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      archiveBytes = zip.stream()
          .mapToLong(ServiceLoaderDescriptorBudget::centralDirectoryBytes).sum();
    }

    List<ServiceLoaderDescriptorScanner.Descriptor> exactArchive = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState exactArchiveState =
        ServiceLoaderDescriptorSource.ScanState.withEnumerationBudget(archiveBytes);
    ServiceLoaderDescriptorArchiveSource.scan(archive, exactArchive, exactArchiveState);
    assertEquals(1, exactArchive.size(), exactArchive.toString());
    assertTrue(exactArchiveState.diagnostics().isEmpty(),
        exactArchiveState.diagnostics().toString());

    List<ServiceLoaderDescriptorScanner.Descriptor> shortArchive = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState shortArchiveState =
        ServiceLoaderDescriptorSource.ScanState.withEnumerationBudget(archiveBytes - 1);
    ServiceLoaderDescriptorArchiveSource.scan(archive, shortArchive, shortArchiveState);
    assertTrue(shortArchive.isEmpty(), shortArchive.toString());
    assertEquals(1, shortArchiveState.diagnostics().size());

    Path classes = temp.resolve("boundary-classes");
    Path services = Files.createDirectories(
        classes.resolve(ServiceLoaderDescriptorSource.SERVICES_DIRECTORY));
    Files.writeString(services.resolve("demo.A"), "demo.ProviderImpl\n");
    Files.writeString(services.resolve("demo.B"), "demo.ProviderImpl\n");
    long directoryBytes;
    try (var paths = Files.newDirectoryStream(services)) {
      directoryBytes = 0;
      for (Path path : paths) {
        directoryBytes += ServiceLoaderDescriptorBudget.directoryEntryBytes(path);
      }
    }

    List<ServiceLoaderDescriptorScanner.Descriptor> exactDirectory = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState exactDirectoryState =
        ServiceLoaderDescriptorSource.ScanState.withEnumerationBudget(directoryBytes);
    ServiceLoaderDescriptorSource.scanClasspathEntry(
        classes, exactDirectory, new java.util.HashSet<>(), exactDirectoryState);
    assertEquals(2, exactDirectory.size(), exactDirectory.toString());
    assertTrue(exactDirectoryState.diagnostics().isEmpty(),
        exactDirectoryState.diagnostics().toString());

    List<ServiceLoaderDescriptorScanner.Descriptor> shortDirectory = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState shortDirectoryState =
        ServiceLoaderDescriptorSource.ScanState.withEnumerationBudget(directoryBytes - 1);
    ServiceLoaderDescriptorSource.scanClasspathEntry(
        classes, shortDirectory, new java.util.HashSet<>(), shortDirectoryState);
    assertTrue(shortDirectory.isEmpty(), shortDirectory.toString());
    assertEquals(1, shortDirectoryState.diagnostics().size());
  }

  @Test
  void enumerationBudgetChargesFloorAndRecordBytes() throws IOException {
    Path plain = temp.resolve("plain-cost.jar");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(plain))) {
      put(output, "META-INF/services/demo.Provider", "demo.ProviderImpl\n");
    }
    List<ServiceLoaderDescriptorScanner.Descriptor> plainDescriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState plainState =
        new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorArchiveSource.scan(plain, plainDescriptors, plainState);
    assertEquals(ServiceLoaderDescriptorBudget.MEASURED_RAW_BYTES_PER_ENTRY,
        plainState.enumeratedBytes());
    assertTrue(plainState.diagnostics().isEmpty(), plainState.diagnostics().toString());

    Path large = temp.resolve("record-cost.jar");
    String longName = "noise/" + "x".repeat(80);
    String unicodeName = "noise/" + "é".repeat(40);
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(large))) {
      ZipEntry ascii = new ZipEntry(longName);
      ascii.setExtra(new byte[10]);
      output.putNextEntry(ascii);
      output.closeEntry();
      output.putNextEntry(new ZipEntry(unicodeName));
      output.closeEntry();
      ZipEntry commented = new ZipEntry("noise/c");
      commented.setComment("c".repeat(100));
      output.putNextEntry(commented);
      output.closeEntry();
      ZipEntry floor = new ZipEntry("noise/short");
      floor.setComment("abcd");
      output.putNextEntry(floor);
      output.closeEntry();
    }
    // 46 + 86 ASCII bytes + 10 extra; 46 + 86 UTF-8 bytes (46 chars);
    // 46 + 7 name + 100 comment; 46 + 11 + 4 floored to 122.
    long expected = 142 + 132 + 153 + 122;

    List<ServiceLoaderDescriptorScanner.Descriptor> largeDescriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState largeState =
        ServiceLoaderDescriptorSource.ScanState.withEnumerationBudget(expected);
    ServiceLoaderDescriptorArchiveSource.scan(large, largeDescriptors, largeState);
    assertEquals(expected, largeState.enumeratedBytes());
    assertTrue(largeState.diagnostics().isEmpty(), largeState.diagnostics().toString());

    List<ServiceLoaderDescriptorScanner.Descriptor> shortDescriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState shortState =
        ServiceLoaderDescriptorSource.ScanState.withEnumerationBudget(expected - 1);
    ServiceLoaderDescriptorArchiveSource.scan(large, shortDescriptors, shortState);
    assertEquals(1, shortState.diagnostics().size());
    assertTrue(shortState.diagnostics().get(0).detail().contains("bytes"));
  }

  @Test
  void retainedDescriptorMetadataIsByteBounded() throws IOException {
    long oneDescriptor = ServiceLoaderDescriptorBudget.RETAINED_DESCRIPTOR_BYTES;

    Path archive = temp.resolve("retention.jar");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      put(output, "META-INF/services/demo.A", "demo.ProviderImpl\n");
      put(output, "META-INF/services/demo.B", "demo.ProviderImpl\n");
      put(output, "META-INF/services/demo.C", "demo.ProviderImpl\n");
    }
    List<ServiceLoaderDescriptorScanner.Descriptor> archiveDescriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState archiveState =
        ServiceLoaderDescriptorSource.ScanState.withBudgets(
            ServiceLoaderDescriptorSource.MAX_ARCHIVE_DECOMPRESSED_BYTES,
            ServiceLoaderDescriptorBudget.MAX_ENUMERATION_BYTES,
            oneDescriptor * 2);
    ServiceLoaderDescriptorArchiveSource.scan(archive, archiveDescriptors, archiveState);
    assertTrue(archiveDescriptors.isEmpty(), archiveDescriptors.toString());
    assertTrue(archiveState.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_retention_limit")),
        archiveState.diagnostics().toString());
    assertTrue(archiveState.diagnostics().stream()
        .noneMatch(item -> item.code().equals("descriptor_enumeration_limit")));
    ServiceLoaderDescriptorScanner.Diagnostic retention = archiveState.diagnostics().get(0);
    assertTrue(retention.detail().contains("bytes"), retention.detail());
    assertTrue(retention.detail().contains(Long.toString(oneDescriptor * 2)),
        retention.detail());
    assertEquals(0L, archiveState.retainedBytes());

    Path laterArchive = temp.resolve("retention-later.jar");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(laterArchive))) {
      put(output, "META-INF/services/demo.C", "demo.ProviderImpl\n");
      put(output, "META-INF/services/demo.D", "demo.ProviderImpl\n");
    }
    ServiceLoaderDescriptorSource.ScanState laterState = archiveState;
    int diagnosticsBefore = laterState.diagnostics().size();
    List<ServiceLoaderDescriptorScanner.Descriptor> laterDescriptors = new ArrayList<>();
    ServiceLoaderDescriptorArchiveSource.scan(laterArchive, laterDescriptors, laterState);
    assertEquals(2, laterDescriptors.size(), laterDescriptors.toString());
    assertEquals(diagnosticsBefore, laterState.diagnostics().size(),
        laterState.diagnostics().toString());

    Path classes = temp.resolve("retention-classes");
    Path services = Files.createDirectories(
        classes.resolve(ServiceLoaderDescriptorSource.SERVICES_DIRECTORY));
    Files.writeString(services.resolve("demo.A"), "demo.ProviderImpl\n");
    Files.writeString(services.resolve("demo.B"), "demo.ProviderImpl\n");
    List<ServiceLoaderDescriptorScanner.Descriptor> directoryDescriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState directoryState =
        ServiceLoaderDescriptorSource.ScanState.withBudgets(
            ServiceLoaderDescriptorSource.MAX_ARCHIVE_DECOMPRESSED_BYTES,
            ServiceLoaderDescriptorBudget.MAX_ENUMERATION_BYTES,
            oneDescriptor);
    ServiceLoaderDescriptorSource.scanClasspathEntry(
        classes, directoryDescriptors, new java.util.HashSet<>(), directoryState);
    assertTrue(directoryDescriptors.isEmpty(), directoryDescriptors.toString());
    assertTrue(directoryState.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_retention_limit")),
        directoryState.diagnostics().toString());
    assertTrue(directoryState.diagnostics().get(0).detail().contains("bytes"));
    assertEquals(0L, directoryState.retainedBytes());
  }

  @Test
  void moduleInfoDescriptorsRespectRetentionBudget() throws IOException {
    byte[] bytes = moduleInfoBytes("retention-module");

    List<ServiceLoaderDescriptorScanner.Descriptor> bounded = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState boundedState =
        ServiceLoaderDescriptorSource.ScanState.withBudgets(
            ServiceLoaderDescriptorSource.MAX_ARCHIVE_DECOMPRESSED_BYTES,
            ServiceLoaderDescriptorBudget.MAX_ENUMERATION_BYTES,
            ServiceLoaderDescriptorBudget.RETAINED_DESCRIPTOR_BYTES);
    ServiceLoaderDescriptorSource.addModuleBytes(
        bytes, "module-info.class", bounded, boundedState);
    assertTrue(bounded.isEmpty(), bounded.toString());
    assertEquals(0L, boundedState.retainedBytes());
    assertTrue(boundedState.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_retention_limit")),
        boundedState.diagnostics().toString());

    List<ServiceLoaderDescriptorScanner.Descriptor> complete = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState completeState =
        ServiceLoaderDescriptorSource.ScanState.withBudgets(
            ServiceLoaderDescriptorSource.MAX_ARCHIVE_DECOMPRESSED_BYTES,
            ServiceLoaderDescriptorBudget.MAX_ENUMERATION_BYTES,
            ServiceLoaderDescriptorBudget.RETAINED_DESCRIPTOR_BYTES * 2);
    ServiceLoaderDescriptorSource.addModuleBytes(
        bytes, "module-info.class", complete, completeState);
    assertEquals(2, complete.size(), complete.toString());
    assertTrue(completeState.diagnostics().isEmpty(), completeState.diagnostics().toString());
  }

  @Test
  void archiveModuleRetentionFailureRollsBackArchiveDescriptors() throws IOException {
    byte[] module = moduleInfoBytes("archive-module-rollback");
    Path archive = temp.resolve("module-retention.jar");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      put(output, "META-INF/services/demo.Provider", "demo.ProviderImpl\n");
      output.putNextEntry(new ZipEntry("module-info.class"));
      output.write(module);
      output.closeEntry();
    }

    List<ServiceLoaderDescriptorScanner.Descriptor> complete = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState completeState =
        ServiceLoaderDescriptorSource.ScanState.withBudgets(
            ServiceLoaderDescriptorSource.MAX_ARCHIVE_DECOMPRESSED_BYTES,
            ServiceLoaderDescriptorBudget.MAX_ENUMERATION_BYTES,
            ServiceLoaderDescriptorBudget.RETAINED_DESCRIPTOR_BYTES * 3);
    ServiceLoaderDescriptorArchiveSource.scan(archive, complete, completeState);
    assertEquals(3, complete.size(), complete.toString());
    assertTrue(completeState.diagnostics().isEmpty(), completeState.diagnostics().toString());

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state =
        ServiceLoaderDescriptorSource.ScanState.withBudgets(
            ServiceLoaderDescriptorSource.MAX_ARCHIVE_DECOMPRESSED_BYTES,
            ServiceLoaderDescriptorBudget.MAX_ENUMERATION_BYTES,
            ServiceLoaderDescriptorBudget.RETAINED_DESCRIPTOR_BYTES);
    ServiceLoaderDescriptorArchiveSource.scan(archive, descriptors, state);

    assertTrue(descriptors.isEmpty(), descriptors.toString());
    assertEquals(0L, state.retainedBytes());
    assertEquals(1, state.diagnostics().stream()
        .filter(item -> item.code().equals("descriptor_retention_limit")).count(),
        state.diagnostics().toString());
  }

  @Test
  void directoryModuleRetentionFailureRollsBackDirectoryDescriptors() throws IOException {
    byte[] module = moduleInfoBytes("directory-module-rollback");
    Path classes = temp.resolve("module-rollback-classes");
    Path services = Files.createDirectories(
        classes.resolve(ServiceLoaderDescriptorSource.SERVICES_DIRECTORY));
    Files.writeString(services.resolve("demo.Provider"), "demo.ProviderImpl\n");
    Files.write(classes.resolve("module-info.class"), module);

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state =
        ServiceLoaderDescriptorSource.ScanState.withBudgets(
            ServiceLoaderDescriptorSource.MAX_ARCHIVE_DECOMPRESSED_BYTES,
            ServiceLoaderDescriptorBudget.MAX_ENUMERATION_BYTES,
            ServiceLoaderDescriptorBudget.RETAINED_DESCRIPTOR_BYTES);
    ServiceLoaderDescriptorSource.scanClasspathEntry(
        classes, descriptors, new java.util.HashSet<>(), state);

    assertTrue(descriptors.isEmpty(), descriptors.toString());
    assertEquals(0L, state.retainedBytes());
    assertEquals(1, state.diagnostics().stream()
        .filter(item -> item.code().equals("descriptor_retention_limit")).count(),
        state.diagnostics().toString());
  }

  private byte[] moduleInfoBytes(String name) throws IOException {
    Path sourceRoot = Files.createDirectories(temp.resolve(name + "-src"));
    Path source = Files.createDirectories(sourceRoot.resolve("demo"));
    for (String service : List.of("A", "B")) {
      Files.writeString(source.resolve(service + ".java"),
          "package demo; public interface " + service + " { void record(); }\n");
      Files.writeString(source.resolve(service + "Impl.java"),
          "package demo; public final class " + service + "Impl implements " + service
              + " { public void record() {} }\n");
    }
    Files.writeString(sourceRoot.resolve("module-info.java"),
        "module demo.provider { provides demo.A with demo.AImpl;"
            + " provides demo.B with demo.BImpl; }\n");
    Path classes = Files.createDirectories(temp.resolve(name + "-classes"));
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    Assumptions.assumeTrue(compiler != null, "a JDK compiler is required for module metadata");
    try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
      List<Path> sources = Files.walk(sourceRoot)
          .filter(path -> path.toString().endsWith(".java"))
          .sorted()
          .toList();
      var units = files.getJavaFileObjectsFromFiles(sources.stream().map(Path::toFile).toList());
      assertTrue(compiler.getTask(
          null, files, null, List.of("--release", "17", "-d", classes.toString()), null,
          units).call());
    }
    return Files.readAllBytes(classes.resolve("module-info.class"));
  }

  @Test
  void decompressionExhaustionMidArchiveKeepsEarlierDescriptors() throws IOException {
    String payload = "demo.ProviderImpl\n".repeat(64);
    Path archive = temp.resolve("decompression-partial.jar");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      for (int index = 0; index < 3; index++) {
        put(output, "META-INF/services/demo.Provider" + index, payload);
      }
    }

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state =
        ServiceLoaderDescriptorSource.ScanState.withBudgets(
            1500,
            ServiceLoaderDescriptorBudget.MAX_ENUMERATION_BYTES,
            ServiceLoaderDescriptorBudget.MAX_RETAINED_DESCRIPTOR_BYTES);
    ServiceLoaderDescriptorArchiveSource.scan(archive, descriptors, state);

    assertEquals(1, descriptors.size(), descriptors.toString());
    assertEquals(2, state.diagnostics().stream()
        .filter(item -> item.code().equals("archive_decompression_limit")).count(),
        state.diagnostics().toString());
    assertTrue(state.diagnostics().stream()
        .noneMatch(item -> item.code().equals("descriptor_enumeration_limit")));
    assertTrue(state.diagnostics().stream()
        .noneMatch(item -> item.code().equals("descriptor_retention_limit")));
  }

  @Test
  void exhaustedDirectoryListingStillScansModuleInfoIndependently() throws IOException {
    byte[] module = moduleInfoBytes("directory-listing-module");
    Path classes = temp.resolve("listing-module-classes");
    Path services = Files.createDirectories(
        classes.resolve(ServiceLoaderDescriptorSource.SERVICES_DIRECTORY));
    for (String name : List.of("demo.A", "demo.B", "demo.C")) {
      Files.writeString(services.resolve(name), "demo.ProviderImpl\n");
    }
    Files.write(classes.resolve("module-info.class"), module);

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state =
        ServiceLoaderDescriptorSource.ScanState.withEnumerationBudget(200);
    ServiceLoaderDescriptorSource.scanClasspathEntry(
        classes, descriptors, new java.util.HashSet<>(), state);

    assertEquals(2, descriptors.size(), descriptors.toString());
    assertTrue(descriptors.stream().allMatch(ServiceLoaderDescriptorScanner.Descriptor::moduleInfo));
    assertTrue(state.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_enumeration_limit")),
        state.diagnostics().toString());
    assertEquals(2L * ServiceLoaderDescriptorBudget.RETAINED_DESCRIPTOR_BYTES,
        state.retainedBytes());
  }

  @Test
  void observedSingleFileDescriptorsChargeRetention() throws IOException {
    Path root = temp.resolve("observed-retention");
    Path sourceRoot = root.resolve("src/main/java");
    Files.createDirectories(sourceRoot);
    Files.createDirectories(root.resolve("target/classes"));
    Path first = root.resolve("observed/demo.One");
    Path second = root.resolve("observed/demo.Two");
    Files.createDirectories(first.getParent());
    Files.writeString(first, "demo.OneImpl\n");
    Files.writeString(second, "demo.TwoImpl\n");

    AnalysisContext context = AnalysisContext.builder()
        .id("observed-retention")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("observed-retention")
        .javaRelease(17)
        .repositoryRoot(root)
        .applicationClasses(root.resolve("target/classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
            "first", "service_loader_descriptor", AnalysisContext.MetadataStatus.OBSERVED,
            first.toString()))
        .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
            "second", "service_loader_descriptor", AnalysisContext.MetadataStatus.OBSERVED,
            second.toString()))
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();

    ServiceLoaderDescriptorSource.ScanState state =
        ServiceLoaderDescriptorSource.ScanState.withBudgets(
            ServiceLoaderDescriptorSource.MAX_ARCHIVE_DECOMPRESSED_BYTES,
            ServiceLoaderDescriptorBudget.MAX_ENUMERATION_BYTES,
            ServiceLoaderDescriptorBudget.RETAINED_DESCRIPTOR_BYTES);
    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(
        context, SootUpInputLocationFactory.dependencyResolution(context), state);

    assertEquals(1, scan.descriptors().size(), scan.descriptors().toString());
    assertEquals(ServiceLoaderDescriptorBudget.RETAINED_DESCRIPTOR_BYTES,
        state.retainedBytes());
    assertEquals(1, scan.diagnostics().stream()
        .filter(item -> item.code().equals("descriptor_retention_limit")).count(),
        scan.diagnostics().toString());
  }

  @Test
  void nonDescriptorServiceEntriesDoNotConsumeRetention() throws IOException {
    Path archive = temp.resolve("non-descriptor-entries.jar");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      put(output, "META-INF/services/not-valid", "ignored");
      put(output, "META-INF/services/a/b", "ignored");
      put(output, "META-INF/services/demo.Provider", "demo.ProviderImpl\n");
    }

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state =
        ServiceLoaderDescriptorSource.ScanState.withBudgets(
            ServiceLoaderDescriptorSource.MAX_ARCHIVE_DECOMPRESSED_BYTES,
            ServiceLoaderDescriptorBudget.MAX_ENUMERATION_BYTES,
            ServiceLoaderDescriptorBudget.RETAINED_DESCRIPTOR_BYTES);
    ServiceLoaderDescriptorArchiveSource.scan(archive, descriptors, state);

    assertEquals(1, descriptors.size(), descriptors.toString());
    assertTrue(state.diagnostics().isEmpty(), state.diagnostics().toString());
    assertEquals(ServiceLoaderDescriptorBudget.RETAINED_DESCRIPTOR_BYTES,
        state.retainedBytes());
  }

  @Test
  void enumerationExhaustionDowngradesCertaintyToPossible() throws IOException {
    AnalysisContext context = certaintyContext("exhaustion-certainty");
    ServiceLoaderDescriptorSource.ScanState state =
        ServiceLoaderDescriptorSource.ScanState.withEnumerationBudget(200);
    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(
        context, SootUpInputLocationFactory.dependencyResolution(context), state);

    assertTrue(scan.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_enumeration_limit")),
        scan.diagnostics().toString());
    assertEquals(1, scan.descriptors().size(), scan.descriptors().toString());
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, certainty(context, scan));
  }

  @Test
  void retentionExhaustionDowngradesCertaintyToPossible() throws IOException {
    AnalysisContext context = certaintyContext("retention-certainty");
    ServiceLoaderDescriptorSource.ScanState state =
        ServiceLoaderDescriptorSource.ScanState.withBudgets(
            ServiceLoaderDescriptorSource.MAX_ARCHIVE_DECOMPRESSED_BYTES,
            ServiceLoaderDescriptorBudget.MAX_ENUMERATION_BYTES,
            ServiceLoaderDescriptorBudget.RETAINED_DESCRIPTOR_BYTES);
    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(
        context, SootUpInputLocationFactory.dependencyResolution(context), state);

    assertTrue(scan.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_retention_limit")),
        scan.diagnostics().toString());
    assertEquals(1, scan.descriptors().size(), scan.descriptors().toString());
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, certainty(context, scan));
  }

  private AnalysisContext certaintyContext(String name) throws IOException {
    Path root = temp.resolve(name);
    Path sourceRoot = root.resolve("src/main/java");
    Path resourceServices = root.resolve("src/main/resources/META-INF/services");
    Files.createDirectories(sourceRoot.resolve("demo"));
    Files.createDirectories(resourceServices);
    Files.createDirectories(root.resolve("target/classes"));
    Files.writeString(sourceRoot.resolve("demo/App.java"), "package demo;\n"
        + "import java.util.ServiceLoader;\n"
        + "class App { void audit() { ServiceLoader.load(Provider.class).findFirst()"
        + ".ifPresent(value -> value.record()); } }\n");
    Files.writeString(sourceRoot.resolve("demo/Provider.java"),
        "package demo; interface Provider { void record(); }\n");
    Files.writeString(resourceServices.resolve("demo.Provider"), "demo.DefaultProvider\n");
    Path dependency = root.resolve("deps/dependency.jar");
    Files.createDirectories(dependency.getParent());
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(dependency))) {
      put(output, "META-INF/services/demo.Provider", "demo.OtherProvider\n");
    }

    return AnalysisContext.builder()
        .id(name)
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId(name)
        .javaRelease(17)
        .repositoryRoot(root)
        .applicationClasses(root.resolve("target/classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspath(List.of(dependency))
        .dependencyClasspathRequired(false)
        .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
            "resources", "service_loader_descriptor", AnalysisContext.MetadataStatus.OBSERVED,
            "src/main/resources/META-INF/services"))
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
  }

  private SemanticCallFacts.Certainty certainty(
      AnalysisContext context, ServiceLoaderDescriptorScanner.Scan scan) {
    CallEdge edge = new CallEdge(
        new MethodSymbol("demo.App", "audit", "()V"),
        new MethodSymbol("demo.DefaultProvider", "record", "()V"),
        3,
        "src/main/java/demo/App.java",
        SemanticCallFacts.Certainty.DEFINITE,
        "service_loader");
    return ServiceLoaderDescriptorScanner.resolve(
        context, edge, scan, new SourceMetadataScanner(context)).certainty();
  }

  @Test
  void decompressionAndEnumerationBudgetsAreOrthogonal() throws IOException {
    Path archive = temp.resolve("orthogonal.jar");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      put(output, "META-INF/services/demo.Provider", "demo.ProviderImpl\n");
      put(output, "noise/a", "ignored");
    }

    List<ServiceLoaderDescriptorScanner.Descriptor> enumeration = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState enumerationState =
        ServiceLoaderDescriptorSource.ScanState.withBudgets(
            ServiceLoaderDescriptorSource.MAX_ARCHIVE_DECOMPRESSED_BYTES,
            122,
            ServiceLoaderDescriptorBudget.MAX_RETAINED_DESCRIPTOR_BYTES);
    ServiceLoaderDescriptorArchiveSource.scan(archive, enumeration, enumerationState);
    assertTrue(enumerationState.diagnostics().stream()
        .anyMatch(item -> item.code().equals("descriptor_enumeration_limit")));
    assertTrue(enumerationState.diagnostics().stream()
        .noneMatch(item -> item.code().equals("archive_decompression_limit")));

    List<ServiceLoaderDescriptorScanner.Descriptor> decompression = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState decompressionState =
        ServiceLoaderDescriptorSource.ScanState.withBudgets(
            1,
            ServiceLoaderDescriptorBudget.MAX_ENUMERATION_BYTES,
            ServiceLoaderDescriptorBudget.MAX_RETAINED_DESCRIPTOR_BYTES);
    ServiceLoaderDescriptorArchiveSource.scan(archive, decompression, decompressionState);
    assertTrue(decompressionState.diagnostics().stream()
        .anyMatch(item -> item.code().equals("archive_decompression_limit")));
    assertTrue(decompressionState.diagnostics().stream()
        .noneMatch(item -> item.code().equals("descriptor_enumeration_limit")));
  }

  @Test
  void manyServiceEntriesWithinDecompressionBudgetComplete() throws IOException {
    String payload = "demo.ProviderImpl\n".repeat(102);
    Path archive = temp.resolve("many-services.jar");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      for (int index = 0; index < 2000; index++) {
        put(output, "META-INF/services/demo.Provider" + index, payload);
      }
    }

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state =
        new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorArchiveSource.scan(archive, descriptors, state);

    assertEquals(2000, descriptors.size(), Integer.toString(descriptors.size()));
    assertTrue(state.diagnostics().isEmpty(), state.diagnostics().toString());
  }

  @Test
  void readerSourceOrderingStarvationIsDiagnosedPerSource() throws IOException {
    Path root = temp.resolve("ordering");
    Path sourceRoot = root.resolve("src/main/java");
    Path resourceServices = root.resolve("src/main/resources/META-INF/services");
    Files.createDirectories(sourceRoot);
    Files.createDirectories(resourceServices);
    Files.createDirectories(root.resolve("target/classes"));
    Files.writeString(resourceServices.resolve("demo.Provider"), "demo.DefaultProvider\n");
    Path dependencies = root.resolve("deps");
    Files.createDirectories(dependencies);
    Path first = dependencies.resolve("first.jar");
    Path second = dependencies.resolve("second.jar");
    for (Path archive : List.of(first, second)) {
      try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
        put(output, "META-INF/services/demo.Dependency", "demo.DependencyProvider\n");
      }
    }

    AnalysisContext context = AnalysisContext.builder()
        .id("ordering")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("ordering")
        .javaRelease(17)
        .repositoryRoot(root)
        .applicationClasses(root.resolve("target/classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspath(List.of(first, second))
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();

    ServiceLoaderDescriptorSource.ScanState state =
        ServiceLoaderDescriptorSource.ScanState.withEnumerationBudget(200);
    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(
        context, SootUpInputLocationFactory.dependencyResolution(context), state);

    assertEquals(1, scan.descriptors().size(), scan.descriptors().toString());
    assertEquals("demo.DefaultProvider", scan.descriptors().get(0).providers().get(0));
    assertEquals(2, scan.diagnostics().stream()
        .filter(item -> item.code().equals("descriptor_enumeration_limit")).count(),
        scan.diagnostics().toString());
  }

  @Test
  void defaultScanStateBudgetsAreTheAdoptedValues() {
    ServiceLoaderDescriptorSource.ScanState state =
        new ServiceLoaderDescriptorSource.ScanState();
    assertEquals(2L * 1024 * 1024 * 1024, state.remainingEnumerated());
    assertEquals(16L * 1024 * 1024, state.remainingRetained());
    assertEquals(ServiceLoaderDescriptorBudget.MAX_ENUMERATION_BYTES,
        state.remainingEnumerated());
    assertEquals(ServiceLoaderDescriptorBudget.MAX_RETAINED_DESCRIPTOR_BYTES,
        state.remainingRetained());
    assertEquals(0L, state.enumeratedBytes());
    assertEquals(0L, state.retainedBytes());
    assertThrows(IllegalArgumentException.class,
        () -> ServiceLoaderDescriptorSource.ScanState.withBudgets(0, 1, 1));
    assertThrows(IllegalArgumentException.class,
        () -> ServiceLoaderDescriptorSource.ScanState.withBudgets(1, 0, 1));
    assertThrows(IllegalArgumentException.class,
        () -> ServiceLoaderDescriptorSource.ScanState.withBudgets(1, 1, 0));
  }

  private static void writeClassicZip(Path archive, List<String> names, String firstContent)
      throws IOException {
    byte[] content = firstContent.getBytes(StandardCharsets.UTF_8);
    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
    crc.update(content);
    try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(archive));
        DataOutputStream output = new DataOutputStream(raw)) {
      int[] offsets = new int[names.size()];
      int position = 0;
      for (int index = 0; index < names.size(); index++) {
        byte[] name = names.get(index).getBytes(StandardCharsets.UTF_8);
        int size = index == 0 ? content.length : 0;
        long checksum = index == 0 ? crc.getValue() : 0;
        offsets[index] = position;
        writeLeInt(output, 0x04034b50);
        writeLeShort(output, 10);
        writeLeShort(output, 0);
        writeLeShort(output, 0);
        writeLeShort(output, 0);
        writeLeShort(output, 0);
        writeLeInt(output, (int) checksum);
        writeLeInt(output, size);
        writeLeInt(output, size);
        writeLeShort(output, name.length);
        writeLeShort(output, 0);
        output.write(name);
        if (index == 0) {
          output.write(content);
        }
        position += 30 + name.length + size;
      }
      int centralStart = position;
      for (int index = 0; index < names.size(); index++) {
        byte[] name = names.get(index).getBytes(StandardCharsets.UTF_8);
        int size = index == 0 ? content.length : 0;
        long checksum = index == 0 ? crc.getValue() : 0;
        writeLeInt(output, 0x02014b50);
        writeLeShort(output, 10);
        writeLeShort(output, 10);
        writeLeShort(output, 0);
        writeLeShort(output, 0);
        writeLeShort(output, 0);
        writeLeShort(output, 0);
        writeLeInt(output, (int) checksum);
        writeLeInt(output, size);
        writeLeInt(output, size);
        writeLeShort(output, name.length);
        writeLeShort(output, 0);
        writeLeShort(output, 0);
        writeLeShort(output, 0);
        writeLeShort(output, 0);
        writeLeInt(output, 0);
        writeLeInt(output, offsets[index]);
        output.write(name);
        position += 46 + name.length;
      }
      writeLeInt(output, 0x06054b50);
      writeLeShort(output, 0);
      writeLeShort(output, 0);
      writeLeShort(output, names.size());
      writeLeShort(output, names.size());
      writeLeInt(output, position - centralStart);
      writeLeInt(output, centralStart);
      writeLeShort(output, 0);
    }
  }

  private static int lastIndexOf(byte[] haystack, byte[] needle) {
    for (int index = haystack.length - needle.length; index >= 0; index--) {
      boolean match = true;
      for (int offset = 0; offset < needle.length; offset++) {
        if (haystack[index + offset] != needle[offset]) {
          match = false;
          break;
        }
      }
      if (match) {
        return index;
      }
    }
    return -1;
  }

  private static void writeLeInt(DataOutputStream output, int value) throws IOException {
    output.writeByte(value);
    output.writeByte(value >>> 8);
    output.writeByte(value >>> 16);
    output.writeByte(value >>> 24);
  }

  private static void writeLeShort(DataOutputStream output, int value) throws IOException {
    output.writeByte(value);
    output.writeByte(value >>> 8);
  }

  private static void putStored(ZipOutputStream output, String name) throws IOException {
    ZipEntry entry = new ZipEntry(name);
    entry.setMethod(ZipEntry.STORED);
    entry.setSize(0);
    entry.setCompressedSize(0);
    entry.setCrc(0);
    output.putNextEntry(entry);
    output.closeEntry();
  }

  private static void put(ZipOutputStream output, String name, String value) throws IOException {
    output.putNextEntry(new ZipEntry(name));
    output.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    output.closeEntry();
  }
}
