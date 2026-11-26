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
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Equivalence-class tests for the Spring Data delegation promotion ladder
 * (issue #41 Edge 2, FLPATH-5075 rule) and the scan-global diagnostics
 * surface on SemanticCallFacts.
 *
 * <p>The methods share a resource lock: each runs a full adapter analysis
 * with an in-JVM javac index, and the default surefire lane's heap budget
 * (1 GB per parallel slot, four slots) was measured before this class
 * existed — serializing the class's javac load keeps the lane's peak
 * concurrent-javac footprint at its measured level.
 */
@ResourceLock("javac-adapter-analysis")
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
        facts, "demo.AccountRepository#findByAccountNumber");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, derived.certainty());
    // Issue #66 default-on delta (#23 canonical repository signatures):
    // the evidence attribute carries the javac-indexed parameter type.
    assertTrue(derived.evidence().stream().anyMatch(evidence ->
        "findByAccountNumber(java.lang.String)".equals(
            evidence.attributes().get("repositoryMethod"))),
        "the source signature stays on the evidence for overload-aware joins: "
            + derived.evidence());
  }

  @Test
  void redeclaredCrudMethodStaysPossible() throws IOException {
    SemanticCallFacts facts = analyze(List.of("""
        package demo;
        import org.springframework.data.jpa.repository.Query;
        public interface AccountRepository
            extends org.springframework.data.jpa.repository.JpaRepository<Account, Long> {
          @Query("select a from Account a")
          java.util.List<Account> findAll();
        }
        """));
    SemanticCallFacts.Fact findAll = delegationFact(facts, "demo.AccountRepository#findAll");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, findAll.certainty(),
        "a source redeclaration of a contract method is override evidence");
  }

  @Test
  void queryRedeclaredSingleParamMethodStaysPossible() throws IOException {
    SemanticCallFacts facts = analyze(List.of("""
        package demo;
        import org.springframework.data.jpa.repository.Query;
        public interface AccountRepository
            extends org.springframework.data.jpa.repository.JpaRepository<Account, Long> {
          @Query("select a from Account a where a.id = :id")
          Account findById(Long id);
        }
        """));
    SemanticCallFacts.Fact findById = delegationFact(facts, "demo.AccountRepository#findById");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, findById.certainty(),
        "an @Query redeclaration with the exact contract shape is override evidence");
  }

  @Test
  void unrelatedOverloadDoesNotDowngradeCrud() throws IOException {
    SemanticCallFacts facts = analyze(List.of("""
        package demo;
        public interface AccountRepository
            extends org.springframework.data.jpa.repository.JpaRepository<Account, Long> {
          int save(Account account, int count);
        }
        """));
    SemanticCallFacts.Fact save = delegationFact(facts, "demo.AccountRepository#save");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, save.certainty(),
        "an overload with a different shape is not override evidence");
  }

  @Test
  void unresolvedBaseClassReferenceUsesItsOwnDiagnostic() throws IOException {
    SemanticCallFacts facts = analyze(List.of("""
        package demo;
        import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
        @EnableJpaRepositories(repositoryBaseClass = NotImportedAnywhere.class)
        public class AppConfig { }
        """, """
        package demo;
        public interface AccountRepository
            extends org.springframework.data.jpa.repository.JpaRepository<Account, Long> { }
        """));
    SemanticCallFacts.Fact save = delegationFact(facts, "demo.AccountRepository#save");
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED, save.certainty());
    assertTrue(facts.diagnostics().stream().anyMatch(item ->
        item.code().equals("spring_data_repository_base_class_unresolved")),
        facts.diagnostics().toString());
    assertTrue(facts.diagnostics().stream()
        .noneMatch(item -> item.code().equals("spring_data_backing_selection_out_of_scope")),
        facts.diagnostics().toString());
  }

  @Test
  void unresolvedFragmentExtensionIsVisibleAsDiagnostic() throws IOException {
    SemanticCallFacts facts = analyze(List.of("""
        package demo;
        public interface AccountRepository extends
            org.springframework.data.repository.CrudRepository<Account, Long>,
            NowhereFragment { }
        """));
    assertTrue(facts.diagnostics().stream().anyMatch(item ->
        item.code().equals("spring_data_repository_fragment_unresolved")
            && item.detail().contains("NowhereFragment")),
        facts.diagnostics().toString());
  }

  @Test
  void duplicateBaseClassConfigurationsDedupeDiagnostics() throws IOException {
    SemanticCallFacts facts = analyze(List.of("""
        package demo;
        import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
        @EnableJpaRepositories(repositoryBaseClass = com.example.MissingBase.class)
        public class FirstConfig { }
        @EnableJpaRepositories(repositoryBaseClass = com.example.MissingBase.class)
        class SecondConfig { }
        """, """
        package demo;
        public interface AccountRepository
            extends org.springframework.data.jpa.repository.JpaRepository<Account, Long> { }
        """));
    assertEquals(1, facts.diagnostics().size(), facts.diagnostics().toString());
  }

  @Test
  void multipleDistinctBaseClassesAbstainAsAmbiguous() throws IOException {
    SemanticCallFacts facts = analyze(List.of("""
        package demo;
        public class FirstBase<T> { }
        """, """
        package demo;
        public class SecondBase<T> { }
        """, """
        package demo;
        import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
        @EnableJpaRepositories(repositoryBaseClass = FirstBase.class)
        public class FirstConfig { }
        """, """
        package demo;
        import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
        @EnableJpaRepositories(repositoryBaseClass = SecondBase.class)
        public class SecondConfig { }
        """, """
        package demo;
        public interface AccountRepository
            extends org.springframework.data.jpa.repository.JpaRepository<Account, Long> { }
        """));
    SemanticCallFacts.Fact save = delegationFact(facts, "demo.AccountRepository#save");
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED, save.certainty());
    assertTrue(facts.diagnostics().stream()
        .anyMatch(item -> item.code().equals("spring_data_backing_selection_ambiguous")),
        facts.diagnostics().toString());
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
    assertEquals("src/main/java/demo/Source0.java", facts.diagnostics().get(0).path());
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
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17",
        "-g", "-d", classes.toString(), provider.toString(), implementation.toString(),
        app.toString()));
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

  @Test
  void fullyQualifiedQueryAnnotationIsOverrideEvidence() throws IOException {
    SemanticCallFacts facts = analyze(List.of("""
        package demo;
        public interface AccountRepository
            extends org.springframework.data.jpa.repository.JpaRepository<Account, Long> {
          @org.springframework.data.jpa.repository.Query("select a from Account a")
          java.util.List<Account> findAll();
        }
        """));
    SemanticCallFacts.Fact findAll = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("delegated_implementation"))
        .filter(item -> item.caller().equals("demo.AccountRepository#findAll"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.frameworkFacts().toString()));
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, findAll.certainty());
    assertEquals(
        "org.springframework.data.jpa.repository.query.AbstractStringBasedJpaQuery#execute",
        findAll.callee());
    assertTrue(facts.frameworkFacts().stream()
            .noneMatch(item -> item.relationshipType().equals("delegated_implementation")
                && item.callee().endsWith("SimpleJpaRepository#findAll")),
        "the query-overridden contract slot must not also claim the default backing");
  }

  @Test
  void lookalikeQueryAnnotationIsNotOverrideEvidence() throws IOException {
    SemanticCallFacts facts = analyze(List.of("""
        package demo;
        public interface AccountRepository
            extends org.springframework.data.jpa.repository.JpaRepository<Account, Long> {
          @NamedQuery(name = "findAll", query = "select a from Account a")
          java.util.List<Account> findAll();
        }
        """));
    SemanticCallFacts.Fact findAll = delegationFact(facts, "demo.AccountRepository#findAll");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, findAll.certainty(),
        "a lookalike annotation does not reroute the contract default");
  }

  @Test
  void wildcardImportedDependencyFragmentDoesNotRaiseUnresolved() throws IOException {
    Path sourceRoot = temp.resolve("wildcard-fragment/src/main/java");
    write(sourceRoot.resolve("demo/Account.java"), "package demo; public class Account {}\n");
    write(sourceRoot.resolve("demo/AccountRepository.java"), """
        package demo;
        import org.springframework.data.querydsl.*;
        public interface AccountRepository extends
            org.springframework.data.repository.CrudRepository<Account, Long>,
            QueryDslPredicateExecutor<Account> { }
        """);
    Path dependency = temp.resolve("wildcard-fragment/deps/querydsl.jar");
    writeArchive(dependency,
        "org/springframework/data/querydsl/QueryDslPredicateExecutor.class",
        "presence is what the scope probe reads");
    AnalysisContext context = AnalysisContext.builder()
        .id("spring-boot-production")
        .framework(AnalysisContext.Framework.SPRING_BOOT)
        .frameworkVersion("2.7.18")
        .fixtureId("fsi-spring-boot")
        .javaRelease(17)
        .repositoryRoot(temp)
        .applicationClasses(temp.resolve("classes"))
        .selectedProfile("production")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspath(List.of(dependency))
        .dependencyClasspathRequired(false)
        .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
            "spring-bean-annotations", "source_annotations",
            AnalysisContext.MetadataStatus.OBSERVED, "src/main/java"))
        .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
            "spring-data-repository-contract", "source_interface_metadata",
            AnalysisContext.MetadataStatus.OBSERVED, "src/main/java"))
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(context, AnalysisGraph.empty());
    assertTrue(facts.diagnostics().stream()
            .noneMatch(item -> item.code().equals("spring_data_repository_fragment_unresolved")),
        facts.diagnostics().toString());
  }

  @Test
  void unconfirmedFragmentImplIsVisibleAsDiagnostic() throws IOException {
    SemanticCallFacts facts = analyze(List.of("""
        package demo;
        public interface CustomFragment {
          void refresh(demo.Account account);
        }
        """, """
        package demo;
        public class CustomFragmentImpl { }
        """, """
        package demo;
        public interface AccountRepository extends
            org.springframework.data.repository.CrudRepository<Account, Long>,
            CustomFragment { }
        """));
    assertTrue(facts.diagnostics().stream().anyMatch(item ->
        item.code().equals("spring_data_repository_fragment_impl_unconfirmed")),
        facts.diagnostics().toString());
  }

  @Test
  void queryOverloadWithDifferentShapeKeepsTheSlotFact() throws IOException {
    SemanticCallFacts facts = analyze(List.of("""
        package demo;
        import org.springframework.data.jpa.repository.Query;
        public interface AccountRepository
            extends org.springframework.data.jpa.repository.JpaRepository<Account, Long> {
          @Query("select a from Account a")
          java.util.List<Account> findAll(demo.Account probe);
        }
        """));
    List<SemanticCallFacts.Fact> delegations = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("delegated_implementation"))
        .filter(item -> item.caller().equals("demo.AccountRepository#findAll"))
        .toList();
    assertEquals(2, delegations.size(), facts.frameworkFacts().toString());
    assertTrue(delegations.stream().anyMatch(item ->
        item.callee().equals(
            "org.springframework.data.jpa.repository.support.SimpleJpaRepository#findAll")
            && item.certainty() == SemanticCallFacts.Certainty.DEFINITE),
        "the contract slot keeps its default backing for a different-shape overload");
    assertTrue(delegations.stream().anyMatch(item ->
        item.callee().equals(
            "org.springframework.data.jpa.repository.query.AbstractStringBasedJpaQuery#execute")),
        "the overload gets its own query fact");
  }

  @Test
  void nonCanonicalQueryAnnotationIsNotOverrideEvidence() throws IOException {
    SemanticCallFacts facts = analyze(List.of("""
        package demo;
        public interface AccountRepository
            extends org.springframework.data.jpa.repository.JpaRepository<Account, Long> {
          @Query("select a from Account a")
          java.util.List<Account> findAll();
        }
        """));
    SemanticCallFacts.Fact findAll = delegationFact(facts, "demo.AccountRepository#findAll");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, findAll.certainty(),
        "a non-imported simple Query spelling is not the Spring Data annotation");
  }

  @Test
  void wildcardImportedQueryAnnotationIsOverrideEvidence() throws IOException {
    SemanticCallFacts facts = analyze(List.of("""
        package demo;
        import org.springframework.data.jpa.repository.*;
        public interface AccountRepository
            extends org.springframework.data.jpa.repository.JpaRepository<Account, Long> {
          @Query("select a from Account a")
          java.util.List<Account> findAll();
        }
        """));
    List<SemanticCallFacts.Fact> delegations = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("delegated_implementation"))
        .filter(item -> item.caller().equals("demo.AccountRepository#findAll"))
        .toList();
    assertEquals(1, delegations.size(), facts.frameworkFacts().toString());
    assertEquals(
        "org.springframework.data.jpa.repository.query.AbstractStringBasedJpaQuery#execute",
        delegations.get(0).callee());
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, delegations.get(0).certainty());
  }

  @Test
  void samePackageQueryAnnotationShadowsTheWildcardImport() throws IOException {
    SemanticCallFacts facts = analyze(List.of("""
        package demo;
        public @interface Query {
          String value();
        }
        """, """
        package demo;
        import org.springframework.data.jpa.repository.*;
        public interface AccountRepository
            extends org.springframework.data.jpa.repository.JpaRepository<Account, Long> {
          @Query("select a from Account a")
          java.util.List<Account> findAll();
        }
        """));
    SemanticCallFacts.Fact findAll = delegationFact(facts, "demo.AccountRepository#findAll");
    assertEquals(
        "org.springframework.data.jpa.repository.support.SimpleJpaRepository#findAll",
        findAll.callee());
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, findAll.certainty(),
        "a same-package Query type shadows the on-demand import (JLS 6.4.1)");
  }

  @Test
  void singleTypeImportShadowsTheWildcardImport() throws IOException {
    SemanticCallFacts facts = analyze(List.of("""
        package demo;
        import com.example.Query;
        import org.springframework.data.jpa.repository.*;
        public interface AccountRepository
            extends org.springframework.data.jpa.repository.JpaRepository<Account, Long> {
          @Query("select a from Account a")
          java.util.List<Account> findAll();
        }
        """));
    SemanticCallFacts.Fact findAll = delegationFact(facts, "demo.AccountRepository#findAll");
    assertEquals(
        "org.springframework.data.jpa.repository.support.SimpleJpaRepository#findAll",
        findAll.callee());
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, findAll.certainty(),
        "a single-type import of another Query shadows the on-demand import (JLS 6.4.1)");
  }

  @Test
  void sameCompilationUnitQueryTypeShadowsTheWildcardImport() throws IOException {
    SemanticCallFacts facts = analyze(List.of("""
        package demo;
        import org.springframework.data.jpa.repository.*;
        public interface AccountRepository
            extends org.springframework.data.jpa.repository.JpaRepository<Account, Long> {
          @interface Query { String value(); }
          @Query("select a from Account a")
          java.util.List<Account> findAll();
        }
        """));
    SemanticCallFacts.Fact findAll = delegationFact(facts, "demo.AccountRepository#findAll");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, findAll.certainty(),
        "a same-compilation-unit Query type shadows the on-demand import (JLS 6.4.1)");
  }

  @Test
  void inheritedQueryMemberTypeShadowsTheWildcardImport() throws IOException {
    SemanticCallFacts facts = analyze(List.of("""
        package demo;
        public interface BaseRepository {
          @interface Query { String value(); }
        }
        """, """
        package demo;
        import org.springframework.data.jpa.repository.*;
        public interface AccountRepository extends BaseRepository,
            org.springframework.data.jpa.repository.JpaRepository<Account, Long> {
          @Query("select a from Account a")
          java.util.List<Account> findAll();
        }
        """));
    SemanticCallFacts.Fact findAll = delegationFact(facts, "demo.AccountRepository#findAll");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, findAll.certainty(),
        "an inherited member type Query shadows the on-demand import (JLS 6.4.1)");
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

  /** Writes one placeholder zip entry for classpath-presence probes. */
  private static void writeArchive(Path archive, String entryName, String value)
      throws IOException {
    Files.createDirectories(archive.getParent());
    try (java.util.zip.ZipOutputStream output =
        new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
      output.putNextEntry(new java.util.zip.ZipEntry(entryName));
      output.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      output.closeEntry();
    }
  }
}
