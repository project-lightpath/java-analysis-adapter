package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

  /** Keeps fixture IDs stable only when their complete source identity is unique. */
  @Test
  void fixtureSpecificFactIdsRequireUnambiguousFullIdentity() throws IOException {
    Path quarkusSource = temp.resolve("id-collisions/quarkus/src/main/java");
    Path quarkusGenerated = temp.resolve("id-collisions/quarkus/generated-src/main/java");
    write(quarkusGenerated, "one/GeneratedTransferMapper.java", """
        package one;
        public class GeneratedTransferMapper {
          public String toResponse(String value) { return value; }
          public String toResponse(int value) { return Integer.toString(value); }
        }
        """);
    write(quarkusGenerated, "two/GeneratedTransferMapper.java", """
        package two;
        public class GeneratedTransferMapper {
          public String toResponse(String value) { return value; }
        }
        """);
    write(quarkusSource, "one/AuthCallback.java", """
        package one;
        public interface AuthCallback {
          void onAuthenticated(String user);
          void onAuthenticated(int user);
        }
        """);
    write(quarkusSource, "two/AuthCallback.java", """
        package two;
        public interface AuthCallback {
          void onAuthenticated(String user);
        }
        """);
    write(quarkusSource, "one/AuthProxyFactory.java", """
        package one;
        import java.lang.reflect.Proxy;
        public class AuthProxyFactory {
          AuthCallback create() {
            return (AuthCallback) Proxy.newProxyInstance(
                AuthCallback.class.getClassLoader(),
                new Class<?>[] {AuthCallback.class},
                (proxy, method, args) -> null);
          }
        }
        """);
    write(quarkusSource, "two/AuthProxyFactory.java", """
        package two;
        import java.lang.reflect.Proxy;
        public class AuthProxyFactory {
          AuthCallback create() {
            return (AuthCallback) Proxy.newProxyInstance(
                AuthCallback.class.getClassLoader(),
                new Class<?>[] {AuthCallback.class},
                (proxy, method, args) -> null);
          }
        }
        """);

    SemanticCallFacts quarkusFacts = new JavaAnalysisAdapter().analyze(
        context(
            AnalysisContext.Framework.QUARKUS,
            quarkusSource,
            quarkusGenerated,
            List.of(new AnalysisContext.FrameworkMetadata(
                "quarkus-generated-source-root",
                "generated_source_root",
                AnalysisContext.MetadataStatus.OBSERVED,
                "generated-src/main/java"))),
        AnalysisGraph.empty());
    List<SemanticCallFacts.Fact> generatedFacts = quarkusFacts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("generated_implementation"))
        .toList();
    assertEquals(3, generatedFacts.size());
    assertEquals(3, generatedFacts.stream().map(SemanticCallFacts.Fact::id).distinct().count());
    assertFalse(generatedFacts.stream().anyMatch(item -> item.id().equals("quarkus-generated-mapper-binding")));

    List<SemanticCallFacts.Fact> dynamicProxyFacts = quarkusFacts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("proxy_dispatch"))
        .toList();
    assertEquals(3, dynamicProxyFacts.size());
    assertEquals(3, dynamicProxyFacts.stream().map(SemanticCallFacts.Fact::id).distinct().count());
    assertFalse(dynamicProxyFacts.stream().anyMatch(item -> item.id().equals("quarkus-unbounded-proxy-abstention")));

    Path springSource = temp.resolve("id-collisions/spring/src/main/java");
    write(springSource, "first/AccountRepository.java", """
        package first;
        public interface AccountRepository
            extends org.springframework.data.jpa.repository.JpaRepository<Account, Long> { }
        """);
    write(springSource, "second/AccountRepository.java", """
        package second;
        public interface AccountRepository
            extends org.springframework.data.jpa.repository.JpaRepository<Account, Long> { }
        """);
    write(springSource, "first/SecurityConfig.java", """
        package first;
        import org.springframework.context.annotation.Bean;
        import org.springframework.context.annotation.Configuration;
        @Configuration
        public class SecurityConfig {
          @Bean
          public Object securityFilterChain(Object http) { return http; }
          @Bean
          public Object securityFilterChain(String http) { return http; }
        }
        """);
    write(springSource, "second/SecurityConfig.java", """
        package second;
        import org.springframework.context.annotation.Bean;
        import org.springframework.context.annotation.Configuration;
        @Configuration
        public class SecurityConfig {
          @Bean
          public Object securityFilterChain(Object http) { return http; }
        }
        """);
    write(springSource, "first/DataSeeder.java", """
        package first;
        import org.springframework.boot.CommandLineRunner;
        import org.springframework.stereotype.Component;
        @Component
        public class DataSeeder implements CommandLineRunner {
          public void run(String... args) { }
        }
        """);
    write(springSource, "second/DataSeeder.java", """
        package second;
        import org.springframework.boot.CommandLineRunner;
        import org.springframework.stereotype.Component;
        @Component
        public class DataSeeder implements CommandLineRunner {
          public void run(String... args) { }
        }
        """);

    SemanticCallFacts springFacts = new JavaAnalysisAdapter().analyze(
        context(
            AnalysisContext.Framework.SPRING_BOOT,
            springSource,
            null,
            List.of(
                new AnalysisContext.FrameworkMetadata(
                    "spring-bean-annotations",
                    "source_annotations",
                    AnalysisContext.MetadataStatus.OBSERVED,
                    "src/main/java"),
                new AnalysisContext.FrameworkMetadata(
                    "spring-data-repository-contract",
                    "source_interface_metadata",
                    AnalysisContext.MetadataStatus.OBSERVED,
                    "src/main/java"))),
        AnalysisGraph.empty());
    List<SemanticCallFacts.Fact> repositoryFacts = springFacts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("generated_implementation"))
        .toList();
    assertEquals(2, repositoryFacts.size());
    assertEquals(2, repositoryFacts.stream().map(SemanticCallFacts.Fact::id).distinct().count());
    assertFalse(repositoryFacts.stream().anyMatch(item -> item.id().equals("spring-data-generated-account-repository")));

    List<SemanticCallFacts.Fact> lifecycleFacts = springFacts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("lifecycle_callback"))
        .toList();
    assertEquals(5, lifecycleFacts.size());
    assertEquals(5, lifecycleFacts.stream().map(SemanticCallFacts.Fact::id).distinct().count());
    assertFalse(lifecycleFacts.stream().anyMatch(item -> item.id().equals("spring-bean-factory-to-security-config")));
    assertFalse(lifecycleFacts.stream().anyMatch(item -> item.id().equals("spring-boot-to-data-seeder")));
  }

  /** Avoids reusing legacy injection IDs when one owner has multiple injection sites. */
  @Test
  void repeatedLegacyInjectionSitesReceiveDistinctFactIds() throws IOException {
    Path quarkusSource = temp.resolve("repeated-injection-ids/quarkus/src/main/java");
    write(quarkusSource, "demo/TransferResource.java", """
        package demo;
        import jakarta.inject.Inject;
        import jakarta.ws.rs.Path;
        @Path("/transfers") public class TransferResource {
          @Inject DefaultTransferService first;
          @Inject DefaultTransferService second;
        }
        """);
    write(quarkusSource, "demo/DefaultTransferService.java", """
        package demo;
        @jakarta.enterprise.context.ApplicationScoped
        public class DefaultTransferService { }
        """);

    SemanticCallFacts quarkusFacts = new JavaAnalysisAdapter().analyze(
        context(
            AnalysisContext.Framework.QUARKUS,
            quarkusSource,
            null,
            List.of(new AnalysisContext.FrameworkMetadata(
                "quarkus-cdi-annotations",
                "source_annotations",
                AnalysisContext.MetadataStatus.OBSERVED,
                "src/main/java"))),
        AnalysisGraph.empty());
    List<SemanticCallFacts.Fact> quarkusInjections = quarkusFacts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .toList();
    assertEquals(2, quarkusInjections.size());
    assertEquals(2, quarkusInjections.stream().map(SemanticCallFacts.Fact::id).distinct().count());
    assertTrue(quarkusInjections.stream().noneMatch(
        item -> item.id().equals("quarkus-resource-to-cdi-bean")));

    Path springSource = temp.resolve("repeated-injection-ids/spring/src/main/java");
    write(springSource, "demo/TransferController.java", """
        package demo;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.web.bind.annotation.RestController;
        @RestController public class TransferController {
          @Autowired TransactionService first;
          @Autowired TransactionService second;
        }
        """);
    write(springSource, "demo/TransactionService.java", """
        package demo;
        import org.springframework.stereotype.Service;
        @Service public class TransactionService { }
        """);

    SemanticCallFacts springFacts = new JavaAnalysisAdapter().analyze(
        context(
            AnalysisContext.Framework.SPRING_BOOT,
            springSource,
            null,
            List.of(new AnalysisContext.FrameworkMetadata(
                "spring-bean-annotations",
                "source_annotations",
                AnalysisContext.MetadataStatus.OBSERVED,
                "src/main/java"))),
        AnalysisGraph.empty());
    List<SemanticCallFacts.Fact> springInjections = springFacts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .toList();
    assertEquals(2, springInjections.size());
    assertEquals(2, springInjections.stream().map(SemanticCallFacts.Fact::id).distinct().count());
    assertTrue(springInjections.stream().noneMatch(
        item -> item.id().equals("spring-controller-to-service-injection")));
  }

  /** Includes overloaded injection method signatures in fallback fact IDs. */
  @Test
  void overloadedInjectionMethodsReceiveDistinctFactIds() throws IOException {
    Path sourceRoot = temp.resolve("overloaded-injection-ids/quarkus/src/main/java");
    write(sourceRoot, "demo/TransferResource.java", """
        package demo;
        import jakarta.inject.Inject;
        import jakarta.ws.rs.Path;
        @Path("/transfers") public class TransferResource {
          @Inject void set(DefaultTransferService service) { }
          @Inject void set(DefaultTransferService service, OtherService other) { }
        }
        """);
    write(sourceRoot, "demo/DefaultTransferService.java", """
        package demo;
        @jakarta.enterprise.context.ApplicationScoped
        public class DefaultTransferService { }
        """);
    write(sourceRoot, "demo/OtherService.java", """
        package demo;
        @jakarta.enterprise.context.ApplicationScoped
        public class OtherService { }
        """);

    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(
        context(
            AnalysisContext.Framework.QUARKUS,
            sourceRoot,
            null,
            List.of(new AnalysisContext.FrameworkMetadata(
                "quarkus-cdi-annotations",
                "source_annotations",
                AnalysisContext.MetadataStatus.OBSERVED,
                "src/main/java"))),
        AnalysisGraph.empty());
    List<SemanticCallFacts.Fact> defaultServiceInjections = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .filter(item -> item.callee().equals("demo.DefaultTransferService#<init>"))
        .toList();

    assertEquals(2, defaultServiceInjections.size());
    assertEquals(2, defaultServiceInjections.stream()
        .map(SemanticCallFacts.Fact::id)
        .distinct()
        .count());
    assertTrue(defaultServiceInjections.stream()
        .noneMatch(item -> item.id().equals("quarkus-resource-to-cdi-bean")));
  }

  /** Includes nested injection owners and source offsets in fallback fact IDs. */
  @Test
  void nestedInjectionSitesReceiveDistinctFactIds() throws IOException {
    Path sourceRoot = temp.resolve("nested-injection-ids/quarkus/src/main/java");
    write(sourceRoot, "demo/TransferResource.java", """
        package demo;
        import jakarta.inject.Inject;
        import jakarta.ws.rs.Path;
        @Path("/transfers") public class TransferResource {
          static class First {
            @Inject DefaultTransferService service;
          }
          static class Second {
            @Inject DefaultTransferService service;
          }
        }
        """);
    write(sourceRoot, "demo/DefaultTransferService.java", """
        package demo;
        @jakarta.enterprise.context.ApplicationScoped
        public class DefaultTransferService { }
        """);

    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(
        context(
            AnalysisContext.Framework.QUARKUS,
            sourceRoot,
            null,
            List.of(new AnalysisContext.FrameworkMetadata(
                "quarkus-cdi-annotations",
                "source_annotations",
                AnalysisContext.MetadataStatus.OBSERVED,
                "src/main/java"))),
        AnalysisGraph.empty());
    List<SemanticCallFacts.Fact> injections = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .filter(item -> item.callee().equals("demo.DefaultTransferService#<init>"))
        .toList();

    assertEquals(2, injections.size());
    assertEquals(2, injections.stream().map(SemanticCallFacts.Fact::id).distinct().count());
    assertTrue(injections.stream().anyMatch(item -> item.caller().contains("demo.TransferResource$First")));
    assertTrue(injections.stream().anyMatch(item -> item.caller().contains("demo.TransferResource$Second")));
  }

  /** Matches resolved binding metadata against nested injection owners. */
  @Test
  void resolvedBindingMatchesNestedInjectionOwner() throws IOException {
    Path sourceRoot = temp.resolve("nested-binding-owner/quarkus/src/main/java");
    write(sourceRoot, "demo/TransferResource.java", """
        package demo;
        import jakarta.inject.Inject;
        import jakarta.ws.rs.Path;
        @Path("/transfers") public class TransferResource {
          static class First {
            @Inject Service service;
          }
        }
        """);
    write(sourceRoot, "demo/Service.java", "package demo; public interface Service { }\n");
    write(sourceRoot, "demo/DefaultService.java", """
        package demo;
        @jakarta.enterprise.context.ApplicationScoped
        public class DefaultService implements Service { }
        """);
    write(sourceRoot, "demo/OtherService.java", """
        package demo;
        @jakarta.enterprise.context.ApplicationScoped
        public class OtherService implements Service { }
        """);

    AnalysisContext.FrameworkBindingMetadata bindingMetadata =
        new AnalysisContext.FrameworkBindingMetadata(
            "0.1.0",
            List.of(
                new AnalysisContext.BindingCandidateMetadata(
                    "default", "demo.DefaultService", List.of("Service"), List.of(), "bean",
                    true, false, false),
                new AnalysisContext.BindingCandidateMetadata(
                    "other", "demo.OtherService", List.of("Service"), List.of(), "bean",
                    true, false, false)),
            List.of(new AnalysisContext.ResolvedBindingMetadata(
                "first-service",
                "demo.TransferResource$First",
                "service",
                "Service",
                0,
                List.of(),
                "default",
                true,
                true,
                "selected nested binding",
                null,
                null)),
            List.of());

    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(
        context(
            AnalysisContext.Framework.QUARKUS,
            sourceRoot,
            null,
            List.of(new AnalysisContext.FrameworkMetadata(
                "quarkus-cdi-annotations",
                "source_annotations",
                AnalysisContext.MetadataStatus.OBSERVED,
                "src/main/java")),
            bindingMetadata),
        AnalysisGraph.empty());
    List<SemanticCallFacts.Fact> injections = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .toList();

    assertEquals(1, injections.size());
    assertEquals("demo.DefaultService#<init>", injections.get(0).callee());
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, injections.get(0).certainty());
    assertEquals("first-service", injections.get(0).evidence().get(1).attributes().get("bindingMetadataId"));
  }

  /** Keeps an unqualified nested owner ambiguous across packages. */
  @Test
  void ambiguousNestedOwnerMetadataDoesNotPromoteBindings() throws IOException {
    Path sourceRoot = temp.resolve("ambiguous-nested-owner/quarkus/src/main/java");
    write(sourceRoot, "one/Outer.java", """
        package one;
        import api.Service;
        import jakarta.inject.Inject;
        import jakarta.ws.rs.Path;
        @Path("/one") public class Outer {
          static class Nested { @Inject Service service; }
        }
        """);
    write(sourceRoot, "two/Outer.java", """
        package two;
        import api.Service;
        import jakarta.inject.Inject;
        import jakarta.ws.rs.Path;
        @Path("/two") public class Outer {
          static class Nested { @Inject Service service; }
        }
        """);
    write(sourceRoot, "api/Service.java", "package api; public interface Service { }\n");
    write(sourceRoot, "one/OneService.java", """
        package one;
        @jakarta.enterprise.context.ApplicationScoped
        public class OneService implements api.Service { }
        """);
    write(sourceRoot, "two/TwoService.java", """
        package two;
        @jakarta.enterprise.context.ApplicationScoped
        public class TwoService implements api.Service { }
        """);

    AnalysisContext.FrameworkBindingMetadata bindingMetadata =
        new AnalysisContext.FrameworkBindingMetadata(
            "0.1.0",
            List.of(
                new AnalysisContext.BindingCandidateMetadata(
                    "one-service", "one.OneService", List.of("api.Service", "Service"), List.of(), "bean",
                    true, false, false),
                new AnalysisContext.BindingCandidateMetadata(
                    "two-service", "two.TwoService", List.of("api.Service", "Service"), List.of(), "bean",
                    true, false, false)),
            List.of(new AnalysisContext.ResolvedBindingMetadata(
                "ambiguous-nested-binding",
                "Outer$Nested",
                "service",
                "Service",
                null,
                List.of(),
                "one-service",
                true,
                true,
                "ambiguous unqualified nested owner",
                null,
                null)),
            List.of());

    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(
        context(
            AnalysisContext.Framework.QUARKUS,
            sourceRoot,
            null,
            List.of(new AnalysisContext.FrameworkMetadata(
                "quarkus-cdi-annotations",
                "source_annotations",
                AnalysisContext.MetadataStatus.OBSERVED,
                "src/main/java")),
            bindingMetadata),
        AnalysisGraph.empty());
    List<SemanticCallFacts.Fact> injections = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .toList();

    assertEquals(4, injections.size());
    assertTrue(injections.stream()
        .allMatch(item -> item.certainty() == SemanticCallFacts.Certainty.POSSIBLE));
    assertEquals(2, injections.stream()
        .map(SemanticCallFacts.Fact::caller)
        .map(caller -> caller.substring(0, caller.indexOf('#')))
        .distinct()
        .count());
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

  /** Resolves a bare CDI {@code @Named} field against its default field name. */
  @Test
  void bareNamedFieldUsesFieldNameForCdiQualifierMatching() throws IOException {
    Path sourceRoot = temp.resolve("cdi-bare-named/src/main/java");
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import jakarta.inject.Inject;
        import jakarta.inject.Named;
        @jakarta.enterprise.context.ApplicationScoped public class Consumer {
          @Inject @Named PaymentService payment;
        }
        """);
    write(sourceRoot, "demo/PaymentService.java", "package demo; public interface PaymentService { }\n");
    write(sourceRoot, "demo/NamedPaymentService.java", """
        package demo;
        import jakarta.inject.Named;
        @jakarta.enterprise.context.ApplicationScoped @Named("payment")
        public class NamedPaymentService implements PaymentService { }
        """);
    AnalysisContext context = context(
        AnalysisContext.Framework.QUARKUS,
        sourceRoot,
        null,
        List.of(new AnalysisContext.FrameworkMetadata(
            "quarkus-cdi-annotations", "source_annotations", AnalysisContext.MetadataStatus.OBSERVED,
            "src/main/java")));

    SemanticCallFacts.Fact injection = new JavaAnalysisAdapter()
        .analyze(context, AnalysisGraph.empty()).frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .findFirst()
        .orElseThrow();
    assertEquals("demo.NamedPaymentService#<init>", injection.callee());
    assertNotEquals(SemanticCallFacts.Certainty.UNRESOLVED, injection.certainty());
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

  /** Uses evaluated profile metadata and skips definitively inactive owners. */
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
    assertEquals(1, proxies.size());
    assertEquals("demo.ProfiledService#run", proxies.get(0).callee());
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, proxies.get(0).certainty());
  }

  /** Keeps unsupported Spring default and expression profiles unresolved. */
  @Test
  void unknownSpringProfileSelectionDoesNotUseMavenProfileAsRuntimeEvidence() throws IOException {
    Path sourceRoot = temp.resolve("unknown-spring-profiles/src/main/java");
    write(sourceRoot, "demo/DefaultProfileService.java", """
        package demo;
        import org.springframework.context.annotation.Profile;
        import org.springframework.stereotype.Service;
        @Service @Profile("default") public class DefaultProfileService { }
        """);
    write(sourceRoot, "demo/ExpressionProfileService.java", """
        package demo;
        import org.springframework.context.annotation.Profile;
        import org.springframework.stereotype.Service;
        @Service @Profile("prod | test") public class ExpressionProfileService { }
        """);
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(
              DefaultProfileService defaultProfileService,
              ExpressionProfileService expressionProfileService) { }
        }
        """);
    AnalysisContext context = context(
        AnalysisContext.Framework.SPRING_BOOT,
        sourceRoot,
        null,
        List.of(new AnalysisContext.FrameworkMetadata(
            "spring-bean-annotations", "source_annotations", AnalysisContext.MetadataStatus.OBSERVED,
            "src/main/java")));

    List<SemanticCallFacts.Fact> injections = new JavaAnalysisAdapter()
        .analyze(context, AnalysisGraph.empty()).frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .toList();
    assertEquals(2, injections.size());
    assertTrue(injections.stream().allMatch(item ->
        item.certainty() == SemanticCallFacts.Certainty.UNRESOLVED));
  }

  /** Prevents an active metadata-only competitor from being ignored. */
  @Test
  void activeMetadataOnlyCompetitorKeepsSourceBindingPossible() throws IOException {
    Path sourceRoot = temp.resolve("metadata-only-competitor/src/main/java");
    write(sourceRoot, "demo/Contract.java", "package demo; public interface Contract { }\n");
    write(sourceRoot, "demo/SourceService.java", """
        package demo;
        import org.springframework.stereotype.Service;
        @Service public class SourceService implements Contract { }
        """);
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(Contract contract) { }
        }
        """);
    AnalysisContext.FrameworkBindingMetadata metadata = new AnalysisContext.FrameworkBindingMetadata(
        "0.1.0",
        List.of(new AnalysisContext.BindingCandidateMetadata(
            "external-service",
            "external.ExternalService",
            List.of("demo.Contract"),
            List.of(),
            "Service",
            "external.ExternalService",
            null,
            null,
            List.of(),
            List.of(),
            true,
            false,
            false,
            null,
            null)),
        List.of(),
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
    assertEquals("demo.SourceService#<init>", injections.get(0).callee());
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, injections.get(0).certainty());
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

  /** Uses producer descriptors for overloads and resolves primitive and array return types. */
  @Test
  void producerMetadataUsesDescriptorsAndResolvesPrimitiveArrayTypes() throws IOException {
    Path sourceRoot = temp.resolve("producer-descriptors/src/main/java");
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import jakarta.inject.Inject;
        @jakarta.enterprise.context.ApplicationScoped public class Consumer {
          @Inject Token token;
          @Inject String[] names;
          @Inject int count;
        }
        """);
    write(sourceRoot, "demo/Token.java", "package demo; public class Token { }\n");
    write(sourceRoot, "demo/Producer.java", """
        package demo;
        @jakarta.enterprise.context.ApplicationScoped public class Producer {
          @jakarta.enterprise.inject.Produces Token token() { return null; }
          @jakarta.enterprise.inject.Produces Token token(String name) { return null; }
          @jakarta.enterprise.inject.Produces String[] names() { return null; }
          @jakarta.enterprise.inject.Produces int count() { return 0; }
        }
        """);
    write(sourceRoot, "demo/RestResource.java", """
        package demo;
        import jakarta.enterprise.inject.*;
        import jakarta.ws.rs.Produces;
        @jakarta.enterprise.context.ApplicationScoped public class RestResource {
          @Produces("text/plain") Token response() { return null; }
        }
        """);
    AnalysisContext.FrameworkBindingMetadata metadata = new AnalysisContext.FrameworkBindingMetadata(
        "0.1.0",
        List.of(
            new AnalysisContext.BindingCandidateMetadata(
                "token-noarg-row", "demo.Token", List.of(), List.of(), "producer",
                "demo.Producer", "token", "()Ldemo/Token;", List.of(), List.of(), false,
                false, false, null, null),
            new AnalysisContext.BindingCandidateMetadata(
                "token-arg-row", "demo.Token", List.of(), List.of(), "producer",
                "demo.Producer", "token", "(Ljava/lang/String;)Ldemo/Token;", List.of(), List.of(), true,
                false, false, null, null),
            new AnalysisContext.BindingCandidateMetadata(
                "token-class-row", "demo.Token", List.of(), List.of(), "Component",
                null, null, null, List.of(), List.of(), false, false, false, null, null)),
        List.of(new AnalysisContext.ResolvedBindingMetadata(
            "consumer-token", "demo.Consumer", "token", "Token", null, List.of(),
            "token-arg-row", true, true, "selected overload", null, null)),
        List.of());
    AnalysisContext context = context(
        AnalysisContext.Framework.QUARKUS,
        sourceRoot,
        null,
        List.of(new AnalysisContext.FrameworkMetadata(
            "quarkus-cdi-annotations", "source_annotations", AnalysisContext.MetadataStatus.OBSERVED,
            "src/main/java")),
        metadata);

    List<SemanticCallFacts.Fact> injections = new JavaAnalysisAdapter()
        .analyze(context, AnalysisGraph.empty()).frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .toList();
    assertEquals(3, injections.size());
    SemanticCallFacts.Fact token = injections.stream()
        .filter(item -> item.callee().equals("demo.Producer#token"))
        .findFirst().orElseThrow();
    assertEquals("demo.Producer#token", token.callee());
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, token.certainty());
    assertTrue(token.evidence().stream().anyMatch(item ->
        "token-arg-row".equals(item.attributes().get("candidateMetadataId"))), token.toString());
    assertTrue(injections.stream().anyMatch(item -> item.callee().equals("demo.Producer#names")));
    assertTrue(injections.stream().anyMatch(item -> item.callee().equals("demo.Producer#count")));
    assertFalse(injections.stream().anyMatch(item -> item.callee().equals("demo.RestResource#response")));
  }

  /** Keeps superclass and interface contracts separate for inherited injection matching. */
  @Test
  void inheritedSuperclassContractMatchesInjectionWithoutCapturingImplementsClause() throws IOException {
    Path sourceRoot = temp.resolve("inheritance-contracts/src/main/java");
    write(sourceRoot, "demo/ParentService.java", "package demo; public class ParentService { }\n");
    write(sourceRoot, "demo/ServiceContract.java", "package demo; public interface ServiceContract { }\n");
    write(sourceRoot, "demo/ChildService.java", """
        package demo;
        @jakarta.enterprise.context.ApplicationScoped
        public class ChildService extends ParentService implements ServiceContract { }
        """);
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import jakarta.inject.Inject;
        @jakarta.enterprise.context.ApplicationScoped public class Consumer {
          @Inject ParentService service;
        }
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
    assertEquals("demo.ChildService#<init>", injection.callee());
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, injection.certainty());
  }

  /** Excludes Spring methods that proxy infrastructure cannot intercept. */
  @Test
  void springProxyTargetsExcludePrivateAndStaticMethods() throws IOException {
    Path sourceRoot = temp.resolve("spring-proxy-modifiers/src/main/java");
    write(sourceRoot, "demo/ProxyService.java", """
        package demo;
        import org.springframework.scheduling.annotation.Async;
        import org.springframework.stereotype.Service;
        @Async @Service public class ProxyService {
          ProxyService() { }
          public String visible() { return helper(); }
          private void hidden() { }
          static void staticWork() { }
          private String helper() { return ""; }
        }
        """);
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
                "target/aop.json")));

    List<SemanticCallFacts.Fact> proxies = new JavaAnalysisAdapter()
        .analyze(context, AnalysisGraph.empty()).frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("proxy_dispatch"))
        .toList();
    assertEquals(1, proxies.size());
    assertEquals("demo.ProxyService#visible", proxies.get(0).callee());
  }

  /** Keeps same-named contracts package-aware instead of promoting the wrong implementation. */
  @Test
  void sameNamedContractsDoNotPromoteAnImplementationFromAnotherPackage() throws IOException {
    Path sourceRoot = temp.resolve("same-named-contracts/src/main/java");
    write(sourceRoot, "api/Service.java", "package api; public interface Service { }\n");
    write(sourceRoot, "other/Service.java", "package other; public interface Service { }\n");
    write(sourceRoot, "demo/ApiService.java", """
        package demo;
        import api.Service;
        @org.springframework.stereotype.Service public class ApiService implements Service { }
        """);
    write(sourceRoot, "demo/OtherService.java", """
        package demo;
        import other.Service;
        @org.springframework.stereotype.Service public class OtherService implements Service { }
        """);
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import api.Service;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(Service service) { }
        }
        """);
    AnalysisContext.FrameworkBindingMetadata metadata = new AnalysisContext.FrameworkBindingMetadata(
        "0.1.0",
        List.of(new AnalysisContext.BindingCandidateMetadata(
            "other-row", "demo.OtherService", List.of("Service"), List.of(), "Service",
            "demo.OtherService", null, null, List.of(), List.of(), true, false, false, null, null)),
        List.of(),
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
    assertEquals("demo.ApiService#<init>", injections.get(0).callee());
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, injections.get(0).certainty());
  }

  /** Keeps selected resource bindings from being hidden by inactive default-name candidates. */
  @Test
  void selectedBindingIgnoresInactiveDefaultResourceNameCandidate() throws IOException {
    Path sourceRoot = temp.resolve("active-resource-binding/src/main/java");
    write(sourceRoot, "demo/Contract.java", "package demo; public interface Contract { }\n");
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import jakarta.annotation.Resource;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Resource Contract service;
        }
        """);
    write(sourceRoot, "demo/Service.java", """
        package demo;
        import org.springframework.stereotype.Component;
        @Component public class Service implements Contract { }
        """);
    write(sourceRoot, "demo/PreferredService.java", """
        package demo;
        import org.springframework.stereotype.Component;
        @Component public class PreferredService implements Contract { }
        """);
    AnalysisContext.FrameworkBindingMetadata metadata = new AnalysisContext.FrameworkBindingMetadata(
        "0.1.0",
        List.of(
            new AnalysisContext.BindingCandidateMetadata(
                "inactive-service", "demo.Service", List.of("demo.Contract"), List.of(), "Component",
                "demo.Service", null, null, List.of(), List.of(), false, false, false, null, null),
            new AnalysisContext.BindingCandidateMetadata(
                "preferred-service", "demo.PreferredService", List.of("demo.Contract"), List.of(), "Component",
                "demo.PreferredService", null, null, List.of(), List.of(), true, false, false, null, null)),
        List.of(new AnalysisContext.ResolvedBindingMetadata(
            "consumer-service", "demo.Consumer", "service", "Contract", null, List.of(),
            "preferred-service", true, true, "active selected binding", null, null)),
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
    assertEquals("demo.PreferredService#<init>", injections.get(0).callee());
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, injections.get(0).certainty());
  }

  /** Scans field and method injection on an unscoped Quarkus REST resource. */
  @Test
  void quarkusPathResourcesIncludeFieldAndMethodInjectionPoints() throws IOException {
    Path sourceRoot = temp.resolve("quarkus-resource-injection/src/main/java");
    write(sourceRoot, "demo/Service.java", "package demo; public interface Service { }\n");
    write(sourceRoot, "demo/ServiceImpl.java", """
        package demo;
        @jakarta.enterprise.context.ApplicationScoped
        public class ServiceImpl implements Service { }
        """);
    write(sourceRoot, "demo/Resource.java", """
        package demo;
        import jakarta.inject.Inject;
        import jakarta.ws.rs.Path;
        @Path("/resource") public class Resource {
          @Inject Service service;
          @Inject void setService(Service service) { this.service = service; }
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
        .filter(item -> item.caller().contains("demo.Resource"))
        .toList();
    assertEquals(2, injections.size());
    assertTrue(injections.stream().anyMatch(item -> item.caller().contains("#service")));
    assertTrue(injections.stream().anyMatch(item -> item.caller().contains("#setService")));
    assertTrue(injections.stream().allMatch(item -> item.callee().equals("demo.ServiceImpl#<init>")));
  }

  /** Uses proxy descriptors to link only the matching overloaded provider target. */
  @Test
  void quarkusProxyFilteringAndProviderLinksUseFullMethodDescriptors() throws IOException {
    Path sourceRoot = temp.resolve("quarkus-proxy-descriptors/src/main/java");
    write(sourceRoot, "demo/ProxyService.java", """
        package demo;
        @jakarta.enterprise.context.ApplicationScoped public class ProxyService {
          public void run() { }
          public void run(String value) { }
          public String visible() {
            return
                helper();
          }
          private void hidden() { }
          static void staticWork() { }
          private String helper() { return ""; }
        }
        """);
    CallEdge providerEdge = new CallEdge(
        new MethodSymbol("demo.Caller", "invoke", "()V"),
        new MethodSymbol("demo.ProxyService", "run", "(Ljava/lang/String;)V"),
        12,
        "src/main/java/demo/Caller.java",
        SemanticCallFacts.Certainty.POSSIBLE,
        "interface_dispatch");
    AnalysisContext context = context(
        AnalysisContext.Framework.QUARKUS,
        sourceRoot,
        null,
        List.of(new AnalysisContext.FrameworkMetadata(
            "quarkus-cdi-annotations", "source_annotations", AnalysisContext.MetadataStatus.OBSERVED,
            "src/main/java")));

    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(
        context,
        new AnalysisGraph(List.of(providerEdge), List.of(), Set.of(), "SootUp", "3.0.1", 1, 0));
    List<SemanticCallFacts.Fact> proxies = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("proxy_dispatch"))
        .toList();
    assertEquals(3, proxies.size());
    assertFalse(proxies.stream().anyMatch(item -> item.callee().equals("demo.ProxyService#hidden")));
    assertFalse(proxies.stream().anyMatch(item -> item.callee().equals("demo.ProxyService#staticWork")));
    assertFalse(proxies.stream().anyMatch(item -> item.callee().equals("demo.ProxyService#helper")));

    List<SemanticCallFacts.Link> links = facts.links().stream()
        .filter(link -> link.providerFactId().equals(facts.providerFacts().get(0).id()))
        .toList();
    assertEquals(1, links.size());
    SemanticCallFacts.Fact linkedProxy = facts.frameworkFacts().stream()
        .filter(item -> item.id().equals(links.get(0).frameworkFactId()))
        .findFirst()
        .orElseThrow();
    assertEquals("(Ljava/lang/String;)V", linkedProxy.evidence().stream()
        .map(item -> item.attributes().get("targetDescriptor"))
        .filter(value -> value != null)
        .findFirst()
        .orElseThrow());
  }

  /** Keeps descriptor-less overloaded proxy metadata possible and unlinked. */
  @Test
  void descriptorlessOverloadedProxyMetadataDoesNotEstablishAnExactLink() throws IOException {
    Path sourceRoot = temp.resolve("descriptorless-overloaded-proxy/src/main/java");
    write(sourceRoot, "demo/OverloadedProxy.java", """
        package demo;
        import org.springframework.transaction.annotation.Transactional;
        @org.springframework.stereotype.Component public class OverloadedProxy {
          @Transactional public void run() { }
          @Transactional public void run(String value) { }
        }
        """);
    AnalysisContext.FrameworkBindingMetadata metadata = new AnalysisContext.FrameworkBindingMetadata(
        "0.1.0",
        List.of(),
        List.of(),
        List.of(new AnalysisContext.ProxyTargetMetadata(
            "overloaded-run",
            "demo.OverloadedProxy",
            "demo.OverloadedProxy",
            "run",
            null,
            "spring-aop-advisor",
            true,
            true,
            "selected by method name only",
            null,
            "ambiguous-provider")));
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

    SemanticCallFacts facts = new JavaAnalysisAdapter()
        .analyze(context, AnalysisGraph.empty());
    List<SemanticCallFacts.Fact> proxies = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("proxy_dispatch"))
        .toList();
    assertEquals(2, proxies.size());
    assertTrue(proxies.stream().allMatch(item ->
        item.certainty() == SemanticCallFacts.Certainty.POSSIBLE
            && item.derivedFrom() == null));
    assertTrue(facts.links().isEmpty());
  }

  /** Avoids choosing a provider identity from conflicting proxy metadata rows. */
  @Test
  void conflictingProxyProviderRowsDoNotCreateAnAmbiguousLink() throws IOException {
    Path sourceRoot = temp.resolve("conflicting-proxy-providers/src/main/java");
    write(sourceRoot, "demo/ProxyService.java", """
        package demo;
        @jakarta.enterprise.context.ApplicationScoped public class ProxyService {
          public void run() { }
        }
        """);
    AnalysisContext.FrameworkBindingMetadata metadata = new AnalysisContext.FrameworkBindingMetadata(
        "0.1.0",
        List.of(),
        List.of(),
        List.of(
            new AnalysisContext.ProxyTargetMetadata(
                "proxy-a", "demo.ProxyService", "demo.ProxyService", "run", "()V",
                "cdi-client-proxy", true, true, "first provider", null, "provider-a"),
            new AnalysisContext.ProxyTargetMetadata(
                "proxy-b", "demo.ProxyService", "demo.ProxyService", "run", "()V",
                "cdi-client-proxy", true, true, "second provider", null, "provider-b")));
    AnalysisContext context = context(
        AnalysisContext.Framework.QUARKUS,
        sourceRoot,
        null,
        List.of(new AnalysisContext.FrameworkMetadata(
            "quarkus-cdi-annotations", "source_annotations", AnalysisContext.MetadataStatus.OBSERVED,
            "src/main/java")),
        metadata);

    SemanticCallFacts.Fact proxy = new JavaAnalysisAdapter()
        .analyze(context, AnalysisGraph.empty()).frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("proxy_dispatch"))
        .findFirst()
        .orElseThrow();
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, proxy.certainty());
    assertEquals(null, proxy.derivedFrom());
  }

  /** Emits actual source scope and advisor annotations instead of framework defaults. */
  @Test
  void frameworkEvidenceUsesActualSourceAnnotations() throws IOException {
    Path sourceRoot = temp.resolve("source-annotation-evidence/src/main/java");
    write(sourceRoot, "demo/DependentService.java", """
        package demo;
        @jakarta.enterprise.context.Dependent public class DependentService { }
        """);
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import jakarta.inject.Inject;
        @jakarta.enterprise.context.ApplicationScoped public class Consumer {
          @Inject DependentService service;
        }
        """);
    write(sourceRoot, "demo/AsyncService.java", """
        package demo;
        @org.springframework.scheduling.annotation.Async
        @org.springframework.stereotype.Service public class AsyncService {
          public void run() { }
        }
        """);

    AnalysisContext quarkusContext = context(
        AnalysisContext.Framework.QUARKUS,
        sourceRoot,
        null,
        List.of(new AnalysisContext.FrameworkMetadata(
            "quarkus-cdi-annotations", "source_annotations", AnalysisContext.MetadataStatus.OBSERVED,
            "src/main/java")));
    SemanticCallFacts.Fact injection = new JavaAnalysisAdapter()
        .analyze(quarkusContext, AnalysisGraph.empty()).frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .findFirst()
        .orElseThrow();
    SemanticCallFacts.Evidence beanEvidence = injection.evidence().stream()
        .filter(item -> item.kind().equals("bean_candidate"))
        .findFirst()
        .orElseThrow();
    assertTrue(beanEvidence.symbols().stream().anyMatch(symbol ->
        symbol.equals("@Dependent") || symbol.endsWith(".Dependent")));
    assertFalse(beanEvidence.symbols().stream().anyMatch(symbol ->
        symbol.equals("@ApplicationScoped") || symbol.endsWith(".ApplicationScoped")));

    AnalysisContext springContext = context(
        AnalysisContext.Framework.SPRING_BOOT,
        sourceRoot,
        null,
        List.of(new AnalysisContext.FrameworkMetadata(
            "spring-bean-annotations", "source_annotations", AnalysisContext.MetadataStatus.OBSERVED,
            "src/main/java")));
    SemanticCallFacts.Fact proxy = new JavaAnalysisAdapter()
        .analyze(springContext, AnalysisGraph.empty()).frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("proxy_dispatch"))
        .findFirst()
        .orElseThrow();
    assertTrue(proxy.evidence().get(0).symbols().stream().anyMatch(symbol ->
        symbol.equals("@Async") || symbol.endsWith(".Async")));
    assertFalse(proxy.evidence().get(0).symbols().stream().anyMatch(symbol ->
        symbol.equals("@Transactional") || symbol.endsWith(".Transactional")));
  }

  /** Resolves wildcard imports for injection matching and proxy descriptors. */
  @Test
  void wildcardImportsResolveInjectionAndProxyDescriptors() throws IOException {
    Path sourceRoot = temp.resolve("wildcard-import-resolution/src/main/java");
    write(sourceRoot, "api/Service.java", "package api; public interface Service { }\n");
    write(sourceRoot, "api/ServiceImpl.java", """
        package api;
        import org.springframework.stereotype.Component;
        @Component public class ServiceImpl implements Service { }
        """);
    write(sourceRoot, "app/Consumer.java", """
        package app;
        import api.*;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(Service service) { }
        }
        """);
    write(sourceRoot, "app/WildcardProxy.java", """
        package app;
        import api.*;
        import org.springframework.transaction.annotation.Transactional;
        @org.springframework.stereotype.Component public class WildcardProxy {
          @Transactional public void invoke(Service service) { }
        }
        """);
    write(sourceRoot, "other/Service.java", "package other; public interface Service { }\n");
    write(sourceRoot, "ambiguous/Consumer.java", """
        package ambiguous;
        import api.*;
        import other.*;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(Service service) { }
        }
        """);
    AnalysisContext.FrameworkBindingMetadata metadata = new AnalysisContext.FrameworkBindingMetadata(
        "0.1.0",
        List.of(new AnalysisContext.BindingCandidateMetadata(
            "api.ServiceImpl",
            "api.ServiceImpl",
            List.of("api.Service"),
            List.of(),
            "Service",
            "api.ServiceImpl",
            null,
            null,
            List.of(),
            List.of(),
            true,
            false,
            false,
            null,
            null)),
        List.of(),
        List.of(new AnalysisContext.ProxyTargetMetadata(
            "wildcard-proxy-target",
            "app.WildcardProxy",
            "app.WildcardProxy",
            "invoke",
            "(Lapi/Service;)V",
            "spring-aop-advisor",
            true,
            true,
            "selected target",
            null,
            null)));
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

    SemanticCallFacts facts = new JavaAnalysisAdapter()
        .analyze(context, AnalysisGraph.empty());
    SemanticCallFacts.Fact injection = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .filter(item -> item.caller().contains("app.Consumer"))
        .findFirst()
        .orElseThrow();
    assertEquals("api.ServiceImpl#<init>", injection.callee());
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, injection.certainty());

    SemanticCallFacts.Fact proxy = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("proxy_dispatch"))
        .findFirst()
        .orElseThrow();
    assertEquals("app.WildcardProxy#invoke", proxy.callee());
    assertEquals(SemanticCallFacts.Certainty.DEFINITE, proxy.certainty());
    assertEquals("(Lapi/Service;)V", proxy.evidence().stream()
        .flatMap(evidence -> evidence.attributes().entrySet().stream())
        .filter(entry -> entry.getKey().equals("targetDescriptor"))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElseThrow());

    SemanticCallFacts.Fact ambiguousInjection = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .filter(item -> item.caller().contains("ambiguous.Consumer"))
        .findFirst()
        .orElseThrow();
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED, ambiguousInjection.certainty());
  }

  /** Resolves an explicit contract import before a same-package type with that name. */
  @Test
  void explicitContractImportWinsOverSamePackageType() throws IOException {
    Path sourceRoot = temp.resolve("explicit-contract-import/src/main/java");
    write(sourceRoot, "demo/Service.java", "package demo; public interface Service { }\n");
    write(sourceRoot, "other/Service.java", "package other; public interface Service { }\n");
    write(sourceRoot, "demo/ImportedService.java", """
        package demo;
        import other.Service;
        @org.springframework.stereotype.Component public class ImportedService implements Service { }
        """);
    write(sourceRoot, "demo/Consumer.java", """
        package demo;
        import other.Service;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(Service service) { }
        }
        """);
    AnalysisContext context = context(
        AnalysisContext.Framework.SPRING_BOOT,
        sourceRoot,
        null,
        List.of(new AnalysisContext.FrameworkMetadata(
            "spring-bean-annotations", "source_annotations", AnalysisContext.MetadataStatus.OBSERVED,
            "src/main/java")));

    List<SemanticCallFacts.Fact> injections = new JavaAnalysisAdapter()
        .analyze(context, AnalysisGraph.empty()).frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .toList();
    assertEquals(1, injections.size());
    assertEquals("demo.ImportedService#<init>", injections.get(0).callee());
  }

  /** Keeps open-world dynamic behavior abstained even when a provider overstates certainty. */
  @Test
  void dynamicBehaviorNeverPromotesAnalyzerEdgesToDefinite() throws IOException {
    Path sourceRoot = temp.resolve("dynamic-guards/src/test/java");
    write(sourceRoot, "demo/DynamicBehavior.java", """
        package demo;
        import java.lang.reflect.Constructor;
        import java.lang.reflect.InvocationHandler;
        import java.lang.reflect.Method;
        import java.lang.reflect.Proxy;
        public class DynamicBehavior {
          Object reflect(String typeName) throws Exception {
            Class<?> type = Class.forName(typeName);
            Method method = type.getMethod("run");
            return method.invoke(type);
          }
          Runnable proxy() {
            return (Runnable) Proxy.newProxyInstance(
                Runnable.class.getClassLoader(), new Class<?>[]{Runnable.class}, (p, m, a) -> null);
          }
        }
        """);
    write(sourceRoot, "demo/Documentation.java", """
        package demo;
        public class Documentation {
          // Class.forName, Method.invoke, Proxy.newProxyInstance
          String example = "getDeclaredMethod and Constructor.newInstance";
        }
        """);
    AnalysisContext context = context(
        AnalysisContext.Framework.QUARKUS,
        sourceRoot,
        null,
        List.of());
    CallEdge overstatedReflection = new CallEdge(
        new MethodSymbol("demo.DynamicBehavior", "reflect", "(Ljava/lang/String;)Ljava/lang/Object;"),
        new MethodSymbol("demo.DynamicBehavior", "run", "()V"),
        7,
        "src/test/java/demo/DynamicBehavior.java",
        SemanticCallFacts.Certainty.DEFINITE,
        "reflection");

    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(context, new AnalysisGraph(
        List.of(overstatedReflection),
        List.of(),
        Set.of(),
        "SootUp",
        "3.0.1",
        0,
        0));

    List<SemanticCallFacts.Fact> dynamicFacts = facts.allFacts().stream()
        .filter(item -> item.relationshipType().equals("reflection")
            || item.relationshipType().equals("proxy_dispatch"))
        .toList();
    assertTrue(dynamicFacts.stream().anyMatch(item -> item.relationshipType().equals("reflection")));
    assertTrue(dynamicFacts.stream().anyMatch(item -> item.relationshipType().equals("proxy_dispatch")));
    assertTrue(dynamicFacts.stream().allMatch(item ->
        item.certainty() == SemanticCallFacts.Certainty.UNRESOLVED
            && item.status() == SemanticCallFacts.Status.ABSTAINED));
    assertFalse(dynamicFacts.stream().anyMatch(item ->
        item.certainty() == SemanticCallFacts.Certainty.DEFINITE));
    assertFalse(facts.providerFacts().stream().anyMatch(item ->
        item.evidence().stream().anyMatch(evidence -> evidence.path().contains("Documentation.java"))));
  }

  /** Reports each dynamic site under its enclosing method without classifying ordinary invoke calls. */
  @Test
  void dynamicSitesKeepCallersAndEquivalentReflectionSpellings() throws IOException {
    Path sourceRoot = temp.resolve("dynamic-sites/src/main/java");
    write(sourceRoot, "demo/DynamicSites.java", """
        package demo;
        import java.lang.reflect.Method;
        import java.net.URLClassLoader;
        import java.lang.reflect.Proxy;
        import static java.lang.Class.forName;
        import static java.lang.reflect.Proxy.newProxyInstance;
        public class DynamicSites {
          static class AnonymousBase {
            <T> AnonymousBase(T value) {}
          }
          private Method method;
          private Method firstField, actionField;
          private Method initializedFirst = null, initializedAction = method;
          private Method comparisonFirst = (1 < 2) ? null : null, comparisonAction = null;
          private int firstFlag = 1, secondFlag = 2;
          private Method identifierFirst = (firstFlag < secondFlag) ? null : null, identifierAction = null;
          private Method genericFirst = this.<Method>genericValue(), genericAction = null;
          private Method comparisonBothFirst = firstFlag < secondFlag ? null : null,
              comparisonBothAction = firstFlag > secondFlag ? null : null;
          @SuppressWarnings({"unused", "unchecked"})
          private Method annotatedFieldFirst, annotatedFieldAction;
          @CompositeMarker(@NestedValue("field"))
          private Method nestedAnnotatedFieldFirst, nestedAnnotatedFieldAction;
          @SuppressWarnings({"unused", "unchecked"})
          private Worker annotatedWorkerFieldFirst, annotatedWorkerFieldAction;
          private Method lambdaScopeAction;
          private Method forScopeAction;
          private Worker forScopeWorkerAction;
          private Method enhancedForAction;
          private Worker enhancedForWorkerAction;
          private Method anonymousBranchAction;
          private Method switchScopeAction;
          private Worker switchScopeWorkerAction;
          private Method tryScopeAction;
          private Worker tryScopeWorkerAction;
          private Worker labeledScopeAction;
          private Method catchScopeAction;
          private Method resourceScopeAction;
          private Worker catchScopeWorkerAction;
          private Method resourceInitializerAction;
          private Worker resourceInitializerWorkerAction;
          private Method laterDeclaratorAction;
          private Worker laterDeclaratorWorkerAction;
          private Method multiForWorkerAction;
          private Worker multiForMethodAction;
          private Method fieldMethod;
          private Class<?> fieldClass;
          private ClassLoader fieldLoader;
          private Constructor<?> fieldConstructor;
          private InvocationHandler fieldHandler = (proxy, method, args) -> method.invoke(this, args);
          private InvocationHandler blockFieldHandler = (proxy, method, args) -> {
            return method.invoke(this, args);
          };
          private InvocationHandler blockLocalFieldHandler = (proxy, method, args) -> {
            Object result = method.invoke(this, args);
            return result;
          };
          private static InvocationHandler staticBlockFieldHandler = (proxy, method, args) -> {
            return method.invoke(null, args);
          };
          private static InvocationHandler staticBlockLocalFieldHandler = (proxy, method, args) -> {
            Object result = method.invoke(null, args);
            return result;
          };
          static {
            Class.forName("demo.StaticInitializer");
          }
          {
            Class.forName("demo.InstanceInitializer");
          }

          Object localClassInitializer() {
            class Local {
              java.lang.reflect.InvocationHandler handler =
                  (proxy, method, args) -> method.invoke(null, args);
            }
            return new Local();
          }

          Object anonymousClassInitializer() {
            return new Object() {
              java.lang.reflect.InvocationHandler handler =
                  (proxy, method, args) -> method.invoke(null, args);
            };
          }

          Object constructionThenBlock(String name) throws Exception {
            new Object();
            if (name != null) {
              Class.forName(name);
            }
            return null;
          }

          Object anonymousConstructorArgument(String name) throws Exception {
            return new AnonymousBase(Class.forName(name)) {};
          }

          Object explicitAnonymousConstructorArgument(String name) throws Exception {
            return new <String> AnonymousBase(Class.forName(name)) {};
          }

          Object explicitAnonymousBody(String name) {
            return new <String> AnonymousBase(null) {
              Object run() throws Exception {
                return Class.forName(name);
              }
            };
          }

          Object annotatedAnonymousConstructorArgument(String name) throws Exception {
            return new @TypeUseMarker AnonymousBase(Class.forName(name)) {};
          }

          Object annotatedAnonymousBody(String name) {
            return new @TypeUseMarker AnonymousBase(null) {
              Object run() throws Exception {
                return Class.forName(name);
              }
            };
          }

          Object spacedQualifiedAnonymousConstructorArgument(String name) throws Exception {
            return new DynamicSites . AnonymousBase(Class.forName(name)) {};
          }

          Object spacedQualifiedAnonymousBody(String name) {
            return new java . lang . Object() {
              Object run() throws Exception {
                return Class.forName(name);
              }
            };
          }

          DynamicSites(String name) throws Exception {
            Class.forName(name);
            this.method.invoke(null);
          }

          Object first(String name) throws Exception {
            Class<?> type = Class . forName(name);
            ClassLoader loader = getClass().getClassLoader();
            loader . loadClass(name);
            Method method = type.getMethod("run");
            return method . invoke(type);
          }
          Object qualifiedReceivers(String name) throws Exception {
            java.lang.Class<?> type = getClass();
            java.lang.ClassLoader loader = getClass().getClassLoader();
            type.getMethod("run");
            loader.loadClass(name);
            return null;
          }
          Object varReceiver() throws Exception {
            var method = getClass().getMethod("run");
            return method.invoke(this);
          }
          Object qualifiedVarReceiver() throws Exception {
            var method = this.getClass().getMethod("run");
            return method.invoke(this);
          }
          Object varLoader(String name) throws Exception {
            var loader = Class.forName(name).getClassLoader();
            loader.loadClass(name);
            return null;
          }
          Object urlClassLoader(String name) throws Exception {
            URLClassLoader loader = null;
            loader.loadClass(name);
            return null;
          }
          Object customClassLoader(String name) throws Exception {
            CustomLoader loader = new CustomLoader();
            loader.loadClass(name);
            return null;
          }
          <T extends ClassLoader> Object boundedParameter(T loader, String name) throws Exception {
            loader.loadClass(name);
            return null;
          }
          <T extends ClassLoader> Object boundedLocal(T loader, String name) throws Exception {
            T local = loader;
            local.loadClass(name);
            return null;
          }
          public <T extends ClassLoader> Object publicBounded(T loader, String name) throws Exception {
            loader.loadClass(name);
            return null;
          }
          static <T extends ClassLoader> Object staticBounded(T loader, String name) throws Exception {
            loader.loadClass(name);
            return null;
          }
          @Deprecated <T extends ClassLoader> Object annotatedBounded(T loader, String name)
              throws Exception {
            loader.loadClass(name);
            return null;
          }
          <@TypeUseMarker T extends @TypeUseMarker ClassLoader> Object annotatedTypeBound(
              T loader, String name) throws Exception {
            loader.loadClass(name);
            return null;
          }
          <T extends @BitwiseMarker(1 & 2) ClassLoader> Object bitwiseAnnotatedBound(
              T loader, String name) throws Exception {
            loader.loadClass(name);
            return null;
          }
          <T extends ClassLoader & Runnable> Object intersectionBound(
              T loader, String name) throws Exception {
            loader.loadClass(name);
            return null;
          }
          <@TypeUseMarker T extends Router> Object annotatedUnrelatedBound(T loader) {
            return loader.loadClass();
          }
          <T> Object unboundedParameter(T loader, String name) throws Exception {
            loader.toString();
            return null;
          }
          <T extends Router> Object unrelatedGeneric(T loader) {
            return loader.loadClass();
          }
          Object localTypeShadow() throws Exception {
            {
              class Method {
                Object invoke(Object value) { return value; }
              }
              Method local = new Method();
              local.invoke(null);
            }
            Method method = null;
            return method.invoke(null);
          }
          Object varClassLiteral() throws Exception {
            var type = String.class;
            return type.getMethod("valueOf", Object.class);
          }
          Object varClassLoader(String name) throws Exception {
            var type = String.class;
            var loader = type.getClassLoader();
            loader.loadClass(name);
            return null;
          }
          Object chainedLoader(String name) throws Exception {
            var loader = getClass().getClassLoader();
            var next = loader.loadClass(name).getClassLoader();
            next.loadClass(name);
            return null;
          }
          Object spacedVarReceiver() throws Exception {
            var method = this . getClass ().getMethod("run");
            return method.invoke(this);
          }
          Object varUnrelated(Router router) throws Exception {
            var type = router.loadClass();
            return type.getMethod("GET");
          }
          Object varScope(Service service) throws Exception {
            {
              var action = getClass().getMethod("run");
              action.invoke(this);
            }
            {
              Service action = service;
              return action.invoke();
            }
          }
          Object wrappedLookup(Wrapper wrapper) throws Exception {
            var action = wrapper.wrap(getClass().getMethod("run"));
            return action.invoke();
          }
          Object wrappedClass(Wrapper wrapper) throws Exception {
            var type = wrapper.wrap(getClass());
            return type.getMethod("run");
          }
          Object second(String name) throws Exception {
            return forName(name);
          }
          Object chained(String name) throws Exception {
            return Class.forName(name).getMethod("run").invoke(null);
          }
          Object classLiteral() throws Exception {
            return String.class.getMethod("valueOf", Object.class);
          }
          Object laterString(String name) throws Exception {
            Class.forName(name);
            return "done";
          }
          Object characterLiteral(String name) throws Exception {
            char brace = '}';
            return Class.forName(name);
          }
          Object ordinary(Service service) {
            return service.invoke();
          }
          Object unrelated(Router router) {
            return router.getMethod("GET");
          }
          Object unrelatedChained(Router router) {
            return router.getMethod("GET").invoke();
          }
          Object unrelatedConstructor(Factory factory) {
            return factory.getConstructor().newInstance();
          }
          Object fieldReceivers(String name) throws Exception {
            fieldMethod.invoke(null);
            fieldClass.getMethod("run");
            fieldLoader.loadClass(name);
            fieldConstructor.newInstance();
            return null;
          }
          Object multiFieldReceivers() throws Exception {
            return actionField.invoke(null);
          }
          Object initializedMultiFieldReceivers() throws Exception {
            return initializedAction.invoke(null);
          }
          Object comparisonMultiFieldReceivers() throws Exception {
            return comparisonAction.invoke(null);
          }
          Object identifierComparisonMultiFieldReceivers() throws Exception {
            return identifierAction.invoke(null);
          }
          Object genericMultiFieldReceivers() throws Exception {
            return genericAction.invoke(null);
          }
          Object comparisonBothMultiFieldReceivers() throws Exception {
            return comparisonBothAction.invoke(null);
          }
          Object multiLocalReceivers() throws Exception {
            Method first = null, action = null;
            return action.invoke(null);
          }
          Object identifierComparisonLocal() throws Exception {
            int left = 1, right = 2;
            Method first = (left < right) ? null : null, action = null;
            return action.invoke(null);
          }
          Object comparisonBothLocal() throws Exception {
            int left = 1, right = 2;
            Method first = left < right ? null : null, action = left > right ? null : null;
            return action.invoke(null);
          }
          Object annotatedFieldReceivers() throws Exception {
            return annotatedFieldAction.invoke(null);
          }
          Object nestedAnnotatedFieldReceivers() throws Exception {
            return nestedAnnotatedFieldAction.invoke(null);
          }
          Object annotatedWorkerFieldReceivers() {
            return annotatedWorkerFieldAction.invoke();
          }
          Object priorAnnotatedLocal() throws Exception {
            @SuppressWarnings({"unused"}) int unused = 0;
            Method action = null;
            return action.invoke(null);
          }
          Object priorAnnotatedWorkerLocal() {
            @SuppressWarnings({"unused"}) int unused = 0;
            Worker action = null;
            return action.invoke();
          }
          Object afterLambdaArgument() throws Exception {
            consume(() -> {});
            Method action = null;
            return action.invoke(null);
          }
          Object afterNestedLambdaArgument() throws Exception {
            consume(() -> consume(() -> {}));
            Method action = null;
            return action.invoke(null);
          }
          Object afterLambdaWorkerArgument() {
            consume(() -> {});
            Worker action = null;
            return action.invoke();
          }
          Object nestedLambdaBlockScope() throws Exception {
            consume(() -> {
              { Worker lambdaScopeAction = null; lambdaScopeAction.invoke(); }
              lambdaScopeAction.invoke(null);
            });
            return null;
          }
          Object controlFlowLambdaBlockScope() throws Exception {
            consume(() -> {
              if (true) {
                Worker lambdaScopeAction = null;
                lambdaScopeAction.invoke();
              }
              lambdaScopeAction.invoke(null);
            });
            return null;
          }
          Object nestedLambdaWorkerShadow() {
            consume(() -> {
              { Worker workerOnly = null; }
              workerOnly.invoke();
            });
            return null;
          }
          Object bracedForScope() throws Exception {
            for (Worker forScopeAction = null; forScopeAction != null; ) {
              forScopeAction.invoke();
            }
            return forScopeAction.invoke(null);
          }
          Object unbracedForScope() throws Exception {
            for (Worker forScopeAction = null; forScopeAction != null; ) forScopeAction.invoke();
            return forScopeAction.invoke(null);
          }
          Object methodLocalForScope() throws Exception {
            for (Method forScopeWorkerAction = null; forScopeWorkerAction != null; ) {
              forScopeWorkerAction.invoke(null);
            }
            return forScopeWorkerAction.invoke();
          }
          Object enhancedForScope() throws Exception {
            for (Worker enhancedForAction : workerValues()) enhancedForAction.invoke();
            return enhancedForAction.invoke(null);
          }
          Object enhancedForMethodScope() throws Exception {
            for (Method enhancedForWorkerAction : methodValues()) {
              enhancedForWorkerAction.invoke(null);
            }
            return enhancedForWorkerAction.invoke();
          }
          Object enhancedForIterableScope() throws Exception {
            for (Worker enhancedForAction : (Worker[]) enhancedForAction.invoke(null)) {
              enhancedForAction.invoke();
            }
            return enhancedForAction.invoke(null);
          }
          Object enhancedForMethodIterableScope() throws Exception {
            for (Method enhancedForWorkerAction : (Method[]) enhancedForWorkerAction.invoke(null)) {
              enhancedForWorkerAction.invoke(null);
            }
            return enhancedForWorkerAction.invoke();
          }
          Object forIfElseScope() throws Exception {
            for (Method enhancedForWorkerAction = null; shouldLoop(); )
              if (false) { }
              else enhancedForWorkerAction.invoke(null);
            return enhancedForWorkerAction.invoke();
          }
          Object anonymousBranchScope() throws Exception {
            for (Worker anonymousBranchAction = null; shouldLoop(); )
              if (false) new Runnable() { public void run() {} };
              else anonymousBranchAction.invoke();
            return anonymousBranchAction.invoke(null);
          }
          Object switchForScope() throws Exception {
            for (Worker switchScopeAction = null; shouldLoop(); )
              switch (1) { default: switchScopeAction.invoke(); }
            return switchScopeAction.invoke(null);
          }
          Object switchForMethodScope() throws Exception {
            for (Method switchScopeWorkerAction = null; shouldLoop(); )
              switch (1) { default: switchScopeWorkerAction.invoke(null); }
            return switchScopeWorkerAction.invoke();
          }
          Object catchScope() throws Exception {
            try {
              throw new RuntimeException();
            } catch (RuntimeException catchScopeAction) {
              catchScopeAction.getMessage();
            }
            return catchScopeAction.invoke(null);
          }
          Object catchWorkerScope() throws Exception {
            try {
              throw new RuntimeException();
            } catch (RuntimeException catchScopeWorkerAction) {
              catchScopeWorkerAction.getMessage();
            }
            return catchScopeWorkerAction.invoke();
          }
          Object resourceScope() throws Exception {
            try (CloseableWorker resourceScopeAction = null) {
              resourceScopeAction.invoke();
            }
            return resourceScopeAction.invoke(null);
          }
          Object resourceInitializerScope() throws Exception {
            try (CloseableWorker resource = makeCloseable(() -> {
              Worker resourceInitializerAction = null;
              resourceInitializerAction.invoke();
            })) {
              resourceInitializerAction.invoke(null);
            }
            return null;
          }
          Object resourceInitializerMethodScope() throws Exception {
            try (CloseableWorker resource = makeCloseable(() -> {
              Method resourceInitializerWorkerAction = null;
              resourceInitializerWorkerAction.invoke(null);
            })) {
              resourceInitializerWorkerAction.invoke();
            }
            return null;
          }
          Object laterDeclaratorBefore() throws Exception {
            Worker first = (Worker) laterDeclaratorAction.invoke(null), laterDeclaratorAction = null;
            laterDeclaratorAction.invoke();
            return null;
          }
          Object laterDeclaratorAfter() throws Exception {
            Method first = (Method) laterDeclaratorWorkerAction.invoke(), laterDeclaratorWorkerAction = null;
            laterDeclaratorWorkerAction.invoke(null);
            return null;
          }
          Object basicForMultiWorkerScope() throws Exception {
            for (Worker first = null, multiForWorkerAction = null;
                shouldLoop();
                multiForWorkerAction.invoke()) {
              multiForWorkerAction.invoke();
            }
            return multiForWorkerAction.invoke(null);
          }
          Object basicForMultiMethodScope() throws Exception {
            for (Method first = null, multiForMethodAction = null;
                shouldLoop();
                multiForMethodAction.invoke(null)) {
              multiForMethodAction.invoke(null);
            }
            return multiForMethodAction.invoke();
          }
          Object forTryFinallyScope() throws Exception {
            for (Worker tryScopeAction = null; shouldLoop(); )
              try { } finally { tryScopeAction.invoke(); }
            return tryScopeAction.invoke(null);
          }
          Object forTryMethodScope() throws Exception {
            for (Method tryScopeWorkerAction = null; shouldLoop(); )
              try { } finally { tryScopeWorkerAction.invoke(null); }
            return tryScopeWorkerAction.invoke();
          }
          Object forLabeledIfScope() throws Exception {
            for (Method labeledScopeAction = null; shouldLoop(); )
              branch: if (false) { }
              else labeledScopeAction.invoke(null);
            return labeledScopeAction.invoke();
          }
          private Worker[] workerValues() { return null; }
          private Method[] methodValues() { return null; }
          private CloseableWorker makeCloseable(Runnable task) { return null; }
          private boolean shouldLoop() { return true; }
          Object finalMultiLocalReceivers() throws Exception {
            final Method first = null, action = null;
            return action.invoke(null);
          }
          Object finalWorkerMultiLocal() {
            final Worker first = null, action = null;
            return action.invoke();
          }
          Object annotatedArrayMultiLocal() throws Exception {
            @SuppressWarnings({"unused", "unchecked"})
            final Method first = null, action = null;
            return action.invoke(null);
          }
          Object annotatedNestedMultiLocal() throws Exception {
            @CompositeMarker(@NestedValue("nested"))
            final Method first = null, action = null;
            return action.invoke(null);
          }
          Object annotatedArrayWorkerMultiLocal() {
            @SuppressWarnings({"unused", "unchecked"})
            final Worker first = null, action = null;
            return action.invoke();
          }
          Object multiFieldParameter(Worker actionField) {
            return actionField.invoke();
          }
          private <T> T genericValue() { return null; }
          private void consume(Runnable task) {}
          Object shadowed(Service method) {
            return method.invoke();
          }
          InvocationHandler handler() {
            InvocationHandler result = (proxy, method, args) -> method.invoke(new Object(), args);
            return result;
          }
          Runnable proxy() {
            return (Runnable) Proxy . newProxyInstance(
                Runnable.class.getClassLoader(), new Class<?>[]{Runnable.class}, (p, m, a) -> null);
          }
          Runnable secondProxy() {
            return (Runnable) Proxy.newProxyInstance(
                Runnable.class.getClassLoader(), new Class<?>[]{Runnable.class}, (p, m, a) -> null);
          }
          Runnable proxyWithVariable() {
            Class<?>[] interfaces = new Class<?>[]{Runnable.class};
            return (Runnable) Proxy.newProxyInstance(
                Runnable.class.getClassLoader(), interfaces, (p, m, a) -> null);
          }
          Runnable inlineInvocationProxy() {
            return (Runnable) Proxy.newProxyInstance(
                Runnable.class.getClassLoader(), new Class<?>[]{Runnable.class},
                (proxy, method, args) -> method.invoke(this, args));
          }
          Runnable inlineShortProxy() {
            return (Runnable) Proxy.newProxyInstance(
                Runnable.class.getClassLoader(), new Class<?>[]{Runnable.class},
                (p, m, a) -> m.invoke(this, a));
          }
          Runnable inlineTypedProxy() {
            return (Runnable) Proxy.newProxyInstance(
                Runnable.class.getClassLoader(), new Class<?>[]{Runnable.class},
                (Object p, Method m, Object[] a) -> m.invoke(this, a));
          }
          Runnable staticProxy() {
            return (Runnable) newProxyInstance(
                Runnable.class.getClassLoader(), new Class<?>[]{Runnable.class}, (p, m, a) -> null);
          }
          Object unrelatedProxyName(Helper helper) {
            return helper.newProxyInstance();
          }
          Runnable overloadedProxy() {
            return (Runnable) Proxy.newProxyInstance(
                Runnable.class.getClassLoader(), new Class<?>[]{Overloaded.class}, (p, m, a) -> null);
          }
          static class NestedOwner {
            static class Method {
              Object invoke(Object value) { return value; }
            }
            Object ordinaryInvoke() {
              Method method = new Method();
              return method.invoke(null);
            }
          }
        }
        @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
        @interface TypeUseMarker {}
        @interface ArrayMarker { int[] value(); }
        @interface ComparisonMarker { boolean value(); }
        @interface BitwiseMarker { int value(); }
        @interface NestedValue { String value(); }
        @interface CompositeMarker { NestedValue value(); }
        interface Service { Object invoke(); }
        interface CloseableWorker extends AutoCloseable {
          Object invoke();
          default void close() {}
        }
        interface Router { Object getMethod(String value); Object loadClass(); }
        interface Factory { Factory getConstructor(); Object newInstance(); }
        interface Wrapper { Object wrap(Object value); }
        """);
    write(sourceRoot, "StringPackageText.java", """
        class StringPackageText {
          String text = "package fake;";
          Object load(String name) { return Class.forName(name); }
        }
        """);
    write(sourceRoot, "demo/CustomLoader.java", """
        package demo;
        class CustomLoader extends java.lang.ClassLoader { }
        """);
    write(sourceRoot, "demo/AnnotatedGeneric.java", """
        package demo;
        class AnnotatedGeneric<@TypeUseMarker T extends @TypeUseMarker ClassLoader> {
          Object run(T loader, String name) throws Exception {
            loader.loadClass(name);
            return null;
          }
        }
        """);
    write(sourceRoot, "demo/AnnotatedArrayGeneric.java", """
        package demo;
        class AnnotatedArrayGeneric<@ArrayMarker({1, 2}) T extends @TypeUseMarker ClassLoader> {
          Object run(T loader, String name) throws Exception {
            loader.loadClass(name);
            return null;
          }
        }
        """);
    write(sourceRoot, "demo/ComparisonGeneric.java", """
        package demo;
        class ComparisonGeneric<@ComparisonMarker(1 < 2) T extends @TypeUseMarker ClassLoader> {
          Object run(T loader, String name) throws Exception {
            loader.loadClass(name);
            return null;
          }
        }
        """);
    write(sourceRoot, "demo/GenericBoundShadow.java", """
        package demo;
        class GenericBoundShadow<U extends ClassLoader, T extends U> {
          <U extends Router> Object run(T loader, String name) throws Exception {
            loader.loadClass(name);
            return null;
          }
        }
        """);
    write(sourceRoot, "demo/WildcardLoader.java", """
        package demo;
        import java.net.*;
        class WildcardLoader {
          Object load(String name) throws Exception {
            URLClassLoader loader = null;
            loader.loadClass(name);
            return null;
          }
        }
        """);
    write(sourceRoot, "demo/WildcardShadowMethod.java", """
        package demo;
        import shadow.*;
        class WildcardShadowMethod {
          Object invoke() {
            Method method = new Method();
            return method.invoke(null);
          }
        }
        """);
    write(sourceRoot, "wildcard/Method.java", """
        package wildcard;
        public class Method {
          public Object invoke(Object... arguments) { return null; }
        }
        """);
    write(sourceRoot, "wildcard/UsesSamePackageMethod.java", """
        package wildcard;
        import java.lang.reflect.*;
        class UsesSamePackageMethod {
          Object invoke() {
            Method method = new Method();
            return method.invoke(null);
          }
        }
        """);
    write(sourceRoot, "inherited/Base.java", """
        package inherited;
        class Base {
          protected static class Method {
            Object invoke(Object value) { return value; }
          }
        }
        """);
    write(sourceRoot, "inherited/Child.java", """
        package inherited;
        import java.lang.reflect.Method;
        class Child extends Base {
          Object ordinary() {
            Method method = new Method();
            return method.invoke(null);
          }
        }
        """);
    write(sourceRoot, "inheritedprivate/Base.java", """
        package inheritedprivate;
        class Base {
          private static class Method {
            Object invoke(Object value) { return value; }
          }
        }
        """);
    write(sourceRoot, "inheritedprivate/Child.java", """
        package inheritedprivate;
        import java.lang.reflect.Method;
        class Child extends Base {
          Object reflective() {
            Method method = null;
            return method.invoke(null);
          }
        }
        """);
    write(sourceRoot, "inheritedbase/Base.java", """
        package inheritedbase;
        public class Base {
          public static class Method {
            public Object invoke(Object value) { return value; }
          }
        }
        """);
    write(sourceRoot, "inheritedchild/Child.java", """
        package inheritedchild;
        import inheritedbase.Base;
        import java.lang.reflect.Method;
        class Child extends Base {
          Object ordinary() {
            Method method = new Method();
            return method.invoke(null);
          }
        }
        """);
    write(sourceRoot, "lexicalprecedence/Base.java", """
        package lexicalprecedence;
        class Base {
          protected static class Method extends java.lang.ClassLoader { }
        }
        """);
    write(sourceRoot, "lexicalprecedence/Outer.java", """
        package lexicalprecedence;
        import java.lang.reflect.Method;
        class Outer {
          static class Method {
            Object invoke(Object value) { return value; }
          }
          static class Inner extends Base {
            Object load(String name) throws Exception {
              Method loader = null;
              loader.loadClass(name);
              return null;
            }
          }
        }
        """);
    write(sourceRoot, "hiddenmembers/A.java", """
        package hiddenmembers;
        class A {
          protected static class Method {
            Object invoke(Object value) { return value; }
          }
        }
        """);
    write(sourceRoot, "hiddenmembers/B.java", """
        package hiddenmembers;
        class B extends A {
          protected static class Method extends java.lang.ClassLoader { }
        }
        """);
    write(sourceRoot, "hiddenmembers/C.java", """
        package hiddenmembers;
        import java.lang.reflect.Method;
        class C extends B {
          Object load(String name) throws Exception {
            Method loader = null;
            loader.loadClass(name);
            return null;
          }
        }
        """);
    write(sourceRoot, "localprecedence/LocalPrecedence.java", """
        package localprecedence;
        class LocalPrecedence {
          Object run(String name) throws Exception {
            class Method extends java.lang.ClassLoader { }
            class Other {
              class Method {
                Object loadClass(String value) { return value; }
              }
              Object ordinary(String value) {
                Method loader = null;
                return loader.loadClass(value);
              }
            }
            return new Other().ordinary(name);
          }
        }
        """);
    write(sourceRoot, "privatehide/A.java", """
        package privatehide;
        class A {
          protected static class Method {
            Object invoke(Object value) { return value; }
          }
        }
        """);
    write(sourceRoot, "privatehide/B.java", """
        package privatehide;
        class B extends A {
          private static class Method { }
        }
        """);
    write(sourceRoot, "privatehide/C.java", """
        package privatehide;
        import java.lang.reflect.Method;
        class C extends B {
          Object reflective() {
            Method method = null;
            return method.invoke(null);
          }
        }
        """);
    write(sourceRoot, "wildcard/WildcardUrlSubclass.java", """
        package wildcard;
        import java.net.*;
        class WildcardUrlSubclass extends URLClassLoader {
          WildcardUrlSubclass() { super(new java.net.URL[0]); }
          Object load(String name) throws Exception {
            WildcardUrlSubclass loader = this;
            loader.loadClass(name);
            return null;
          }
        }
        """);
    write(sourceRoot, "demo/Overloaded.java", """
        package demo;
        public interface Overloaded {
          void run(String value);
          void run(int value);
        }
        """);
    write(sourceRoot, "demo/Helper.java", """
        package demo;
        public interface Helper { Object newProxyInstance(); }
        """);
    write(sourceRoot, "demo/Outer.java", """
        package demo;
        import java.lang.reflect.Method;
        public class Outer {
          private Worker inherited;
          static class Inner {
            Inner(String name) throws Exception { Class.forName(name); }
            void run(String name) throws Exception { Class.forName(name); }
          }
          static class SiblingA {
            private Method action;
            Object reflect() throws Exception { return action.invoke(null); }
          }
          static class SiblingB {
            private Worker action;
            Object ordinary() { return action.invoke(); }
          }
          static class InheritedInner extends Base {
            Object reflectInherited() throws Exception { return inherited.invoke(null); }
          }
        }
        """);
    write(sourceRoot, "demo/Worker.java", """
        package demo;
        public interface Worker { Object invoke(); }
        """);
    write(sourceRoot, "demo/Base.java", """
        package demo;
        import java.lang.reflect.Method;
        class Base { protected Method inherited; }
        """);
    write(sourceRoot, "demo/Child.java", """
        package demo;
        class Child extends Base {
          Object run() throws Exception { return inherited.invoke(null); }
        }
        """);
    write(sourceRoot, "demo/ChildWithShadow.java", """
        package demo;
        class ChildWithShadow extends Base {
          private Worker inherited;
          Object run() { return inherited.invoke(); }
        }
        """);
    write(sourceRoot, "demo/ReverseBase.java", """
        package demo;
        class ReverseBase { protected Worker inherited; }
        """);
    write(sourceRoot, "demo/ReverseOuter.java", """
        package demo;
        import java.lang.reflect.Method;
        class ReverseOuter {
          private Method inherited;
          static class Inner extends ReverseBase {
            Object ordinary() throws Exception { return inherited.invoke(null); }
          }
        }
        """);
    write(sourceRoot, "demo/ShadowedStatic.java", """
        package demo;
        import static java.lang.reflect.Proxy.newProxyInstance;
        class ShadowedStatic {
          Object newProxyInstance(Object loader, Object interfaces, Object handler) { return null; }
          Object call() { return newProxyInstance(null, null, null); }
        }
        """);
    write(sourceRoot, "demo/StaticForNameShadow.java", """
        package demo;
        import static java.lang.Class.forName;
        class StaticForNameShadow {
          Object forName(String name) { return name; }
          Object local(String name) { return forName(name); }
          Object helper(ForNameHelper helper, String name) { return helper.forName(name); }
        }
        """);
    write(sourceRoot, "demo/InheritedDynamicApiUse.java", """
        package demo;
        import static java.lang.Class.forName;
        import static java.lang.reflect.Proxy.newProxyInstance;
        class InheritedDynamicApis {
          Object forName(String name) { return name; }
          Object newProxyInstance(Object loader, Object interfaces, Object handler) { return null; }
        }
        class InheritedDynamicApiUse extends InheritedDynamicApis {
          Object className(String name) { return forName(name); }
          Object proxy() { return newProxyInstance(null, null, null); }
        }
        """);
    write(sourceRoot, "demo/PrivateDynamicApiUse.java", """
        package demo;
        import static java.lang.Class.forName;
        import static java.lang.reflect.Proxy.newProxyInstance;
        class PrivateDynamicApiUse {
          private Object forName(String name) { return name; }
          private Object newProxyInstance(Object loader, Object interfaces, Object handler) { return null; }
          Object className(String name) { return forName(name); }
          Object proxy() { return newProxyInstance(null, null, null); }
        }
        """);
    write(sourceRoot, "demo/InheritedDynamicApiContract.java", """
        package demo;
        interface InheritedDynamicApiContract {
          default Object forName(String name) { return name; }
          default Object newProxyInstance(Object loader, Object interfaces, Object handler) { return null; }
        }
        """);
    write(sourceRoot, "demo/InheritedInterfaceDynamicApiUse.java", """
        package demo;
        import static java.lang.Class.forName;
        import static java.lang.reflect.Proxy.newProxyInstance;
        class InheritedInterfaceDynamicApiUse implements InheritedDynamicApiContract {
          Object className(String name) { return forName(name); }
          Object proxy() { return newProxyInstance(null, null, null); }
        }
        """);
    write(sourceRoot, "demo/GenericInheritedDynamicApiUse.java", """
        package demo;
        import static java.lang.Class.forName;
        import static java.lang.reflect.Proxy.newProxyInstance;
        class GenericInheritedDynamicApiUse<T extends Object> extends InheritedDynamicApis
            implements InheritedDynamicApiContract {
          Object className(String name) { return forName(name); }
          Object proxy() { return newProxyInstance(null, null, null); }
        }
        """);
    write(sourceRoot, "library/Base.java", """
        package library;
        public class Base {
          public Object forName(String name) { return name; }
          public Object newProxyInstance(Object loader, Object interfaces, Object handler) { return null; }
        }
        """);
    write(sourceRoot, "demo/ExplicitImportedParentUse.java", """
        package demo;
        import library.Base;
        import static java.lang.Class.forName;
        import static java.lang.reflect.Proxy.newProxyInstance;
        class ExplicitImportedParentUse extends Base {
          Object className(String name) { return forName(name); }
          Object proxy() { return newProxyInstance(null, null, null); }
        }
        """);
    write(sourceRoot, "library/Outer.java", """
        package library;
        public class Outer {
          public static class Base {
            public java.lang.reflect.Method inherited;
            public Object forName(String name) { return name; }
            public Object newProxyInstance(Object loader, Object interfaces, Object handler) { return null; }
          }
          public interface Contract {
            default Object forName(String name) { return name; }
            default Object newProxyInstance(Object loader, Object interfaces, Object handler) { return null; }
          }
        }
        """);
    write(sourceRoot, "demo/ImportedNestedParentUse.java", """
        package demo;
        import library.Outer.Base;
        import static java.lang.Class.forName;
        import static java.lang.reflect.Proxy.newProxyInstance;
        class ImportedNestedParentUse extends Base {
          Object className(String name) { return forName(name); }
          Object inheritedMethod() throws Exception { return inherited.invoke(null); }
          Object proxy() { return newProxyInstance(null, null, null); }
        }
        """);
    write(sourceRoot, "demo/ImportedNestedInterfaceUse.java", """
        package demo;
        import library.Outer.Contract;
        import static java.lang.Class.forName;
        import static java.lang.reflect.Proxy.newProxyInstance;
        class ImportedNestedInterfaceUse implements Contract {
          Object className(String name) { return forName(name); }
          Object proxy() { return newProxyInstance(null, null, null); }
        }
        """);
    write(sourceRoot, "library/PackagePrivateBase.java", """
        package library;
        import demo.Worker;
        public class PackagePrivateBase { Worker action; }
        """);
    write(sourceRoot, "demo/CrossPackageFieldOuter.java", """
        package demo;
        import java.lang.reflect.Method;
        import library.PackagePrivateBase;
        class CrossPackageFieldOuter {
          Method action;
          class Inner extends PackagePrivateBase {
            Object run() { return action.invoke(); }
          }
        }
        """);
    write(sourceRoot, "library/PackagePrivateReflectiveBase.java", """
        package library;
        import java.lang.reflect.Method;
        public class PackagePrivateReflectiveBase { Method action; }
        """);
    write(sourceRoot, "demo/CrossPackageWorkerOuter.java", """
        package demo;
        import library.PackagePrivateReflectiveBase;
        class CrossPackageWorkerOuter {
          Worker action;
          class Inner extends PackagePrivateReflectiveBase {
            Object run() { return action.invoke(); }
          }
        }
        """);
    write(sourceRoot, "demo/ForNameHelper.java", """
        package demo;
        public interface ForNameHelper { Object forName(String name); }
        """);
    write(sourceRoot, "demo/OrdinaryForName.java", """
        package demo;
        class OrdinaryForName {
          static Object forName(String name) { return name; }
        }
        """);
    write(sourceRoot, "demo/FakeStaticImportTextBlock.java",
        "package demo;\n"
            + "import static demo.OrdinaryForName.forName;\n"
            + "class FakeStaticImportTextBlock {\n"
            + "  String fakeImport = \"\"\"\n"
            + "      import static java.lang.Class.forName;\n"
            + "      \"\"\";\n"
            + "  Object call(String name) { return forName(name); }\n"
            + "}\n");
    write(sourceRoot, "demo/FakeExplicitTypeImportTextBlock.java",
        "package demo;\n"
            + "class FakeExplicitTypeImportTextBlock {\n"
            + "  String fakeImport = \"\"\"\n"
            + "      import shadow.Class;\n"
            + "      \"\"\";\n"
            + "  Object load(String name) { return Class.forName(name); }\n"
            + "}\n");
    write(sourceRoot, "demo/FakeWildcardTypeImportTextBlock.java",
        "package demo;\n"
            + "class FakeWildcardTypeImportTextBlock {\n"
            + "  String fakeImport = \"\"\"\n"
            + "      import shadow.*;\n"
            + "      \"\"\";\n"
            + "  Object load(String name) { return Class.forName(name); }\n"
            + "}\n");
    write(sourceRoot, "demo/FakeProxyImportTextBlock.java",
        "package demo;\n"
            + "import external.*;\n"
            + "class FakeProxyImportTextBlock {\n"
            + "  String fakeImport = \"\"\"\n"
            + "      import java.lang.reflect.*;\n"
            + "      \"\"\";\n"
            + "  Object call() { return Proxy.newProxyInstance(null, null, null); }\n"
            + "}\n");
    write(sourceRoot, "demo/ShadowedTypeNames.java", """
        package demo;
        interface OrdinaryClassReceiver { Object forName(String name); }
        interface OrdinaryProxyReceiver {
          Object newProxyInstance(Object loader, Object interfaces, Object handler);
        }
        class ShadowedTypeNames {
          private OrdinaryClassReceiver Class;
          private OrdinaryProxyReceiver Proxy;
          Object fieldClass(String name) { return Class.forName(name); }
          Object fieldProxy() { return Proxy.newProxyInstance(null, null, null); }
          Object parameterClass(OrdinaryClassReceiver Class, String name) {
            return Class.forName(name);
          }
          Object parameterProxy(OrdinaryProxyReceiver Proxy) {
            return Proxy.newProxyInstance(null, null, null);
          }
          Object localClass(String name) {
            OrdinaryClassReceiver Class = null;
            return Class.forName(name);
          }
          Object localProxy() {
            OrdinaryProxyReceiver Proxy = null;
            return Proxy.newProxyInstance(null, null, null);
          }
        }
        """);
    write(sourceRoot, "demo/JdkTypedTypeNames.java", """
        package demo;
        class JdkTypedTypeNames {
          private Class<?> Class;
          private java.lang.reflect.Proxy Proxy;
          Object fieldClass(String name) { return Class.forName(name); }
          Object fieldProxy() { return Proxy.newProxyInstance(null, null, null); }
          Object thisFieldClass(String name) { return this.Class.forName(name); }
          Object thisFieldProxy() { return this.Proxy.newProxyInstance(null, null, null); }
          Object parameterClass(Class<?> Class, String name) { return Class.forName(name); }
          Object parameterProxy(java.lang.reflect.Proxy Proxy) {
            return Proxy.newProxyInstance(null, null, null);
          }
          Object aliasClass(Class<?> klass, String name) { return klass.forName(name); }
          Object aliasProxy(java.lang.reflect.Proxy proxy) {
            return proxy.newProxyInstance(null, null, null);
          }
          Object localClass(String name) {
            Class<?> Class = null;
            return Class.forName(name);
          }
          Object localProxy() {
            java.lang.reflect.Proxy Proxy = null;
            return Proxy.newProxyInstance(null, null, null);
          }
        }
        """);
    write(sourceRoot, "demo/QualifiedShadowedTypeNames.java", """
        package demo;
        class QualifiedShadowedTypeNames {
          private OrdinaryClassReceiver klass;
          private OrdinaryProxyReceiver proxy;
          Object classCall(Class<?> klass, String name) { return this.klass.forName(name); }
          Object proxyCall(java.lang.reflect.Proxy proxy) {
            return this.proxy.newProxyInstance(null, null, null);
          }
          Object classWhitespace(Class<?> klass, String name) { return this. klass.forName(name); }
          Object proxyWhitespace(java.lang.reflect.Proxy proxy) {
            return this. proxy.newProxyInstance(null, null, null);
          }
          Object classComment(Class<?> klass, String name) {
            return this. /* ordinary receiver */ klass.forName(name);
          }
          Object proxyComment(java.lang.reflect.Proxy proxy) {
            return this. /* ordinary receiver */ proxy.newProxyInstance(null, null, null);
          }
        }
        """);
    write(sourceRoot, "demo/QualifiedThisOwners.java", """
        package demo;
        class QualifiedThisOwners {
          private OrdinaryClassReceiver classField;
          private OrdinaryProxyReceiver proxyField;
          class Inner {
            private Class<?> classField;
            private java.lang.reflect.Proxy proxyField;
            Object outerClass(String name) {
              return QualifiedThisOwners.this.classField.forName(name);
            }
            Object outerProxy() {
              return QualifiedThisOwners.this.proxyField.newProxyInstance(null, null, null);
            }
            Object outerClassWhitespace(String name) {
              return QualifiedThisOwners. this.classField.forName(name);
            }
            Object outerProxyWhitespace() {
              return QualifiedThisOwners. this.proxyField.newProxyInstance(null, null, null);
            }
            Object outerClassComment(String name) {
              return QualifiedThisOwners. /* ordinary receiver */ this.classField.forName(name);
            }
            Object outerProxyComment() {
              return QualifiedThisOwners. /* ordinary receiver */ this.proxyField
                  .newProxyInstance(null, null, null);
            }
          }
        }
        """);
    write(sourceRoot, "demo/QualifiedThisJdkOwners.java", """
        package demo;
        class QualifiedThisJdkOwners {
          private Class<?> classField;
          private java.lang.reflect.Proxy proxyField;
          class Inner {
            private OrdinaryClassReceiver classField;
            private OrdinaryProxyReceiver proxyField;
            Object outerClass(String name) {
              return QualifiedThisJdkOwners.this.classField.forName(name);
            }
            Object outerProxy() {
              return QualifiedThisJdkOwners.this.proxyField.newProxyInstance(null, null, null);
            }
          }
        }
        """);
    write(sourceRoot, "demo/QualifiedThisInheritedJdkBase.java", """
        package demo;
        class QualifiedThisInheritedJdkBase {
          protected Class<?> classField;
          protected java.lang.reflect.Proxy proxyField;
        }
        """);
    write(sourceRoot, "demo/QualifiedThisInheritedJdkOwners.java", """
        package demo;
        class QualifiedThisInheritedJdkOwners extends QualifiedThisInheritedJdkBase {
          class Inner {
            Object outerClass(String name) {
              return QualifiedThisInheritedJdkOwners.this.classField.forName(name);
            }
            Object outerProxy() {
              return QualifiedThisInheritedJdkOwners.this.proxyField.newProxyInstance(
                  null, null, null);
            }
          }
        }
        """);
    write(sourceRoot, "demo/QualifiedThisHiddenOrdinaryBase.java", """
        package demo;
        class QualifiedThisHiddenOrdinaryBase {
          protected Class<?> classField;
          protected java.lang.reflect.Proxy proxyField;
        }
        """);
    write(sourceRoot, "demo/QualifiedThisHiddenOrdinaryOwners.java", """
        package demo;
        class QualifiedThisHiddenOrdinaryOwners extends QualifiedThisHiddenOrdinaryBase {
          private OrdinaryClassReceiver classField;
          private OrdinaryProxyReceiver proxyField;
          class Inner {
            Object outerClass(String name) {
              return QualifiedThisHiddenOrdinaryOwners.this.classField.forName(name);
            }
            Object outerProxy() {
              return QualifiedThisHiddenOrdinaryOwners.this.proxyField.newProxyInstance(
                  null, null, null);
            }
          }
        }
        """);
    write(sourceRoot, "demo/LocalQualifiedThisOwners.java", """
        package demo;
        class LocalQualifiedThisOwners {
          Object run(String name) {
            class Local {
              private Class<?> klass;
              private java.lang.reflect.Proxy proxy;
              Object classCall(String value) { return Local.this.klass.forName(value); }
              Object proxyCall() {
                return Local.this.proxy.newProxyInstance(null, null, null);
              }
            }
            return new Local().classCall(name);
          }
        }
        """);
    write(sourceRoot, "demo/QualifiedThisOtherApis.java", """
        package demo;
        interface OrdinaryDynamicReceiver {
          Object loadClass(String name);
          Object getMethod(String name);
          Object getDeclaredConstructor();
          Object invoke(Object value);
          Object newInstance();
        }
        class QualifiedThisOtherOrdinaryOwners {
          private OrdinaryDynamicReceiver loader;
          private OrdinaryDynamicReceiver type;
          private OrdinaryDynamicReceiver method;
          private OrdinaryDynamicReceiver constructor;
          class Inner {
            private ClassLoader loader;
            private Class<?> type;
            private java.lang.reflect.Method method;
            private java.lang.reflect.Constructor<?> constructor;
            Object load(String name) throws Exception {
              return QualifiedThisOtherOrdinaryOwners.this.loader.loadClass(name);
            }
            Object lookup() throws Exception {
              QualifiedThisOtherOrdinaryOwners.this.type.getMethod("run");
              QualifiedThisOtherOrdinaryOwners.this.type.getDeclaredConstructor();
              return null;
            }
            Object invoke() throws Exception {
              return QualifiedThisOtherOrdinaryOwners.this.method.invoke(null);
            }
            Object construct() throws Exception {
              return QualifiedThisOtherOrdinaryOwners.this.constructor.newInstance();
            }
          }
        }
        class QualifiedThisOtherJdkOwners {
          private ClassLoader loader;
          private Class<?> type;
          private java.lang.reflect.Method method;
          private java.lang.reflect.Constructor<?> constructor;
          class Inner {
            private OrdinaryDynamicReceiver loader;
            private OrdinaryDynamicReceiver type;
            private OrdinaryDynamicReceiver method;
            private OrdinaryDynamicReceiver constructor;
            Object load(String name) throws Exception {
              return QualifiedThisOtherJdkOwners.this.loader.loadClass(name);
            }
            Object lookup() throws Exception {
              QualifiedThisOtherJdkOwners.this.type.getMethod("run");
              QualifiedThisOtherJdkOwners.this.type.getDeclaredConstructor();
              return null;
            }
            Object invoke() throws Exception {
              return QualifiedThisOtherJdkOwners.this.method.invoke(null);
            }
            Object construct() throws Exception {
              return QualifiedThisOtherJdkOwners.this.constructor.newInstance();
            }
          }
        }
        """);
    write(sourceRoot, "demo/ChainedQualifiedShadowNames.java", """
        package demo;
        interface OrdinaryClassChain {
          OrdinaryLookup forName(String name);
        }
        interface OrdinaryLookup {
          OrdinaryChainMethod getMethod(String name);
          OrdinaryChainConstructor getDeclaredConstructor();
        }
        interface OrdinaryChainMethod {
          Object invoke(Object value);
        }
        interface OrdinaryChainConstructor {
          Object newInstance();
        }
        class ChainedQualifiedShadowNames {
          private Class<?> classAlias;
          private OrdinaryClassChain ordinaryClassAlias;
          class Holder {
            OrdinaryDynamicReceiver klass;
            OrdinaryClassChain Class;
          }
          Object invoke(Holder holder, Class<?> klass) throws Exception {
            return holder.klass.getMethod("run").invoke(null);
          }
          Object construct(Holder holder, Class<?> klass) throws Exception {
            return holder.klass.getDeclaredConstructor().newInstance();
          }
          Object lookup(Holder holder, Class<?> Class) throws Exception {
            return holder.Class.forName("x").getMethod("run");
          }
          Object lookupConstructor(Holder holder, Class<?> Class) throws Exception {
            return holder.Class.forName("x").getDeclaredConstructor();
          }
          Object realLookup(String name) throws Exception {
            return Class.forName(name).getMethod("run");
          }
          Object realLookupConstructor(String name) throws Exception {
            return Class.forName(name).getDeclaredConstructor();
          }
          Object simpleMethodLookup(Class<?> type) throws Exception {
            return type.getMethod("run");
          }
          Object simpleConstructorLookup(Class<?> type) throws Exception {
            return type.getDeclaredConstructor();
          }
          Object qualifiedGetClassLookup(Object object) throws Exception {
            return object.getClass().getMethod("run");
          }
          Object qualifiedGetClassConstructor(Object object) throws Exception {
            return object.getClass().getDeclaredConstructor();
          }
          Object spacedGetClassLookup(Object object) throws Exception {
            return object.getClass ().getMethod("run").invoke(object);
          }
          Object spacedGetClassConstructor(Object object) throws Exception {
            return object.getClass ( /* preserve lookup */ ).getDeclaredConstructor().newInstance();
          }
          Object misleadingInvokeName(Object target, String invokeName) throws Exception {
            return target.getClass().getMethod(
                invokeName
            ).invoke(target);
          }
          Object misleadingNewInstanceName(Object target, Class<?>[] newInstance) throws Exception {
            return target.getClass().getDeclaredConstructor(
                newInstance
            ).newInstance();
          }
          Object twoMethodChains(Object first, Object second) throws Exception {
            return consume(
                first.getClass().getMethod("a").invoke(first),
                second.getClass().getMethod("b").invoke(second));
          }
          Object nestedMethodChain(Object target, String methodName) throws Exception {
            return target.getClass().getMethod(
                target.getClass().getMethod(methodName).getName()
            ).invoke(target);
          }
          Object nestedConstructorChain(Object target, Class<?>[] types) throws Exception {
            return target.getClass().getDeclaredConstructor(
                target.getClass().getDeclaredConstructor(types).getName()
            ).newInstance();
          }
          Object consume(Object first, Object second) { return first; }
          Object aliasMethodLookup(Class<?> klass, String name) throws Exception {
            return klass.forName(name).getMethod("run").invoke(null);
          }
          Object aliasConstructorLookup(Class<?> klass, String name) throws Exception {
            return klass.forName(name).getDeclaredConstructor().newInstance();
          }
          Object spacedVarMethodLookup(Object object) throws Exception {
            var method = object.getClass ( /* infer Method */ ).getMethod("run");
            return method.invoke(object);
          }
          Object spacedVarConstructorLookup(Object object) throws Exception {
            var constructor = object.getClass ( /* infer Constructor */ ).getDeclaredConstructor();
            return constructor.newInstance();
          }
          Object spacedStandaloneVarMethodLookup(Object object) throws Exception {
            var klass = object.getClass ( /* infer Class */ );
            return klass.getMethod("run");
          }
          Object spacedStandaloneVarConstructorLookup(Object object) throws Exception {
            var klass = object.getClass ( /* infer Class */ );
            return klass.getDeclaredConstructor();
          }
          Object spacedClassLoaderLookup(String name) throws Exception {
            var loader = String.class.getClassLoader ( /* infer ClassLoader */ );
            return loader.loadClass(name);
          }
          Object thisAliasMethodLookup(String name) throws Exception {
            return this.classAlias.forName(name).getMethod("run").invoke(null);
          }
          Object thisAliasConstructorLookup(String name) throws Exception {
            return this.classAlias.forName(name).getDeclaredConstructor().newInstance();
          }
          Object thisAliasVarMethodLookup(String name) throws Exception {
            var method = this.classAlias.forName(name).getMethod("run");
            return method.invoke(null);
          }
          Object thisAliasVarConstructorLookup(String name) throws Exception {
            var constructor = this.classAlias.forName(name).getDeclaredConstructor();
            return constructor.newInstance();
          }
          Object thisAliasDirectVarMethodLookup() throws Exception {
            var method = this.classAlias.getMethod("run");
            return method.invoke(null);
          }
          Object thisAliasDirectVarConstructorLookup() throws Exception {
            var constructor = this.classAlias.getDeclaredConstructor();
            return constructor.newInstance();
          }
          Object thisAliasStandaloneVarMethodLookup(String name) throws Exception {
            var loaded = this.classAlias.forName(name);
            return loaded.getMethod("run");
          }
          Object thisAliasStandaloneVarConstructorLookup(String name) throws Exception {
            var loaded = this.classAlias.forName(name);
            return loaded.getDeclaredConstructor();
          }
          Object ordinaryThisAliasLookup(String name) throws Exception {
            return this.ordinaryClassAlias.forName(name).getMethod("run");
          }
          Object ordinaryThisAliasVarMethodLookup(String name) throws Exception {
            var method = this.ordinaryClassAlias.forName(name).getMethod("run");
            return method.invoke(null);
          }
          Object ordinaryThisAliasVarConstructorLookup(String name) throws Exception {
            var constructor = this.ordinaryClassAlias.forName(name).getDeclaredConstructor();
            return constructor.newInstance();
          }
          Object ordinaryThisAliasDirectVarMethodLookup() throws Exception {
            var method = this.ordinaryClassAlias.getMethod("run");
            return method.invoke(null);
          }
          Object ordinaryThisAliasDirectVarConstructorLookup() throws Exception {
            var constructor = this.ordinaryClassAlias.getDeclaredConstructor();
            return constructor.newInstance();
          }
          Object ordinaryThisAliasDirectChainMethodLookup() throws Exception {
            return this.ordinaryClassAlias.getMethod("run").invoke(null);
          }
          Object ordinaryThisAliasDirectChainConstructorLookup() throws Exception {
            return this.ordinaryClassAlias.getDeclaredConstructor().newInstance();
          }
          Object ordinaryThisAliasStandaloneVarMethodLookup(String name) throws Exception {
            var loaded = this.ordinaryClassAlias.forName(name);
            return loaded.getMethod("run");
          }
          Object ordinaryThisAliasStandaloneVarConstructorLookup(String name) throws Exception {
            var loaded = this.ordinaryClassAlias.forName(name);
            return loaded.getDeclaredConstructor();
          }
          class Inner {
            private Class<?> ordinaryClassAlias;
            Object outerAliasMethodLookup(String name) throws Exception {
              return ChainedQualifiedShadowNames.this.classAlias
                  .forName(name).getMethod("run").invoke(null);
            }
            Object outerAliasConstructorLookup(String name) throws Exception {
              return ChainedQualifiedShadowNames.this.classAlias
                  .forName(name).getDeclaredConstructor().newInstance();
            }
            Object outerAliasVarMethodLookup(String name) throws Exception {
              var method = ChainedQualifiedShadowNames.this.classAlias
                  .forName(name).getMethod("run");
              return method.invoke(null);
            }
            Object outerAliasVarConstructorLookup(String name) throws Exception {
              var constructor = ChainedQualifiedShadowNames.this.classAlias
                  .forName(name).getDeclaredConstructor();
              return constructor.newInstance();
            }
            Object outerAliasDirectVarMethodLookup() throws Exception {
              var method = ChainedQualifiedShadowNames.this.classAlias.getMethod("run");
              return method.invoke(null);
            }
            Object outerAliasDirectVarConstructorLookup() throws Exception {
              var constructor = ChainedQualifiedShadowNames.this.classAlias.getDeclaredConstructor();
              return constructor.newInstance();
            }
            Object outerAliasStandaloneVarMethodLookup(String name) throws Exception {
              var loaded = ChainedQualifiedShadowNames.this.classAlias.forName(name);
              return loaded.getMethod("run");
            }
            Object outerAliasStandaloneVarConstructorLookup(String name) throws Exception {
              var loaded = ChainedQualifiedShadowNames.this.classAlias.forName(name);
              return loaded.getDeclaredConstructor();
            }
            Object outerOrdinaryAliasVarMethodLookup(String name) throws Exception {
              var method = ChainedQualifiedShadowNames.this.ordinaryClassAlias
                  .forName(name).getMethod("run");
              return method.invoke(null);
            }
            Object outerOrdinaryAliasVarConstructorLookup(String name) throws Exception {
              var constructor = ChainedQualifiedShadowNames.this.ordinaryClassAlias
                  .forName(name).getDeclaredConstructor();
              return constructor.newInstance();
            }
            Object outerOrdinaryAliasDirectVarMethodLookup() throws Exception {
              var method = ChainedQualifiedShadowNames.this.ordinaryClassAlias.getMethod("run");
              return method.invoke(null);
            }
            Object outerOrdinaryAliasDirectVarConstructorLookup() throws Exception {
              var constructor = ChainedQualifiedShadowNames.this.ordinaryClassAlias.getDeclaredConstructor();
              return constructor.newInstance();
            }
            Object outerOrdinaryAliasDirectChainMethodLookup() throws Exception {
              return ChainedQualifiedShadowNames.this.ordinaryClassAlias.getMethod("run").invoke(null);
            }
            Object outerOrdinaryAliasDirectChainConstructorLookup() throws Exception {
              return ChainedQualifiedShadowNames.this.ordinaryClassAlias
                  .getDeclaredConstructor().newInstance();
            }
            Object outerOrdinaryAliasStandaloneVarMethodLookup(String name) throws Exception {
              var loaded = ChainedQualifiedShadowNames.this.ordinaryClassAlias.forName(name);
              return loaded.getMethod("run");
            }
            Object outerOrdinaryAliasStandaloneVarConstructorLookup(String name) throws Exception {
              var loaded = ChainedQualifiedShadowNames.this.ordinaryClassAlias.forName(name);
              return loaded.getDeclaredConstructor();
            }
            Object outerShadowedWhitespaceMethod(String name) throws Exception {
              return ChainedQualifiedShadowNames. this.ordinaryClassAlias
                  .forName(name).getMethod("run").invoke(null);
            }
            Object outerShadowedCommentMethod(String name) throws Exception {
              return ChainedQualifiedShadowNames. /* ordinary outer */ this.ordinaryClassAlias
                  .forName(name).getMethod("run").invoke(null);
            }
            Object outerShadowedWhitespaceConstructor(String name) throws Exception {
              return ChainedQualifiedShadowNames. this.ordinaryClassAlias
                  .forName(name).getDeclaredConstructor().newInstance();
            }
            Object outerShadowedCommentConstructor(String name) throws Exception {
              return ChainedQualifiedShadowNames. /* ordinary outer */ this.ordinaryClassAlias
                  .forName(name).getDeclaredConstructor().newInstance();
            }
          }
        }
        """);
    write(sourceRoot, "demo/WildcardForName.java", """
        package demo;
        import static java.lang.Class.*;
        class WildcardForName {
          Object load(String name) throws Exception { return forName(name); }
        }
        """);
    write(sourceRoot, "demo/NestedInheritanceOuter.java", """
        package demo;
        import java.lang.reflect.Method;
        class NestedInheritanceOuter {
          static class Base { protected Method action; }
          static class Child extends Base {
            Object run() throws Exception { return action.invoke(null); }
          }
          static class QualifiedChild extends NestedInheritanceOuter.Base {
            Object run() throws Exception { return action.invoke(null); }
          }
        }
        """);
    write(sourceRoot, "demo/AOuter.java", """
        package demo;
        import java.lang.reflect.Method;
        class AOuter {
          Method action;
          class Inner extends ZBase {
            Object run() { return action.invoke(); }
          }
        }
        """);
    write(sourceRoot, "demo/ZBase.java", """
        package demo;
        class ZBase { Worker action; }
        """);
    write(sourceRoot, "demo/BOuter.java", """
        package demo;
        class BOuter {
          Worker action;
          class Inner extends CBase {
            Object run() { return action.invoke(); }
          }
        }
        """);
    write(sourceRoot, "demo/CBase.java", """
        package demo;
        import java.lang.reflect.Method;
        class CBase { Method action; }
        """);
    write(sourceRoot, "shadow/Class.java", """
        package shadow;
        public class Class {
          public static Object forName(String name) { return name; }
          public Object getMethod(String name) { return name; }
        }
        """);
    write(sourceRoot, "shadow/Method.java", """
        package shadow;
        public class Method {
          public Object invoke(Object... arguments) { return null; }
        }
        """);
    write(sourceRoot, "shadow/ReceiverTypes.java", """
        package shadow;
        class ReceiverTypes {
          private Method method;
          private Class classValue;
          Object fields() { return method.invoke(null); }
          Object classes() { return classValue.getMethod("run"); }
          Object parameters(Method method, Class classValue) {
            return method.invoke(null);
          }
          Object locals() {
            Method method = new Method();
            Class classValue = new Class();
            return method.invoke(classValue);
          }
        }
        """);
    write(sourceRoot, "shadow/QualifiedReceiverTypes.java", """
        package shadow;
        class QualifiedReceiverTypes {
          private java.lang.reflect.Method method;
          private java.lang.Class<?> type;
          private java.lang.ClassLoader loader;
          private java.lang.reflect.Constructor<?> constructor;
          Object run(String name) throws Exception {
            method.invoke(null);
            type.getMethod("run");
            loader.loadClass(name);
            constructor.newInstance();
            return null;
          }
        }
        """);
    write(sourceRoot, "shadow/InvocationHandler.java", """
        package shadow;
        interface InvocationHandler { Object call(Object proxy, Payload value); }
        """);
    write(sourceRoot, "shadow/Payload.java", """
        package shadow;
        interface Payload { Object invoke(); }
        """);
    write(sourceRoot, "shadow/HandlerUse.java", """
        package shadow;
        class HandlerUse {
          InvocationHandler handler = (proxy, value) -> value.invoke();
          Object run() {
            InvocationHandler local = (proxy, value) -> value.invoke();
            return local;
          }
        }
        """);
    write(sourceRoot, "shadow/Proxy.java", """
        package shadow;
        public class Proxy {
          public static Object newProxyInstance(Object loader, Object interfaces, Object handler) {
            return null;
          }
        }
        """);
    write(sourceRoot, "shadow/UsesShadowApis.java", """
        package shadow;
        class UsesShadowApis {
          Object classCall(String name) { return Class.forName(name); }
          Object proxyCall() { return Proxy.newProxyInstance(null, null, null); }
        }
        """);
    write(sourceRoot, "demo/InterfaceHandlers.java", """
        package demo;
        interface InterfaceHandlers {
          java.lang.reflect.InvocationHandler handler =
              (proxy, method, args) -> method.invoke(null, args);
        }
        """);
    write(sourceRoot, "demo/TextBlockExamples.java",
        "package demo;\n"
            + "public class TextBlockExamples {\n"
            + "  String examples = \"\"\"\n"
            + "    Class.forName(name);\n"
            + "    Proxy.newProxyInstance(loader, interfaces, handler);\n"
            + "    \"\"\";\n"
            + "}\n");
    AnalysisContext context = context(
        AnalysisContext.Framework.QUARKUS,
        sourceRoot,
        null,
        List.of());

    List<DynamicFeature> dynamicFeatures = new SourceMetadataScanner(context).dynamicFeatures();
    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(context, new AnalysisGraph(
        List.of(),
        dynamicFeatures,
        Set.of(),
        "SootUp",
        "3.0.1",
        0,
        0));
    List<SemanticCallFacts.Fact> reflectionFacts = facts.providerFacts().stream()
        .filter(item -> item.relationshipType().equals("reflection"))
        .toList();
    assertTrue(reflectionFacts.size() >= 5);
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#first")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#qualifiedReceivers")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#varReceiver")
        && item.evidence().get(0).symbols().contains("Method.invoke")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#qualifiedVarReceiver")
        && item.evidence().get(0).symbols().contains("Method.invoke")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#varLoader")
        && item.evidence().get(0).symbols().contains("ClassLoader.loadClass")));
    assertTrue(reflectionFacts.stream()
        .anyMatch(item -> item.caller().equals("StringPackageText#load")
            && item.evidence().get(0).symbols().contains("Class.forName")),
        reflectionFacts.toString());
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#urlClassLoader")
        && item.evidence().get(0).symbols().contains("ClassLoader.loadClass")), reflectionFacts.toString());
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#customClassLoader")
        && item.evidence().get(0).symbols().contains("ClassLoader.loadClass")), reflectionFacts.toString());
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#boundedParameter")
        && item.evidence().get(0).symbols().contains("ClassLoader.loadClass")), reflectionFacts.toString());
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#boundedLocal")
        && item.evidence().get(0).symbols().contains("ClassLoader.loadClass")), reflectionFacts.toString());
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#publicBounded")
        && item.evidence().get(0).symbols().contains("ClassLoader.loadClass")), reflectionFacts.toString());
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#staticBounded")
        && item.evidence().get(0).symbols().contains("ClassLoader.loadClass")), reflectionFacts.toString());
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#annotatedBounded")
        && item.evidence().get(0).symbols().contains("ClassLoader.loadClass")), reflectionFacts.toString());
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#annotatedTypeBound")
        && item.evidence().get(0).symbols().contains("ClassLoader.loadClass")), reflectionFacts.toString());
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#bitwiseAnnotatedBound")
        && item.evidence().get(0).symbols().contains("ClassLoader.loadClass")), reflectionFacts.toString());
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#intersectionBound")
        && item.evidence().get(0).symbols().contains("ClassLoader.loadClass")), reflectionFacts.toString());
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().equals("demo.AnnotatedGeneric#run")
        && item.evidence().get(0).symbols().contains("ClassLoader.loadClass")), reflectionFacts.toString());
    assertTrue(reflectionFacts.stream()
        .anyMatch(item -> item.caller().equals("demo.AnnotatedArrayGeneric#run")
            && item.evidence().get(0).symbols().contains("ClassLoader.loadClass")),
        reflectionFacts.toString());
    assertTrue(reflectionFacts.stream()
        .anyMatch(item -> item.caller().equals("demo.ComparisonGeneric#run")
            && item.evidence().get(0).symbols().contains("ClassLoader.loadClass")),
        reflectionFacts.toString());
    assertTrue(reflectionFacts.stream()
        .anyMatch(item -> item.caller().equals("demo.GenericBoundShadow#run")
            && item.evidence().get(0).symbols().contains("ClassLoader.loadClass")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#annotatedUnrelatedBound")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#unrelatedGeneric")),
        reflectionFacts.toString());
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().equals("demo.WildcardLoader#load")
        && item.evidence().get(0).symbols().contains("ClassLoader.loadClass")), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("#localTypeShadow"))
        .count());
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#ordinaryInvoke")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.caller().equals("demo.WildcardShadowMethod#invoke")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().equals("wildcard.UsesSamePackageMethod#invoke")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().equals("inherited.Child#ordinary")),
        reflectionFacts.toString());
    assertTrue(reflectionFacts.stream()
        .anyMatch(item -> item.caller().equals("inheritedprivate.Child#reflective")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().equals("inheritedchild.Child#ordinary")),
        reflectionFacts.toString());
    assertTrue(reflectionFacts.stream()
        .anyMatch(item -> item.caller().equals("lexicalprecedence.Outer$Inner#load")
            && item.evidence().get(0).symbols().contains("ClassLoader.loadClass")),
        reflectionFacts.toString());
    assertTrue(reflectionFacts.stream()
        .anyMatch(item -> item.caller().equals("hiddenmembers.C#load")
            && item.evidence().get(0).symbols().contains("ClassLoader.loadClass")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("localprecedence.LocalPrecedence$1Other#ordinary")),
        reflectionFacts.toString());
    assertTrue(reflectionFacts.stream()
        .anyMatch(item -> item.caller().equals("privatehide.C#reflective")
            && item.evidence().get(0).symbols().contains("Method.invoke")),
        reflectionFacts.toString());
    assertTrue(reflectionFacts.stream()
        .anyMatch(item -> item.caller().equals("wildcard.WildcardUrlSubclass#load")
            && item.evidence().get(0).symbols().contains("ClassLoader.loadClass")),
        reflectionFacts.toString());
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#varClassLiteral")
        && item.evidence().get(0).symbols().contains("getMethod")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#varClassLoader")
        && item.evidence().get(0).symbols().contains("ClassLoader.loadClass")));
    assertEquals(2, reflectionFacts.stream()
        .filter(item -> item.caller().contains("#chainedLoader")
            && item.evidence().get(0).symbols().contains("ClassLoader.loadClass"))
        .count());
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#spacedVarReceiver")
        && item.evidence().get(0).symbols().contains("Method.invoke")));
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#varUnrelated")));
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("#varScope")
            && item.evidence().get(0).symbols().contains("Method.invoke"))
        .count());
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#wrappedLookup")
        && item.evidence().get(0).symbols().contains("Method.invoke")));
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#wrappedClass")
        && item.evidence().get(0).symbols().contains("Method.invoke")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#second")), reflectionFacts.toString());
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#chained")
        && item.evidence().get(0).symbols().contains("Method.invoke")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#classLiteral")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#inlineInvocationProxy")
        && item.evidence().get(0).symbols().contains("Method.invoke")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#inlineShortProxy")
        && item.evidence().get(0).symbols().contains("Method.invoke")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#inlineTypedProxy")
        && item.evidence().get(0).symbols().contains("Method.invoke")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#<init>")), reflectionFacts.toString());
    assertTrue(reflectionFacts.stream()
        .filter(item -> item.caller().equals("demo.DynamicSites#<init>")
            && item.evidence().get(0).symbols().contains("Method.invoke"))
        .count() >= 4, reflectionFacts.toString());
    assertTrue(reflectionFacts.stream()
        .filter(item -> item.caller().equals("demo.DynamicSites#<clinit>")
            && item.evidence().get(0).symbols().contains("Method.invoke"))
        .count() >= 2);
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().equals("demo.DynamicSites#<clinit>")
        && item.evidence().get(0).symbols().contains("Class.forName")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().equals("demo.DynamicSites#<init>")
        && item.evidence().get(0).symbols().contains("Class.forName")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().equals("demo.InterfaceHandlers#<clinit>")
        && item.evidence().get(0).symbols().contains("Method.invoke")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().equals("demo.DynamicSites$1Local#<init>")
        && item.evidence().get(0).symbols().contains("Method.invoke")), reflectionFacts.toString());
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().equals("unknown#run")
        && item.evidence().get(0).path().contains("DynamicSites.java")
        && item.evidence().get(0).symbols().contains("Class.forName")), reflectionFacts.toString());
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#constructionThenBlock")
        && item.evidence().get(0).symbols().contains("Class.forName")), reflectionFacts.toString());
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#anonymousConstructorArgument")
        && item.evidence().get(0).symbols().contains("Class.forName")), reflectionFacts.toString());
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#explicitAnonymousConstructorArgument")
        && item.evidence().get(0).symbols().contains("Class.forName")), reflectionFacts.toString());
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#annotatedAnonymousConstructorArgument")
        && item.evidence().get(0).symbols().contains("Class.forName")), reflectionFacts.toString());
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#spacedQualifiedAnonymousConstructorArgument")
        && item.evidence().get(0).symbols().contains("Class.forName")), reflectionFacts.toString());
    assertTrue(reflectionFacts.stream()
        .filter(item -> item.caller().equals("unknown#run")
            && item.evidence().get(0).symbols().contains("Class.forName"))
        .count() >= 3, reflectionFacts.toString());
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().equals("demo.Outer$Inner#run")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().equals("demo.Outer$Inner#<init>")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().equals("demo.Outer$SiblingA#reflect")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().equals("demo.Outer$InheritedInner#reflectInherited")), reflectionFacts.toString());
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().equals("demo.Child#run")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().equals("demo.NestedInheritanceOuter$Child#run")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().equals("demo.NestedInheritanceOuter$QualifiedChild#run")));
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.caller().equals("demo.AOuter$Inner#run")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().equals("demo.BOuter$Inner#run")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().equals("demo.CrossPackageFieldOuter$Inner#run")));
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.caller().equals("demo.CrossPackageWorkerOuter$Inner#run")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().equals("shadow.QualifiedReceiverTypes#run")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#characterLiteral")));
    assertTrue(reflectionFacts.stream().noneMatch(item -> item.callee().contains("done")));
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#ordinary")));
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.caller().equals("demo.ChildWithShadow#run")));
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.caller().equals("demo.ReverseOuter$Inner#ordinary")));
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#unrelated")));
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#unrelatedChained")));
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#unrelatedConstructor")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#fieldReceivers")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#multiFieldReceivers")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#initializedMultiFieldReceivers")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#comparisonMultiFieldReceivers")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#multiLocalReceivers")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#identifierComparisonMultiFieldReceivers")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#genericMultiFieldReceivers")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#identifierComparisonLocal")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#comparisonBothMultiFieldReceivers")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#comparisonBothLocal")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#finalMultiLocalReceivers")));
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#finalWorkerMultiLocal")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#annotatedArrayMultiLocal")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#annotatedNestedMultiLocal")));
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#annotatedArrayWorkerMultiLocal")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#annotatedFieldReceivers")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#nestedAnnotatedFieldReceivers")));
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#annotatedWorkerFieldReceivers")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#priorAnnotatedLocal")));
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#priorAnnotatedWorkerLocal")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#afterLambdaArgument")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#afterNestedLambdaArgument")));
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#afterLambdaWorkerArgument")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#nestedLambdaBlockScope")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#controlFlowLambdaBlockScope")));
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#nestedLambdaWorkerShadow")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#bracedForScope")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#unbracedForScope")));
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("#methodLocalForScope"))
        .count());
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#enhancedForScope")));
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("#enhancedForMethodScope"))
        .count());
    assertEquals(2, reflectionFacts.stream()
        .filter(item -> item.caller().contains("#enhancedForIterableScope"))
        .count());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("#enhancedForMethodIterableScope"))
        .count());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("#forIfElseScope"))
        .count());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("#anonymousBranchScope"))
        .count());
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#switchForScope")));
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("#switchForMethodScope"))
        .count());
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#catchScope")));
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#catchWorkerScope")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#resourceScope")));
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("#resourceInitializerScope"))
        .count());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("#resourceInitializerMethodScope"))
        .count());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("#laterDeclaratorBefore"))
        .count());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("#laterDeclaratorAfter"))
        .count());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("#basicForMultiWorkerScope"))
        .count());
    assertEquals(2, reflectionFacts.stream()
        .filter(item -> item.caller().contains("#basicForMultiMethodScope"))
        .count());
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#forTryFinallyScope")));
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("#forTryMethodScope"))
        .count());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("#forLabeledIfScope"))
        .count());
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#multiFieldParameter")));
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#shadowed")));
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#unrelatedProxyName")));
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.caller().contains("#ordinary")));
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.evidence().get(0).path().contains("TextBlockExamples.java")));
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.caller().equals("demo.FakeStaticImportTextBlock#call")),
        reflectionFacts.toString());
    assertTrue(reflectionFacts.stream()
        .anyMatch(item -> item.caller().equals("demo.FakeExplicitTypeImportTextBlock#load")
            && item.evidence().get(0).symbols().contains("Class.forName")),
        reflectionFacts.toString());
    assertTrue(reflectionFacts.stream()
        .anyMatch(item -> item.caller().equals("demo.FakeWildcardTypeImportTextBlock#load")
            && item.evidence().get(0).symbols().contains("Class.forName")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ShadowedTypeNames#")),
        reflectionFacts.toString());
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().equals("demo.JdkTypedTypeNames#fieldClass")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().equals("demo.JdkTypedTypeNames#thisFieldClass")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().equals("demo.JdkTypedTypeNames#parameterClass")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().equals("demo.JdkTypedTypeNames#aliasClass")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.caller().equals("demo.JdkTypedTypeNames#localClass")));
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.QualifiedShadowedTypeNames#")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.QualifiedThisOwners$Inner#outerClass")),
        reflectionFacts.toString());
    assertTrue(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.QualifiedThisJdkOwners$Inner#outerClass")),
        reflectionFacts.toString());
    assertTrue(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.QualifiedThisInheritedJdkOwners$Inner#outerClass")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.QualifiedThisHiddenOrdinaryOwners$Inner#outerClass")),
        reflectionFacts.toString());
    assertTrue(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.LocalQualifiedThisOwners$")
            && item.caller().contains("#classCall")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.QualifiedThisOtherOrdinaryOwners$Inner#")),
        reflectionFacts.toString());
    assertTrue(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.QualifiedThisOtherJdkOwners$Inner#")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#invoke")
            || item.caller().contains("demo.ChainedQualifiedShadowNames#construct")
            || item.caller().contains("demo.ChainedQualifiedShadowNames#lookup")
            || item.caller().contains("demo.ChainedQualifiedShadowNames#lookupConstructor")),
        reflectionFacts.toString());
    assertTrue(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#realLookup")
            && item.evidence().get(0).symbols().contains("getMethod")),
        reflectionFacts.toString());
    assertTrue(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#realLookupConstructor")
            && item.evidence().get(0).symbols().contains("getDeclaredConstructor")),
        reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#simpleMethodLookup")
            && item.evidence().get(0).symbols().contains("getMethod"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#simpleConstructorLookup")
            && item.evidence().get(0).symbols().contains("getDeclaredConstructor"))
        .count(), reflectionFacts.toString());
    assertTrue(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#qualifiedGetClassLookup")
            && item.evidence().get(0).symbols().contains("getMethod")),
        reflectionFacts.toString());
    assertTrue(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#qualifiedGetClassConstructor")
            && item.evidence().get(0).symbols().contains("getDeclaredConstructor")),
        reflectionFacts.toString());
    assertTrue(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#spacedGetClassLookup")
            && item.evidence().get(0).symbols().contains("Method.invoke")),
        reflectionFacts.toString());
    assertTrue(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#spacedGetClassConstructor")
            && item.evidence().get(0).symbols().contains("Constructor.newInstance")),
        reflectionFacts.toString());
    assertTrue(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#misleadingInvokeName")
            && item.evidence().get(0).symbols().contains("Method.invoke")
            && item.evidence().get(0).line() == 61),
        reflectionFacts.toString());
    assertTrue(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#misleadingNewInstanceName")
            && item.evidence().get(0).symbols().contains("Constructor.newInstance")
            && item.evidence().get(0).line() == 66),
        reflectionFacts.toString());
    assertEquals(2, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#twoMethodChains")
            && item.evidence().get(0).symbols().contains("Method.invoke"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#nestedMethodChain")
            && item.evidence().get(0).symbols().contains("Method.invoke"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#nestedConstructorChain")
            && item.evidence().get(0).symbols().contains("Constructor.newInstance"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#aliasMethodLookup")
            && item.evidence().get(0).symbols().contains("getMethod"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#aliasMethodLookup")
            && item.evidence().get(0).symbols().contains("Method.invoke"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#aliasConstructorLookup")
            && item.evidence().get(0).symbols().contains("getDeclaredConstructor"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#aliasConstructorLookup")
            && item.evidence().get(0).symbols().contains("Constructor.newInstance"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#spacedVarMethodLookup")
            && item.evidence().get(0).symbols().contains("Method.invoke"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#spacedVarConstructorLookup")
            && item.evidence().get(0).symbols().contains("Constructor.newInstance"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#spacedStandaloneVarMethodLookup")
            && item.evidence().get(0).symbols().contains("getMethod"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#spacedStandaloneVarConstructorLookup")
            && item.evidence().get(0).symbols().contains("getDeclaredConstructor"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#spacedClassLoaderLookup")
            && item.evidence().get(0).symbols().contains("ClassLoader.loadClass"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#thisAliasMethodLookup")
            && item.evidence().get(0).symbols().contains("getMethod"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#thisAliasMethodLookup")
            && item.evidence().get(0).symbols().contains("Method.invoke"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#thisAliasConstructorLookup")
            && item.evidence().get(0).symbols().contains("getDeclaredConstructor"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#thisAliasConstructorLookup")
            && item.evidence().get(0).symbols().contains("Constructor.newInstance"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#thisAliasVarMethodLookup")
            && item.evidence().get(0).symbols().contains("Method.invoke"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#thisAliasVarConstructorLookup")
            && item.evidence().get(0).symbols().contains("Constructor.newInstance"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#thisAliasDirectVarMethodLookup")
            && item.evidence().get(0).symbols().contains("Method.invoke"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#thisAliasDirectVarConstructorLookup")
            && item.evidence().get(0).symbols().contains("Constructor.newInstance"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#thisAliasStandaloneVarMethodLookup")
            && item.evidence().get(0).symbols().contains("getMethod"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#thisAliasStandaloneVarConstructorLookup")
            && item.evidence().get(0).symbols().contains("getDeclaredConstructor"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames$Inner#outerAliasMethodLookup")
            && item.evidence().get(0).symbols().contains("Method.invoke"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames$Inner#outerAliasConstructorLookup")
            && item.evidence().get(0).symbols().contains("Constructor.newInstance"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames$Inner#outerAliasVarMethodLookup")
            && item.evidence().get(0).symbols().contains("Method.invoke"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames$Inner#outerAliasVarConstructorLookup")
            && item.evidence().get(0).symbols().contains("Constructor.newInstance"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames$Inner#outerAliasDirectVarMethodLookup")
            && item.evidence().get(0).symbols().contains("Method.invoke"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames$Inner#outerAliasDirectVarConstructorLookup")
            && item.evidence().get(0).symbols().contains("Constructor.newInstance"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames$Inner#outerAliasStandaloneVarMethodLookup")
            && item.evidence().get(0).symbols().contains("getMethod"))
        .count(), reflectionFacts.toString());
    assertEquals(1, reflectionFacts.stream()
        .filter(item -> item.caller().contains("demo.ChainedQualifiedShadowNames$Inner#outerAliasStandaloneVarConstructorLookup")
            && item.evidence().get(0).symbols().contains("getDeclaredConstructor"))
        .count(), reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#ordinaryThisAliasLookup")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#ordinaryThisAliasVarMethodLookup")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#ordinaryThisAliasVarConstructorLookup")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#ordinaryThisAliasDirectVarMethodLookup")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#ordinaryThisAliasDirectVarConstructorLookup")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#ordinaryThisAliasDirectChainMethodLookup")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#ordinaryThisAliasDirectChainConstructorLookup")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames$Inner#outerOrdinaryAliasVarMethodLookup")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames$Inner#outerOrdinaryAliasVarConstructorLookup")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames$Inner#outerOrdinaryAliasDirectVarMethodLookup")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames$Inner#outerOrdinaryAliasDirectVarConstructorLookup")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames$Inner#outerOrdinaryAliasDirectChainMethodLookup")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames$Inner#outerOrdinaryAliasDirectChainConstructorLookup")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#ordinaryThisAliasStandaloneVarMethodLookup")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames#ordinaryThisAliasStandaloneVarConstructorLookup")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames$Inner#outerOrdinaryAliasStandaloneVarMethodLookup")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames$Inner#outerOrdinaryAliasStandaloneVarConstructorLookup")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames$Inner#outerShadowedWhitespaceMethod")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames$Inner#outerShadowedCommentMethod")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames$Inner#outerShadowedWhitespaceConstructor")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream()
        .anyMatch(item -> item.caller().contains("demo.ChainedQualifiedShadowNames$Inner#outerShadowedCommentConstructor")),
        reflectionFacts.toString());
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.evidence().get(0).path().contains("StaticForNameShadow.java")));
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.evidence().get(0).path().contains("InheritedDynamicApiUse.java")));
    assertFalse(facts.providerFacts().stream().anyMatch(item -> item.evidence().get(0).path().contains("InheritedDynamicApiUse.java")));
    assertFalse(facts.providerFacts().stream().anyMatch(item -> item.evidence().get(0).path().contains("PrivateDynamicApiUse.java")));
    assertFalse(facts.providerFacts().stream().anyMatch(item -> item.evidence().get(0).path().contains("InheritedInterfaceDynamicApiUse.java")));
    assertFalse(facts.providerFacts().stream().anyMatch(item -> item.evidence().get(0).path().contains("GenericInheritedDynamicApiUse.java")));
    assertFalse(facts.providerFacts().stream().anyMatch(item -> item.evidence().get(0).path().contains("ExplicitImportedParentUse.java")));
    assertTrue(facts.providerFacts().stream().anyMatch(item -> item.caller().equals("demo.ImportedNestedParentUse#inheritedMethod")
        && item.evidence().get(0).path().contains("ImportedNestedParentUse.java")));
    assertFalse(facts.providerFacts().stream().anyMatch(item -> item.evidence().get(0).path().contains("ImportedNestedInterfaceUse.java")));
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.evidence().get(0).path().contains("UsesShadowApis.java")));
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.evidence().get(0).path().contains("/ReceiverTypes.java")));
    assertFalse(reflectionFacts.stream().anyMatch(item -> item.evidence().get(0).path().contains("/HandlerUse.java")));
    assertTrue(reflectionFacts.stream().anyMatch(item -> item.evidence().get(0).path().contains("WildcardForName.java")));
    assertTrue(reflectionFacts.stream().allMatch(item ->
        item.certainty() == SemanticCallFacts.Certainty.UNRESOLVED
            && item.status() == SemanticCallFacts.Status.ABSTAINED
            && item.evidence().get(0).line() != null));

    List<SemanticCallFacts.Fact> proxyFacts = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("proxy_dispatch"))
        .toList();
    assertEquals(17, proxyFacts.size(), proxyFacts.toString());
    assertTrue(proxyFacts.stream().anyMatch(item -> item.evidence().get(0).attributes().get("caller").contains("#proxy")));
    assertTrue(proxyFacts.stream().anyMatch(item -> item.evidence().get(0).attributes().get("caller").contains("#secondProxy")));
    assertTrue(proxyFacts.stream().anyMatch(item -> item.evidence().get(0).attributes().get("caller").contains("#proxyWithVariable")
        && item.callee().equals("unknown#unknown")));
    assertTrue(proxyFacts.stream().anyMatch(item -> item.callee().equals("demo.Overloaded#run")
        && item.evidence().get(0).attributes().get("targetDescriptor").equals("(String)")));
    assertTrue(proxyFacts.stream().anyMatch(item -> item.callee().equals("demo.Overloaded#run")
        && item.evidence().get(0).attributes().get("targetDescriptor").equals("(int)")));
    assertFalse(proxyFacts.stream().anyMatch(item -> item.evidence().get(0).path().contains("TextBlockExamples.java")));
    assertFalse(proxyFacts.stream().anyMatch(item -> item.evidence().get(0).path().contains("ShadowedStatic.java")));
    assertFalse(proxyFacts.stream().anyMatch(item -> item.evidence().get(0).path().contains("UsesShadowApis.java")));
    assertFalse(proxyFacts.stream()
        .anyMatch(item -> item.evidence().get(0).path().contains("FakeProxyImportTextBlock.java")),
        proxyFacts.toString());
    assertFalse(proxyFacts.stream()
        .anyMatch(item -> item.evidence().get(0).path().contains("ShadowedTypeNames.java")),
        proxyFacts.toString());
    assertTrue(proxyFacts.stream().anyMatch(item -> item.evidence().get(0).path().contains("JdkTypedTypeNames.java")
        && item.evidence().get(0).attributes().get("caller").contains("#fieldProxy")));
    assertTrue(proxyFacts.stream().anyMatch(item -> item.evidence().get(0).path().contains("JdkTypedTypeNames.java")
        && item.evidence().get(0).attributes().get("caller").contains("#thisFieldProxy")));
    assertTrue(proxyFacts.stream().anyMatch(item -> item.evidence().get(0).path().contains("JdkTypedTypeNames.java")
        && item.evidence().get(0).attributes().get("caller").contains("#parameterProxy")));
    assertTrue(proxyFacts.stream().anyMatch(item -> item.evidence().get(0).path().contains("JdkTypedTypeNames.java")
        && item.evidence().get(0).attributes().get("caller").contains("#aliasProxy")));
    assertTrue(proxyFacts.stream().anyMatch(item -> item.evidence().get(0).path().contains("JdkTypedTypeNames.java")
        && item.evidence().get(0).attributes().get("caller").contains("#localProxy")));
    assertFalse(proxyFacts.stream()
        .anyMatch(item -> item.evidence().get(0).path().contains("QualifiedShadowedTypeNames.java")),
        proxyFacts.toString());
    assertFalse(proxyFacts.stream()
        .anyMatch(item -> item.evidence().get(0).path().contains("/demo/QualifiedThisOwners.java")),
        proxyFacts.toString());
    assertTrue(proxyFacts.stream()
        .anyMatch(item -> item.evidence().get(0).path().contains("QualifiedThisJdkOwners.java")));
    assertTrue(proxyFacts.stream()
        .anyMatch(item -> item.evidence().get(0).path().contains("QualifiedThisInheritedJdkOwners.java")));
    assertFalse(proxyFacts.stream()
        .anyMatch(item -> item.evidence().get(0).path().contains("QualifiedThisHiddenOrdinaryOwners.java")),
        proxyFacts.toString());
    assertTrue(proxyFacts.stream()
        .anyMatch(item -> item.evidence().get(0).path().contains("LocalQualifiedThisOwners.java")));
    List<SemanticCallFacts.Fact> overloadedProviderFacts = facts.providerFacts().stream()
        .filter(item -> item.callee().equals("demo.Overloaded#run"))
        .toList();
    assertEquals(2, overloadedProviderFacts.size());
    assertTrue(overloadedProviderFacts.stream().allMatch(item ->
        item.evidence().get(0).attributes().containsKey("targetDescriptor")));
  }

  /** Keeps colliding provider identities stable without reserving the legacy ID for one edge. */
  @Test
  void collidingProviderBaseIdsAreStableAndNeverUseLegacyId() throws IOException {
    Path sourceRoot = temp.resolve("repeated-calls/src/main/java");
    write(sourceRoot, "demo/RepeatedCalls.java", "package demo; class RepeatedCalls {}\n");
    AnalysisContext context = context(
        AnalysisContext.Framework.QUARKUS,
        sourceRoot,
        null,
        List.of());
    MethodSymbol caller = new MethodSymbol("demo.RepeatedCalls", "run", "()V");
    MethodSymbol callee = new MethodSymbol("demo.Target", "call", "()V");
    CallEdge first = new CallEdge(
        caller, callee, 4, "src/main/java/demo/First.java",
        SemanticCallFacts.Certainty.DEFINITE, "invokes");
    CallEdge second = new CallEdge(
        caller, callee, 4, "src/main/java/demo/Second.java",
        SemanticCallFacts.Certainty.DEFINITE, "invokes");

    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(context, new AnalysisGraph(
        List.of(first, second), List.of(), Set.of(), "SootUp", "3.0.1", 1, 2));
    SemanticCallFacts reversedFacts = new JavaAnalysisAdapter().analyze(context, new AnalysisGraph(
        List.of(second, first), List.of(), Set.of(), "SootUp", "3.0.1", 1, 2));
    String legacyId = "sootup-call-" + Integer.toHexString(
        (caller.descriptor() + "->" + callee.descriptor() + "@4").hashCode());

    assertEquals(2, facts.providerFacts().size());
    assertNotEquals(facts.providerFacts().get(0).id(), facts.providerFacts().get(1).id());
    assertTrue(facts.providerFacts().stream().noneMatch(fact -> fact.id().equals(legacyId)));
    Map<String, String> idsByPath = facts.providerFacts().stream()
        .collect(java.util.stream.Collectors.toMap(
            fact -> fact.evidence().get(0).path(), SemanticCallFacts.Fact::id));
    Map<String, String> reversedIdsByPath = reversedFacts.providerFacts().stream()
        .collect(java.util.stream.Collectors.toMap(
            fact -> fact.evidence().get(0).path(), SemanticCallFacts.Fact::id));
    assertEquals(idsByPath, reversedIdsByPath);
  }

  /** Preserves selected build context and generated roots in the serialized run metadata. */
  @Test
  void runMetadataFactoryPreservesProfileScopeRootsFingerprintsAndLimitations() throws IOException {
    Path sourceRoot = temp.resolve("metadata/src/test/java");
    Path generatedRoot = temp.resolve("metadata/target/generated-test-sources");
    Files.createDirectories(sourceRoot);
    Files.createDirectories(generatedRoot);
    AnalysisContext context = AnalysisContext.builder()
        .id("spring-boot-test")
        .framework(AnalysisContext.Framework.SPRING_BOOT)
        .frameworkVersion("2.7.18")
        .fixtureId("fsi-spring-boot")
        .javaRelease(17)
        .repositoryRoot(temp)
        .applicationClasses(temp.resolve("metadata/classes"))
        .selectedProfile("test")
        .analysisScope(AnalysisContext.Scope.TEST)
        .sourceRoot(
            "src/test/java",
            sourceRoot,
            AnalysisContext.Scope.TEST,
            true,
            "source-root-fingerprint")
        .generatedSourceRoot(
            "target/generated-test-sources",
            generatedRoot,
            AnalysisContext.Scope.TEST,
            true,
            false)
        .dependencyClasspathRequired(false)
        .provenance(new AnalysisContext.Provenance(
            "https://example.test/repository.git",
            "test",
            "test-revision",
            "source-fingerprint",
            "generated-fingerprint",
            "dependency-fingerprint",
            "build-fingerprint",
            "toolchain-fingerprint",
            "analyzer-fingerprint",
            "git-revision",
            "provider-runtime",
            "profile-digest",
            "2026-01-01T00:00:00Z",
            60))
        .handoff(new AnalysisContext.Handoff(
            "0.1.0",
            "spring-boot-test",
            "1.0.0",
            "profile-digest",
            "maven",
            List.of("mvn", "-Ptest", "test"),
            "target/application.jar",
            "build-fingerprint",
            List.of("dynamic behavior remains unresolved")))
        .build();

    AnalysisGraph graph = new AnalysisGraph(
        List.of(), List.of(), Set.of(), "SootUp", "3.0.1", 42, 17);
    SemanticCallFacts.RunMetadata factoryMetadata = SemanticCallFacts.RunMetadata.from(context, graph);
    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(context, graph);
    SemanticCallFacts.RunMetadata metadata = facts.runMetadata();
    assertEquals(factoryMetadata, metadata);
    assertEquals("test", metadata.selectedProfile());
    assertEquals("test", metadata.analysisScope());
    assertEquals("SootUp", metadata.providerName());
    assertEquals(42, metadata.graphMethodCount());
    assertEquals(17, metadata.graphCallCount());
    assertEquals(List.of(generatedRoot.toAbsolutePath().normalize()), metadata.generatedSourceRoots().stream()
        .map(AnalysisContext.SourceRoot::path)
        .toList());
    assertEquals("source-root-fingerprint", metadata.sourceRoots().get(0).fingerprint());
    assertEquals("generated-fingerprint", metadata.provenance().generatedSourceFingerprint());
    assertEquals("profile-digest", metadata.provenance().profileDigest());
    assertEquals(List.of("dynamic behavior remains unresolved"), metadata.handoff().limitations());
    assertThrows(UnsupportedOperationException.class, () -> factoryMetadata.sourceRoots().clear());
    assertThrows(UnsupportedOperationException.class, () -> factoryMetadata.generatedSourceRoots().clear());

    Path output = temp.resolve("metadata/semantic-call-facts.json");
    SemanticCallFactsJsonWriter.write(facts, output);
    String json = Files.readString(output);
    assertTrue(json.contains("\"selectedProfile\" : \"test\""));
    assertTrue(json.contains("\"analysisScope\" : \"test\""));
    assertTrue(json.contains("\"generatedSourceRoots\""));
    assertTrue(json.contains(generatedRoot.toAbsolutePath().normalize().toString()));
    assertTrue(json.contains("\"sourceRoots\""));
    assertTrue(json.contains("\"source-root-fingerprint\""));
    assertTrue(json.contains("\"generatedSourceFingerprint\" : \"generated-fingerprint\""));
    assertTrue(json.contains("dynamic behavior remains unresolved"));
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
