package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Equivalence-class tests for the Spring Data delegation framework facts
 * (issue #41 Edge 2): repository interface method -> runtime backing
 * implementation, default certainty possible, reactive contracts abstaining.
 */
class BoundedFrameworkModelSpringDelegationTest {
  @TempDir Path temp;

  @Test
  void derivedQueryMethodDelegatesToQueryDerivationMachinery() throws IOException {
    SemanticCallFacts facts = analyze("""
        package demo;
        public interface AccountRepository
            extends org.springframework.data.jpa.repository.JpaRepository<Account, Long> {
          Account findByAccountNumber(String number);
        }
        """);
    SemanticCallFacts.Fact fact = delegationFact(facts, "demo.AccountRepository#findByAccountNumber");
    assertEquals(
        "org.springframework.data.jpa.repository.query.PartTreeJpaQuery#execute",
        fact.callee());
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, fact.certainty());
    assertTrue(fact.evidence().stream().anyMatch(evidence ->
        evidence.kind().equals("spring_data_backing_selection")));
  }

  @Test
  void crudMethodsDelegateToSimpleJpaRepository() throws IOException {
    SemanticCallFacts facts = analyze("""
        package demo;
        public interface AccountRepository
            extends org.springframework.data.jpa.repository.JpaRepository<Account, Long> {
          Account findByAccountNumber(String number);
        }
        """);
    SemanticCallFacts.Fact save = delegationFact(facts, "demo.AccountRepository#save");
    assertEquals(
        "org.springframework.data.jpa.repository.support.SimpleJpaRepository#save",
        save.callee());
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, save.certainty());
    SemanticCallFacts.Fact findById = delegationFact(facts, "demo.AccountRepository#findById");
    assertEquals(
        "org.springframework.data.jpa.repository.support.SimpleJpaRepository#findById",
        findById.callee());
  }

  @Test
  void fragmentMethodsDelegateToApplicationImpl() throws IOException {
    SemanticCallFacts facts = analyze("""
        package demo;
        public interface CustomFragment {
          void refresh(demo.Account account);
        }
        """, """
        package demo;
        public class CustomFragmentImpl implements CustomFragment {
          public void refresh(demo.Account account) { }
        }
        """, """
        package demo;
        public interface AccountRepository extends
            org.springframework.data.repository.CrudRepository<demo.Account, Long>,
            CustomFragment { }
        """);
    SemanticCallFacts.Fact fact = delegationFact(facts, "demo.AccountRepository#refresh");
    assertEquals("demo.CustomFragmentImpl#refresh", fact.callee());
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, fact.certainty());
  }

  @Test
  void reactiveRepositoryMethodsAbstainWithoutBacking() throws IOException {
    SemanticCallFacts facts = analyze("""
        package demo;
        public interface ReactiveAccountRepository
            extends org.springframework.data.repository.reactive.ReactiveCrudRepository<Account, String> {
          reactor.core.publisher.Mono<Account> findByNumber(String number);
        }
        """);
    List<SemanticCallFacts.Fact> delegations = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("delegated_implementation"))
        .toList();
    assertFalse(delegations.isEmpty(),
        "reactive repository methods still emit delegation facts");
    assertTrue(delegations.stream()
        .allMatch(item -> item.certainty() == SemanticCallFacts.Certainty.UNRESOLVED),
        delegations.toString());
    assertTrue(delegations.stream()
        .noneMatch(item -> item.callee().contains("SimpleJpaRepository")),
        delegations.toString());
  }

  @Test
  void lookalikeRepositoryEmitsNoDelegationFacts() throws IOException {
    SemanticCallFacts facts = analyze("""
        package demo;
        import org.springframework.stereotype.Repository;
        @Repository public interface LookalikeRepository extends demo.Repository<Account> {
          Account findByEmail(String email);
        }
        """, """
        package demo;
        public interface Repository<T> { }
        """);
    assertTrue(facts.frameworkFacts().stream()
        .noneMatch(item -> item.relationshipType().equals("delegated_implementation")),
        facts.frameworkFacts().toString());
  }

  private SemanticCallFacts.Fact delegationFact(SemanticCallFacts facts, String caller) {
    return facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("delegated_implementation"))
        .filter(item -> item.caller().equals(caller))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.frameworkFacts().toString()));
  }

  private SemanticCallFacts analyze(String... sources) throws IOException {
    Path sourceRoot = temp.resolve("delegation/src/main/java");
    write(sourceRoot, "demo/Account.java", "package demo; public class Account {}\n");
    for (int index = 0; index < sources.length; index++) {
      write(sourceRoot, "demo/Source" + index + ".java", sources[index]);
    }
    return new JavaAnalysisAdapter().analyze(
        AnalysisContext.builder()
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
            .dependencyClasspathRequired(false)
            .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
                "spring-bean-annotations", "source_annotations",
                AnalysisContext.MetadataStatus.OBSERVED, "src/main/java"))
            .frameworkMetadata(new AnalysisContext.FrameworkMetadata(
                "spring-data-repository-contract", "source_interface_metadata",
                AnalysisContext.MetadataStatus.OBSERVED, "src/main/java"))
            .provenance(AnalysisContext.Provenance.forTesting())
            .build(),
        AnalysisGraph.empty());
  }

  private static void write(Path root, String relative, String source) throws IOException {
    Path path = root.resolve(relative);
    Files.createDirectories(path.getParent());
    Files.writeString(path, source);
  }
}
