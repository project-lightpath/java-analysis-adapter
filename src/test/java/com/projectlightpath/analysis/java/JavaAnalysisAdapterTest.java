package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    SemanticCallFacts.Fact route = route(facts, "demo.TransferResource#transfer");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, route.certainty());
    assertEquals("registered_route_mapping", route.claim());
    assertEquals("POST", route.selectionCriteria().httpMethod());
    assertEquals("/transfers", route.selectionCriteria().pathTemplate());
    assertEquals(List.of("application/json"), route.selectionCriteria().consumes());
    assertEquals(List.of("application/json"), route.selectionCriteria().produces());
    assertEquals(List.of(), route.selectionCriteria().headers());
    assertEquals(List.of(), route.selectionCriteria().parameters());
    assertEquals("quarkus-production", route.selectionCriteria().activeAnalysisContext());
    assertEquals("false", route.evidence().get(0).attributes().get("registeredRouteMapping"));
    assertTrue(route.evidence().get(0).symbols().contains("@Path(\"/transfers\")"));
    assertTrue(route.evidence().get(0).symbols().contains("@POST"));

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
    assertEquals(0, facts.links().stream()
        .filter(link -> link.frameworkFactId().equals(route.id()))
        .count());
    assertFalse(facts.providerFacts().stream().anyMatch(item -> item.relationshipType().equals("framework_entrypoint")));
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

    SemanticCallFacts.Fact route = route(facts, "demo.TransferController#transfer");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, route.certainty());
    assertEquals("POST", route.selectionCriteria().httpMethod());
    assertEquals("/api/transfers", route.selectionCriteria().pathTemplate());
    assertEquals("registered_route_mapping", route.claim());
    assertEquals("false", route.evidence().get(0).attributes().get("registeredRouteMapping"));
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
  void quarkusRoutesEnumerateMethodPathsAndKeepMethodOnlyPaths() throws IOException {
    Path sourceRoot = temp.resolve("quarkus-routes/src/main/java");
    write(sourceRoot, "routes/MultiResource.java", """
        package routes;
        import jakarta.ws.rs.Consumes;
        import jakarta.ws.rs.GET;
        import jakarta.ws.rs.POST;
        import jakarta.ws.rs.Path;
        import jakarta.ws.rs.Produces;
        import jakarta.ws.rs.core.MediaType;
        @Path("/api/")
        @Consumes({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
        @Produces(MediaType.APPLICATION_JSON)
        public class MultiResource {
          @Path({"/one/", "/two"})
          @GET public String read() { return "ok"; }
          @Path("/submit")
          @POST @Consumes("text/plain") @Produces("application/xml")
          public String submit(String request) { return request; }
        }
        """);
    write(sourceRoot, "routes/MethodOnlyResource.java", """
        package routes;
        import jakarta.ws.rs.GET;
        import jakarta.ws.rs.Path;
        @Path("/root") public class MethodOnlyResource {
          @Path("/only") @GET public String only() { return "ok"; }
        }
        """);
    write(sourceRoot, "routes/UnregisteredMethodResource.java", """
        package routes;
        import jakarta.ws.rs.GET;
        import jakarta.ws.rs.Path;
        public class UnregisteredMethodResource {
          @Path("/not-a-root") @GET public String only() { return "not registered"; }
        }
        """);

    AnalysisContext context = context(
        AnalysisContext.Framework.QUARKUS,
        sourceRoot,
        null,
        List.of(new AnalysisContext.FrameworkMetadata(
            "quarkus-rest-annotations", "source_annotations", AnalysisContext.MetadataStatus.OBSERVED, "src/main/java")));

    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(context, AnalysisGraph.empty());
    List<SemanticCallFacts.Fact> routes = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("framework_entrypoint"))
        .toList();

    assertEquals(4, routes.size());
    SemanticCallFacts.Fact readOne = routes.stream()
        .filter(item -> item.callee().equals("routes.MultiResource#read"))
        .filter(item -> item.selectionCriteria().pathTemplate().equals("/api/one"))
        .findFirst()
        .orElseThrow();
    assertEquals("GET", readOne.selectionCriteria().httpMethod());
    assertEquals(List.of("application/json", "application/xml"), readOne.selectionCriteria().consumes());
    assertEquals(List.of("application/json"), readOne.selectionCriteria().produces());
    assertTrue(readOne.evidence().get(0).symbols().contains("@Path({\"/one/\", \"/two\"})"));

    SemanticCallFacts.Fact submit = routes.stream()
        .filter(item -> item.callee().equals("routes.MultiResource#submit"))
        .findFirst()
        .orElseThrow();
    assertEquals("/api/submit", submit.selectionCriteria().pathTemplate());
    assertEquals(List.of("text/plain"), submit.selectionCriteria().consumes());
    assertEquals(List.of("application/xml"), submit.selectionCriteria().produces());

    SemanticCallFacts.Fact methodOnly = route(facts, "routes.MethodOnlyResource#only");
    assertEquals("/root/only", methodOnly.selectionCriteria().pathTemplate());
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, methodOnly.certainty());
    assertFalse(facts.frameworkFacts().stream()
        .anyMatch(item -> item.callee().startsWith("routes.UnregisteredMethodResource#")));
  }

  @Test
  void springRoutesEnumeratePathsMethodsConditionsAndAvoidIdCollisions() throws IOException {
    Path sourceRoot = temp.resolve("spring-routes/src/main/java");
    write(sourceRoot, "first/CollisionController.java", """
        package first;
        import org.springframework.web.bind.annotation.RequestMapping;
        import org.springframework.web.bind.annotation.RequestMethod;
        import org.springframework.web.bind.annotation.RestController;
        import org.springframework.http.MediaType;
        @RestController
        @RequestMapping(path = {"/api", "/v2"}, headers = "X-Api=1", consumes = MediaType.APPLICATION_JSON_VALUE)
        public class CollisionController {
          @RequestMapping(path = {"/items/", "/records"}, method = {RequestMethod.GET, RequestMethod.POST},
              consumes = "text/plain", produces = MediaType.APPLICATION_XML_VALUE,
              headers = "X-Mode=fast", params = "version=1")
          public String handle(String request) { return request; }
        }
        """);
    write(sourceRoot, "second/CollisionController.java", """
        package second;
        import org.springframework.web.bind.annotation.GetMapping;
        import org.springframework.web.bind.annotation.RequestMapping;
        import org.springframework.web.bind.annotation.RestController;
        @RestController @RequestMapping("/api")
        public class CollisionController {
          @GetMapping("/items") public String handle() { return "ok"; }
        }
        """);
    write(sourceRoot, "first/RootController.java", """
        package first;
        import org.springframework.web.bind.annotation.GetMapping;
        import org.springframework.web.bind.annotation.RestController;
        @RestController public class RootController {
          @GetMapping("/root") public String root() { return "ok"; }
          @GetMapping("/{id:(a|b)}") public String regex(String id) { return id; }
        }
        """);
    write(sourceRoot, "first/AmbiguousController.java", """
        package first;
        import org.springframework.web.bind.annotation.GetMapping;
        import org.springframework.web.bind.annotation.RestController;
        @RestController public class AmbiguousController {
          @GetMapping("/same") public String first() { return "one"; }
          @GetMapping("/same") public String second() { return "two"; }
        }
        """);
    write(sourceRoot, "first/ConstantController.java", """
        package first;
        import org.springframework.web.bind.annotation.GetMapping;
        import org.springframework.web.bind.annotation.RestController;
        @RestController public class ConstantController {
          static final String PATH = "/constant";
          @GetMapping(PATH) public String constant() { return PATH; }
          @GetMapping("/" + PATH) public String concatenated() { return PATH; }
        }
        """);

    List<AnalysisContext.FrameworkMetadata> metadata = List.of(
        new AnalysisContext.FrameworkMetadata(
            "spring-web-annotations", "source_annotations", AnalysisContext.MetadataStatus.OBSERVED, "src/main/java"),
        new AnalysisContext.FrameworkMetadata(
            "spring-mvc-handler-mappings", "runtime_or_build_metadata", AnalysisContext.MetadataStatus.NOT_CAPTURED, null));
    SemanticCallFacts sourceOnly = new JavaAnalysisAdapter().analyze(
        context(AnalysisContext.Framework.SPRING_BOOT, sourceRoot, null, metadata),
        AnalysisGraph.empty());

    SemanticCallFacts.Fact detailed = sourceOnly.frameworkFacts().stream()
        .filter(item -> item.callee().equals("first.CollisionController#handle"))
        .filter(item -> item.selectionCriteria().pathTemplate().equals("/api/items"))
        .filter(item -> item.selectionCriteria().httpMethod().equals("GET"))
        .findFirst()
        .orElseThrow();
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, detailed.certainty());
    assertEquals(List.of("X-Api=1", "X-Mode=fast"), detailed.selectionCriteria().headers());
    assertEquals(List.of("version=1"), detailed.selectionCriteria().parameters());
    assertEquals(List.of("text/plain"), detailed.selectionCriteria().consumes());
    assertEquals(List.of("application/xml"), detailed.selectionCriteria().produces());

    List<SemanticCallFacts.Fact> collisionRoutes = sourceOnly.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("framework_entrypoint"))
        .filter(item -> item.callee().endsWith("CollisionController#handle"))
        .toList();
    assertEquals(9, collisionRoutes.size());
    assertNotEquals(
        collisionRoutes.stream().filter(item -> item.callee().startsWith("first.")).findFirst().orElseThrow().id(),
        collisionRoutes.stream().filter(item -> item.callee().startsWith("second.")).findFirst().orElseThrow().id());

    SemanticCallFacts.Fact ambiguous = sourceOnly.frameworkFacts().stream()
        .filter(item -> item.callee().startsWith("first.AmbiguousController#"))
        .filter(item -> item.selectionCriteria().pathTemplate().equals("/same"))
        .findFirst()
        .orElseThrow();
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, ambiguous.certainty());
    assertTrue(ambiguous.limitation().contains("Multiple bounded handlers"));

    List<AnalysisContext.FrameworkMetadata> registeredMetadata = new java.util.ArrayList<>(metadata);
    registeredMetadata.set(1, new AnalysisContext.FrameworkMetadata(
        "spring-mvc-handler-mappings", "runtime_or_build_metadata", AnalysisContext.MetadataStatus.OBSERVED, "target/handler-mappings.json"));
    SemanticCallFacts registered = new JavaAnalysisAdapter().analyze(
        context(AnalysisContext.Framework.SPRING_BOOT, sourceRoot, null, registeredMetadata),
        AnalysisGraph.empty());
    SemanticCallFacts.Fact registeredRoute = route(registered, "first.RootController#root");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, registeredRoute.certainty());
    assertEquals("true", registeredRoute.evidence().get(0).attributes().get("registrationMetadataObserved"));
    assertEquals("false", registeredRoute.evidence().get(0).attributes().get("registeredRouteMapping"));
    assertEquals("/{id:(a|b)}", route(registered, "first.RootController#regex")
        .selectionCriteria().pathTemplate());
    assertFalse(registered.frameworkFacts().stream()
        .anyMatch(item -> item.callee().equals("first.ConstantController#constant")));
    assertFalse(registered.frameworkFacts().stream()
        .anyMatch(item -> item.callee().equals("first.ConstantController#concatenated")));
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

  private static SemanticCallFacts.Fact route(SemanticCallFacts facts, String callee) {
    return facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("framework_entrypoint"))
        .filter(item -> item.callee().equals(callee))
        .findFirst()
        .orElseThrow();
  }

  private static void write(Path root, String relative, String source) throws IOException {
    Path path = root.resolve(relative);
    Files.createDirectories(path.getParent());
    Files.writeString(path, source);
  }
}
