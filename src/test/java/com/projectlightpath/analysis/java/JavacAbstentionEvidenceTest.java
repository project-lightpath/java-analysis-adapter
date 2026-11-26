package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Issue #62: the per-member javac abstention evidence channel. Flag-on,
 * every javac consumer that declines (correlation guard, parameter
 * repair, framework-annotation mirror) attaches a {@code javacAbstention}
 * evidence attribute to the affected fact — the blast-radius-safe
 * visibility the scan-global diagnostics surface cannot provide
 * ({@code ServiceLoaderDescriptorScanner.isUnique} blocks DEFINITE
 * promotion for the WHOLE scan when {@code scan.diagnostics()} is
 * non-empty).
 *
 * <p>The class runs same-thread and {@link Isolated isolated} from all
 * other classes because it writes the flag, which production scan code
 * reads.
 */
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("javac-adapter-analysis")
class JavacAbstentionEvidenceTest {
  /** Opt-out flag: defaults ON since #66; "false" pins the textual path. */
  private static final String FLAG = "flpath.java.symbolindex";

  @TempDir Path temp;

  @AfterEach
  void clearFlag() {
    System.clearProperty(FLAG);
  }

  // C1: a proxy target whose owner file declares a nested type makes the
  // owner unprovable for name+arity correlation — the descriptor keeps
  // the textual dialect, and the proxy fact's evidence must say WHY.
  @Test
  void proxyTargetDescriptorAbstentionIsVisiblePerFact() throws IOException {
    SemanticCallFacts facts = analyze(List.of("""
        package demo;
        import org.springframework.scheduling.annotation.Async;
        import org.springframework.stereotype.Service;
        @Async @Service public class ProxyService {
          class Nested { }
          public String visible() { return ""; }
        }
        """));
    SemanticCallFacts.Fact proxy = frameworkFact(facts, "proxy_dispatch",
        "demo.ProxyService#visible");
    assertEquals("multi_type_file", attribute(proxy, "javacAbstention"),
        proxy.evidence().toString());
    assertEquals("demo.ProxyService#visible(0)",
        attribute(proxy, "javacAbstentionMember"), proxy.evidence().toString());
    assertTrue(proxy.certainty() == SemanticCallFacts.Certainty.POSSIBLE,
        "the abstention is visibility-only; the certainty is the pre-#62 "
            + "value: " + proxy.certainty());
    assertTrue(facts.diagnostics().isEmpty(),
        "the per-member channel never enters scan.diagnostics(): "
            + facts.diagnostics());
  }

  // C2: an unresolved classpath type in the signature makes the member
  // error-typed; the underlying javac error code must ride the fact.
  @Test
  void errorTypedMemberCarriesTheIndexDiagnostics() throws IOException {
    SemanticCallFacts facts = analyze(List.of("""
        package demo;
        import org.springframework.scheduling.annotation.Async;
        import org.springframework.stereotype.Service;
        @Async @Service public class ErrorService {
          public missing.Thing broken() { return null; }
        }
        """));
    SemanticCallFacts.Fact proxy = frameworkFact(facts, "proxy_dispatch",
        "demo.ErrorService#broken");
    assertEquals("error_type_member", attribute(proxy, "javacAbstention"),
        proxy.evidence().toString());
    String diagnostics = attribute(proxy, "javacAbstentionDiagnostics");
    assertTrue(diagnostics != null && diagnostics.startsWith("compiler.err."),
        "the javac error code recorded by the index must be visible: "
            + proxy.evidence());
    assertTrue(facts.diagnostics().isEmpty(), facts.diagnostics().toString());
  }

