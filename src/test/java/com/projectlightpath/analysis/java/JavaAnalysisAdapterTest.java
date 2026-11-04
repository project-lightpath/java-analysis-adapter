package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaAnalysisAdapterTest {
  @TempDir Path temp;

  @Test
  void quarkusFactsKeepRoutesAndUnresolvedDynamicProxySeparate() throws IOException {
    Path sourceRoot = temp.resolve("quarkus/src/main/java");
    Path generatedRoot = temp.resolve("quarkus/generated-src/main/java");
    write(sourceRoot, "demo/TransferResource.java", """
        package demo;
        import jakarta.inject.Inject;
        import jakarta.ws.rs.Consumes;
        import jakarta.ws.rs.POST;
        import jakarta.ws.rs.Path;
        import jakarta.ws.rs.Produces;
        import jakarta.ws.rs.core.MediaType;
        @Path("/transfers")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.APPLICATION_JSON)
        public class TransferResource {
          @Inject public TransferResource(TransferService transferService) { }
          @POST public String transfer(String request) { return request; }
        }
        """);
    write(sourceRoot, "demo/TransferService.java", """
        package demo;
        public interface TransferService { String transfer(String request); }
        """);
    write(sourceRoot, "demo/DefaultTransferService.java", """
        package demo;
        import jakarta.enterprise.context.ApplicationScoped;
        @ApplicationScoped public class DefaultTransferService implements TransferService {
          public String transfer(String request) { return request; }
        }
        """);
    write(sourceRoot, "demo/AuthCallback.java", """
        package demo;
        public interface AuthCallback { void onAuthenticated(String user); }
        """);
    write(sourceRoot, "demo/AuthProxyFactory.java", """
        package demo;
        import java.lang.reflect.InvocationHandler;
        import java.lang.reflect.Proxy;
        public class AuthProxyFactory {
          AuthCallback wrap(AuthCallback delegate) {
            InvocationHandler handler = (proxy, method, args) -> method.invoke(delegate, args);
            return (AuthCallback) Proxy.newProxyInstance(
                AuthCallback.class.getClassLoader(), new Class<?>[]{AuthCallback.class}, handler);
          }
        }
        """);
    write(generatedRoot, "demo/generated/GeneratedTransferMapper.java", """
        package demo.generated;
        public class GeneratedTransferMapper {
          public String toResponse(String value) { return value; }
        }
        """);

    AnalysisContext context = context(
        AnalysisContext.Framework.QUARKUS,
        sourceRoot,
        generatedRoot,
        List.of(
            new AnalysisContext.FrameworkMetadata("quarkus-rest-annotations", "source_annotations", AnalysisContext.MetadataStatus.OBSERVED, "src/main/java"),
            new AnalysisContext.FrameworkMetadata("quarkus-cdi-annotations", "source_annotations", AnalysisContext.MetadataStatus.OBSERVED, "src/main/java"),
            new AnalysisContext.FrameworkMetadata("quarkus-arc-bean-index", "framework_build_metadata", AnalysisContext.MetadataStatus.NOT_CAPTURED, null),
            new AnalysisContext.FrameworkMetadata("quarkus-generated-source-root", "generated_source_root", AnalysisContext.MetadataStatus.OBSERVED, "generated-src/main/java")));
    MethodSymbol caller = new MethodSymbol("demo.Server", "handle", "(Ljava/lang/String;)Z");
    MethodSymbol callee = new MethodSymbol("demo.Target", "doAuth", "(Ljava/lang/String;)Z");
    DynamicFeature dynamicProxy = new DynamicFeature(
        DynamicFeature.Kind.DYNAMIC_PROXY,
        new MethodSymbol("demo.AuthProxyFactory", "wrap", ""),
        "demo.AuthCallback#onAuthenticated",
        "src/main/java/demo/AuthProxyFactory.java",
        List.of("Proxy.newProxyInstance", "InvocationHandler"),
        "The proxy handler target is not promoted without explicit registration evidence.");
    DynamicFeature reflection = new DynamicFeature(
        DynamicFeature.Kind.REFLECTION,
        new MethodSymbol("demo.ReflectiveInvoker", "validate", "(Ljava/lang/String;)Z"),
        "demo.ReflectiveTarget#validate",
        "src/main/java/demo/ReflectiveInvoker.java",
        List.of("Class.forName", "getDeclaredMethod", "Method.invoke"),
        "Class.forName, Method.getDeclaredMethod, and Method.invoke do not identify a unique runtime target.");
    AnalysisGraph graph = new AnalysisGraph(
        List.of(
            CallEdge.definite(caller, callee, 12, "src/main/java/demo/Server.java"),
            new CallEdge(
                new MethodSymbol("demo.ServiceLoaderBridge", "lambda$0", ""),
                new MethodSymbol("demo.DefaultAuditProvider", "record", ""),
                8,
                "src/main/java/demo/ServiceLoaderBridge.java",
                SemanticCallFacts.Certainty.POSSIBLE,
                "service_loader")),
        List.of(dynamicProxy, reflection),
        Set.of("demo.Server", "demo.Target"),
        "SootUp",
        "3.0.1",
        2,
        1);

    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(context, graph);

    SemanticCallFacts.Fact route = fact(facts.frameworkFacts(), "quarkus-http-to-resource-enriched");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, route.certainty());
    assertEquals("registered_route_mapping", route.claim());
    assertEquals("POST", route.selectionCriteria().httpMethod());
    assertEquals("/transfers", route.selectionCriteria().pathTemplate());
    assertEquals(List.of("application/json"), route.selectionCriteria().consumes());
    assertEquals(List.of("application/json"), route.selectionCriteria().produces());
    assertEquals(List.of(), route.selectionCriteria().headers());
    assertEquals(List.of(), route.selectionCriteria().parameters());
    assertEquals("quarkus-production", route.selectionCriteria().activeAnalysisContext());

    SemanticCallFacts.Fact injection = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .findFirst()
        .orElseThrow();
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, injection.certainty());

    SemanticCallFacts.Fact generated = fact(facts.frameworkFacts(), "quarkus-generated-mapper-binding");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, generated.certainty());

    SemanticCallFacts.Fact unresolvedProxy = fact(facts.frameworkFacts(), "quarkus-unbounded-proxy-abstention");
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED, unresolvedProxy.certainty());
    assertEquals(SemanticCallFacts.Status.ABSTAINED, unresolvedProxy.status());
    assertEquals(SemanticCallFacts.FactSet.FRAMEWORK, unresolvedProxy.factSet());
    assertEquals(SemanticCallFacts.EvidenceOrigin.FRAMEWORK_MODEL, unresolvedProxy.evidenceOrigin());

    assertTrue(facts.providerFacts().stream().anyMatch(item -> item.relationshipType().equals("service_loader")
        && item.certainty() == SemanticCallFacts.Certainty.POSSIBLE));
    assertTrue(facts.providerFacts().stream().anyMatch(item -> item.relationshipType().equals("reflection")
        && item.certainty() == SemanticCallFacts.Certainty.UNRESOLVED
        && item.status() == SemanticCallFacts.Status.ABSTAINED));
    assertTrue(facts.providerFacts().stream().anyMatch(item -> item.factSet() == SemanticCallFacts.FactSet.PROVIDER));
    assertTrue(facts.providerFacts().stream().anyMatch(item -> item.evidenceOrigin() == SemanticCallFacts.EvidenceOrigin.SOOTUP_BASE_GRAPH));
    assertFalse(facts.providerFacts().stream().anyMatch(item -> item.callee().equals("demo.TransferResource#transfer")
        && item.certainty() != SemanticCallFacts.Certainty.UNRESOLVED));
    assertTrue(facts.mergePolicy().providerCallerAbsenceDoesNotDisproveRoute());
    assertTrue(facts.mergePolicy().frameworkFactsNeverReplaceProviderFacts());
    assertEquals(1, facts.links().stream()
        .filter(link -> link.frameworkFactId().equals("quarkus-http-to-resource-enriched"))
        .count());
    assertTrue(facts.providerFacts().stream().anyMatch(item -> item.relationshipType().equals("framework_entrypoint")
        && item.certainty() == SemanticCallFacts.Certainty.UNRESOLVED));
    assertEquals("SootUp", facts.runMetadata().providerName());
    assertEquals("3.0.1", facts.runMetadata().providerVersion());
    assertEquals("quarkus-production", facts.runMetadata().contextId());
    assertEquals("(Ljava/lang/String;)Z", facts.providerFacts().get(0).evidence().get(0).attributes().get("callerSignature"));
  }

  @Test
  void springFactsCoverMvcInjectionProxyRepositoryAndLifecycle() throws IOException {
    Path sourceRoot = temp.resolve("spring/src/main/java");
    write(sourceRoot, "demo/TransferController.java", """
        package demo;
        import org.springframework.web.bind.annotation.PostMapping;
        import org.springframework.web.bind.annotation.RequestMapping;
        import org.springframework.web.bind.annotation.RestController;
        @RestController @RequestMapping("/api/transfers")
        public class TransferController {
          private final TransactionService service;
          public TransferController(TransactionService service) { this.service = service; }
          @PostMapping public String transfer(String request) { return service.transfer(request); }
        }
        """);
    write(sourceRoot, "demo/TransactionService.java", """
        package demo;
        import org.springframework.stereotype.Service;
        import org.springframework.transaction.annotation.Transactional;
        @Service public class TransactionService {
          @Transactional public String transfer(String request) { return request; }
        }
        """);
    write(sourceRoot, "demo/AccountRepository.java", """
        package demo;
        import org.springframework.data.jpa.repository.JpaRepository;
        public interface AccountRepository extends JpaRepository<Account, Long> { }
        """);
    write(sourceRoot, "demo/Account.java", "package demo; public class Account {}\n");
    write(sourceRoot, "demo/SecurityConfig.java", """
        package demo;
        import org.springframework.context.annotation.Bean;
        import org.springframework.context.annotation.Configuration;
        @Configuration public class SecurityConfig {
          @Bean public Object securityFilterChain(Object http) { return http; }
        }
        """);
    write(sourceRoot, "demo/DataSeeder.java", """
        package demo;
        import org.springframework.boot.CommandLineRunner;
        import org.springframework.stereotype.Component;
        @Component public class DataSeeder implements CommandLineRunner {
          @Override public void run(String... args) { }
        }
        """);

    AnalysisContext context = context(
        AnalysisContext.Framework.SPRING_BOOT,
        sourceRoot,
        null,
        List.of(
            new AnalysisContext.FrameworkMetadata("spring-web-annotations", "source_annotations", AnalysisContext.MetadataStatus.OBSERVED, "src/main/java"),
            new AnalysisContext.FrameworkMetadata("spring-bean-annotations", "source_annotations", AnalysisContext.MetadataStatus.OBSERVED, "src/main/java"),
            new AnalysisContext.FrameworkMetadata("spring-bean-definition-registry", "runtime_or_build_metadata", AnalysisContext.MetadataStatus.NOT_CAPTURED, null),
            new AnalysisContext.FrameworkMetadata("spring-data-repository-contract", "source_interface_metadata", AnalysisContext.MetadataStatus.OBSERVED, "src/main/java"),
            new AnalysisContext.FrameworkMetadata("spring-aop-advisor-metadata", "runtime_or_build_metadata", AnalysisContext.MetadataStatus.NOT_CAPTURED, null)));

    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(context, AnalysisGraph.empty());

    SemanticCallFacts.Fact route = fact(facts.frameworkFacts(), "spring-http-to-transfer-controller-enriched");
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, route.certainty());
    assertEquals("POST", route.selectionCriteria().httpMethod());
    assertEquals("/api/transfers", route.selectionCriteria().pathTemplate());
    assertEquals("registered_route_mapping", route.claim());
    assertTrue(facts.frameworkFacts().stream().anyMatch(item -> item.id().equals("spring-controller-to-service-injection")
        && item.certainty() == SemanticCallFacts.Certainty.POSSIBLE));
    assertTrue(facts.frameworkFacts().stream().anyMatch(item -> item.id().equals("spring-transaction-proxy-to-service")
        && item.certainty() == SemanticCallFacts.Certainty.POSSIBLE));
    assertTrue(facts.frameworkFacts().stream().anyMatch(item -> item.id().equals("spring-data-generated-account-repository")
        || item.relationshipType().equals("dependency_injection")));
    assertTrue(facts.frameworkFacts().stream().anyMatch(item -> item.id().equals("spring-bean-factory-to-security-config")));
    assertTrue(facts.frameworkFacts().stream().anyMatch(item -> item.id().equals("spring-boot-to-data-seeder")));
  }

  @Test
  void missingRouteMetadataCannotPromoteSourceAnnotationToDefinite() throws IOException {
    Path sourceRoot = temp.resolve("missing/src/main/java");
    write(sourceRoot, "demo/Resource.java", """
        package demo;
        @jakarta.ws.rs.Path("/items") public class Resource {
          @jakarta.ws.rs.GET public String get() { return "ok"; }
        }
        """);
    AnalysisContext context = context(AnalysisContext.Framework.QUARKUS, sourceRoot, null, List.of());

    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(context, AnalysisGraph.empty());

    SemanticCallFacts.Fact route = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("framework_entrypoint"))
        .findFirst()
        .orElseThrow();
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED, route.certainty());
    assertEquals(SemanticCallFacts.Status.ABSTAINED, route.status());
    assertNotNull(route.selectionCriteria());
  }

  private AnalysisContext context(
      AnalysisContext.Framework framework,
      Path sourceRoot,
      Path generatedRoot,
      List<AnalysisContext.FrameworkMetadata> metadata) {
    AnalysisContext.Builder builder = AnalysisContext.builder()
        .id(framework == AnalysisContext.Framework.QUARKUS ? "quarkus-production" : "spring-boot-production")
        .framework(framework)
        .frameworkVersion(framework == AnalysisContext.Framework.QUARKUS ? "3.40.1" : "2.7.18")
        .fixtureId(framework == AnalysisContext.Framework.QUARKUS ? "java21-quarkus" : "fsi-spring-boot")
        .javaRelease(framework == AnalysisContext.Framework.QUARKUS ? 21 : 17)
        .repositoryRoot(temp)
        .applicationClasses(temp.resolve("classes"))
        .selectedProfile("production")
        .analysisScope(AnalysisContext.Scope.PRODUCTION)
        .sourceRoot("src/main/java", sourceRoot, AnalysisContext.Scope.PRODUCTION, true)
        .dependencyClasspathRequired(false)
        .provenance(AnalysisContext.Provenance.forTesting());
    if (generatedRoot != null) {
      builder.generatedSourceRoot(
          "generated-src/main/java", generatedRoot, AnalysisContext.Scope.PRODUCTION, true, true);
    }
    metadata.forEach(builder::frameworkMetadata);
    return builder.build();
  }

  private static SemanticCallFacts.Fact fact(List<SemanticCallFacts.Fact> facts, String id) {
    return facts.stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
  }

  private static void write(Path root, String relative, String source) throws IOException {
    Path path = root.resolve(relative);
    Files.createDirectories(path.getParent());
    Files.writeString(path, source);
  }
}
