package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Equivalence-class tests for the Spring Data repository scanner substrate:
 * per-contract guaranteed-method tables (versioned), reactive contract
 * classification, fragment composition, and {@code @EnableJpaRepositories}
 * repository base-class reading.
 */
class SourceMetadataScannerRepositoryContractTest {
  @TempDir Path temp;

  @Test
  void inheritedCrudMethodsAreGuaranteedPerContract() throws IOException {
    Path sourceRoot = temp.resolve("src/main/java");
    write(sourceRoot, "demo/Account.java", "package demo; public class Account {}\n");
    write(sourceRoot, "demo/CrudAccountRepository.java", """
        package demo;
        public interface CrudAccountRepository
            extends org.springframework.data.repository.CrudRepository<Account, Long> { }
        """);
    write(sourceRoot, "demo/JpaAccountRepository.java", """
        package demo;
        public interface JpaAccountRepository
            extends org.springframework.data.jpa.repository.JpaRepository<Account, Long> { }
        """);
    SourceMetadataScanner scanner = new SourceMetadataScanner(context(sourceRoot, "2.7.18"));
    SourceMetadataScannerState.SourceFile crud = file(scanner, "demo.CrudAccountRepository");
    SourceMetadataScannerState.SourceFile jpa = file(scanner, "demo.JpaAccountRepository");

    List<String> crudNames = guaranteedNames(scanner.springRepositoryMethods(crud));
    assertEquals(
        List.of("count", "delete", "deleteAll", "deleteAllById", "deleteById", "existsById",
            "findAll", "findAllById", "findById", "save", "saveAll"),
        crudNames);
    List<String> jpaNames = guaranteedNames(scanner.springRepositoryMethods(jpa));
    assertEquals(
        List.of("count", "delete", "deleteAll", "deleteAllById", "deleteAllByIdInBatch",
            "deleteAllInBatch", "deleteById", "existsById", "findAll", "findAllById", "findById",
            "flush", "getById", "getOne", "getReferenceById", "save", "saveAll", "saveAllAndFlush",
            "saveAndFlush"),
        jpaNames);
    for (SourceMetadataScannerState.RepositoryMethod method : scanner.springRepositoryMethods(crud)) {
      if (method.guaranteed()) {
        assertEquals(method.name(), method.identity(), "guaranteed identity is the bare name");
      }
    }
  }

  @Test
  void pagingContractGuaranteesFollowBootMajorVersion() throws IOException {
    Path sourceRoot = temp.resolve("src/main/java");
    write(sourceRoot, "demo/Account.java", "package demo; public class Account {}\n");
    write(sourceRoot, "demo/PagingAccountRepository.java", """
        package demo;
        public interface PagingAccountRepository
            extends org.springframework.data.repository.PagingAndSortingRepository<Account, Long> {
          Account findByName(String name);
        }
        """);
    SourceMetadataScanner boot2 = new SourceMetadataScanner(context(sourceRoot, "2.7.18"));
    SourceMetadataScannerState.SourceFile file = file(boot2, "demo.PagingAccountRepository");
    assertTrue(guaranteedNames(boot2.springRepositoryMethods(file)).contains("save"));
    assertTrue(boot2.springRepositoryMethods(file).stream()
        .anyMatch(method -> method.name().equals("findByName") && !method.guaranteed()));

    SourceMetadataScanner boot3 = new SourceMetadataScanner(context(sourceRoot, "3.2.0"));
    assertTrue(guaranteedNames(boot3.springRepositoryMethods(file)).isEmpty());
    assertTrue(boot3.springRepositoryMethods(file).stream()
        .anyMatch(method -> method.name().equals("findByName") && !method.guaranteed()));

    SourceMetadataScanner unparsable = new SourceMetadataScanner(context(sourceRoot, "custom"));
    assertTrue(guaranteedNames(unparsable.springRepositoryMethods(file)).isEmpty());
  }

  @Test
  void reactiveRepositoriesClassifyWithGuaranteedCrudSet() throws IOException {
    Path sourceRoot = temp.resolve("src/main/java");
    write(sourceRoot, "demo/Account.java", "package demo; public class Account {}\n");
    write(sourceRoot, "demo/ReactiveAccountRepository.java", """
        package demo;
        public interface ReactiveAccountRepository
            extends org.springframework.data.repository.reactive.ReactiveCrudRepository<Account, String> {
          reactor.core.publisher.Mono<Account> findByNumber(String number);
        }
        """);
    SourceMetadataScanner scanner = new SourceMetadataScanner(context(sourceRoot, "2.7.18"));
    SourceMetadataScannerState.SourceFile file = file(scanner, "demo.ReactiveAccountRepository");
    assertEquals(Optional.of("ReactiveCrudRepository"), scanner.springRepositoryContract(file));
    assertEquals(
        List.of("count", "delete", "deleteAll", "deleteAllById", "deleteById", "existsById",
            "findAll", "findAllById", "findById", "save", "saveAll"),
        guaranteedNames(scanner.springRepositoryMethods(file)));
    assertTrue(scanner.beanCandidates(AnalysisContext.Framework.SPRING_BOOT).stream()
        .filter(candidate -> candidate.file().typeName().equals("demo.ReactiveAccountRepository"))
        .anyMatch(candidate -> candidate.kind().equals("repository_interface")));
  }

