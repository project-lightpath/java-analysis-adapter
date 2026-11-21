package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Behavioral boundaries for selected paths and repeated use of one analysis context. */
class ServiceLoaderSafetyLifetimeTest {
  @TempDir Path temp;

  @Test
  void derivedResourcesRejectEscapingParents() throws IOException {
    for (String component : List.of("resources", "resources/META-INF")) {
      Path project = temp.resolve(component.replace('/', '-'));
      Path source = Files.createDirectories(project.resolve("src/main/java"));
      Path classes = Files.createDirectories(project.resolve("classes"));
      Path outside = Files.createDirectories(project.resolve("outside"));
      Path link = source.getParent().resolve(component);
      Files.createDirectories(link.getParent());
      Files.createSymbolicLink(link, outside);
      Path services = outside.resolve(component.endsWith("META-INF") ? "services" : "META-INF/services");
      write(services.resolve("demo.Provider"), "demo.EscapedSentinel\n");
      ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(
          context(project, source, classes, List.of()));
      assertTrue(scan.descriptors().isEmpty(), scan.descriptors().toString());
      assertFalse(scan.diagnostics().isEmpty(), "an existing rejected input must be diagnosed");
    }
  }

  @Test
  void classpathMetadataRejectsEscapingParentsForApplicationAndDependency() throws IOException {
    Path project = Files.createDirectories(temp.resolve("project"));
    Path source = Files.createDirectories(project.resolve("src/main/java"));
    Path classes = Files.createDirectories(project.resolve("classes"));
    Path dependency = Files.createDirectories(temp.resolve("external-dependency"));
    for (Path entry : List.of(classes, dependency)) {
      Path outside = Files.createDirectories(temp.resolve(entry.getFileName() + "-outside"));
      write(outside.resolve("services/demo.Provider"), "demo.EscapedSentinel\n");
      Files.createSymbolicLink(entry.resolve("META-INF"), outside);
    }
    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(
        context(project, source, classes, List.of(dependency)));
    assertTrue(scan.descriptors().isEmpty(), scan.descriptors().toString());
    assertEquals(2, scan.diagnostics().size(), scan.diagnostics().toString());
  }

  @Test
  void selectedSymlinkAncestorsAndExternalDirectoriesPreserveProvenance() throws IOException {
    Path real = Files.createDirectories(temp.resolve("real"));
    Path alias = Files.createSymbolicLink(temp.resolve("alias"), real);
    Path source = Files.createDirectories(alias.resolve("src/main/java"));
    Path classes = Files.createDirectories(alias.resolve("classes"));
    Path dependency = Files.createDirectories(temp.resolve("external"));
    write(source.getParent().resolve("resources/META-INF/services/demo.Provider"), "demo.SourceProvider\n");
    write(dependency.resolve("META-INF/services/demo.Other"), "demo.ExternalProvider\n");
    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(
        context(alias, source, classes, List.of(dependency)));
    assertEquals(2, scan.descriptors().size(), scan.descriptors().toString());
    assertTrue(scan.diagnostics().isEmpty(), scan.diagnostics().toString());
    assertTrue(scan.descriptors().stream().anyMatch(item ->
        item.path().equals("src/main/resources/META-INF/services/demo.Provider")));
    assertTrue(scan.descriptors().stream().anyMatch(item ->
        item.path().equals(dependency + "/META-INF/services/demo.Other")));
  }

