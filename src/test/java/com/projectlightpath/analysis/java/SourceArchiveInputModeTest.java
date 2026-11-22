package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Equivalence-class tests for the explicit input-mode contract (issue #42
 * scope 1): mode selection is caller/SBOM state, never guessed, and every
 * invalid mode/input combination fails closed at build or load time.
 */
class SourceArchiveInputModeTest {
  @TempDir Path temp;

  private final ObjectMapper json = new ObjectMapper();

  private Path existingArchive() throws IOException {
    Path archive = temp.resolve("application-sources.jar");
    Files.writeString(archive, "zip bytes");
    return archive;
  }

  private AnalysisContext.Builder baseBuilder() throws IOException {
    Path classes = temp.resolve("classes");
    Files.createDirectories(classes);
    return AnalysisContext.builder()
        .id("input-mode-contract")
        .framework(AnalysisContext.Framework.QUARKUS)
        .frameworkVersion("3.40.1")
        .fixtureId("input-mode-contract")
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

  // --- default mode -------------------------------------------------------

  @Test
  void defaultModeIsSourceTree() throws IOException {
    AnalysisContext context = sourceTreeBuilder().build();

    assertEquals(AnalysisContext.InputMode.SOURCE_TREE, context.inputMode());
    assertTrue(context.sourceArchives().isEmpty());
  }

  // --- SOURCE_ARCHIVE invariants ------------------------------------------

  @Test
  void sourceArchiveModeRequiresAnArchive() throws IOException {
    AnalysisContext.Builder builder = sourceTreeBuilder()
        .inputMode(AnalysisContext.InputMode.SOURCE_ARCHIVE);

    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  void sourceArchiveModeSatisfiesTheRequiredRootWithArchives() throws IOException {
    Path archive = existingArchive();
    AnalysisContext context = baseBuilder()
        .inputMode(AnalysisContext.InputMode.SOURCE_ARCHIVE)
        .dependencyClasspathRequired(false)
        .sourceArchive(archive)
        .build();

    assertEquals(AnalysisContext.InputMode.SOURCE_ARCHIVE, context.inputMode());
    assertEquals(
        List.of(archive.toAbsolutePath().normalize()), context.sourceArchives());
  }

  @Test
  void sourceArchiveModeRejectsSourceRoots() throws IOException {
    AnalysisContext.Builder builder = sourceTreeBuilder()
        .inputMode(AnalysisContext.InputMode.SOURCE_ARCHIVE)
        .sourceArchive(existingArchive());

    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  void sourceArchiveModeRejectsGeneratedSourceRoots() throws IOException {
    Path generated = temp.resolve("target/generated-sources");
    Files.createDirectories(generated);
    AnalysisContext.Builder builder = baseBuilder()
        .inputMode(AnalysisContext.InputMode.SOURCE_ARCHIVE)
        .dependencyClasspathRequired(false)
        .sourceArchive(existingArchive())
        .generatedSourceRoot(
            "target/generated-sources", generated, AnalysisContext.Scope.PRODUCTION, true, false);

    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  void sourceArchiveModeRejectsDependencyClasspathOverlap() throws IOException {
    Path archive = existingArchive();
    AnalysisContext.Builder builder = baseBuilder()
        .inputMode(AnalysisContext.InputMode.SOURCE_ARCHIVE)
        .dependencyClasspathRequired(false)
        .sourceArchive(archive)
        .addDependencyClasspath(archive);

    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  void sourceArchiveModeAcceptsIndependentDependencyBinaries() throws IOException {
    Path binary = temp.resolve("application-1.0.0.jar");
    Files.writeString(binary, "binary jar bytes");
    AnalysisContext context = baseBuilder()
        .inputMode(AnalysisContext.InputMode.SOURCE_ARCHIVE)
        .dependencyClasspathRequired(false)
        .sourceArchive(existingArchive())
        .addDependencyClasspath(binary)
        .build();

    assertEquals(1, context.dependencyClasspath().size());
  }

  // --- SOURCE_TREE invariants ---------------------------------------------

  @Test
  void sourceTreeModeRejectsSourceArchives() throws IOException {
    AnalysisContext.Builder builder = sourceTreeBuilder()
        .inputMode(AnalysisContext.InputMode.SOURCE_TREE)
        .sourceArchive(existingArchive());

    assertThrows(IllegalArgumentException.class, builder::build);
  }

  // --- BINARY_ARCHIVE invariants ------------------------------------------

  @Test
  void binaryArchiveModeRejectsSourceRoots() throws IOException {
    Path binary = temp.resolve("application-1.0.0.jar");
    Files.writeString(binary, "binary jar bytes");
    AnalysisContext.Builder builder = baseBuilder()
        .inputMode(AnalysisContext.InputMode.BINARY_ARCHIVE)
        .dependencyClasspathRequired(false)
        .addDependencyClasspath(binary)
        .sourceRoot("src/main/java", temp.resolve("src/main/java"),
            AnalysisContext.Scope.PRODUCTION, true);

    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  void binaryArchiveModeRequiresADependencyEntry() throws IOException {
    AnalysisContext.Builder builder = baseBuilder()
        .inputMode(AnalysisContext.InputMode.BINARY_ARCHIVE)
        .dependencyClasspathRequired(false);

    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  void binaryArchiveModeBuildsWithDependencyEntriesOnly() throws IOException {
    Path binary = temp.resolve("application-1.0.0.jar");
    Files.writeString(binary, "binary jar bytes");
    AnalysisContext context = baseBuilder()
        .inputMode(AnalysisContext.InputMode.BINARY_ARCHIVE)
        .dependencyClasspathRequired(false)
        .addDependencyClasspath(binary)
        .build();

    assertEquals(AnalysisContext.InputMode.BINARY_ARCHIVE, context.inputMode());
    assertTrue(context.sourceRoots().isEmpty());
  }

  // --- serialized handoff -------------------------------------------------

  @Test
  void loaderParsesInputModeAndSourceArchives() throws IOException {
    Path archive = existingArchive();
    Path contextFile = writeContext(root -> {
      root.put("inputMode", "SOURCE_ARCHIVE");
      root.putArray("sourceArchives").add(archive.toString());
    });

    AnalysisContext context = AnalysisContextLoader.load(contextFile);

    assertEquals(AnalysisContext.InputMode.SOURCE_ARCHIVE, context.inputMode());
    assertEquals(
        List.of(archive.toAbsolutePath().normalize()), context.sourceArchives());
  }

  @Test
  void loaderDefaultsToSourceTreeWithoutInputMode() throws IOException {
    Path contextFile = writeContext(root -> {
      root.remove("sourceRoots");
    });

    AnalysisContext context = AnalysisContextLoader.load(contextFile);

    assertEquals(AnalysisContext.InputMode.SOURCE_TREE, context.inputMode());
  }

  @Test
  void loaderRejectsUnknownInputMode() throws IOException {
    Path contextFile = writeContext(root -> {
      root.put("inputMode", "GUESSED_FROM_CONTENTS");
    });

    assertThrows(IllegalArgumentException.class, () -> AnalysisContextLoader.load(contextFile));
  }

  @Test
  void loaderRejectsMissingSourceArchiveFile() throws IOException {
    Path contextFile = writeContext(root -> {
      root.put("inputMode", "SOURCE_ARCHIVE");
      root.putArray("sourceArchives").add(temp.resolve("missing-sources.jar").toString());
    });

    assertThrows(IllegalArgumentException.class, () -> AnalysisContextLoader.load(contextFile));
  }

  @Test
  void loaderRejectsWildcardSourceArchive() throws IOException {
    Path contextFile = writeContext(root -> {
      root.put("inputMode", "SOURCE_ARCHIVE");
      root.putArray("sourceArchives").add(temp.resolve("lib/*").toString());
    });

    assertThrows(IllegalArgumentException.class, () -> AnalysisContextLoader.load(contextFile));
  }

  @Test
  void loaderRejectsSymbolicLinkSourceArchive() throws IOException {
    Path real = existingArchive();
    Path link = temp.resolve("linked-sources.jar");
    Files.createSymbolicLink(link, real.getFileName());
    Path contextFile = writeContext(root -> {
      root.put("inputMode", "SOURCE_ARCHIVE");
      root.putArray("sourceArchives").add(link.toString());
    });

    assertThrows(IllegalArgumentException.class, () -> AnalysisContextLoader.load(contextFile));
  }

  @Test
  void loaderRejectsSourceArchiveDocumentWithSourceRoots() throws IOException {
    Path archive = existingArchive();
    Path contextFile = writeContext(root -> {
      root.put("inputMode", "SOURCE_ARCHIVE");
      root.putArray("sourceArchives").add(archive.toString());
    });

    assertThrows(IllegalArgumentException.class, () -> AnalysisContextLoader.load(contextFile));
  }

  private Path writeContext(java.util.function.Consumer<ObjectNode> mutation) throws IOException {
    Path sourceRoot = temp.resolve("src/main/java");
    Path classes = temp.resolve("classes");
    Files.createDirectories(sourceRoot);
    Files.createDirectories(classes);
    Files.writeString(sourceRoot.resolve("Application.java"),
        "package demo; class Application {}\n");
    ObjectNode root = json.createObjectNode();
    root.put("schemaVersion", "0.1.0");
    root.put("id", "input-mode-contract");
    root.put("fixtureId", "input-mode-contract");
    root.put("framework", "quarkus");
    root.put("frameworkVersion", "3.40.1");
    root.put("javaRelease", 17);
    root.put("repositoryRoot", temp.toString());
    root.put("selectedProfile", "production");
    root.put("analysisScope", "production");
    root.put("applicationClasses", classes.toString());
    root.put("dependencyClasspathRequired", false);

    ObjectNode sourceRootNode = root.putArray("sourceRoots").addObject();
    sourceRootNode.put("declaredPath", "src/main/java");
    sourceRootNode.put("path", sourceRoot.toString());
    sourceRootNode.put("scope", "production");
    sourceRootNode.put("generated", false);
    sourceRootNode.put("included", true);
    sourceRootNode.put("required", true);

    ObjectNode provenance = root.putObject("provenance");
    provenance.put("sourceRepository", "https://example.test/application.git");
    provenance.put("sourceReference", "test-revision");
    provenance.put("sourceRevision", "test-revision");
    provenance.put("sourceFingerprint", "source-fingerprint");
    provenance.put("generatedSourceFingerprint", "generated-fingerprint");
    provenance.put("dependencyFingerprint", "dependency-fingerprint");
    provenance.put("gitRevision", "test-revision");
    provenance.put("providerRuntime", "test-runtime");
    root.putArray("limitations");

    mutation.accept(root);
    assertNotNull(root);
    Path contextFile = temp.resolve(
        "analysis-context-" + Math.abs(mutation.hashCode()) + ".json");
    Files.writeString(contextFile, json.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    return contextFile;
  }
}
