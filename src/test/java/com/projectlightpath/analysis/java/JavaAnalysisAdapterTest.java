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

  /** Keeps provider routes separate from unresolved dynamic proxy evidence. */
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

  /** Covers Spring MVC, injection, proxy, repository, and lifecycle facts. */
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

  /** Enumerates Quarkus route paths and rejects method-only roots. */
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

  /** Enumerates Spring mappings, conditions, and collision-safe route IDs. */
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
    write(sourceRoot, "first/MethodlessController.java", """
        package first;
        import org.springframework.web.bind.annotation.RequestMapping;
        import org.springframework.web.bind.annotation.RestController;
        @RestController @RequestMapping("/api")
        public class MethodlessController {
          @RequestMapping("/wildcard") public String wildcard() { return "ok"; }
        }
        """);
    write(sourceRoot, "first/RestrictedMethodController.java", """
        package first;
        import org.springframework.web.bind.annotation.GetMapping;
        import org.springframework.web.bind.annotation.RequestMapping;
        import org.springframework.web.bind.annotation.RequestMethod;
        import org.springframework.web.bind.annotation.RestController;
        @RestController @RequestMapping(path = "/restricted", method = RequestMethod.POST)
        public class RestrictedMethodController {
          @RequestMapping("/wildcard") public String wildcard() { return "ok"; }
          @GetMapping("/get") public String get() { return "wrong"; }
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

    List<SemanticCallFacts.Fact> methodless = sourceOnly.frameworkFacts().stream()
        .filter(item -> item.callee().equals("first.MethodlessController#wildcard"))
        .toList();
    assertEquals(Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"),
        methodless.stream().map(item -> item.selectionCriteria().httpMethod()).collect(java.util.stream.Collectors.toSet()));
    assertEquals("/api/wildcard", methodless.get(0).selectionCriteria().pathTemplate());

    List<SemanticCallFacts.Fact> restricted = sourceOnly.frameworkFacts().stream()
        .filter(item -> item.callee().equals("first.RestrictedMethodController#wildcard"))
        .toList();
    assertEquals(List.of("POST"), restricted.stream()
        .map(item -> item.selectionCriteria().httpMethod()).toList());
    assertFalse(sourceOnly.frameworkFacts().stream()
        .anyMatch(item -> item.callee().equals("first.RestrictedMethodController#get")));
  }

  /** Keeps source-only routes unresolved when registration metadata is absent. */
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

  /** Verifies metadata-backed Spring bindings, producers, profiles, proxies, and links. */
  @Test
  void metadataBackedSpringBindingsCoverQualifiersProducersProfilesProxyTargetsAndLinks()
      throws IOException {
    Path sourceRoot = temp.resolve("spring-bindings/src/main/java");
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.beans.factory.annotation.Qualifier;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          private final PaymentService payment;
          private final Clock clock;
          @Autowired public Consumer(@Qualifier("fast") PaymentService payment, Clock clock) {
            this.payment = payment;
            this.clock = clock;
          }
        }
        """);
    write(sourceRoot, "demo/PaymentService.java", "package demo; public interface PaymentService { void pay(); }\n");
    write(sourceRoot, "demo/FastPaymentService.java", """
        package demo;
        import org.springframework.beans.factory.annotation.Qualifier;
        import org.springframework.context.annotation.Primary;
        import org.springframework.stereotype.Service;
        import org.springframework.transaction.annotation.Transactional;
        @Service @Qualifier("fast") @Primary
        public class FastPaymentService implements PaymentService {
          @Transactional public void pay() { }
        }
        """);
    write(sourceRoot, "demo/TestPaymentService.java", """
        package demo;
        import org.springframework.context.annotation.Profile;
        import org.springframework.stereotype.Service;
        @Service @Profile("test") public class TestPaymentService implements PaymentService {
          public void pay() { }
        }
        """);
    write(sourceRoot, "demo/Clock.java", "package demo; public class Clock {}\n");
    write(sourceRoot, "demo/Beans.java", """
        package demo;
        import org.springframework.context.annotation.Bean;
        import org.springframework.context.annotation.Configuration;
        @Configuration public class Beans {
          @Bean public Clock clock() { return new Clock(); }
        }
        """);

    MethodSymbol providerCaller = new MethodSymbol("demo.Consumer", "<init>", "(Ldemo/PaymentService;Ldemo/Clock;)V");
    MethodSymbol providerCallee = new MethodSymbol("demo.FastPaymentService", "pay", "()V");
    CallEdge providerEdge = new CallEdge(
        providerCaller,
        providerCallee,
        8,
        "src/main/java/demo/Consumer.java",
        SemanticCallFacts.Certainty.POSSIBLE,
        "interface_dispatch");
    String providerFactId = "sootup-call-" + Integer.toHexString(
        (providerEdge.caller().descriptor() + "->" + providerEdge.callee().descriptor()
            + "@" + providerEdge.line()).hashCode());

    AnalysisContext.FrameworkBindingMetadata bindingMetadata = new AnalysisContext.FrameworkBindingMetadata(
        "0.1.0",
        List.of(
            new AnalysisContext.BindingCandidateMetadata(
                "fast",
                "demo.FastPaymentService",
                List.of("PaymentService"),
                List.of("Qualifier:fast"),
                "Service",
                "demo.FastPaymentService",
                null,
                null,
                List.of(),
                List.of(),
                true,
                false,
                true,
                "src/main/java/demo/FastPaymentService.java",
                providerFactId),
            new AnalysisContext.BindingCandidateMetadata(
                "test",
                "demo.TestPaymentService",
                List.of("PaymentService"),
                List.of(),
                "Service",
                "demo.TestPaymentService",
                null,
                null,
                List.of("test"),
                List.of(),
                false,
                false,
                false,
                "src/main/java/demo/TestPaymentService.java",
                null),
            new AnalysisContext.BindingCandidateMetadata(
                "demo.Beans#clock",
                "demo.Clock",
                List.of(),
                List.of(),
                "producer",
                "demo.Beans",
                "clock",
                "()Ldemo/Clock;",
                List.of(),
                List.of(),
                true,
                false,
                false,
                "src/main/java/demo/Beans.java",
                null)),
        List.of(
            new AnalysisContext.ResolvedBindingMetadata(
                "consumer-payment",
                "demo.Consumer",
                "<init>",
                "PaymentService",
                0,
                List.of("Qualifier:fast"),
                "fast",
                true,
                true,
                "primary qualified bean",
                "src/main/java/demo/Consumer.java",
                providerFactId),
            new AnalysisContext.ResolvedBindingMetadata(
                "consumer-clock",
                "demo.Consumer",
                "<init>",
                "Clock",
                1,
                List.of(),
                "demo.Beans#clock",
                true,
                true,
                "unique @Bean producer",
                "src/main/java/demo/Consumer.java",
                null)),
        List.of(new AnalysisContext.ProxyTargetMetadata(
            "fast-pay-proxy",
            "fast",
            "demo.FastPaymentService",
            "pay",
            "()V",
            "spring-aop-advisor",
            true,
            true,
            "transaction advisor",
            "src/main/java/demo/FastPaymentService.java",
            providerFactId)));

    AnalysisContext context = context(
        AnalysisContext.Framework.SPRING_BOOT,
        sourceRoot,
        null,
        List.of(
            new AnalysisContext.FrameworkMetadata(
                "spring-bean-annotations", "source_annotations", AnalysisContext.MetadataStatus.OBSERVED, "src/main/java"),
            new AnalysisContext.FrameworkMetadata(
                "spring-aop-advisor-metadata", "runtime_or_build_metadata", AnalysisContext.MetadataStatus.NOT_CAPTURED, null)),
        bindingMetadata);
    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(
        context,
        new AnalysisGraph(List.of(providerEdge), List.of(), Set.of(), "SootUp", "3.0.1", 4, 1));

    List<SemanticCallFacts.Fact> injections = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .toList();
    assertEquals(2, injections.size());
    assertTrue(injections.stream().anyMatch(item -> item.callee().equals("demo.FastPaymentService#<init>")));
    assertTrue(injections.stream().anyMatch(item -> item.callee().equals("demo.Beans#clock")));
    assertTrue(injections.stream().allMatch(item -> item.certainty() == SemanticCallFacts.Certainty.DEFINITE));

    SemanticCallFacts.Fact proxy = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("proxy_dispatch"))
        .filter(item -> item.callee().equals("demo.FastPaymentService#pay"))
        .findFirst()
        .orElseThrow();
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, proxy.certainty());
    assertEquals(2, facts.links().stream()
        .filter(link -> link.providerFactId().equals(providerFactId))
        .count());
    assertTrue(facts.frameworkFacts().stream().noneMatch(item -> item.callee().contains("TestPaymentService")));
  }

  /** Covers CDI field, method, qualifier, producer, and alternative scanning. */
  @Test
  void sourceScannerCoversCdiFieldMethodQualifierProducerAndAlternativeCandidates() throws IOException {
    Path sourceRoot = temp.resolve("cdi-bindings/src/main/java");
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import jakarta.inject.Inject;
        @jakarta.enterprise.context.ApplicationScoped public class Consumer {
          @Inject @Blue PaymentService service;
          private Token token;
          @Inject void setToken(Token token) { this.token = token; }
        }
        """);
    write(sourceRoot, "demo/PaymentService.java", "package demo; public interface PaymentService { }\n");
    write(sourceRoot, "demo/BluePaymentService.java", """
        package demo;
        @jakarta.enterprise.context.ApplicationScoped @Blue
        public class BluePaymentService implements PaymentService { }
        """);
    write(sourceRoot, "demo/Token.java", "package demo; public interface Token { }\n");
    write(sourceRoot, "demo/TokenProducer.java", """
        package demo;
        @jakarta.enterprise.context.ApplicationScoped public class TokenProducer {
          @jakarta.enterprise.inject.Produces Token token() { return null; }
        }
        """);
    AnalysisContext context = context(
        AnalysisContext.Framework.QUARKUS,
        sourceRoot,
        null,
        List.of(new AnalysisContext.FrameworkMetadata(
            "quarkus-cdi-annotations", "source_annotations", AnalysisContext.MetadataStatus.OBSERVED, "src/main/java")));

    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(context, AnalysisGraph.empty());
    List<SemanticCallFacts.Fact> injections = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .toList();
    assertEquals(2, injections.size());
    assertTrue(injections.stream().anyMatch(item -> item.caller().contains("#service")));
    assertTrue(injections.stream().anyMatch(item -> item.caller().contains("#setToken")));
    assertTrue(injections.stream().anyMatch(item -> item.callee().equals("demo.TokenProducer#token")));
    assertTrue(injections.stream().allMatch(item -> item.certainty() == SemanticCallFacts.Certainty.POSSIBLE));
  }

  /** Preserves possible ambiguity and unresolved conditional selection. */
  @Test
  void bindingAndProxyAmbiguityStaysPossibleAndUnknownConditionsStayUnresolved() throws IOException {
    Path sourceRoot = temp.resolve("spring-ambiguity/src/main/java");
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(Service service) { }
        }
        """);
    write(sourceRoot, "demo/ConditionalConsumer.java", """
        package demo;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class ConditionalConsumer {
          @Autowired public ConditionalConsumer(ConditionalService service) { }
        }
        """);
    write(sourceRoot, "demo/Service.java", "package demo; public interface Service { void run(); }\n");
    write(sourceRoot, "demo/ConditionalService.java", "package demo; public interface ConditionalService { }\n");
    write(sourceRoot, "demo/AService.java", """
        package demo;
        import org.springframework.stereotype.Service;
        import org.springframework.transaction.annotation.Transactional;
        @Service public class AService implements Service {
          @Transactional public void run() { }
        }
        """);
    write(sourceRoot, "demo/BService.java", """
        package demo;
        import org.springframework.stereotype.Service;
        import org.springframework.transaction.annotation.Transactional;
        @Service public class BService implements Service {
          @Transactional public void run() { }
        }
        """);
    write(sourceRoot, "demo/ConditionalServiceImpl.java", """
        package demo;
        import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
        import org.springframework.stereotype.Service;
        @Service @ConditionalOnProperty("feature.enabled")
        public class ConditionalServiceImpl implements ConditionalService { }
        """);

    AnalysisContext.FrameworkBindingMetadata metadata = new AnalysisContext.FrameworkBindingMetadata(
        "0.1.0",
        List.of(
            new AnalysisContext.BindingCandidateMetadata(
                "demo.AService", "demo.AService", List.of("Service"), List.of(), "Service",
                "demo.AService", null, null, List.of(), List.of(), true, false, false, null, null),
            new AnalysisContext.BindingCandidateMetadata(
                "demo.BService", "demo.BService", List.of("Service"), List.of(), "Service",
                "demo.BService", null, null, List.of(), List.of(), true, false, false, null, null)),
        List.of(new AnalysisContext.ResolvedBindingMetadata(
            "consumer-service", "demo.Consumer", "<init>", "Service", 0, List.of(),
            "demo.AService", null, true, "selection not evaluated", null, null)),
        List.of(
            new AnalysisContext.ProxyTargetMetadata(
                "a-proxy", "demo.AService", "demo.AService", "run", null,
                "spring-aop-advisor", null, true, "target not evaluated", null, null),
            new AnalysisContext.ProxyTargetMetadata(
                "b-proxy", "demo.BService", "demo.BService", "run", null,
                "spring-aop-advisor", false, true, "excluded target", null, null)));
    AnalysisContext context = context(
        AnalysisContext.Framework.SPRING_BOOT,
        sourceRoot,
        null,
        List.of(new AnalysisContext.FrameworkMetadata(
            "spring-bean-annotations", "source_annotations", AnalysisContext.MetadataStatus.OBSERVED, "src/main/java")),
        metadata);

    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(context, AnalysisGraph.empty());
    List<SemanticCallFacts.Fact> serviceBindings = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .filter(item -> item.caller().contains("demo.Consumer"))
        .toList();
    assertEquals(2, serviceBindings.size());
    assertTrue(serviceBindings.stream().allMatch(item -> item.certainty() == SemanticCallFacts.Certainty.POSSIBLE));

    SemanticCallFacts.Fact conditionalBinding = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .filter(item -> item.caller().contains("demo.ConditionalConsumer"))
        .findFirst()
        .orElseThrow();
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED, conditionalBinding.certainty());
    assertEquals(SemanticCallFacts.Status.ABSTAINED, conditionalBinding.status());

    SemanticCallFacts.Fact aProxy = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("proxy_dispatch"))
        .filter(item -> item.callee().equals("demo.AService#run"))
        .findFirst()
        .orElseThrow();
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, aProxy.certainty());
    assertFalse(facts.frameworkFacts().stream()
        .anyMatch(item -> item.relationshipType().equals("proxy_dispatch")
            && item.callee().equals("demo.BService#run")));
  }

  /** Ensures resolved metadata selection is applied before primary preference. */
  @Test
  void resolvedBindingWinsOverPrimaryAndQualifiedMetadataNamesStayScoped() throws IOException {
    Path sourceRoot = temp.resolve("binding-order/src/main/java");
    write(sourceRoot, "consumer/Consumer.java", """
        package consumer;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        import api.Service;
        @Component public class Consumer {
          @Autowired public Consumer(Service service) { }
        }
        """);
    write(sourceRoot, "api/Service.java", "package api; public interface Service { }\n");
    write(sourceRoot, "first/ServiceImpl.java", """
        package first;
        import api.Service;
        import org.springframework.context.annotation.Primary;
        import org.springframework.stereotype.Service;
        @Service @Primary public class ServiceImpl implements Service { }
        """);
    write(sourceRoot, "second/ServiceImpl.java", """
        package second;
        import api.Service;
        import org.springframework.stereotype.Service;
        @Service public class ServiceImpl implements Service { }
        """);

    AnalysisContext.FrameworkBindingMetadata metadata = new AnalysisContext.FrameworkBindingMetadata(
        "0.1.0",
        List.of(
            new AnalysisContext.BindingCandidateMetadata(
                "first-row", "first.ServiceImpl", List.of("Service"), List.of(), "Service",
                true, false, true),
            new AnalysisContext.BindingCandidateMetadata(
                "second-row", "second.ServiceImpl", List.of("Service"), List.of(), "Service",
                true, false, false)),
        List.of(new AnalysisContext.ResolvedBindingMetadata(
            "consumer-service", "consumer.Consumer", "Service", 0, "second-row")),
        List.of());
    AnalysisContext context = context(
        AnalysisContext.Framework.SPRING_BOOT,
        sourceRoot,
        null,
        List.of(new AnalysisContext.FrameworkMetadata(
            "spring-bean-annotations", "source_annotations", AnalysisContext.MetadataStatus.OBSERVED,
            "src/main/java")),
        metadata);

    List<SemanticCallFacts.Fact> injections = new JavaAnalysisAdapter()
        .analyze(context, AnalysisGraph.empty()).frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .toList();

    assertEquals(1, injections.size());
    assertEquals("second.ServiceImpl#<init>", injections.get(0).callee());
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, injections.get(0).certainty());
    assertEquals("second-row", injections.get(0).evidence().get(1).attributes().get("candidateMetadataId"));
  }

  /** Covers Spring bean-name qualifier fallback and resource-name fallback. */
  @Test
  void springQualifierUsesBeanNamesAndResourceFallsBackToTypeOnlyWhenNameIsAbsent()
      throws IOException {
    Path sourceRoot = temp.resolve("spring-names/src/main/java");
    write(sourceRoot, "demo/QualifierConsumer.java", """
        package demo;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.beans.factory.annotation.Qualifier;
        import org.springframework.stereotype.Component;
        @Component public class QualifierConsumer {
          @Autowired public QualifierConsumer(@Qualifier("fast") PaymentService service) { }
        }
        """);
    write(sourceRoot, "demo/ResourceConsumer.java", """
        package demo;
        import jakarta.annotation.Resource;
        import org.springframework.stereotype.Component;
        @Component public class ResourceConsumer {
          @Resource Service service;
          @Resource(name = "missing") OtherService missing;
        }
        """);
    write(sourceRoot, "demo/PaymentService.java", "package demo; public interface PaymentService { }\n");
    write(sourceRoot, "demo/FastPaymentService.java", """
        package demo;
        import org.springframework.stereotype.Service;
        @Service("fast") public class FastPaymentService implements PaymentService { }
        """);
    write(sourceRoot, "demo/OtherPaymentService.java", """
        package demo;
        import jakarta.inject.Named;
        @Named("other.fast") public class OtherPaymentService implements PaymentService { }
        """);
    write(sourceRoot, "demo/Service.java", "package demo; public interface Service { }\n");
    write(sourceRoot, "demo/AvailableService.java", """
        package demo;
        import org.springframework.stereotype.Service;
        @Service public class AvailableService implements Service { }
        """);
    write(sourceRoot, "demo/OtherService.java", "package demo; public interface OtherService { }\n");
    write(sourceRoot, "demo/OtherServiceImpl.java", """
        package demo;
        import org.springframework.stereotype.Service;
        @Service public class OtherServiceImpl implements OtherService { }
        """);
    AnalysisContext context = context(
        AnalysisContext.Framework.SPRING_BOOT,
        sourceRoot,
        null,
        List.of(new AnalysisContext.FrameworkMetadata(
            "spring-bean-annotations", "source_annotations", AnalysisContext.MetadataStatus.OBSERVED,
            "src/main/java")));

    List<SemanticCallFacts.Fact> facts = new JavaAnalysisAdapter()
        .analyze(context, AnalysisGraph.empty()).frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .toList();

    SemanticCallFacts.Fact qualified = facts.stream()
        .filter(item -> item.caller().contains("QualifierConsumer"))
        .findFirst().orElseThrow();
    assertEquals("demo.FastPaymentService#<init>", qualified.callee());
    assertFalse(facts.stream().anyMatch(item -> item.caller().contains("QualifierConsumer")
        && item.callee().contains("OtherPaymentService")));
    SemanticCallFacts.Fact resourceFallback = facts.stream()
        .filter(item -> item.caller().equals("spring:injection:demo.ResourceConsumer#service(Service)"))
        .findFirst().orElseThrow();
    assertEquals("demo.AvailableService#<init>", resourceFallback.callee());
    SemanticCallFacts.Fact namedResource = facts.stream()
        .filter(item -> item.caller().equals("spring:injection:demo.ResourceConsumer#missing(OtherService)"))
        .findFirst().orElseThrow();
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED, namedResource.certainty());
    assertTrue(namedResource.callee().contains("OtherService#<unresolved-bean>"));
  }

  /** Requires active alternative metadata and normal-scope proxy eligibility. */
  @Test
  void quarkusAlternativesNeedActiveMetadataAndDependentBeansNeedExplicitProxyTargets()
      throws IOException {
    Path sourceRoot = temp.resolve("quarkus-alternatives/src/main/java");
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import jakarta.inject.Inject;
        @jakarta.enterprise.context.ApplicationScoped public class Consumer {
          @Inject public Consumer(Service service) { }
        }
        """);
    write(sourceRoot, "demo/Service.java", "package demo; public interface Service { }\n");
    write(sourceRoot, "demo/DefaultService.java", """
        package demo;
        @jakarta.enterprise.context.ApplicationScoped public class DefaultService implements Service {
          public void run() { }
        }
        """);
    write(sourceRoot, "demo/AlternativeService.java", """
        package demo;
        @jakarta.enterprise.context.ApplicationScoped @jakarta.enterprise.inject.Alternative
        public class AlternativeService implements Service { public void run() { } }
        """);
    write(sourceRoot, "demo/DependentService.java", """
        package demo;
        @jakarta.enterprise.context.Dependent public class DependentService {
          public void run() { }
          public void run(String value) { }
        }
        """);
    List<AnalysisContext.BindingCandidateMetadata> candidates = List.of(
        new AnalysisContext.BindingCandidateMetadata(
            "default", "demo.DefaultService", List.of("Service"), List.of(), "ApplicationScoped",
            true, false, false),
        new AnalysisContext.BindingCandidateMetadata(
            "alternative", "demo.AlternativeService", List.of("Service"), List.of(), "ApplicationScoped",
            null, true, false));
    List<AnalysisContext.FrameworkMetadata> frameworkMetadata = List.of(
        new AnalysisContext.FrameworkMetadata(
            "quarkus-cdi-annotations", "source_annotations", AnalysisContext.MetadataStatus.OBSERVED,
            "src/main/java"));
    AnalysisContext.FrameworkBindingMetadata unknownAlternative = new AnalysisContext.FrameworkBindingMetadata(
        "0.1.0", candidates, List.of(), List.of());
    AnalysisContext context = context(
        AnalysisContext.Framework.QUARKUS, sourceRoot, null, frameworkMetadata, unknownAlternative);
    SemanticCallFacts unknownFacts = new JavaAnalysisAdapter().analyze(context, AnalysisGraph.empty());
    List<SemanticCallFacts.Fact> unknownBindings = unknownFacts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .toList();
    assertEquals(Set.of("demo.DefaultService#<init>", "demo.AlternativeService#<init>"),
        unknownBindings.stream().map(SemanticCallFacts.Fact::callee).collect(java.util.stream.Collectors.toSet()));
    assertFalse(unknownFacts.frameworkFacts().stream()
        .anyMatch(item -> item.relationshipType().equals("proxy_dispatch")
            && item.callee().equals("demo.DependentService#run")));

    List<AnalysisContext.BindingCandidateMetadata> enabledCandidates = List.of(
        candidates.get(0),
        new AnalysisContext.BindingCandidateMetadata(
            "alternative", "demo.AlternativeService", List.of("Service"), List.of(), "ApplicationScoped",
            true, true, false));
    AnalysisContext.FrameworkBindingMetadata enabled = new AnalysisContext.FrameworkBindingMetadata(
        "0.1.0", enabledCandidates, List.of(), List.of(new AnalysisContext.ProxyTargetMetadata(
            "dependent-proxy", null, "demo.DependentService", "run", "()V", "cdi-client-proxy",
            true, true, "exact target", null, null)));
    SemanticCallFacts enabledFacts = new JavaAnalysisAdapter().analyze(
        context(AnalysisContext.Framework.QUARKUS, sourceRoot, null, frameworkMetadata, enabled),
        AnalysisGraph.empty());
    List<SemanticCallFacts.Fact> enabledBindings = enabledFacts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .toList();
    assertEquals(1, enabledBindings.size());
    assertEquals("demo.AlternativeService#<init>", enabledBindings.get(0).callee());
    List<SemanticCallFacts.Fact> dependentProxies = enabledFacts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("proxy_dispatch"))
        .filter(item -> item.callee().equals("demo.DependentService#run"))
        .toList();
    assertEquals(1, dependentProxies.size());
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, dependentProxies.get(0).certainty());
  }

  /** Resolves proxy descriptors and erased generic producer types. */
  @Test
  void proxyDescriptorsResolveImportsAndNestedGenericProducers() throws IOException {
    Path sourceRoot = temp.resolve("descriptor-generics/src/main/java");
    write(sourceRoot, "demo/Clock.java", "package demo; public class Clock { }\n");
    write(sourceRoot, "demo/ClockService.java", """
        package demo;
        import org.springframework.stereotype.Service;
        import org.springframework.transaction.annotation.Transactional;
        @Service public class ClockService {
          @Transactional public void tick(Clock clock) { }
        }
        """);
    write(sourceRoot, "demo/Foo.java", "package demo; public class Foo { }\n");
    write(sourceRoot, "demo/Beans.java", """
        package demo;
        import java.util.List;
        import java.util.Map;
        import org.springframework.context.annotation.Bean;
        import org.springframework.context.annotation.Configuration;
        @Configuration public class Beans {
          @Bean public Map<String, List<Foo>> values() { return null; }
          @Bean public Map<Integer, String> other() { return null; }
        }
        """);
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import java.util.List;
        import java.util.Map;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(Map<String, List<Foo>> values) { }
        }
        """);
    AnalysisContext.ProxyTargetMetadata selected = new AnalysisContext.ProxyTargetMetadata(
        "clock-proxy", "demo.ClockService", "demo.ClockService", "tick", "(Ldemo/Clock;)V",
        "spring-aop-advisor", true, true, "exact target", null, null);
    AnalysisContext.FrameworkBindingMetadata metadata = new AnalysisContext.FrameworkBindingMetadata(
        "0.1.0",
        List.of(
            new AnalysisContext.BindingCandidateMetadata(
                "map-producer",
                "java.util.Map",
                List.of(),
                List.of(),
                "producer",
                "demo.Beans",
                "values",
                "()Ljava/util/Map;",
                List.of(),
                List.of(),
                true,
                false,
                false,
                "src/main/java/demo/Beans.java",
                null),
            new AnalysisContext.BindingCandidateMetadata(
                "other-producer",
                "java.util.Map",
                List.of(),
                List.of(),
                "producer",
                "demo.Beans",
                "other",
                "()Ljava/util/Map;",
                List.of(),
                List.of(),
                true,
                false,
                false,
                "src/main/java/demo/Beans.java",
                null)),
        List.of(),
        List.of(selected));
    List<AnalysisContext.FrameworkMetadata> frameworkMetadata = List.of(
        new AnalysisContext.FrameworkMetadata(
            "spring-bean-annotations", "source_annotations", AnalysisContext.MetadataStatus.OBSERVED,
            "src/main/java"));
    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(
        context(AnalysisContext.Framework.SPRING_BOOT, sourceRoot, null, frameworkMetadata, metadata),
        AnalysisGraph.empty());

    List<SemanticCallFacts.Fact> producerBindings = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .toList();
    assertEquals(1, producerBindings.size());
    assertEquals(Set.of("demo.Beans#values"),
        producerBindings.stream().map(SemanticCallFacts.Fact::callee)
            .collect(java.util.stream.Collectors.toSet()));
    SemanticCallFacts.Fact producerBinding = producerBindings.stream()
        .filter(item -> item.callee().equals("demo.Beans#values"))
        .findFirst().orElseThrow();
    assertEquals("map-producer", producerBinding.evidence().get(1).attributes().get("candidateMetadataId"));
    SemanticCallFacts.Fact proxy = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("proxy_dispatch"))
        .filter(item -> item.callee().equals("demo.ClockService#tick"))
        .findFirst().orElseThrow();
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, proxy.certainty());

    AnalysisContext.FrameworkBindingMetadata excludedMetadata = new AnalysisContext.FrameworkBindingMetadata(
        "0.1.0", List.of(), List.of(), List.of(new AnalysisContext.ProxyTargetMetadata(
            "clock-proxy", "demo.ClockService", "demo.ClockService", "tick", "(Ldemo/Clock;)V",
            "spring-aop-advisor", false, true, "excluded target", null, null)));
    SemanticCallFacts excludedFacts = new JavaAnalysisAdapter().analyze(
        context(AnalysisContext.Framework.SPRING_BOOT, sourceRoot, null, frameworkMetadata, excludedMetadata),
        AnalysisGraph.empty());
    assertFalse(excludedFacts.frameworkFacts().stream()
        .anyMatch(item -> item.relationshipType().equals("proxy_dispatch")
            && item.callee().equals("demo.ClockService#tick")));
  }

  /** Keeps CDI named beans eligible for unqualified injection after comment stripping. */
  @Test
  void cdiNamedBeansIgnoreCommentsAndRemainCompatibleWithUnqualifiedInjection() throws IOException {
    Path sourceRoot = temp.resolve("cdi-named-comments/src/main/java");
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import jakarta.inject.Inject;
        @jakarta.enterprise.context.ApplicationScoped public class Consumer {
          @Inject public Consumer(Service service) { }
        }
        """);
    write(sourceRoot, "demo/Service.java", "package demo; public interface Service { }\n");
    write(sourceRoot, "demo/NamedService.java", """
        package demo;
        /** @author documentation should not become a qualifier; @Lazy is also only text. */
        @jakarta.enterprise.context.ApplicationScoped @jakarta.inject.Named("service")
        public class NamedService implements Service { }
        """);
    AnalysisContext context = context(
        AnalysisContext.Framework.QUARKUS,
        sourceRoot,
        null,
        List.of(new AnalysisContext.FrameworkMetadata(
            "quarkus-cdi-annotations", "source_annotations", AnalysisContext.MetadataStatus.OBSERVED,
            "src/main/java")));

    SemanticCallFacts.Fact injection = new JavaAnalysisAdapter().analyze(context, AnalysisGraph.empty())
        .frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .findFirst().orElseThrow();
    assertEquals("demo.NamedService#<init>", injection.callee());
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, injection.certainty());
  }

  /** Distinguishes CDI producers from JAX-RS producers and ignores commented declarations. */
  @Test
  void quarkusProducerScanningIgnoresJaxRsAnnotationsCommentsAndUnknownTypes() throws IOException {
    Path sourceRoot = temp.resolve("producer-annotations/src/main/java");
    write(sourceRoot, "demo/Consumer.java", """
        /* class Ghost { @Inject MissingType ghost; } */
        package demo;
        import jakarta.inject.Inject;
        @jakarta.enterprise.context.ApplicationScoped public class Consumer {
          @Inject public Consumer(Token token) { }
          // @Inject Token commented;
          @Inject MissingType missing;
        }
        """);
    write(sourceRoot, "demo/Token.java", "package demo; public class Token { }\n");
    write(sourceRoot, "demo/TokenProducer.java", """
        package demo;
        @jakarta.enterprise.context.ApplicationScoped public class TokenProducer {
          @jakarta.enterprise.inject.Produces Token token() { return null; }
          @jakarta.enterprise.inject.Produces Token token(String name) { return null; }
        }
        """);
    write(sourceRoot, "demo/MultiResource.java", """
        package demo;
        import jakarta.ws.rs.Produces;
        public class MultiResource {
          @Produces("text/plain") Token token() { return null; }
        }
        """);
    AnalysisContext context = context(
        AnalysisContext.Framework.QUARKUS,
        sourceRoot,
        null,
        List.of(new AnalysisContext.FrameworkMetadata(
            "quarkus-cdi-annotations", "source_annotations", AnalysisContext.MetadataStatus.OBSERVED,
            "src/main/java")));

    List<SemanticCallFacts.Fact> injections = new JavaAnalysisAdapter()
        .analyze(context, AnalysisGraph.empty()).frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .toList();

    assertEquals(3, injections.size());
    List<SemanticCallFacts.Fact> tokenBindings = injections.stream()
        .filter(item -> item.callee().equals("demo.TokenProducer#token"))
        .toList();
    assertEquals(2, tokenBindings.size());
    assertEquals(2, tokenBindings.stream().map(SemanticCallFacts.Fact::id).distinct().count());
    assertFalse(injections.stream().anyMatch(item -> item.callee().equals("demo.MultiResource#token")));
    SemanticCallFacts.Fact unknown = injections.stream()
        .filter(item -> item.caller().contains("#missing"))
        .findFirst().orElseThrow();
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED, unknown.certainty());
    assertTrue(unknown.callee().equals("MissingType#<unresolved-bean>"));
  }

  /** Skips proxy facts for profile-excluded and definitively inactive owners. */
  @Test
  void proxyFactsRespectOwnerProfilesAndInactiveCandidateMetadata() throws IOException {
    Path sourceRoot = temp.resolve("proxy-owner-eligibility/src/main/java");
    write(sourceRoot, "demo/ProfiledService.java", """
        package demo;
        import org.springframework.context.annotation.Profile;
        import org.springframework.stereotype.Service;
        import org.springframework.transaction.annotation.Transactional;
        @Service @Profile("test") public class ProfiledService {
          @Transactional public void run() { }
        }
        """);
    write(sourceRoot, "demo/InactiveService.java", """
        package demo;
        import org.springframework.stereotype.Service;
        import org.springframework.transaction.annotation.Transactional;
        @Service public class InactiveService {
          @Transactional public void run() { }
        }
        """);
    AnalysisContext.FrameworkBindingMetadata metadata = new AnalysisContext.FrameworkBindingMetadata(
        "0.1.0",
        List.of(
            new AnalysisContext.BindingCandidateMetadata(
                "profiled", "demo.ProfiledService", List.of(), List.of(), "Service",
                "demo.ProfiledService", null, null, List.of("test"), List.of(), true, false, false,
                null, null),
            new AnalysisContext.BindingCandidateMetadata(
                "inactive", "demo.InactiveService", List.of(), List.of(), "Service",
                "demo.InactiveService", null, null, List.of(), List.of(), false, false, false,
                null, null)),
        List.of(),
        List.of(
            new AnalysisContext.ProxyTargetMetadata(
                "profiled-proxy", "profiled", "demo.ProfiledService", "run", "()V",
                "spring-aop-advisor", true, true, "selected", null, null),
            new AnalysisContext.ProxyTargetMetadata(
                "inactive-proxy", "inactive", "demo.InactiveService", "run", "()V",
                "spring-aop-advisor", true, true, "selected", null, null)));
    AnalysisContext context = context(
        AnalysisContext.Framework.SPRING_BOOT,
        sourceRoot,
        null,
        List.of(
            new AnalysisContext.FrameworkMetadata(
                "spring-bean-annotations", "source_annotations", AnalysisContext.MetadataStatus.OBSERVED,
                "src/main/java"),
            new AnalysisContext.FrameworkMetadata(
                "spring-aop-advisor-metadata", "runtime_or_build_metadata", AnalysisContext.MetadataStatus.OBSERVED,
                "target/aop.json")),
        metadata);

    List<SemanticCallFacts.Fact> proxies = new JavaAnalysisAdapter()
        .analyze(context, AnalysisGraph.empty()).frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("proxy_dispatch"))
        .toList();
    assertTrue(proxies.isEmpty());
  }

  /** Keeps ambiguous simple-name metadata possible and makes package/overload IDs unique. */
  @Test
  void ambiguousMetadataDoesNotPromoteBindingsAndFactIdsIncludeFullIdentity() throws IOException {
    Path sourceRoot = temp.resolve("ambiguous-identities/src/main/java");
    write(sourceRoot, "api/Service.java", "package api; public interface Service { void run(); }\n");
    write(sourceRoot, "one/Consumer.java", """
        package one;
        import api.Service;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(Service service) { }
        }
        """);
    write(sourceRoot, "two/Consumer.java", "package two; public class Consumer { }\n");
    write(sourceRoot, "one/ServiceImpl.java", """
        package one;
        import api.Service;
        import org.springframework.stereotype.Service;
        import org.springframework.transaction.annotation.Transactional;
        @Service public class ServiceImpl implements Service {
          @Transactional public void run() { }
        }
        """);
    write(sourceRoot, "two/ServiceImpl.java", """
        package two;
        import api.Service;
        import org.springframework.stereotype.Service;
        import org.springframework.transaction.annotation.Transactional;
        @Service public class ServiceImpl implements Service {
          @Transactional public void run() { }
        }
        """);
    AnalysisContext.FrameworkBindingMetadata metadata = new AnalysisContext.FrameworkBindingMetadata(
        "0.1.0",
        List.of(new AnalysisContext.BindingCandidateMetadata(
            "ambiguous-row", "ServiceImpl", List.of("Service"), List.of(), "Service",
            null, null, null, List.of(), List.of(), true, false, false, null, null)),
        List.of(new AnalysisContext.ResolvedBindingMetadata(
            "ambiguous-binding", "Consumer", "Service", 0, "ambiguous-row")),
        List.of());
    AnalysisContext context = context(
        AnalysisContext.Framework.SPRING_BOOT,
        sourceRoot,
        null,
        List.of(
            new AnalysisContext.FrameworkMetadata(
                "spring-bean-annotations", "source_annotations", AnalysisContext.MetadataStatus.OBSERVED,
                "src/main/java"),
            new AnalysisContext.FrameworkMetadata(
                "spring-aop-advisor-metadata", "runtime_or_build_metadata", AnalysisContext.MetadataStatus.OBSERVED,
                "target/aop.json")),
        metadata);

    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(context, AnalysisGraph.empty());
    List<SemanticCallFacts.Fact> injections = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .toList();
    assertEquals(2, injections.size());
    assertTrue(injections.stream().allMatch(item -> item.certainty() == SemanticCallFacts.Certainty.POSSIBLE));
    assertEquals(2, injections.stream().map(SemanticCallFacts.Fact::id).distinct().count());
    List<SemanticCallFacts.Fact> proxies = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("proxy_dispatch"))
        .toList();
    assertEquals(2, proxies.size());
    assertEquals(2, proxies.stream().map(SemanticCallFacts.Fact::id).distinct().count());
  }

  /** Builds the minimal production context used by adapter fixtures. */
  private AnalysisContext context(
      AnalysisContext.Framework framework,
      Path sourceRoot,
      Path generatedRoot,
      List<AnalysisContext.FrameworkMetadata> metadata) {
    return context(framework, sourceRoot, generatedRoot, metadata,
        AnalysisContext.FrameworkBindingMetadata.empty());
  }

  /** Builds a production context with optional binding metadata. */
  private AnalysisContext context(
      AnalysisContext.Framework framework,
      Path sourceRoot,
      Path generatedRoot,
      List<AnalysisContext.FrameworkMetadata> metadata,
      AnalysisContext.FrameworkBindingMetadata bindingMetadata) {
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
        .frameworkBindingMetadata(bindingMetadata)
        .provenance(AnalysisContext.Provenance.forTesting());
    if (generatedRoot != null) {
      builder.generatedSourceRoot(
          "generated-src/main/java", generatedRoot, AnalysisContext.Scope.PRODUCTION, true, true);
    }
    metadata.forEach(builder::frameworkMetadata);
    return builder.build();
  }

  /** Finds a framework fact by stable ID. */
  private static SemanticCallFacts.Fact fact(List<SemanticCallFacts.Fact> facts, String id) {
    return facts.stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
  }

  /** Finds a route fact by callee identity. */
  private static SemanticCallFacts.Fact route(SemanticCallFacts facts, String callee) {
    return facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("framework_entrypoint"))
        .filter(item -> item.callee().equals(callee))
        .findFirst()
        .orElseThrow();
  }

  /** Writes one temporary Java fixture source file. */
  private static void write(Path root, String relative, String source) throws IOException {
    Path path = root.resolve(relative);
    Files.createDirectories(path.getParent());
    Files.writeString(path, source);
  }
}
