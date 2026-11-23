package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SootUp scene lane: declared-target provider edges for application invokes on
 * app-declared Spring Data repository interfaces (issue #41 Edge 1).
 */
@Tag("sootup")
class SootUpRepositoryDeclaredInvokeIntegrationTest {
  @TempDir Path temp;

  @Test
  void derivedQueryInvokeEmitsDeclaredTargetEdge() throws IOException {
    Path fixture = fixture("derived");
    SemanticCallFacts facts = analyze(fixture, fixture.resolve("src/main/java"));

    SemanticCallFacts.Fact fact = facts.providerFacts().stream()
        .filter(item -> item.caller().equals("demo.AccountService#create"))
        .filter(item -> item.relationshipType().equals("declared_invokes"))
        .filter(item -> item.callee().equals("demo.AccountRepository#findByAccountNumber"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.providerFacts().toString()));
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, fact.certainty());
    assertEquals("(Ljava/lang/String;)Ldemo/Account;",
        fact.evidence().get(0).attributes().get("calleeSignature"));
  }

  @Test
  void inheritedCrudInvokeEmitsDeclaredTargetEdge() throws IOException {
    Path fixture = fixture("derived");
    SemanticCallFacts facts = analyze(fixture, fixture.resolve("src/main/java"));

    SemanticCallFacts.Fact fact = facts.providerFacts().stream()
        .filter(item -> item.caller().equals("demo.AccountService#create"))
        .filter(item -> item.relationshipType().equals("declared_invokes"))
        .filter(item -> item.callee().equals("demo.AccountRepository#save"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.providerFacts().toString()));
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, fact.certainty());
  }

  @Test
  void fragmentMethodInvokeEmitsDeclaredTargetEdge() throws IOException {
    Path sourceRoot = temp.resolve("fragment/src/main/java");
    Path stubs = temp.resolve("fragment/stubs");
    Path classes = temp.resolve("fragment/target/classes");
    writeStubs(stubs);
    write(sourceRoot, "demo/Account.java", "package demo; public class Account {}\n");
    write(sourceRoot, "demo/CustomFragment.java", """
        package demo;
        public interface CustomFragment {
          void refresh(Account account);
        }
        """);
    write(sourceRoot, "demo/CustomFragmentImpl.java", """
        package demo;
        public class CustomFragmentImpl implements CustomFragment {
          public void refresh(Account account) { }
        }
        """);
    write(sourceRoot, "demo/AccountRepository.java", """
        package demo;
        public interface AccountRepository extends
            org.springframework.data.repository.CrudRepository<Account, Long>, CustomFragment { }
        """);
    write(sourceRoot, "demo/AccountService.java", """
        package demo;
        public class AccountService {
          private final AccountRepository accountRepository;
          public AccountService(AccountRepository accountRepository) {
            this.accountRepository = accountRepository;
          }
          public void create(Account account) {
            accountRepository.refresh(account);
          }
        }
        """);
    compile(sourceRoot, stubs, classes);

    SemanticCallFacts facts = analyze(temp.resolve("fragment"), sourceRoot);

    SemanticCallFacts.Fact fact = facts.providerFacts().stream()
        .filter(item -> item.caller().equals("demo.AccountService#create"))
        .filter(item -> item.relationshipType().equals("declared_invokes"))
        .filter(item -> item.callee().equals("demo.AccountRepository#refresh"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.providerFacts().toString()));
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, fact.certainty());
  }

  @Test
  void nonRepositoryInterfaceInvokeEmitsNoDeclaredEdge() throws IOException {
    Path sourceRoot = temp.resolve("plain/src/main/java");
    Path stubs = temp.resolve("plain/stubs");
    Path classes = temp.resolve("plain/target/classes");
    writeStubs(stubs);
    write(sourceRoot, "demo/Account.java", "package demo; public class Account {}\n");
    write(sourceRoot, "demo/PlainInterface.java", """
        package demo;
        public interface PlainInterface {
          Account find(Account account);
        }
        """);
    write(sourceRoot, "demo/AccountService.java", """
        package demo;
        public class AccountService {
          private final PlainInterface plainInterface;
          public AccountService(PlainInterface plainInterface) {
            this.plainInterface = plainInterface;
          }
          public Account create(Account account) {
            return plainInterface.find(account);
          }
        }
        """);
    compile(sourceRoot, stubs, classes);

    SemanticCallFacts facts = analyze(temp.resolve("plain"), sourceRoot);
    assertTrue(facts.providerFacts().stream()
        .noneMatch(item -> item.relationshipType().equals("declared_invokes")),
        facts.providerFacts().toString());
  }

  @Test
  void repositoryDefaultMethodKeepsInterfaceDispatchAndLegacyIds() throws IOException {
    Path fixture = fixture("default-method");
    SemanticCallFacts facts = analyze(fixture, fixture.resolve("src/main/java"));

    List<SemanticCallFacts.Fact> auditFacts = facts.providerFacts().stream()
        .filter(item -> item.caller().equals("demo.AccountService#create"))
        .filter(item -> item.callee().equals("demo.AccountRepository#audit"))
        .toList();
    assertEquals(2, auditFacts.size(), facts.providerFacts().toString());
    SemanticCallFacts.Fact dispatch = auditFacts.stream()
        .filter(item -> item.relationshipType().equals("interface_dispatch"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(auditFacts.toString()));
    SemanticCallFacts.Fact declared = auditFacts.stream()
        .filter(item -> item.relationshipType().equals("declared_invokes"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(auditFacts.toString()));
    assertTrue(dispatch.id().startsWith("sootup-call-"),
        "interface_dispatch edge keeps the legacy id namespace: " + dispatch.id());
    assertFalse(dispatch.id().contains("-sha256"),
        "interface_dispatch edge must not flip to the collision suffix: " + dispatch.id());
    assertTrue(declared.id().startsWith("sootup-declared-"),
        "declared_invokes edge uses its own id namespace: " + declared.id());
  }

  @Test
  void emptySourceRootRunsPassAndEmitsNothingDifferentially() throws IOException {
    Path fixture = fixture("derived");
    Path emptyRoot = fixture.resolve("empty-src/main/java");
    Files.createDirectories(emptyRoot);
    SemanticCallFacts withoutSources = analyze(fixture, emptyRoot);
    assertTrue(withoutSources.providerFacts().stream()
        .noneMatch(item -> item.relationshipType().equals("declared_invokes")),
        withoutSources.providerFacts().toString());

    SemanticCallFacts withSources = analyze(fixture, fixture.resolve("src/main/java"));
    assertTrue(withSources.providerFacts().stream()
        .anyMatch(item -> item.relationshipType().equals("declared_invokes")
            && item.callee().equals("demo.AccountRepository#save")),
        withSources.providerFacts().toString());
  }

  @Test
  void overloadedRepositoryIdentitiesStayDistinctAndNestedInterfacesAreBounded() throws IOException {
    Path sourceRoot = temp.resolve("nested/src/main/java");
    Path stubs = temp.resolve("nested/stubs");
    Path classes = temp.resolve("nested/target/classes");
    writeStubs(stubs);
    write(sourceRoot, "demo/Account.java", "package demo; public class Account {}\n");
    write(sourceRoot, "demo/OverloadRepository.java", """
        package demo;
        public interface OverloadRepository
            extends org.springframework.data.repository.CrudRepository<Account, Long> {
          Account findByNumber(String number);
          Account findByNumber(int number);
        }
        """);
    write(sourceRoot, "demo/Repositories.java", """
        package demo;
        public final class Repositories {
          public interface NestedRepository
              extends org.springframework.data.repository.CrudRepository<Account, Long> {
            Account findByNumber(String number);
          }
        }
        """);
    write(sourceRoot, "demo/AccountService.java", """
        package demo;
        public class AccountService {
          private final OverloadRepository repository;
          private final Repositories.NestedRepository nested;
          public AccountService(OverloadRepository repository, Repositories.NestedRepository nested) {
            this.repository = repository;
            this.nested = nested;
          }
          public Account create(Account account) {
            repository.findByNumber("x");
            repository.findByNumber(1);
            nested.findByNumber("y");
            return account;
          }
        }
        """);
    compile(sourceRoot, stubs, classes);

    SemanticCallFacts facts = analyze(temp.resolve("nested"), sourceRoot);
    List<SemanticCallFacts.Fact> declared = facts.providerFacts().stream()
        .filter(item -> item.caller().equals("demo.AccountService#create"))
        .filter(item -> item.relationshipType().equals("declared_invokes"))
        .toList();
    assertEquals(2, declared.size(), facts.providerFacts().toString());
    assertTrue(declared.stream().anyMatch(item ->
        item.callee().equals("demo.OverloadRepository#findByNumber")
            && "(Ljava/lang/String;)Ldemo/Account;".equals(
                item.evidence().get(0).attributes().get("calleeSignature"))));
    assertTrue(declared.stream().anyMatch(item ->
        item.callee().equals("demo.OverloadRepository#findByNumber")
            && "(I)Ldemo/Account;".equals(
                item.evidence().get(0).attributes().get("calleeSignature"))));
    assertTrue(declared.stream().noneMatch(item ->
        item.callee().startsWith("demo.Repositories$")),
        "nested repository interfaces are a documented scanner residual:"
            + " not classified, no declared edges emitted");
  }

  @Test
  void declaredEdgesJoinDelegationFactsOnNodeIdentity() throws IOException {
    Path fixture = fixture("derived");
    SemanticCallFacts facts = analyze(fixture, fixture.resolve("src/main/java"));

    SemanticCallFacts.Fact crudEdge = facts.providerFacts().stream()
        .filter(item -> item.relationshipType().equals("declared_invokes"))
        .filter(item -> item.callee().equals("demo.AccountRepository#save"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.providerFacts().toString()));
    SemanticCallFacts.Fact crudDelegation = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("delegated_implementation"))
        .filter(item -> item.caller().equals(crudEdge.callee()))
        .filter(item -> item.callee().equals(
            "org.springframework.data.jpa.repository.support.SimpleJpaRepository#save"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.frameworkFacts().toString()));
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, crudDelegation.certainty());

    SemanticCallFacts.Fact derivedEdge = facts.providerFacts().stream()
        .filter(item -> item.relationshipType().equals("declared_invokes"))
        .filter(item -> item.callee().equals("demo.AccountRepository#findByAccountNumber"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.providerFacts().toString()));
    SemanticCallFacts.Fact derivedDelegation = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("delegated_implementation"))
        .filter(item -> item.caller().equals(derivedEdge.callee()))
        .filter(item -> item.callee().equals(
            "org.springframework.data.jpa.repository.query.PartTreeJpaQuery#execute"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.frameworkFacts().toString()));
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, derivedDelegation.certainty());
  }

  @Test
  void overloadedDerivedMethodsJoinWithDistinctIds() throws IOException {
    Path sourceRoot = temp.resolve("overload-join/src/main/java");
    Path stubs = temp.resolve("overload-join/stubs");
    Path classes = temp.resolve("overload-join/target/classes");
    writeStubs(stubs);
    write(sourceRoot, "demo/Account.java", "package demo; public class Account {}\n");
    write(sourceRoot, "demo/AccountRepository.java", """
        package demo;
        public interface AccountRepository
            extends org.springframework.data.repository.CrudRepository<Account, Long> {
          Account findByNumber(String number);
          Account findByNumber(int number);
        }
        """);
    write(sourceRoot, "demo/AccountService.java", """
        package demo;
        public class AccountService {
          private final AccountRepository accountRepository;
          public AccountService(AccountRepository accountRepository) {
            this.accountRepository = accountRepository;
          }
          public Account create(Account account) {
            accountRepository.findByNumber("x");
            return accountRepository.findByNumber(1);
          }
        }
        """);
    compile(sourceRoot, stubs, classes);

    SemanticCallFacts facts = analyze(temp.resolve("overload-join"), sourceRoot);
    List<SemanticCallFacts.Fact> delegations = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("delegated_implementation"))
        .filter(item -> item.caller().equals("demo.AccountRepository#findByNumber"))
        .toList();
    assertEquals(2, delegations.size(), facts.frameworkFacts().toString());
    assertEquals(2, delegations.stream().map(SemanticCallFacts.Fact::id).distinct().count());
    assertTrue(delegations.stream().allMatch(item ->
        item.callee().equals(
            "org.springframework.data.jpa.repository.query.PartTreeJpaQuery#execute")));
    List<SemanticCallFacts.Fact> declared = facts.providerFacts().stream()
        .filter(item -> item.relationshipType().equals("declared_invokes"))
        .filter(item -> item.callee().equals("demo.AccountRepository#findByNumber"))
        .toList();
    assertEquals(2, declared.size(), facts.providerFacts().toString());
    assertEquals(2, declared.stream()
        .map(item -> item.evidence().get(0).attributes().get("calleeSignature"))
        .distinct().count());
  }

  /** Builds the shared derived-query fixture and returns its root. */
  private Path fixture(String name) throws IOException {
    Path root = temp.resolve(name);
    Path sourceRoot = root.resolve("src/main/java");
    Path stubs = root.resolve("stubs");
    Path classes = root.resolve("target/classes");
    writeStubs(stubs);
    write(sourceRoot, "demo/Account.java", "package demo; public class Account {}\n");
    write(sourceRoot, "demo/AccountRepository.java", """
        package demo;
        public interface AccountRepository
            extends org.springframework.data.repository.CrudRepository<Account, Long> {
          Account findByAccountNumber(String number);
        }
        """);
    write(sourceRoot, "demo/AccountService.java", """
        package demo;
        public class AccountService {
          private final AccountRepository accountRepository;
          public AccountService(AccountRepository accountRepository) {
            this.accountRepository = accountRepository;
          }
          public Account create(Account account) {
            accountRepository.save(account);
            return accountRepository.findByAccountNumber("x");
          }
        }
        """);
    if (name.equals("default-method")) {
      write(sourceRoot, "demo/AccountRepository.java", """
          package demo;
          public interface AccountRepository
              extends org.springframework.data.repository.CrudRepository<Account, Long> {
            Account findByAccountNumber(String number);
            default String audit() { return "audited"; }
          }
          """);
      write(sourceRoot, "demo/AccountService.java", """
          package demo;
          public class AccountService {
            private final AccountRepository accountRepository;
            public AccountService(AccountRepository accountRepository) {
              this.accountRepository = accountRepository;
            }
            public Account create(Account account) {
              accountRepository.save(account);
              accountRepository.audit();
              return accountRepository.findByAccountNumber("x");
            }
          }
          """);
    }
    compile(sourceRoot, stubs, classes);
    return root;
  }

  private SemanticCallFacts analyze(Path root, Path sourceRoot) {
    return new JavaAnalysisAdapter().analyze(AnalysisContext.builder()
        .id("repository-declared-invoke")
        .framework(AnalysisContext.Framework.SPRING_BOOT)
        .frameworkVersion("2.7.18")
        .fixtureId("fsi-spring-boot")
        .javaRelease(17)
        .repositoryRoot(root)
        .applicationClasses(root.resolve("target/classes"))
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
        .build());
  }

  private static void writeStubs(Path stubs) throws IOException {
    write(stubs, "org/springframework/data/repository/Repository.java", """
        package org.springframework.data.repository;
        public interface Repository<T, ID> {}
        """);
    write(stubs, "org/springframework/data/repository/CrudRepository.java", """
        package org.springframework.data.repository;
        public interface CrudRepository<T, ID> extends Repository<T, ID> {
          <S extends T> S save(S entity);
          long count();
        }
        """);
  }

  private static void compile(Path sourceRoot, Path stubs, Path classes) throws IOException {
    Path stubClasses = classes.resolveSibling("stub-classes");
    compile(Files.walk(stubs)
        .filter(path -> path.toString().endsWith(".java"))
        .sorted()
        .toList(), null, stubClasses);
    compile(Files.walk(sourceRoot)
        .filter(path -> path.toString().endsWith(".java"))
        .sorted()
        .toList(), stubClasses, classes);
  }

  private static void compile(List<Path> sources, Path classpath, Path classes)
      throws IOException {
    Files.createDirectories(classes);
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException("a JDK compiler is required for the SootUp integration test");
    }
    try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
      var units = fileManager.getJavaFileObjectsFromFiles(
          sources.stream().map(Path::toFile).toList());
      List<String> options = new java.util.ArrayList<>(
          List.of("--release", "17", "-d", classes.toString()));
      if (classpath != null) {
        options.add("-cp");
        options.add(classpath.toString());
      }
      boolean compiled = compiler.getTask(null, fileManager, null, options, null, units).call();
      assertTrue(compiled, "integration fixture compilation failed");
    }
  }

  private static void write(Path root, String relative, String source) throws IOException {
    Path path = root.resolve(relative);
    Files.createDirectories(path.getParent());
    Files.writeString(path, source, StandardCharsets.UTF_8);
  }
}
