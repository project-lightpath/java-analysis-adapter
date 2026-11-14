package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceLoaderWarDescriptorTest {
  @TempDir Path temp;

  @Test
  void recordsSkippedWarMetadataAndPreservesPossibleResolution() throws IOException {
    Path sourceRoot = temp.resolve("src/main/java");
    Path classes = temp.resolve("target/classes");
    Path source = sourceRoot.resolve("demo/App.java");
    Files.createDirectories(source.getParent());
    Files.createDirectories(classes);
    Files.writeString(source, "package demo;\n"
        + "import java.util.ServiceLoader;\n"
        + "class App {\n"
        + "  void audit() { ServiceLoader.load(Provider.class).findFirst()"
        + ".ifPresent(value -> value.record()); }\n"
        + "}\n");
    Path war = temp.resolve("provider.war");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(war))) {
      put(output, "META-INF/services/demo.Provider", "demo.DefaultProvider\n");
      put(output, "WEB-INF/classes/META-INF/services/demo.Provider", "demo.NestedProvider\n");
      put(output, "WEB-INF/lib/nested-provider.jar", "not scanned\n");
    }

    AnalysisContext context = AnalysisContext.builder()
        .id("war-service-loader")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("war-descriptor-test")
        .javaRelease(17)
        .repositoryRoot(temp)
        .applicationClasses(classes)
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspath(List.of(war))
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();

    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(context);

    assertEquals(1, scan.descriptors().size(), scan.descriptors().toString());
    assertEquals("demo.DefaultProvider", scan.descriptors().get(0).providers().get(0));
    assertTrue(scan.diagnostics().stream().anyMatch(item ->
        item.code().equals("war_nested_service_metadata_skipped")), scan.diagnostics().toString());

    CallEdge edge = new CallEdge(
        new MethodSymbol("demo.App", "audit", "()V"),
        new MethodSymbol("demo.DefaultProvider", "record", "()V"),
        4,
        "src/main/java/demo/App.java",
        SemanticCallFacts.Certainty.DEFINITE,
        "service_loader");
    ServiceLoaderDescriptorScanner.Resolution resolution = ServiceLoaderDescriptorScanner.resolve(
        context, edge, scan, new SourceMetadataScanner(context));
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, resolution.certainty());
    assertTrue(resolution.evidence().stream().anyMatch(evidence ->
        evidence.kind().equals("service_loader_scan_diagnostic")), resolution.evidence().toString());
  }

  @Test
  void identicalOutputAndResourceDescriptorsRemainUnique() throws IOException {
    Path sourceRoot = temp.resolve("src/main/java");
    Path resources = temp.resolve("src/main/resources/META-INF/services");
    Path classes = temp.resolve("target/classes/META-INF/services");
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
        .id("duplicate-service-loader")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("duplicate-service-loader")
        .javaRelease(17)
        .repositoryRoot(temp)
        .applicationClasses(temp.resolve("target/classes"))
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
  }

  @Test
  void capsDiagnosticsForLargeWarMetadata() throws IOException {
    Path war = temp.resolve("large-metadata.war");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(war))) {
      for (int index = 0; index < 3000; index++) {
        put(output, "WEB-INF/classes/META-INF/services/demo.Provider" + index,
            "demo.Provider\n");
      }
    }

    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new java.util.ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state = new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorArchiveSource.scan(war, descriptors, state);

    long skipped = state.diagnostics().stream()
        .filter(item -> item.code().equals("war_nested_service_metadata_skipped"))
        .count();
    long suppressed = state.diagnostics().stream()
        .filter(item -> item.code().equals("war_nested_service_metadata_skipped_limit"))
        .count();
    assertEquals(1, skipped, state.diagnostics().toString());
    assertEquals(1, suppressed, state.diagnostics().toString());
    assertTrue(state.diagnostics().size() <= 3, state.diagnostics().toString());
  }

  private static void put(ZipOutputStream output, String name, String value) throws IOException {
    output.putNextEntry(new ZipEntry(name));
    output.write(value.getBytes(StandardCharsets.UTF_8));
    output.closeEntry();
  }
}
