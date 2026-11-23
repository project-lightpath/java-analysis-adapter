package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Equivalence-class tests for the Spring Data delegation promotion ladder
 * (issue #41 Edge 2, FLPATH-5075 rule) and the scan-global diagnostics
 * surface on SemanticCallFacts.
 */
class SpringDataDelegationLadderTest {
  @TempDir Path temp;

  @Test
  void crudDelegationIsDefiniteWithoutOverrideMechanisms() throws IOException {
    SemanticCallFacts facts = analyze(List.of("""
        package demo;
        public interface AccountRepository
            extends org.springframework.data.jpa.repository.JpaRepository<Account, Long> {
          Account findByAccountNumber(String number);
        }
        """));
    SemanticCallFacts.Fact save = delegationFact(facts, "demo.AccountRepository#save");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, save.certainty());
    assertEquals(
        "org.springframework.data.jpa.repository.support.SimpleJpaRepository#save",
        save.callee());
    SemanticCallFacts.Fact derived = delegationFact(
        facts, "demo.AccountRepository#findByAccountNumber(String)");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, derived.certainty());
  }

  @Test
  void inScopeRepositoryBaseClassBacksCrudAsPossible() throws IOException {
    SemanticCallFacts facts = analyze(List.of("""
        package demo;
        public class AuditedRepositoryImpl<T> { }
        """, """
        package demo;
        import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
        @EnableJpaRepositories(repositoryBaseClass = AuditedRepositoryImpl.class)
        public class AppConfig { }
        """, """
        package demo;
        public interface AccountRepository
            extends org.springframework.data.jpa.repository.JpaRepository<Account, Long> { }
        """));
    SemanticCallFacts.Fact save = delegationFact(facts, "demo.AccountRepository#save");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, save.certainty());
    assertEquals("demo.AuditedRepositoryImpl#save", save.callee());
  }

  @Test
  void outOfScopeRepositoryBaseClassAbstainsWithDiagnostic() throws IOException {
    SemanticCallFacts facts = analyze(List.of("""
        package demo;
        import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
        @EnableJpaRepositories(repositoryBaseClass = com.example.MissingBase.class)
        public class AppConfig { }
        """, """
        package demo;
        public interface AccountRepository
            extends org.springframework.data.jpa.repository.JpaRepository<Account, Long> { }
        """));
    SemanticCallFacts.Fact save = delegationFact(facts, "demo.AccountRepository#save");
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED, save.certainty());
    assertEquals("com.example.MissingBase#save", save.callee());
    assertTrue(save.evidence().stream().anyMatch(evidence ->
        "spring_data_backing_selection_out_of_scope"
            .equals(evidence.attributes().get("diagnosticCode"))),
        save.evidence().toString());
    assertEquals(1, facts.diagnostics().size(), facts.diagnostics().toString());
    assertEquals("spring_data_backing_selection_out_of_scope",
        facts.diagnostics().get(0).code());
    assertEquals("src/main/java/demo/AppConfig.java", facts.diagnostics().get(0).path());
  }

  @Test
  void diagnosticsBlockServiceLoaderDefinitePromotion() throws IOException {
    Path source = Files.createDirectories(temp.resolve("src/main/java"));
    Path classes = Files.createDirectories(temp.resolve("classes"));
    Path dependencies = Files.createDirectories(temp.resolve("deps"));
    Path provider = source.resolve("demo/Handler.java");
    Path implementation = source.resolve("demo/HandlerImpl.java");
    Path app = source.resolve("demo/App.java");
    write(provider, "package demo; public interface Handler { void record(); }\n");
    write(implementation,
        "package demo; public class HandlerImpl implements Handler { public void record() {} }\n");
    write(app, "package demo;\nimport java.util.ServiceLoader;\nclass App {\n"
        + "void call() { ServiceLoader.load(Handler.class).findFirst().ifPresent(h -> h.record()); }\n}\n");
    write(source.resolve("demo/AccountRepository.java"), """
        package demo;
        public interface AccountRepository
            extends org.springframework.data.jpa.repository.JpaRepository<Account, Long> { }
        """);
    write(source.resolve("demo/Account.java"), "package demo; public class Account {}\n");
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17",
        "-g", "-d", classes.toString(), provider.toString(), implementation.toString(),
        app.toString(), source.resolve("demo/AccountRepository.java").toString(),
        source.resolve("demo/Account.java").toString()));
    Path unique = dependencies.resolve("unique.jar");
    jar(unique, "demo.HandlerImpl");

    SemanticCallFacts clean = new JavaAnalysisAdapter().analyze(
        context(source, classes, List.of(unique)));
    assertTrue(clean.providerFacts().stream()
        .filter(item -> item.relationshipType().equals("service_loader"))
        .allMatch(item -> item.certainty() == SemanticCallFacts.Certainty.DEFINITE),
        clean.providerFacts().toString());

    write(source.resolve("demo/AppConfig.java"), """
        package demo;
        import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
        @EnableJpaRepositories(repositoryBaseClass = com.example.MissingBase.class)
        public class AppConfig { }
        """);
    SemanticCallFacts blocked = new JavaAnalysisAdapter().analyze(
        context(source, classes, List.of(unique)));
    assertTrue(blocked.providerFacts().stream()
        .filter(item -> item.relationshipType().equals("service_loader"))
        .allMatch(item -> item.certainty() == SemanticCallFacts.Certainty.POSSIBLE),
        blocked.providerFacts().toString());
    assertFalse(blocked.diagnostics().isEmpty(), blocked.diagnostics().toString());
  }

  private SemanticCallFacts.Fact delegationFact(SemanticCallFacts facts, String caller) {
    return facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("delegated_implementation"))
        .filter(item -> item.caller().equals(caller))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.frameworkFacts().toString()));
  }

  private SemanticCallFacts analyze(List<String> sources) throws IOException {
    Path sourceRoot = temp.resolve("ladder/src/main/java");
    write(sourceRoot.resolve("demo/Account.java"), "package demo; public class Account {}\n");
    for (int index = 0; index < sources.size(); index++) {
      write(sourceRoot.resolve("demo/Source" + index + ".java"), sources.get(index));
    }
    return new JavaAnalysisAdapter().analyze(
        context(sourceRoot, temp.resolve("ladder/classes"), List.of()),
        AnalysisGraph.empty());
  }

  private AnalysisContext context(Path source, Path classes, List<Path> dependencies) {
    return AnalysisContext.builder()
        .id("spring-boot-production")
        .framework(AnalysisContext.Framework.SPRING_BOOT)
        .frameworkVersion("2.7.18")
        .fixtureId("fsi-spring-boot")
        .javaRelease(17)
        .repositoryRoot(temp)
        .applicationClasses(classes)
        .selectedProfile("production")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", source, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspath(dependencies)
        .dependencyClasspathRequired(false)
        .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
            "spring-bean-annotations", "source_annotations",
            AnalysisContext.MetadataStatus.OBSERVED, "src/main/java"))
        .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
            "spring-data-repository-contract", "source_interface_metadata",
            AnalysisContext.MetadataStatus.OBSERVED, "src/main/java"))
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
  }

  private static void jar(Path file, String provider) throws IOException {
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(file))) {
      output.putNextEntry(new ZipEntry("META-INF/services/demo.Handler"));
      output.write((provider + "\n").getBytes(StandardCharsets.UTF_8));
      output.closeEntry();
    }
  }

  private static void write(Path file, String contents) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, contents);
  }
}