  @Test
  void derivedResourcesUseTheLexicalSourceParentAndOnlyIncludedActiveRoots() throws IOException {
    Path sourceParent = Files.createDirectories(temp.resolve("src/main"));
    Path target = Files.createDirectories(temp.resolve("elsewhere/java"));
    Path source = Files.createSymbolicLink(sourceParent.resolve("java"), target);
    Path classes = Files.createDirectories(temp.resolve("classes"));
    write(sourceParent.resolve("resources/META-INF/services/demo.Selected"), "demo.SelectedProvider\n");
    write(target.getParent().resolve("resources/META-INF/services/demo.Unselected"), "demo.EscapedSentinel\n");
    Path testRoot = Files.createDirectories(temp.resolve("src/test/java"));
    write(testRoot.getParent().resolve("resources/META-INF/services/demo.Test"), "demo.EscapedSentinel\n");
    Path excluded = Files.createDirectories(temp.resolve("excluded/java"));
    write(excluded.getParent().resolve("resources/META-INF/services/demo.Excluded"), "demo.EscapedSentinel\n");
    Path generated = Files.createDirectories(temp.resolve("generated/java"));
    write(generated.getParent().resolve("resources/META-INF/services/demo.Generated"), "demo.GeneratedProvider\n");
    AnalysisContext context = builder(temp, source, classes, List.of())
        .sourceRoot("src/test/java", testRoot, AnalysisContext.Scope.TEST, true)
        .generatedSourceRoot("excluded/java", excluded, AnalysisContext.Scope.PRODUCTION, false, false)
        .generatedSourceRoot("generated/java", generated, AnalysisContext.Scope.PRODUCTION, true, true)
        .build();
    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(context);
    assertEquals(Set.of("demo.Selected", "demo.Generated"), scan.descriptors().stream()
        .map(ServiceLoaderDescriptorScanner.Descriptor::serviceType).collect(java.util.stream.Collectors.toSet()));
    assertTrue(scan.diagnostics().isEmpty(), scan.diagnostics().toString());
  }