  @Test
  void repositoryFragmentsExposeCustomInterfaceAndImpl() throws IOException {
    Path sourceRoot = temp.resolve("src/main/java");
    write(sourceRoot, "demo/Account.java", "package demo; public class Account {}\n");
    write(sourceRoot, "demo/CustomAccountFragment.java", """
        package demo;
        public interface CustomAccountFragment {
          void refresh(Account account);
        }
        """);
    write(sourceRoot, "demo/CustomAccountFragmentImpl.java", """
        package demo;
        public class CustomAccountFragmentImpl implements CustomAccountFragment {
          public void refresh(Account account) { }
        }
        """);
    write(sourceRoot, "demo/FragmentedRepository.java", """
        package demo;
        public interface FragmentedRepository extends
            org.springframework.data.repository.CrudRepository<Account, Long>,
            CustomAccountFragment { }
        """);
    write(sourceRoot, "demo/FragmentWithoutImplRepository.java", """
        package demo;
        public interface FragmentWithoutImplRepository extends
            org.springframework.data.repository.CrudRepository<Account, Long>,
            OrphanFragment { }
        """);
    write(sourceRoot, "demo/OrphanFragment.java", """
        package demo;
        public interface OrphanFragment {
          void orphan(Account account);
        }
        """);
    SourceMetadataScanner scanner = new SourceMetadataScanner(context(sourceRoot, "2.7.18"));
    List<SourceMetadataScannerState.RepositoryFragment> fragments = scanner.springRepositoryFragments();
    assertEquals(1, fragments.size());
    SourceMetadataScannerState.RepositoryFragment fragment = fragments.get(0);
    assertEquals("demo.FragmentedRepository", fragment.repositoryType());
    assertEquals("demo.CustomAccountFragment", fragment.fragmentType());
    assertEquals("demo.CustomAccountFragmentImpl", fragment.implementationType());
    assertEquals(List.of("refresh"), fragment.methodNames());
  }

  @Test
  void enableJpaRepositoriesBaseClassIsRead() throws IOException {
    Path sourceRoot = temp.resolve("src/main/java");
    write(sourceRoot, "demo/AuditedRepositoryImpl.java", """
        package demo;
        public class AuditedRepositoryImpl<T> { }
        """);
    write(sourceRoot, "demo/AppConfig.java", """
        package demo;
        import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
        @EnableJpaRepositories(repositoryBaseClass = AuditedRepositoryImpl.class)
        public class AppConfig { }
        """);
    SourceMetadataScanner scanner = new SourceMetadataScanner(context(sourceRoot, "2.7.18"));
    Optional<SourceMetadataScannerState.RepositoryBaseClass> baseClass =
        scanner.springRepositoryBaseClass();
    assertTrue(baseClass.isPresent());
    assertEquals("demo.AuditedRepositoryImpl", baseClass.get().configuredType());
    assertTrue(baseClass.get().applicationScoped());

    Path outOfScopeRoot = temp.resolve("out-of-scope/src/main/java");
    write(outOfScopeRoot, "demo/AppConfig.java", """
        package demo;
        import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
        @EnableJpaRepositories(repositoryBaseClass = com.example.MissingBase.class)
        public class AppConfig { }
        """);
    SourceMetadataScanner outOfScope =
        new SourceMetadataScanner(context(outOfScopeRoot, "2.7.18"));
    Optional<SourceMetadataScannerState.RepositoryBaseClass> missing =
        outOfScope.springRepositoryBaseClass();
    assertTrue(missing.isPresent());
    assertEquals("com.example.MissingBase", missing.get().configuredType());
    assertFalse(missing.get().applicationScoped());

    Path plainRoot = temp.resolve("plain/src/main/java");
    write(plainRoot, "demo/AppConfig.java", "package demo; public class AppConfig { }\n");
    assertTrue(new SourceMetadataScanner(context(plainRoot, "2.7.18"))
        .springRepositoryBaseClass().isEmpty());
  }

  @Test
  void nonRepositoryInterfacesStayUnclassified() throws IOException {
    Path sourceRoot = temp.resolve("src/main/java");
    write(sourceRoot, "demo/Account.java", "package demo; public class Account {}\n");
    write(sourceRoot, "demo/PlainInterface.java", """
        package demo;
        public interface PlainInterface {
          Account find(Account account);
        }
        """);
    SourceMetadataScanner scanner = new SourceMetadataScanner(context(sourceRoot, "2.7.18"));
    SourceMetadataScannerState.SourceFile file = file(scanner, "demo.PlainInterface");
    assertEquals(Optional.empty(), scanner.springRepositoryContract(file));
    assertTrue(scanner.springRepositoryMethods(file).isEmpty());
    assertTrue(scanner.springRepositoryFragments().isEmpty());
  }

  private static List<String> guaranteedNames(
      List<SourceMetadataScannerState.RepositoryMethod> methods) {
    return methods.stream()
        .filter(SourceMetadataScannerState.RepositoryMethod::guaranteed)
        .map(SourceMetadataScannerState.RepositoryMethod::name)
        .sorted()
        .toList();
  }

  private SourceMetadataScannerState.SourceFile file(
      SourceMetadataScanner scanner, String typeName) {
    return scanner.files().stream()
        .filter(candidate -> candidate.typeName().equals(typeName))
        .findFirst()
        .orElseThrow();
  }

  private AnalysisContext context(Path sourceRoot, String frameworkVersion) {
    return AnalysisContext.builder()
        .id("spring-boot-production")
        .framework(AnalysisContext.Framework.SPRING_BOOT)
        .frameworkVersion(frameworkVersion)
        .fixtureId("fsi-spring-boot")
        .javaRelease(17)
        .repositoryRoot(temp)
        .applicationClasses(temp.resolve("classes"))
        .selectedProfile("production")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
  }

  /** Writes one temporary Java fixture source file. */
  private static void write(Path root, String relative, String source) throws IOException {
    Path path = root.resolve(relative);
    Files.createDirectories(path.getParent());
    Files.writeString(path, source);
  }
}
