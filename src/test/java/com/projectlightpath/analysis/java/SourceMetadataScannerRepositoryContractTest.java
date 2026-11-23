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
    assertEquals(1, fragment.methods().size());
    assertEquals("refresh", fragment.methods().get(0).name());
    assertEquals("refresh(Account)", fragment.methods().get(0).identity());
  }

  @Test
  void implWithoutFragmentInterfaceIsNotABacking() throws IOException {
    Path sourceRoot = temp.resolve("impl-negative/src/main/java");
    write(sourceRoot, "demo/Account.java", "package demo; public class Account {}\n");
    write(sourceRoot, "demo/CustomFragment.java", """
        package demo;
        public interface CustomFragment {
          void refresh(Account account);
        }
        """);
    write(sourceRoot, "demo/CustomFragmentImpl.java", """
        package demo;
        public class CustomFragmentImpl { }
        """);
    write(sourceRoot, "demo/FragmentedRepository.java", """
        package demo;
        public interface FragmentedRepository extends
            org.springframework.data.repository.CrudRepository<Account, Long>,
            CustomFragment { }
        """);
    SourceMetadataScanner scanner = new SourceMetadataScanner(context(sourceRoot, "2.7.18"));
    assertTrue(scanner.springRepositoryFragments().isEmpty());
  }

  @Test
  void implOfADifferentInterfaceIsNotABacking() throws IOException {
    Path sourceRoot = temp.resolve("impl-wrong/src/main/java");
    write(sourceRoot, "shared/SharedFragment.java", """
        package shared;
        public interface SharedFragment {
          void refresh(demo.Account account);
        }
        """);
    write(sourceRoot, "demo/SharedFragmentImpl.java", """
        package demo;
        public interface SharedFragment {
          void unrelated();
        }
        """);
    write(sourceRoot, "demo/WrongImpl.java", """
        package demo;
        public class SharedFragmentImpl implements SharedFragment {
          public void unrelated() { }
        }
        """);
    write(sourceRoot, "demo/Account.java", "package demo; public class Account {}\n");
    write(sourceRoot, "demo/FragmentedRepository.java", """
        package demo;
        import shared.SharedFragment;
        public interface FragmentedRepository extends
            org.springframework.data.repository.CrudRepository<Account, Long>,
            SharedFragment { }
        """);
    SourceMetadataScanner scanner = new SourceMetadataScanner(context(sourceRoot, "2.7.18"));
    assertTrue(scanner.springRepositoryFragments().isEmpty(),
        "an Impl of a same-named different interface is not the backing");
  }

  @Test
  void crossPackageFragmentResolvesThroughImports() throws IOException {
    Path sourceRoot = temp.resolve("cross-package/src/main/java");
    write(sourceRoot, "demo/Account.java", "package demo; public class Account {}\n");
    write(sourceRoot, "shared/SharedFragment.java", """
        package shared;
        public interface SharedFragment {
          void refresh(demo.Account account);
        }
        """);
    write(sourceRoot, "shared/SharedFragmentImpl.java", """
        package shared;
        import demo.Account;
        public class SharedFragmentImpl implements SharedFragment {
          public void refresh(Account account) { }
        }
        """);
    write(sourceRoot, "demo/FragmentedRepository.java", """
        package demo;
        import shared.SharedFragment;
        public interface FragmentedRepository extends
            org.springframework.data.repository.CrudRepository<Account, Long>,
            SharedFragment { }
        """);
    SourceMetadataScanner scanner = new SourceMetadataScanner(context(sourceRoot, "2.7.18"));
    List<SourceMetadataScannerState.RepositoryFragment> fragments = scanner.springRepositoryFragments();
    assertEquals(1, fragments.size());
    assertEquals("shared.SharedFragment", fragments.get(0).fragmentType());
    assertEquals("shared.SharedFragmentImpl", fragments.get(0).implementationType());
  }

  @Test
  void enableJpaRepositoriesBaseClassesAreAllRead() throws IOException {
    Path sourceRoot = temp.resolve("src/main/java");
    write(sourceRoot, "demo/AuditedRepositoryImpl.java", """
        package demo;
        public class AuditedRepositoryImpl<T> { }
        """);
    write(sourceRoot, "demo/AppConfig.java", """
        package demo;
        import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
        @EnableJpaRepositories
        public class AppConfig { }
        """);
    write(sourceRoot, "demo/RepositoryConfig.java", """
        package demo;
        import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
        @EnableJpaRepositories(repositoryBaseClass = AuditedRepositoryImpl.class)
        public class RepositoryConfig { }
        """);
    SourceMetadataScanner scanner = new SourceMetadataScanner(context(sourceRoot, "2.7.18"));
    List<SourceMetadataScannerState.RepositoryBaseClass> baseClasses =
        scanner.springRepositoryBaseClasses();
    assertEquals(1, baseClasses.size());
    assertEquals("demo.AuditedRepositoryImpl", baseClasses.get(0).configuredType());
    assertTrue(baseClasses.get(0).applicationScoped());

    Path outOfScopeRoot = temp.resolve("out-of-scope/src/main/java");
    write(outOfScopeRoot, "demo/AppConfig.java", """
        package demo;
        import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
        @EnableJpaRepositories(repositoryBaseClass = com.example.MissingBase.class)
        public class AppConfig { }
        """);
    SourceMetadataScanner outOfScope =
        new SourceMetadataScanner(context(outOfScopeRoot, "2.7.18"));
    List<SourceMetadataScannerState.RepositoryBaseClass> missing =
        outOfScope.springRepositoryBaseClasses();
    assertEquals(1, missing.size());
    assertEquals("com.example.MissingBase", missing.get(0).configuredType());
    assertFalse(missing.get(0).applicationScoped());

    Path plainRoot = temp.resolve("plain/src/main/java");
    write(plainRoot, "demo/AppConfig.java", "package demo; public class AppConfig { }\n");
    assertTrue(new SourceMetadataScanner(context(plainRoot, "2.7.18"))
        .springRepositoryBaseClasses().isEmpty());
  }

  @Test
  void dependencyProvidedBaseClassIsInAnalysisScope() throws IOException {
    Path sourceRoot = temp.resolve("classpath-scope/src/main/java");
    write(sourceRoot, "demo/AppConfig.java", """
        package demo;
        import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
        @EnableJpaRepositories(
            repositoryBaseClass = org.springframework.data.jpa.repository.support.SimpleJpaRepository.class)
        public class AppConfig { }
        """);
    Path dependency = temp.resolve("classpath-scope/deps/base.jar");
    writeArchive(dependency,
        "org/springframework/data/jpa/repository/support/SimpleJpaRepository.class",
        "not real bytecode but presence is what the scope check reads");
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
        .provenance(AnalysisContext.Provenance.forTesting())
        .build();
    List<SourceMetadataScannerState.RepositoryBaseClass> baseClasses =
        new SourceMetadataScanner(context).springRepositoryBaseClasses();
    assertEquals(1, baseClasses.size());
    assertTrue(baseClasses.get(0).applicationScoped(),
        "a classpath-resolvable base class is a bounded in-scope candidate");
  }

  @Test
  void jpaRepositoryGuaranteesFollowBootVersion() throws IOException {
    Path sourceRoot = temp.resolve("jpa-version/src/main/java");
    write(sourceRoot, "demo/Account.java", "package demo; public class Account {}\n");
    write(sourceRoot, "demo/JpaAccountRepository.java", """
        package demo;
        public interface JpaAccountRepository
            extends org.springframework.data.jpa.repository.JpaRepository<Account, Long> { }
        """);
    SourceMetadataScannerState.SourceFile file = file(
        new SourceMetadataScanner(context(sourceRoot, "2.7.18")),
        "demo.JpaAccountRepository");
    List<String> boot2 = guaranteedNames(
        new SourceMetadataScanner(context(sourceRoot, "2.7.18")).springRepositoryMethods(file));
    assertTrue(boot2.contains("getOne"));
    assertTrue(boot2.contains("saveAllAndFlush"));
    List<String> boot24 = guaranteedNames(
        new SourceMetadataScanner(context(sourceRoot, "2.4.0")).springRepositoryMethods(file));
    assertFalse(boot24.contains("getById"));
    assertFalse(boot24.contains("saveAllAndFlush"));
    assertFalse(boot24.contains("deleteAllByIdInBatch"));
    assertFalse(boot24.contains("getReferenceById"));
    assertTrue(boot24.contains("getOne"));
    List<String> boot26 = guaranteedNames(
        new SourceMetadataScanner(context(sourceRoot, "2.6.5")).springRepositoryMethods(file));
    assertTrue(boot26.contains("getById"));
    assertTrue(boot26.contains("saveAllAndFlush"));
    assertFalse(boot26.contains("getReferenceById"));
    List<String> boot3 = guaranteedNames(
        new SourceMetadataScanner(context(sourceRoot, "3.2.0")).springRepositoryMethods(file));
    assertFalse(boot3.contains("getOne"));
    assertFalse(boot3.contains("getById"));
    assertTrue(boot3.contains("getReferenceById"));
    List<String> unparsable = guaranteedNames(
        new SourceMetadataScanner(context(sourceRoot, "custom")).springRepositoryMethods(file));
    assertFalse(unparsable.contains("getOne"));
    assertFalse(unparsable.contains("flush"));
    assertTrue(unparsable.contains("save"));
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