  @Test
  void rejectedSymlinkLeavesDiagnoseAndDowngradeWithoutLosingSafeEvidence() throws IOException {
    Path source = Files.createDirectories(temp.resolve("src/main/java"));
    Path classes = Files.createDirectories(temp.resolve("classes"));
    write(source.resolve("demo/App.java"), "package demo;\nimport java.util.ServiceLoader;\n"
        + "class App { void call() { ServiceLoader.load(Provider.class).findFirst()"
        + ".ifPresent(p -> p.record()); } }\n");
    write(source.resolve("demo/Provider.java"), "package demo; interface Provider { void record(); }\n");
    Path services = classes.resolve("META-INF/services");
    write(services.resolve("demo.Provider"), "demo.SafeProvider\n");
    Path sentinel = temp.resolve("sentinel");
    Files.writeString(sentinel, "demo.EscapedSentinel\n");
    Files.createSymbolicLink(services.resolve("demo.Rejected"), sentinel);
    Files.createSymbolicLink(classes.resolve("module-info.class"), sentinel);
    Path resources = source.getParent().resolve("resources/META-INF/services");
    Files.createDirectories(resources);
    Files.createSymbolicLink(resources.resolve("demo.Rejected"), sentinel);
    AnalysisContext context = context(temp, source, classes, List.of());
    ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(context);
    assertEquals(1, scan.descriptors().size(), scan.descriptors().toString());
    assertEquals(List.of("demo.SafeProvider"), scan.descriptors().get(0).providers());
    assertEquals(3, scan.diagnostics().size(), scan.diagnostics().toString());
    CallEdge edge = new CallEdge(new MethodSymbol("demo.App", "call", "()V"),
        new MethodSymbol("demo.SafeProvider", "record", "()V"), 3,
        "src/main/java/demo/App.java", SemanticCallFacts.Certainty.DEFINITE, "service_loader");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, ServiceLoaderDescriptorScanner.resolve(
        context, edge, scan, new SourceMetadataScanner(context)).certainty());
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, ServiceLoaderDescriptorScanner.resolve(
        context, edge, new ServiceLoaderDescriptorScanner.Scan(scan.descriptors()),
        new SourceMetadataScanner(context)).certainty(), "diagnostics must cause the downgrade");
  }

  @Test
  void unsupportedNoFollowOpensDiagnoseAllReadersWithoutFollowingFallback() throws IOException {
    // The JDK ZIP filesystem deterministically rejects NOFOLLOW_LINKS on input streams.
    // This exercises provider failure handling, not additional production archive support.
    Path archive = temp.resolve("no-follow-provider.zip");
    Path safeDependency = Files.createDirectories(temp.resolve("safe-dependency"));
    write(safeDependency.resolve("META-INF/services/demo.Safe"), "demo.SafeProvider\n");
    try (var filesystem = FileSystems.newFileSystem(
        java.net.URI.create("jar:" + archive.toUri()), Map.of("create", "true"))) {
      Path repository = Files.createDirectories(filesystem.getPath("/project"));
      Path source = Files.createDirectories(repository.resolve("src/main/java"));
      Path classes = Files.createDirectories(repository.resolve("classes"));
      Path descriptor = classes.resolve("META-INF/services/demo.Provider");
      write(descriptor, "demo.EscapedSentinel\n");
      write(source.getParent().resolve("resources/META-INF/services/demo.Resource"), "demo.EscapedSentinel\n");
      write(classes.resolve("module-info.class"), "demo.EscapedSentinel\n");
      assertThrows(UnsupportedOperationException.class,
          () -> Files.newInputStream(descriptor, LinkOption.NOFOLLOW_LINKS));
      ServiceLoaderDescriptorScanner.Scan scan = ServiceLoaderDescriptorScanner.scan(
          context(repository, source, classes, List.of(safeDependency)));
      assertEquals(1, scan.descriptors().size(), scan.descriptors().toString());
      assertEquals(List.of("demo.SafeProvider"), scan.descriptors().get(0).providers());
      assertEquals(3, scan.diagnostics().size(), scan.diagnostics().toString());
      assertTrue(scan.diagnostics().stream().allMatch(item -> item.code().equals("descriptor_read_error")),
          scan.diagnostics().toString());
    }
  }

  @Test
  void repeatedStandaloneCallsRefreshWildcardMembership() throws IOException {
    Path source = Files.createDirectories(temp.resolve("src/main/java"));
    Path classes = Files.createDirectories(temp.resolve("classes"));
    Path dependencies = Files.createDirectories(temp.resolve("deps"));
    Path first = dependencies.resolve("first.jar");
    jar(first, "demo.First");
    AnalysisContext context = context(temp, source, classes, List.of(dependencies.resolve("*")));
    assertEquals(List.of(first), SootUpInputLocationFactory.resolvedDependencyEntries(context));
    assertEquals(List.of("demo.First"), ServiceLoaderDescriptorScanner.scan(context).descriptors().get(0).providers());
    Path renamed = dependencies.resolve("renamed.jar");
    Files.move(first, renamed);
    Path added = dependencies.resolve("added.jar");
    jar(added, "demo.Added");
    assertEquals(Set.of(renamed, added), Set.copyOf(SootUpInputLocationFactory.resolvedDependencyEntries(context)));
    assertEquals(2, ServiceLoaderDescriptorScanner.scan(context).descriptors().size());
    Files.delete(renamed);
    assertEquals(List.of(added), SootUpInputLocationFactory.resolvedDependencyEntries(context));
    assertEquals(List.of("demo.Added"), ServiceLoaderDescriptorScanner.scan(context).descriptors().get(0).providers());
    assertEquals(Set.of(added), Set.copyOf(SootUpInputLocationFactory.createView(context).dependencyEntries().values()));
  }

  @Test
  void truncatedWildcardCanRecoverOnTheSameContext() throws IOException {
    Path source = Files.createDirectories(temp.resolve("src/main/java"));
    Path classes = Files.createDirectories(temp.resolve("classes"));
    Path dependencies = Files.createDirectories(temp.resolve("deps"));
    for (int index = 0; index <= SootUpInputLocationFactory.MAX_DEPENDENCY_ENTRIES; index++) {
      Files.writeString(dependencies.resolve("noise-" + index), "");
    }
    AnalysisContext context = context(temp, source, classes, List.of(dependencies.resolve("*")));
    assertThrows(IllegalArgumentException.class, () -> new SootUpBaseGraphProvider().analyze(context));
    assertFalse(ServiceLoaderDescriptorScanner.scan(context).diagnostics().isEmpty());
    try (var entries = Files.list(dependencies)) {
      for (Path entry : entries.toList()) {
        Files.delete(entry);
      }
    }
    Path valid = dependencies.resolve("valid.jar");
    jar(valid, "demo.Valid");
    assertEquals(List.of(valid), SootUpInputLocationFactory.resolvedDependencyEntries(context));
    assertTrue(ServiceLoaderDescriptorScanner.scan(context).diagnostics().isEmpty());
    assertEquals(Set.of(valid), Set.copyOf(SootUpInputLocationFactory.createView(context).dependencyEntries().values()));
  }

  @Test
  void prebuiltGraphWithoutServiceLoaderDoesNotExpandDependencies() throws IOException {
    Path source = Files.createDirectories(temp.resolve("src/main/java"));
    Path classes = Files.createDirectories(temp.resolve("classes"));
    Path dependencies = Files.createDirectories(temp.resolve("deps"));
    AnalysisContext context = context(temp, source, classes, List.of(dependencies.resolve("*")));
    Files.delete(dependencies);
    Files.writeString(dependencies, "not a directory");
    assertTrue(new JavaAnalysisAdapter().analyze(context, AnalysisGraph.empty()).providerFacts().isEmpty());
    assertTrue(ProviderFactNormalizer.normalize(context, AnalysisGraph.empty(), () -> {
      throw new AssertionError("no ServiceLoader edge must mean no dependency expansion");
    }).isEmpty());
  }

  @Test
  void immutableSnapshotsAreSharedExplicitlyAndIsolatedAcrossConcurrentConsumers() throws IOException {
    Path source = Files.createDirectories(temp.resolve("src/main/java"));
    Path classes = Files.createDirectories(temp.resolve("classes"));
    Path dependencies = Files.createDirectories(temp.resolve("deps"));
    Path first = dependencies.resolve("first.jar");
    jar(first, "demo.First");
    AnalysisContext context = context(temp, source, classes, List.of(dependencies.resolve("*")));
    var earlier = SootUpInputLocationFactory.dependencyResolution(context);
    Path second = dependencies.resolve("second.jar");
    jar(second, "demo.Second");
    var later = SootUpInputLocationFactory.dependencyResolution(context);
    assertThrows(UnsupportedOperationException.class, () -> earlier.entries().add(second));
    var oldConsumer = CompletableFuture.supplyAsync(() -> {
      assertEquals(Set.of(first), Set.copyOf(SootUpInputLocationFactory.createView(context, earlier)
          .dependencyEntries().values()));
      return ServiceLoaderDescriptorScanner.scan(context, earlier);
    });
    var newConsumer = CompletableFuture.supplyAsync(() -> {
      assertEquals(Set.of(first, second), Set.copyOf(SootUpInputLocationFactory.createView(context, later)
          .dependencyEntries().values()));
      return ServiceLoaderDescriptorScanner.scan(context, later);
    });
    assertEquals(1, oldConsumer.join().descriptors().size());
    assertEquals(2, newConsumer.join().descriptors().size());
  }

  @Test
  void fullAndPrebuiltInvocationsRefreshDescriptorsUsingTheSameContext() throws IOException {
    Path source = Files.createDirectories(temp.resolve("src/main/java"));
    Path classes = Files.createDirectories(temp.resolve("classes"));
    Path provider = source.resolve("demo/Provider.java");
    Path implementation = source.resolve("demo/Impl.java");
    Path app = source.resolve("demo/App.java");
    write(provider, "package demo; public interface Provider { void record(); }\n");
    write(implementation, "package demo; public class Impl implements Provider { public void record() {} }\n");
    write(app, "package demo;\nimport java.util.ServiceLoader;\nclass App {\n"
        + "void call() { ServiceLoader.load(Provider.class).findFirst().ifPresent(p -> p.record()); }\n}\n");
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17", "-g",
        "-d", classes.toString(), provider.toString(), implementation.toString(), app.toString()));
    Path dependencies = Files.createDirectories(temp.resolve("deps"));
    Path first = dependencies.resolve("first.jar");
    jar(first, "demo.Impl");
    AnalysisContext context = context(temp, source, classes, List.of(dependencies.resolve("*")));
    JavaAnalysisAdapter adapter = new JavaAnalysisAdapter();
    SemanticCallFacts firstFacts = adapter.analyze(context);
    assertServiceCertainty(firstFacts, SemanticCallFacts.Certainty.DEFINITE);
    CallEdge edge = new CallEdge(new MethodSymbol("demo.App", "call", "()V"),
        new MethodSymbol("demo.Impl", "record", "()V"), 4,
        "src/main/java/demo/App.java", SemanticCallFacts.Certainty.DEFINITE, "service_loader");
    AnalysisGraph prebuilt = new AnalysisGraph(List.of(edge), List.of(), Set.of("demo.App", "demo.Impl"),
        "SootUp", "3.0.1", 2, 1);
    Path renamed = dependencies.resolve("renamed.jar");
    Files.move(first, renamed);
    Path second = dependencies.resolve("second.jar");
    jar(second, "demo.Other");
    SemanticCallFacts secondFacts = adapter.analyze(context);
    assertServiceCertainty(secondFacts, SemanticCallFacts.Certainty.POSSIBLE);
    assertFalse(secondFacts.providerFacts().stream().flatMap(item -> item.evidence().stream())
        .anyMatch(item -> item.path() != null && item.path().contains("first.jar")));
    assertServiceCertainty(adapter.analyze(context, prebuilt), SemanticCallFacts.Certainty.POSSIBLE);
    Files.delete(second);
    assertServiceCertainty(adapter.analyze(context), SemanticCallFacts.Certainty.DEFINITE);
    assertServiceCertainty(adapter.analyze(context, prebuilt), SemanticCallFacts.Certainty.DEFINITE);
  }

  private static void assertServiceCertainty(SemanticCallFacts facts, SemanticCallFacts.Certainty certainty) {
    var services = facts.providerFacts().stream()
        .filter(item -> item.relationshipType().equals("service_loader")).toList();
    assertFalse(services.isEmpty(), facts.providerFacts().toString());
    assertTrue(services.stream().allMatch(item -> item.certainty() == certainty), services.toString());
  }

  @Test
  void compiledEnhancedForCallsRetainOrdinaryEdgesWithoutServiceLoaderAttribution() throws IOException {
    Path source = Files.createDirectories(temp.resolve("src/main/java"));
    Path classes = Files.createDirectories(temp.resolve("classes"));
    Path provider = source.resolve("demo/Provider.java");
    Path implementation = source.resolve("demo/Impl.java");
    Path app = source.resolve("demo/App.java");
    write(provider, "package demo; public interface Provider { void record(); }\n");
    write(implementation, "package demo; public class Impl implements Provider { public void record() {} }\n");
    write(app, "package demo;\nimport java.util.ServiceLoader;\nclass App {\n"
        + "void single() { for (Provider p : ServiceLoader.load(Provider.class)) p.record(); }\n"
        + "void multi() {\n for (Provider p : ServiceLoader.load(Provider.class)) {\n p.record();\n }\n}\n}\n");
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17", "-g",
        "-d", classes.toString(), provider.toString(), implementation.toString(), app.toString()));
    write(source.getParent().resolve("resources/META-INF/services/demo.Provider"), "demo.Impl\n");
    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(context(temp, source, classes, List.of()));
    for (String method : List.of("single", "multi")) {
      var calls = facts.providerFacts().stream().filter(item -> item.caller().equals("demo.App#" + method)
          && item.callee().endsWith("#record")).toList();
      assertFalse(calls.isEmpty(), facts.providerFacts().toString());
      assertTrue(calls.stream().noneMatch(item -> item.relationshipType().equals("service_loader")));
      assertTrue(calls.stream().flatMap(item -> item.evidence().stream())
          .noneMatch(item -> item.kind().equals("service_loader_descriptor")));
    }
  }

  private static void write(Path file, String contents) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, contents);
  }

  private static void jar(Path file, String provider) throws IOException {
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(file))) {
      output.putNextEntry(new ZipEntry("META-INF/services/demo.Provider"));
      output.write((provider + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      output.closeEntry();
    }
  }

  @Test
  void deeplyDottedInvalidNameIsRejectedWithoutStackOverflow() {
    StringBuilder deep = new StringBuilder("demo");
    for (int index = 0; index < 5_000; index++) {
      deep.append(".Provider");
    }
    deep.append(".9invalid");
    String line = deep.toString();

    ServiceLoaderDescriptorSource.ProviderEntries parsed = ServiceLoaderDescriptorSource.providers(
        (line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));

    assertEquals(List.of(), parsed.providers());
    assertEquals(List.of(line), parsed.malformedEntries());
  }

  private static AnalysisContext context(Path repository, Path source, Path classes, List<Path> dependencies) {
    return builder(repository, source, classes, dependencies).build();
  }

  private static AnalysisContext.Builder builder(Path repository, Path source, Path classes, List<Path> dependencies) {
    return AnalysisContext.builder().id("safety-lifetime").framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1").fixtureId("safety-lifetime").javaRelease(17)
        .repositoryRoot(repository).applicationClasses(classes).selectedProfile("production")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", source, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspath(dependencies).dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting());
  }
}