  // C3: mirrored overloads whose textual vectors hit a type parameter
  // (the unresolved-bounds class) abstain with their own code.
  @Test
  void typeParameterOverloadGroupHasItsOwnCode() throws IOException {
    SemanticCallFacts facts = analyze(List.of("""
        package demo;
        public class Thing { }
        """, """
        package demo;
        import org.springframework.context.annotation.Bean;
        import org.springframework.stereotype.Service;
        @Service public class BeanFactory<T> {
          @Bean public Thing make(T value) { return null; }
          public Thing make(java.lang.String value) { return null; }
        }
        """, """
        package demo;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(Thing thing) { }
        }
        """));
    SemanticCallFacts.Fact binding = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .filter(item -> item.evidence().stream().anyMatch(evidence ->
            evidence.attributes().getOrDefault("candidateId", "")
                .startsWith("demo.BeanFactory#make")))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.frameworkFacts().toString()));
    assertEquals("type_parameter_overload_group",
        attribute(binding, "javacAbstention"), binding.evidence().toString());
    assertEquals("demo.BeanFactory#make(1)",
        attribute(binding, "javacAbstentionMember"), binding.evidence().toString());
  }

  // C4 + C5: a producer in a multi-type file — the descriptor and the
  // producer type both keep the textual dialect, the bean_candidate
  // evidence carries the code, and the candidate type is unchanged
  // (visibility-only).
  @Test
  void producerAbstentionIsVisibleOnTheCandidateEvidence() throws IOException {
    SemanticCallFacts facts = analyze(List.of("""
        package demo;
        public class Thing { }
        """, """
        package demo;
        import org.springframework.context.annotation.Bean;
        import org.springframework.stereotype.Service;
        @Service public class ProducerConfig {
          class Helper { }
          @Bean public Thing build(java.lang.String value) {
            return null;
          }
        }
        """, """
        package demo;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(Thing thing) { }
        }
        """));
    SemanticCallFacts.Fact binding = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .filter(item -> item.evidence().stream().anyMatch(evidence ->
            "demo.ProducerConfig#build(java.lang.String value)".equals(
                evidence.attributes().get("candidateId"))))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.frameworkFacts().toString()));
    assertEquals("multi_type_file", attribute(binding, "javacAbstention"),
        binding.evidence().toString());
    // The descriptor seam derives the owner through the declarationStart
    // normalization; the producer-TYPE seam keeps its historical
    // raw-extraction anchor — two different derivations, two key
    // families, both visible (review round 1, finding 2).
    assertEquals("demo.ProducerConfig#build(1)",
        attribute(binding, "javacAbstentionMember"), binding.evidence().toString());
    assertEquals("multi_type_file",
        attribute(binding, "javacProducerTypeAbstention"),
        binding.evidence().toString());
    assertEquals("demo.ProducerConfig$Helper#build(1)",
        attribute(binding, "javacProducerTypeAbstentionMember"),
        binding.evidence().toString());
    boolean textualCandidateType = binding.evidence().stream()
        .anyMatch(evidence -> "demo.Thing".equals(
            evidence.attributes().get("candidateType")));
    assertTrue(textualCandidateType,
        "the producer type keeps its textual spelling (visibility-only): "
            + binding.evidence());
  }

  // C6: the interface-method descriptor of a JDK dynamic proxy feature
  // abstains when the interface file declares a nested type.
  @Test
  void interfaceProxyDescriptorAbstentionIsVisible() throws IOException {
    SemanticCallFacts facts = analyzeDynamic(List.of("""
        package demo;
        public interface Api {
          class Inner { }
          void run(java.lang.String value);
        }
        """, """
        package demo;
        import java.lang.reflect.Proxy;
        public class ApiProxyFactory {
          public Api wrap() {
            return (Api) Proxy.newProxyInstance(Api.class.getClassLoader(),
                new Class<?>[]{Api.class}, (proxy, method, args) -> null);
          }
        }
        """));
    SemanticCallFacts.Fact proxy = facts.providerFacts().stream()
        .filter(item -> item.relationshipType().equals("proxy_dispatch"))
        .filter(item -> item.callee().equals("demo.Api#run"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.providerFacts().toString()));
    assertEquals("multi_type_file", attribute(proxy, "javacAbstention"),
        proxy.evidence().toString());
  }

  // C7: the interface-method descriptor's two lookups decline for
  // different reasons on one fixture: the correlation's bijection fails
  // on the sibling-count mismatch (a comment-corrupted overload is
  // excluded from the sibling set, seam A) while the per-parameter
  // repair's unique member lookup sees the same-arity overload pair
  // (seam B, annotation_member_ambiguous) — both declines visible under
  // their own keys. (A same-ERASED-signature overload pair is illegal
  // Java — javac collapses it to one index member — so the bijection's
  // collision arm is not constructible; RED-calibration finding.)
  @Test
  void repairDeclineCarriesItsOwnKey() throws IOException {
    SemanticCallFacts facts = analyzeDynamic(List.of("""
        package demo;
        public interface Overloaded {
          void run(@Mar$k java.lang.String first);
          void run(/* odd */ java.lang.Integer count);
        }
        """, """
        package demo;
        import java.lang.reflect.Proxy;
        public class OverloadedProxyFactory {
          public Overloaded wrap() {
            return (Overloaded) Proxy.newProxyInstance(
                Overloaded.class.getClassLoader(),
                new Class<?>[]{Overloaded.class}, (proxy, method, args) -> null);
          }
        }
        """));
    SemanticCallFacts.Fact proxy = facts.providerFacts().stream()
        .filter(item -> item.relationshipType().equals("proxy_dispatch"))
        .filter(item -> item.callee().equals("demo.Overloaded#run"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.providerFacts().toString()));
    assertEquals("overload_group_mismatch", attribute(proxy, "javacAbstention"),
        proxy.evidence().toString());
    assertEquals("annotation_member_ambiguous",
        attribute(proxy, "javacRepairAbstention"), proxy.evidence().toString());
  }

  // C8: an ambiguous same-arity producer overload group correlates
  // through the bijection (seam A succeeds, no javacAbstention) but
  // defeats the unique member lookup behind the framework-annotation
  // mirror (seam C) — the mirror decline is visible under its own key.
  @Test
  void mirrorDeclineIsVisibleAlongsideTheCorrelationAbstention() throws IOException {
    SemanticCallFacts facts = analyze(List.of("""
        package demo;
        public class Thing { }
        """, """
        package demo;
        import org.springframework.context.annotation.Bean;
        import org.springframework.stereotype.Service;
        @Service public class AmbiguousProducer {
          @Bean public Thing make(java.lang.String value) { return null; }
          public Thing make(java.lang.Integer count) { return null; }
        }
        """, """
        package demo;
        import org.springframework.beans.factory.annotation.Autowired;
        import org.springframework.stereotype.Component;
        @Component public class Consumer {
          @Autowired public Consumer(Thing thing) { }
        }
        """));
    SemanticCallFacts.Fact binding = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("dependency_injection"))
        .filter(item -> item.evidence().stream().anyMatch(evidence ->
            evidence.attributes().getOrDefault("candidateId", "")
                .startsWith("demo.AmbiguousProducer#make")))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.frameworkFacts().toString()));
    assertNull(attribute(binding, "javacAbstention"),
        "the bijection correlates the group; only the mirror declines: "
            + binding.evidence());
    assertEquals("mirror_member_ambiguous",
        attribute(binding, "javacMirrorAbstention"), binding.evidence().toString());
  }

  // C9: the repository guaranteed-contract check consults the correlated
  // first parameter; when that correlation abstains, the declared
  // method's delegation fact carries the code.
  @Test
  void repositorySignatureAbstentionIsVisibleOnTheDelegationFact() throws IOException {
    SemanticCallFacts facts = analyze(List.of("""
        package demo;
        public interface AccountRepository
            extends org.springframework.data.jpa.repository.JpaRepository<Account, Long> {
          class Inner { }
          Account findById(java.lang.String bogus);
        }
        """));
    SemanticCallFacts.Fact findById = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("delegated_implementation"))
        .filter(item -> item.caller().equals("demo.AccountRepository#findById"))
        .filter(item -> item.evidence().stream().anyMatch(evidence ->
            "findById(java.lang.String)".equals(
                evidence.attributes().get("repositoryMethod"))))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.frameworkFacts().toString()));
    assertEquals("multi_type_file", attribute(findById, "javacAbstention"),
        findById.evidence().toString());
    assertEquals("demo.AccountRepository#findById(1)",
        attribute(findById, "javacAbstentionMember"), findById.evidence().toString());
  }

  // C10: a reflection site whose receiver is a caller PARAMETER, inside
  // a member whose correlation abstains — the reflection fact carries
  // the CALLER member's code, distinct from the #25 target evidence; a
  // field-receiver twin carries no caller attribute (causality), and the
  // addJdkOwnerSites arm (Constructor parameter receiver) is covered.
  @Test
  void reflectionCallerAbstentionIsVisibleAndCausal() throws IOException {
    SemanticCallFacts parameter = analyzeDynamic(List.of("""
        package demo;
        public class Sites {
          void call(Class<?> loader) {
            loader.getMethod("run");
          }
          class Nested { }
        }
        """));
    SemanticCallFacts.Fact site = parameter.providerFacts().stream()
        .filter(item -> item.relationshipType().equals("reflection"))
        .filter(item -> item.caller().startsWith("demo.Sites#"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(parameter.providerFacts().toString()));
    assertEquals("multi_type_file", attribute(site, "javacCallerAbstention"),
        site.evidence().toString());
    assertEquals("demo.Sites#call(1)",
        attribute(site, "javacCallerAbstentionMember"), site.evidence().toString());

    SemanticCallFacts field = analyzeDynamic(List.of("""
        package demo;
        public class FieldSites {
          Class<?> loader;
          void call() {
            loader.getMethod("run");
          }
          class Nested { }
        }
        """));
    SemanticCallFacts.Fact fieldSite = field.providerFacts().stream()
        .filter(item -> item.relationshipType().equals("reflection"))
        .filter(item -> item.caller().startsWith("demo.FieldSites#"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(field.providerFacts().toString()));
    assertNull(attribute(fieldSite, "javacCallerAbstention"),
        "a field receiver never consulted the correlation arm: "
            + fieldSite.evidence());

    SemanticCallFacts constructor = analyzeDynamic(List.of("""
        package demo;
        public class CtorSites {
          void call(java.lang.reflect.Constructor<?> ctor) {
            ctor.newInstance();
          }
          class Nested { }
        }
        """));
    SemanticCallFacts.Fact ctorSite = constructor.providerFacts().stream()
        .filter(item -> item.relationshipType().equals("reflection"))
        .filter(item -> item.caller().startsWith("demo.CtorSites#"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(constructor.providerFacts().toString()));
    assertEquals("multi_type_file",
        attribute(ctorSite, "javacCallerAbstention"),
        "the isJdkApiOwner arm reaches isDeclaredVariableBefore too: "
            + ctorSite.evidence());
  }

  // D1: the blast-radius pin — an abstaining fixture AND a ServiceLoader
  // DEFINITE site in one scan: promotion is untouched, diagnostics stay
  // empty, the abstention is on the fact.
  @Test
  void abstentionsDoNotBlockServiceLoaderDefinitePromotion() throws IOException {
    Path source = Files.createDirectories(temp.resolve("blast/src/main/java"));
    Path classes = Files.createDirectories(temp.resolve("blast/classes"));
    Path dependencies = Files.createDirectories(temp.resolve("blast/deps"));
    Path provider = source.resolve("demo/Handler.java");
    Path implementation = source.resolve("demo/HandlerImpl.java");
    Path app = source.resolve("demo/App.java");
    write(provider, "package demo; public interface Handler { void record(); }\n");
    write(implementation,
        "package demo; public class HandlerImpl implements Handler { public void record() {} }\n");
    write(app, """
        package demo;
        import java.util.ServiceLoader;
        class App {
          void call() { ServiceLoader.load(Handler.class).forEach(h -> h.record()); }
          void reflect(Class<?> loader) throws Exception { loader.getMethod("record"); }
          class Nested { }
        }
        """);
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
        "--release", "17", "-g", "-d", classes.toString(), provider.toString(),
        implementation.toString(), app.toString()));
    Path unique = dependencies.resolve("unique.jar");
    jar(unique, "demo.HandlerImpl");

    AnalysisContext context = context(source, classes, List.of(unique));
    SemanticCallFacts facts = new JavaAnalysisAdapter().analyze(context);
    assertTrue(facts.providerFacts().stream()
        .filter(item -> item.relationshipType().equals("service_loader"))
        .allMatch(item -> item.certainty() == SemanticCallFacts.Certainty.DEFINITE),
        "an unrelated abstention must not block DEFINITE promotion: "
            + facts.providerFacts());
    assertTrue(facts.diagnostics().isEmpty(),
        "the channel must never enter scan.diagnostics(): " + facts.diagnostics());
    assertTrue(facts.providerFacts().stream()
        .filter(item -> item.relationshipType().equals("reflection"))
        .anyMatch(item -> item.evidence().stream()
            .anyMatch(evidence -> "multi_type_file".equals(
                evidence.attributes().get("javacCallerAbstention")))),
        "the abstention rides the reflection fact: " + facts.providerFacts());
  }

  // D2: the opt-out twin — flag "false" produces no abstention attributes
  // anywhere (the ungated paths included).
  @Test
  void optOutScanCarriesNoAbstentionAttributes() throws IOException {
    System.setProperty(FLAG, "false");
    SemanticCallFacts facts = analyze(List.of("""
        package demo;
        import org.springframework.scheduling.annotation.Async;
        import org.springframework.stereotype.Service;
        @Async @Service public class ProxyService {
          class Nested { }
          public String visible() { return ""; }
        }
        """, """
        package demo;
        public interface AccountRepository
            extends org.springframework.data.jpa.repository.JpaRepository<Account, Long> {
          class Inner { }
          Account findById(java.lang.String bogus);
        }
        """));
    boolean anyAbstentionAttribute = facts.frameworkFacts().stream()
        .anyMatch(item -> item.evidence().stream()
            .anyMatch(evidence -> evidence.attributes().keySet().stream()
                .anyMatch(key -> key.startsWith("javac") && key.contains("Abstention"))));
    assertFalse(anyAbstentionAttribute,
        "the opt-out path is byte-identical to main: " + facts.frameworkFacts());
    boolean anyProviderAbstention = facts.providerFacts().stream()
        .anyMatch(item -> item.evidence().stream()
            .anyMatch(evidence -> evidence.attributes().keySet().stream()
                .anyMatch(key -> key.startsWith("javac") && key.contains("Abstention"))));
    assertFalse(anyProviderAbstention,
        "the opt-out provider path is byte-identical to main: "
            + facts.providerFacts());
  }

  // D3: the control — a correlating member carries NO attribute.
  @Test
  void correlatingMembersCarryNoAbstention() throws IOException {
    SemanticCallFacts facts = analyze(List.of("""
        package demo;
        import org.springframework.scheduling.annotation.Async;
        import org.springframework.stereotype.Service;
        @Async @Service public class ProxyService {
          public String visible() { return ""; }
        }
        """));
    SemanticCallFacts.Fact proxy = frameworkFact(facts, "proxy_dispatch",
        "demo.ProxyService#visible");
    assertNull(attribute(proxy, "javacAbstention"),
        "a correlated member has nothing to abstain about: "
            + proxy.evidence());
  }

  // D4: cache-ordering pin — the same context analyzed twice carries
  // identical attributes, and a flag-off scan of the same fixture (warm
  // JVM, fresh context) carries none: attributes follow the flag, never
  // the cache order.
  @Test
  void abstentionAttributesAreCacheOrderIndependent() throws IOException {
    Path sourceRoot = temp.resolve("cache-order/src/main/java");
    write(sourceRoot.resolve("demo/Sites.java"), """
        package demo;
        public class Sites {
          void call(Class<?> loader) {
            loader.getMethod("run");
          }
          class Nested { }
        }
        """);
    AnalysisContext context = context(sourceRoot, temp.resolve("cache-order/classes"),
        List.of());
    SemanticCallFacts first = dynamicScan(context);
    SemanticCallFacts second = dynamicScan(context);
    assertEquals(
        first.providerFacts().stream()
            .filter(item -> item.relationshipType().equals("reflection"))
            .findFirst().orElseThrow().evidence().toString(),
        second.providerFacts().stream()
            .filter(item -> item.relationshipType().equals("reflection"))
            .findFirst().orElseThrow().evidence().toString(),
        "repeated scans of one context are identical");
    assertTrue(second.providerFacts().stream()
            .filter(item -> item.relationshipType().equals("reflection"))
            .allMatch(item -> item.evidence().stream().anyMatch(evidence ->
                "multi_type_file".equals(
                    evidence.attributes().get("javacCallerAbstention")))),
        "the warm second scan keeps the attribute: "
            + second.providerFacts());
    System.setProperty(FLAG, "false");
    Path optOutRoot = temp.resolve("cache-order-optout/src/main/java");
    write(optOutRoot.resolve("demo/Sites.java"), """
        package demo;
        public class Sites {
          void call(Class<?> loader) {
            loader.getMethod("run");
          }
          class Nested { }
        }
        """);
    SemanticCallFacts optedOut = dynamicScan(
        context(optOutRoot, temp.resolve("cache-order-optout/classes"), List.of()));
    assertTrue(optedOut.providerFacts().stream()
        .filter(item -> item.relationshipType().equals("reflection"))
        .allMatch(item -> item.evidence().stream().noneMatch(evidence ->
            evidence.attributes().containsKey("javacCallerAbstention"))),
        "a warm-JVM flag-off scan carries no abstention: "
            + optedOut.providerFacts());
  }

  // D5 (review round 2, finding 1): a FAILED index build is a scan-global
  // integrity failure, not a per-member abstention — the channel stays
  // silent rather than labeling every decline multi_type_file.
  @Test
  void failedIndexBuildIsNotAMemberAbstention() throws IOException {
    JavacSourceSymbolIndex.COMPILER.set(() -> null);
    try {
      SemanticCallFacts facts = analyze(List.of("""
          package demo;
          import org.springframework.scheduling.annotation.Async;
          import org.springframework.stereotype.Service;
          @Async @Service public class ProxyService {
            class Nested { }
            public String visible() { return ""; }
          }
          """));
      SemanticCallFacts.Fact proxy = frameworkFact(facts, "proxy_dispatch",
          "demo.ProxyService#visible");
      assertNull(attribute(proxy, "javacAbstention"),
          "a failed index build must not be mislabeled a per-member cause: "
              + proxy.evidence());
      assertTrue(facts.diagnostics().isEmpty(),
          "the integrity failure stays on the index diagnostics surface: "
              + facts.diagnostics());
    } finally {
      JavacSourceSymbolIndex.COMPILER.set(ToolProvider::getSystemJavaCompiler);
    }
  }

  // D6 (review round 2, finding 3): the PROXY path's caller-abstention
  // gate, in the affirmative — a bare-receiver proxy construction inside
  // a member whose correlation abstains.
  @Test
  void proxyCallerAbstentionIsVisible() throws IOException {
    SemanticCallFacts facts = analyzeDynamic(List.of("""
        package demo;
        import java.lang.reflect.Proxy;
        public class ProxySites {
          void wrap(java.lang.reflect.Proxy Proxy) {
            Proxy.newProxyInstance(null, null, null);
          }
          class Nested { }
        }
        """));
    SemanticCallFacts.Fact proxy = facts.providerFacts().stream()
        .filter(item -> item.relationshipType().equals("proxy_dispatch"))
        .filter(item -> item.caller().startsWith("demo.ProxySites#"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.providerFacts().toString()));
    assertEquals("multi_type_file",
        attribute(proxy, "javacCallerAbstention"), proxy.evidence().toString());
    assertEquals("demo.ProxySites#wrap(1)",
        attribute(proxy, "javacCallerAbstentionMember"), proxy.evidence().toString());
  }

  private SemanticCallFacts.Fact frameworkFact(
      SemanticCallFacts facts, String relationship, String callee) {
    return facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals(relationship))
        .filter(item -> item.callee().equals(callee))
        .findFirst()
        .orElseThrow(() -> new AssertionError(facts.frameworkFacts().toString()));
  }

  private static String attribute(SemanticCallFacts.Fact fact, String key) {
    return fact.evidence().stream()
        .map(evidence -> evidence.attributes().get(key))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  private SemanticCallFacts analyze(List<String> sources) throws IOException {
    Path sourceRoot = temp.resolve("abstention/src/main/java");
    write(sourceRoot.resolve("demo/Account.java"),
        "package demo; public class Account {}\n");
    writeSources(sourceRoot, sources);
    return new JavaAnalysisAdapter().analyze(
        context(sourceRoot, temp.resolve("abstention/classes"), List.of()),
        AnalysisGraph.empty());
  }

  /** The dynamic-feature cells need the graph the production path builds. */
  private SemanticCallFacts dynamicScan(AnalysisContext scanContext) {
    return new JavaAnalysisAdapter().analyze(scanContext, new AnalysisGraph(
        List.of(), new SourceMetadataScanner(scanContext).dynamicFeatures(),
        java.util.Set.of(), "SootUp", "3.0.1", 0, 0));
  }

  private SemanticCallFacts analyzeDynamic(List<String> sources) throws IOException {
    Path sourceRoot = temp.resolve("abstention-dynamic/src/main/java");
    write(sourceRoot.resolve("demo/Account.java"),
        "package demo; public class Account {}\n");
    writeSources(sourceRoot, sources);
    AnalysisContext scanContext =
        context(sourceRoot, temp.resolve("abstention-dynamic/classes"), List.of());
    return dynamicScan(scanContext);
  }

  /** Files are named after their declared type: the scanner's reflection
   * extraction anchors on the file/type name, so Source0.java fixtures
   * would silently produce no sites. */
  private static void writeSources(Path sourceRoot, List<String> sources)
      throws IOException {
    for (int index = 0; index < sources.size(); index++) {
      java.util.regex.Matcher type = java.util.regex.Pattern
          .compile("(?:public\\s+)?(?:final\\s+)?(?:abstract\\s+)?"
              + "(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)")
          .matcher(sources.get(index));
      String name = type.find() ? type.group(1) : "Source" + index;
      write(sourceRoot.resolve("demo/" + name + ".java"), sources.get(index));
    }
  }

  private AnalysisContext context(
      Path source, Path classes, List<Path> dependencies) {
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
            "spring-aop-advisor-metadata", "runtime_or_build_metadata",
            AnalysisContext.MetadataStatus.OBSERVED, "target/aop.json"))
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
