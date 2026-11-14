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

  private static void put(ZipOutputStream output, String name, String value) throws IOException {
    output.putNextEntry(new ZipEntry(name));
    output.write(value.getBytes(StandardCharsets.UTF_8));
    output.closeEntry();
  }
}
