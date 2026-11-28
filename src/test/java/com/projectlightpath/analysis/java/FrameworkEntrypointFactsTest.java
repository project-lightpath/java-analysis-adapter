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
 * Issue #84: framework-invoked entrypoint roots (message listeners,
 * scheduled tasks, main methods) for the #71 CVE-reachability answer.
 *
 * <p>Cells are the PLAN-84 equivalence matrix: certainty ladder (POSSIBLE
 * with observed source-annotation metadata, UNRESOLVED without, never
 * DEFINITE), canonical-annotation resolution including lookalikes and
 * wildcard imports, bean-declaration gating, nested-type declaring types,
 * main-shape boundaries, runner-usage containment, and family
 * interactions.
 */
final class FrameworkEntrypointFactsTest {
  @TempDir
  Path temp;

  private static final List<AnalysisContext.FrameworkMetadata> SPRING_LISTENER =
      List.of(metadata("spring-listener-annotations"));
  private static final List<AnalysisContext.FrameworkMetadata> SPRING_SCHEDULING =
      List.of(metadata("spring-scheduling-annotations"));
  private static final List<AnalysisContext.FrameworkMetadata> QUARKUS_OBSERVER =
      List.of(metadata("quarkus-cdi-observer-annotations"));
  private static final List<AnalysisContext.FrameworkMetadata> QUARKUS_SCHEDULER =
      List.of(metadata("quarkus-scheduler-annotations"));

  private static AnalysisContext.FrameworkMetadata metadata(String id) {
    return new AnalysisContext.FrameworkMetadata(
        id, "source_annotations", AnalysisContext.MetadataStatus.OBSERVED, "src/main/java");
  }

  // ------------------------------------------------------------------
  // Spring message listeners (L1-L10)
  // ------------------------------------------------------------------

  /** L1: canonical @KafkaListener on a bean, metadata observed ⇒ POSSIBLE. */
  @Test
  void kafkaListenerIsPossibleWithListenerMetadata() throws IOException {
    Path root = springRoot("spring-listener/src/main/java");
    write(root, "demo/OrderListener.java", """
        package demo;
        import org.springframework.stereotype.Component;
        import org.springframework.kafka.annotation.KafkaListener;
        @Component
        public class OrderListener {
          @KafkaListener(topics = "orders", groupId = "g")
          public void onKafka(String message) { }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.SPRING_BOOT, root, SPRING_LISTENER);
    SemanticCallFacts.Fact fact = entrypoint(facts, "demo.OrderListener#onKafka");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, fact.certainty());
    assertEquals("external Kafka listener", fact.caller());
    assertEquals("spring-message-listener", fact.modelRule());
    assertEquals(1, fact.evidence().size());
    assertEquals("listener_annotation", fact.evidence().get(0).kind());
    assertEquals("KafkaListener", fact.evidence().get(0).attributes().get("mechanism"));
    assertTrue(fact.limitation() != null && !fact.limitation().isBlank());
  }

  /** L2: same shape without the metadata family ⇒ UNRESOLVED, not dropped. */
  @Test
  void listenerStaysUnresolvedWithoutMetadata() throws IOException {
    Path root = springRoot("spring-listener-no-metadata/src/main/java");
    write(root, "demo/OrderListener.java", """
        package demo;
        import org.springframework.stereotype.Component;
        import org.springframework.kafka.annotation.KafkaListener;
        @Component
        public class OrderListener {
          @KafkaListener(topics = "orders")
          public void onKafka(String message) { }
        }
        """);

    SemanticCallFacts facts =
        analyze(AnalysisContext.Framework.SPRING_BOOT, root, List.of());
    SemanticCallFacts.Fact fact = entrypoint(facts, "demo.OrderListener#onKafka");
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED, fact.certainty());
    assertEquals(SemanticCallFacts.Status.ABSTAINED, fact.status());
  }

  /** L3: user-defined same-simple-name lookalike ⇒ no fact (#66 rule). */
  @Test
  void lookalikeListenerAnnotationIsSuppressed() throws IOException {
    Path root = springRoot("spring-listener-lookalike/src/main/java");
    write(root, "demo/KafkaListener.java", """
        package demo;
        public @interface KafkaListener { String[] topics(); }
        """);
    write(root, "demo/LookalikeUser.java", """
        package demo;
        import org.springframework.stereotype.Component;
        @Component
        public class LookalikeUser {
          @KafkaListener(topics = "y")
          public void onKafka(String message) { }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.SPRING_BOOT, root, SPRING_LISTENER);
    assertTrue(entrypoints(facts, "demo.LookalikeUser#onKafka").isEmpty(),
        facts.frameworkFacts().toString());
  }

  /** L4: canonical annotation on a non-bean type ⇒ no fact. */
  @Test
  void listenerOnNonBeanTypeIsSuppressed() throws IOException {
    Path root = springRoot("spring-listener-non-bean/src/main/java");
    write(root, "demo/PlainListener.java", """
        package demo;
        import org.springframework.kafka.annotation.KafkaListener;
        public class PlainListener {
          @KafkaListener(topics = "orders")
          public void onKafka(String message) { }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.SPRING_BOOT, root, SPRING_LISTENER);
    assertTrue(entrypoints(facts, "demo.PlainListener#onKafka").isEmpty(),
        facts.frameworkFacts().toString());
  }

  /** L5: every Spring listener mechanism emits with its own caller. */
  @Test
  void rabbitJmsAndEventListenerMechanismsEmit() throws IOException {
    Path root = springRoot("spring-listener-mechanisms/src/main/java");
    write(root, "demo/Rabbit.java", """
        package demo;
        import org.springframework.stereotype.Component;
        import org.springframework.amqp.rabbit.annotation.RabbitListener;
        @Component
        public class Rabbit {
          @RabbitListener(queues = "q")
          public void onRabbit(String message) { }
        }
        """);
    write(root, "demo/Jms.java", """
        package demo;
        import org.springframework.stereotype.Component;
        import org.springframework.jms.annotation.JmsListener;
        @Component
        public class Jms {
          @JmsListener(destination = "d")
          public void onJms(String message) { }
        }
        """);
    write(root, "demo/Events.java", """
        package demo;
        import org.springframework.stereotype.Component;
        import org.springframework.context.event.EventListener;
        @Component
        public class Events {
          @EventListener
          public void onEvent(Object event) { }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.SPRING_BOOT, root, SPRING_LISTENER);
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE,
        entrypoint(facts, "demo.Rabbit#onRabbit").certainty());
    assertEquals("external RabbitMQ listener", entrypoint(facts, "demo.Rabbit#onRabbit").caller());
    assertEquals("external JMS listener", entrypoint(facts, "demo.Jms#onJms").caller());
    assertEquals("Spring ApplicationContext event",
        entrypoint(facts, "demo.Events#onEvent").caller());
    assertEquals("EventListener",
        entrypoint(facts, "demo.Events#onEvent").evidence().get(0).attributes().get("mechanism"));
  }

  /** L6: a @Bean-registered type carries listener facts too. */
  @Test
  void eventListenerOnBeanRegisteredTypeEmits() throws IOException {
    Path root = springRoot("spring-listener-bean-registered/src/main/java");
    write(root, "demo/ListenerConfig.java", """
        package demo;
        import org.springframework.context.annotation.Bean;
        import org.springframework.context.annotation.Configuration;
        import org.springframework.context.event.EventListener;
        public class ListenerConfig {
          @EventListener
          public void onEvent(Object event) { }
        }
        """);
    write(root, "demo/AppConfig.java", """
        package demo;
        import org.springframework.context.annotation.Bean;
        import org.springframework.context.annotation.Configuration;
        @Configuration
        public class AppConfig {
          @Bean
          public ListenerConfig listenerConfig() { return new ListenerConfig(); }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.SPRING_BOOT, root, SPRING_LISTENER);
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE,
        entrypoint(facts, "demo.ListenerConfig#onEvent").certainty());
  }

  /** L7: fully-qualified spelling resolves without an import. */
  @Test
  void fullyQualifiedListenerSpellingEmits() throws IOException {
    Path root = springRoot("spring-listener-qualified/src/main/java");
    write(root, "demo/Qualified.java", """
        package demo;
        import org.springframework.stereotype.Component;
        @Component
        public class Qualified {
          @org.springframework.kafka.annotation.KafkaListener(topics = "orders")
          public void onKafka(String message) { }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.SPRING_BOOT, root, SPRING_LISTENER);
    SemanticCallFacts.Fact fact = entrypoint(facts, "demo.Qualified#onKafka");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, fact.certainty());
    assertTrue(fact.evidence().get(0).symbols().stream()
            .anyMatch(symbol -> symbol.startsWith("@org.springframework.kafka.annotation.KafkaListener")),
        fact.evidence().toString());
  }

  /** L8: on-demand import never resolves a canonical occurrence. */
  @Test
  void wildcardImportListenerIsSuppressed() throws IOException {
    Path root = springRoot("spring-listener-wildcard/src/main/java");
    write(root, "demo/Wildcard.java", """
        package demo;
        import org.springframework.stereotype.Component;
        import org.springframework.kafka.annotation.*;
        @Component
        public class Wildcard {
          @KafkaListener(topics = "orders")
          public void onKafka(String message) { }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.SPRING_BOOT, root, SPRING_LISTENER);
    assertTrue(entrypoints(facts, "demo.Wildcard#onKafka").isEmpty(),
        facts.frameworkFacts().toString());
  }

  /** L9 (characterization): private listener methods still emit POSSIBLE. */
  @Test
  void privateListenerMethodStillEmits() throws IOException {
    Path root = springRoot("spring-listener-private/src/main/java");
    write(root, "demo/PrivateListener.java", """
        package demo;
        import org.springframework.stereotype.Component;
        import org.springframework.kafka.annotation.KafkaListener;
        @Component
        public class PrivateListener {
          @KafkaListener(topics = "orders")
          private void onKafka(String message) { }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.SPRING_BOOT, root, SPRING_LISTENER);
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE,
        entrypoint(facts, "demo.PrivateListener#onKafka").certainty());
  }

  /** L10: repeatable container spelling emits one fact for the method. */
  @Test
  void repeatableKafkaListenersContainerEmitsOneFact() throws IOException {
    Path root = springRoot("spring-listener-container/src/main/java");
    write(root, "demo/ContainerListener.java", """
        package demo;
        import org.springframework.stereotype.Component;
        import org.springframework.kafka.annotation.KafkaListener;
        import org.springframework.kafka.annotation.KafkaListeners;
        @Component
        public class ContainerListener {
          @KafkaListeners({
              @KafkaListener(topics = "a"),
              @KafkaListener(topics = "b")})
          public void onKafka(String message) { }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.SPRING_BOOT, root, SPRING_LISTENER);
    List<SemanticCallFacts.Fact> matches = entrypoints(facts, "demo.ContainerListener#onKafka");
    assertEquals(1, matches.size(), facts.frameworkFacts().toString());
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, matches.get(0).certainty());
  }

  // ------------------------------------------------------------------
  // Quarkus message listeners (Q1-Q10)
  // ------------------------------------------------------------------

  /** Q1: canonical @Observes on a CDI bean, metadata observed ⇒ POSSIBLE. */
  @Test
  void observesIsPossibleWithObserverMetadata() throws IOException {
    Path root = quarkusRoot("quarkus-observer/src/main/java");
    write(root, "demo/CdiObservers.java", """
        package demo;
        import jakarta.enterprise.context.ApplicationScoped;
        import jakarta.enterprise.event.Observes;
        @ApplicationScoped
        public class CdiObservers {
          void onEvent(@Observes Object event) { }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.QUARKUS, root, QUARKUS_OBSERVER);
    SemanticCallFacts.Fact fact = entrypoint(facts, "demo.CdiObservers#onEvent");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, fact.certainty());
    assertEquals("CDI event dispatcher", fact.caller());
    assertEquals("cdi-observer-method", fact.modelRule());
    assertEquals("observer_parameter_annotation", fact.evidence().get(0).kind());
    assertEquals("Observes", fact.evidence().get(0).attributes().get("mechanism"));
  }

  /** Q2: parameter annotation arguments survive extraction. */
  @Test
  void observesWithArgumentsEmits() throws IOException {
    Path root = quarkusRoot("quarkus-observer-arguments/src/main/java");
    write(root, "demo/ArgObservers.java", """
        package demo;
        import jakarta.enterprise.context.ApplicationScoped;
        import jakarta.enterprise.event.Observes;
        import jakarta.enterprise.event.TransactionPhase;
        @ApplicationScoped
        public class ArgObservers {
          void onEvent(@Observes(during = TransactionPhase.IN_PROGRESS) Object event) { }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.QUARKUS, root, QUARKUS_OBSERVER);
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE,
        entrypoint(facts, "demo.ArgObservers#onEvent").certainty());
  }

  /** Q3: user-defined lookalike @Observes ⇒ no fact. */
  @Test
  void lookalikeObservesIsSuppressed() throws IOException {
    Path root = quarkusRoot("quarkus-observer-lookalike/src/main/java");
    write(root, "demo/Observes.java", """
        package demo;
        public @interface Observes { }
        """);
    write(root, "demo/LookalikeObserver.java", """
        package demo;
        import jakarta.enterprise.context.ApplicationScoped;
        @ApplicationScoped
        public class LookalikeObserver {
          void onEvent(@Observes Object event) { }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.QUARKUS, root, QUARKUS_OBSERVER);
    assertTrue(entrypoints(facts, "demo.LookalikeObserver#onEvent").isEmpty(),
        facts.frameworkFacts().toString());
  }

  /** Q4: the javax spelling resolves canonically. */
  @Test
  void javaxObservesEmits() throws IOException {
    Path root = quarkusRoot("quarkus-observer-javax/src/main/java");
    write(root, "demo/JavaxObservers.java", """
        package demo;
        import javax.enterprise.context.ApplicationScoped;
        import javax.enterprise.event.Observes;
        @ApplicationScoped
        public class JavaxObservers {
          void onEvent(@Observes Object event) { }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.QUARKUS, root, QUARKUS_OBSERVER);
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE,
        entrypoint(facts, "demo.JavaxObservers#onEvent").certainty());
  }

  /** Q5: canonical @Observes on a non-bean ⇒ no fact. */
  @Test
  void observesOnNonBeanIsSuppressed() throws IOException {
    Path root = quarkusRoot("quarkus-observer-non-bean/src/main/java");
    write(root, "demo/PlainObservers.java", """
        package demo;
        import jakarta.enterprise.event.Observes;
        public class PlainObservers {
          void onEvent(@Observes Object event) { }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.QUARKUS, root, QUARKUS_OBSERVER);
    assertTrue(entrypoints(facts, "demo.PlainObservers#onEvent").isEmpty(),
        facts.frameworkFacts().toString());
  }

  /** Q9 (characterization): lookalike CDI scope + canonical @Observes emits. */
  @Test
  void lookalikeCdiScopeWithCanonicalObservesEmits() throws IOException {
    Path root = quarkusRoot("quarkus-observer-scope-lookalike/src/main/java");
    write(root, "demo/ApplicationScoped.java", """
        package demo;
        public @interface ApplicationScoped { }
        """);
    write(root, "demo/ScopeLookalikeObserver.java", """
        package demo;
        import jakarta.enterprise.event.Observes;
        @ApplicationScoped
        public class ScopeLookalikeObserver {
          void onEvent(@Observes Object event) { }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.QUARKUS, root, QUARKUS_OBSERVER);
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE,
        entrypoint(facts, "demo.ScopeLookalikeObserver#onEvent").certainty());
  }

  // ------------------------------------------------------------------
  // Scheduled tasks (S1-S4, Q6-Q8, Q10)
  // ------------------------------------------------------------------

  /** S1: canonical Spring @Scheduled + bean + metadata ⇒ POSSIBLE. */
  @Test
  void springScheduledIsPossibleWithSchedulingMetadata() throws IOException {
    Path root = springRoot("spring-scheduled/src/main/java");
    write(root, "demo/Tick.java", """
        package demo;
        import org.springframework.stereotype.Component;
        import org.springframework.scheduling.annotation.Scheduled;
        @Component
        public class Tick {
          @Scheduled(fixedRate = 5000)
          public void tick() { }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.SPRING_BOOT, root, SPRING_SCHEDULING);
    SemanticCallFacts.Fact fact = entrypoint(facts, "demo.Tick#tick");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, fact.certainty());
    assertEquals("Spring task scheduler", fact.caller());
    assertEquals("spring-scheduled-task", fact.modelRule());
    assertEquals("scheduling_annotation", fact.evidence().get(0).kind());
    assertEquals("Scheduled", fact.evidence().get(0).attributes().get("mechanism"));
  }

  /** S2: no metadata ⇒ UNRESOLVED. */
  @Test
  void springScheduledStaysUnresolvedWithoutMetadata() throws IOException {
    Path root = springRoot("spring-scheduled-no-metadata/src/main/java");
    write(root, "demo/Tick.java", """
        package demo;
        import org.springframework.stereotype.Component;
        import org.springframework.scheduling.annotation.Scheduled;
        @Component
        public class Tick {
          @Scheduled(fixedRate = 5000)
          public void tick() { }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.SPRING_BOOT, root, List.of());
    assertEquals(SemanticCallFacts.Certainty.UNRESOLVED,
        entrypoint(facts, "demo.Tick#tick").certainty());
  }

  /** S3: Spring-side lookalike @Scheduled ⇒ no fact. */
  @Test
  void lookalikeSpringScheduledIsSuppressed() throws IOException {
    Path root = springRoot("spring-scheduled-lookalike/src/main/java");
    write(root, "demo/Scheduled.java", """
        package demo;
        public @interface Scheduled { long fixedRate(); }
        """);
    write(root, "demo/LookalikeTick.java", """
        package demo;
        import org.springframework.stereotype.Component;
        @Component
        public class LookalikeTick {
          @Scheduled(fixedRate = 5000)
          public void tick() { }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.SPRING_BOOT, root, SPRING_SCHEDULING);
    assertTrue(entrypoints(facts, "demo.LookalikeTick#tick").isEmpty(),
        facts.frameworkFacts().toString());
  }

  /** S4: Spring @Scheduled on a non-bean ⇒ no fact. */
  @Test
  void springScheduledOnNonBeanIsSuppressed() throws IOException {
    Path root = springRoot("spring-scheduled-non-bean/src/main/java");
    write(root, "demo/PlainTick.java", """
        package demo;
        import org.springframework.scheduling.annotation.Scheduled;
        public class PlainTick {
          @Scheduled(fixedRate = 5000)
          public void tick() { }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.SPRING_BOOT, root, SPRING_SCHEDULING);
    assertTrue(entrypoints(facts, "demo.PlainTick#tick").isEmpty(),
        facts.frameworkFacts().toString());
  }

  /** Q6: Quarkus @Scheduled + CDI bean + metadata ⇒ POSSIBLE. */
  @Test
  void quarkusScheduledIsPossibleWithSchedulerMetadata() throws IOException {
    Path root = quarkusRoot("quarkus-scheduled/src/main/java");
    write(root, "demo/Quark.java", """
        package demo;
        import jakarta.enterprise.context.ApplicationScoped;
        import io.quarkus.scheduler.Scheduled;
        @ApplicationScoped
        public class Quark {
          @Scheduled(every = "10s")
          void tick() { }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.QUARKUS, root, QUARKUS_SCHEDULER);
    SemanticCallFacts.Fact fact = entrypoint(facts, "demo.Quark#tick");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, fact.certainty());
    assertEquals("Quarkus scheduler", fact.caller());
    assertEquals("quarkus-scheduled-task", fact.modelRule());
  }

  /** Q7: user-defined lookalike @Scheduled under Quarkus ⇒ no fact. */
  @Test
  void lookalikeQuarkusScheduledIsSuppressed() throws IOException {
    Path root = quarkusRoot("quarkus-scheduled-lookalike/src/main/java");
    write(root, "demo/Scheduled.java", """
        package demo;
        public @interface Scheduled { String every(); }
        """);
    write(root, "demo/LookalikeQuark.java", """
        package demo;
        import jakarta.enterprise.context.ApplicationScoped;
        @ApplicationScoped
        public class LookalikeQuark {
          @Scheduled(every = "10s")
          void tick() { }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.QUARKUS, root, QUARKUS_SCHEDULER);
    assertTrue(entrypoints(facts, "demo.LookalikeQuark#tick").isEmpty(),
        facts.frameworkFacts().toString());
  }

  /** Q8: cross-framework canonical decline in both directions. */
  @Test
  void crossFrameworkScheduledCanonicalDeclines() throws IOException {
    Path quarkusRootPath = quarkusRoot("quarkus-scheduled-cross/src/main/java");
    write(quarkusRootPath, "demo/SpringSpelling.java", """
        package demo;
        import jakarta.enterprise.context.ApplicationScoped;
        import org.springframework.scheduling.annotation.Scheduled;
        @ApplicationScoped
        public class SpringSpelling {
          @Scheduled(fixedRate = 5000)
          void tick() { }
        }
        """);
    Path springRootPath = springRoot("spring-scheduled-cross/src/main/java");
    write(springRootPath, "demo/QuarkusSpelling.java", """
        package demo;
        import org.springframework.stereotype.Component;
        import io.quarkus.scheduler.Scheduled;
        @Component
        public class QuarkusSpelling {
          @Scheduled(every = "10s")
          public void tick() { }
        }
        """);

    SemanticCallFacts quarkusFacts =
        analyze(AnalysisContext.Framework.QUARKUS, quarkusRootPath, QUARKUS_SCHEDULER);
    assertTrue(entrypoints(quarkusFacts, "demo.SpringSpelling#tick").isEmpty(),
        quarkusFacts.frameworkFacts().toString());
    SemanticCallFacts springFacts =
        analyze(AnalysisContext.Framework.SPRING_BOOT, springRootPath, SPRING_SCHEDULING);
    assertTrue(entrypoints(springFacts, "demo.QuarkusSpelling#tick").isEmpty(),
        springFacts.frameworkFacts().toString());
  }

  /** Q10: Quarkus repeatable container emits one fact per method. */
  @Test
  void schedulesRepeatableContainerEmitsOneFact() throws IOException {
    Path root = quarkusRoot("quarkus-scheduled-container/src/main/java");
    write(root, "demo/ContainerQuark.java", """
        package demo;
        import jakarta.enterprise.context.ApplicationScoped;
        import io.quarkus.scheduler.Scheduled;
        import io.quarkus.scheduler.Schedules;
        @ApplicationScoped
        public class ContainerQuark {
          @Schedules({
              @Scheduled(every = "1s"),
              @Scheduled(every = "1m")})
          void tick() { }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.QUARKUS, root, QUARKUS_SCHEDULER);
    List<SemanticCallFacts.Fact> matches = entrypoints(facts, "demo.ContainerQuark#tick");
    assertEquals(1, matches.size(), facts.frameworkFacts().toString());
  }

  // ------------------------------------------------------------------
  // Main methods (M1-M9, A2)
  // ------------------------------------------------------------------

  /** M1: plain public static void main(String[]) ⇒ POSSIBLE JVM entrypoint. */
  @Test
  void plainMainIsPossibleJvmEntrypoint() throws IOException {
    Path root = springRoot("main-plain/src/main/java");
    write(root, "demo/App.java", """
        package demo;
        public class App {
          public static void main(String[] args) { }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.SPRING_BOOT, root, List.of());
    SemanticCallFacts.Fact fact = entrypoint(facts, "demo.App#main");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, fact.certainty());
    assertEquals("external JVM launcher", fact.caller());
    assertEquals("jvm-main-entrypoint", fact.modelRule());
    assertEquals("main_declaration", fact.evidence().get(0).kind());
  }

  /** M2: classic-protocol shape negatives ⇒ no fact. */
  @Test
  void mainShapeNegativesDecline() throws IOException {
    Path root = springRoot("main-negatives/src/main/java");
    write(root, "demo/Negatives.java", """
        package demo;
        public class Negatives {
          static void main(String[] args) { }
          public void main(String[] args) { }
          public static void main(String notAnArray) { }
          public static int main(String[] args) { return 0; }
          public static void main(int[] args) { }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.SPRING_BOOT, root, List.of());
    assertTrue(facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("framework_entrypoint"))
        .noneMatch(item -> item.callee().startsWith("demo.Negatives#")),
        facts.frameworkFacts().toString());
  }

  /** M3: bounded shape variants all emit. */
  @Test
  void mainShapeVariantsEmit() throws IOException {
    Path root = springRoot("main-variants/src/main/java");
    write(root, "demo/Varargs.java", """
        package demo;
        public class Varargs {
          public static void main(String... args) { }
        }
        """);
    write(root, "demo/Qualified.java", """
        package demo;
        public class Qualified {
          public static void main(java.lang.String[] args) { }
        }
        """);
    write(root, "demo/FinalParam.java", """
        package demo;
        public class FinalParam {
          public static void main(final String[] args) { }
        }
        """);
    write(root, "demo/CStyle.java", """
        package demo;
        public class CStyle {
          public static void main(String args[]) { }
        }
        """);
    write(root, "demo/AnnotatedParam.java", """
        package demo;
        public class AnnotatedParam {
          public static void main(final @SuppressWarnings("x") String[] args) { }
        }
        """);
    write(root, "demo/CStyleAnnotated.java", """
        package demo;
        public class CStyleAnnotated {
          public static void main(final @SuppressWarnings("x") String args[]) { }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.SPRING_BOOT, root, List.of());
    for (String callee : List.of(
        "demo.Varargs#main", "demo.Qualified#main", "demo.FinalParam#main",
        "demo.CStyle#main", "demo.AnnotatedParam#main", "demo.CStyleAnnotated#main")) {
      assertEquals(SemanticCallFacts.Certainty.POSSIBLE, entrypoint(facts, callee).certainty(),
          callee);
    }
  }

  /** M4: Quarkus runner usage in the main body ⇒ quarkus-main-runner. */
  @Test
  void quarkusRunnerMainEmitsRunnerFact() throws IOException {
    Path root = quarkusRoot("main-quarkus/src/main/java");
    write(root, "demo/QuarkusApp.java", """
        package demo;
        import io.quarkus.runtime.Quarkus;
        import io.quarkus.runtime.QuarkusApplication;
        public class QuarkusApp implements QuarkusApplication {
          public static void main(String[] args) {
            Quarkus.run(QuarkusApp.class, args);
          }
          @Override
          public int run(String... args) { return 0; }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.QUARKUS, root, List.of());
    SemanticCallFacts.Fact fact = entrypoint(facts, "demo.QuarkusApp#main");
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, fact.certainty());
    assertEquals("Quarkus application launcher", fact.caller());
    assertEquals("quarkus-main-runner", fact.modelRule());
    assertEquals("quarkus_runner_usage", fact.evidence().get(0).kind());
  }

  /** M5: Quarkus.run inside a string literal is not runner evidence. */
  @Test
  void quarkusRunInStringLiteralIsNotRunner() throws IOException {
    Path root = quarkusRoot("main-quarkus-literal/src/main/java");
    write(root, "demo/LiteralApp.java", """
        package demo;
        public class LiteralApp {
          public static void main(String[] args) {
            System.out.println("Quarkus.run(App.class, args)");
          }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.QUARKUS, root, List.of());
    SemanticCallFacts.Fact fact = entrypoint(facts, "demo.LiteralApp#main");
    assertEquals("jvm-main-entrypoint", fact.modelRule());
  }

  /** M6: two mains in one scan are two distinct bounded candidates. */
  @Test
  void twoMainsEmitTwoDistinctCandidates() throws IOException {
    Path root = springRoot("main-two/src/main/java");
    write(root, "demo/One.java", """
        package demo;
        public class One {
          public static void main(String[] args) { }
        }
        """);
    write(root, "demo/Two.java", """
        package demo;
        public class Two {
          public static void main(String[] args) { }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.SPRING_BOOT, root, List.of());
    List<SemanticCallFacts.Fact> mains = facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("framework_entrypoint"))
        .filter(item -> item.modelRule().equals("jvm-main-entrypoint"))
        .toList();
    assertEquals(2, mains.size(), facts.frameworkFacts().toString());
    assertEquals(2, mains.stream().map(SemanticCallFacts.Fact::id).distinct().count());
  }

  /** M7: qualified Quarkus.run spelling also marks the runner. */
  @Test
  void qualifiedQuarkusRunEmitsRunnerFact() throws IOException {
    Path root = quarkusRoot("main-quarkus-qualified/src/main/java");
    write(root, "demo/QualifiedApp.java", """
        package demo;
        public class QualifiedApp {
          public static void main(String[] args) {
            io.quarkus.runtime.Quarkus.run(args);
          }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.QUARKUS, root, List.of());
    assertEquals("quarkus-main-runner", entrypoint(facts, "demo.QualifiedApp#main").modelRule());
  }

  /** M8: lookalike Quarkus receiver does not mark the runner. */
  @Test
  void lookalikeQuarkusReceiverDeclines() throws IOException {
    Path root = quarkusRoot("main-quarkus-lookalike/src/main/java");
    write(root, "demo/com/example/Quarkus.java", """
        package com.example;
        public class Quarkus {
          public static void run(String[] args) { }
        }
        """);
    write(root, "demo/LookalikeApp.java", """
        package demo;
        public class LookalikeApp {
          public static void main(String[] args) {
            com.example.Quarkus.run(args);
          }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.QUARKUS, root, List.of());
    assertEquals("jvm-main-entrypoint", entrypoint(facts, "demo.LookalikeApp#main").modelRule());
  }

  /** M8b: same-package simple-spelling Quarkus lookalike does not mark the runner. */
  @Test
  void samePackageSimpleQuarkusLookalikeDeclines() throws IOException {
    Path root = quarkusRoot("main-quarkus-simple-lookalike/src/main/java");
    write(root, "demo/Quarkus.java", """
        package demo;
        public class Quarkus {
          public static void run(String[] args) { }
        }
        """);
    write(root, "demo/SimpleLookalikeApp.java", """
        package demo;
        public class SimpleLookalikeApp {
          public static void main(String[] args) {
            Quarkus.run(args);
          }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.QUARKUS, root, List.of());
    assertEquals("jvm-main-entrypoint", entrypoint(facts, "demo.SimpleLookalikeApp#main").modelRule());
  }

  /** M9: Quarkus.run in a sibling method does not tag the main. */
  @Test
  void quarkusRunInSiblingMethodDoesNotTagMain() throws IOException {
    Path root = quarkusRoot("main-quarkus-sibling/src/main/java");
    write(root, "demo/SiblingApp.java", """
        package demo;
        import io.quarkus.runtime.Quarkus;
        public class SiblingApp {
          public static void main(String[] args) { }
          void startSomewhereElse() {
            Quarkus.run(SiblingApp.class);
          }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.QUARKUS, root, List.of());
    assertEquals("jvm-main-entrypoint", entrypoint(facts, "demo.SiblingApp#main").modelRule());
  }

  /** A2: the Spring fixture's SpringApplication.run shape stays a plain main. */
  @Test
  void springFixtureReplicaMainIsPlain() throws IOException {
    Path root = springRoot("main-spring-fixture/src/main/java");
    write(root, "com/redhat/lightwell/Application.java", """
        package com.redhat.lightwell;
        import org.springframework.boot.SpringApplication;
        import org.springframework.boot.autoconfigure.SpringBootApplication;
        @SpringBootApplication
        public class Application {
          public static void main(String[] args) {
            SpringApplication.run(Application.class, args);
          }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.SPRING_BOOT, root, List.of());
    SemanticCallFacts.Fact fact =
        entrypoint(facts, "com.redhat.lightwell.Application#main");
    assertEquals("jvm-main-entrypoint", fact.modelRule());
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, fact.certainty());
  }

  // ------------------------------------------------------------------
  // Interactions and nested types (I1-I3, I1b, N1-N3)
  // ------------------------------------------------------------------

  /** I1: listener + scheduled on one method ⇒ two facts, kind separation. */
  @Test
  void kafkaPlusScheduledEmitTwoFacts() throws IOException {
    Path root = springRoot("interaction-kafka-scheduled/src/main/java");
    write(root, "demo/Both.java", """
        package demo;
        import org.springframework.stereotype.Component;
        import org.springframework.kafka.annotation.KafkaListener;
        import org.springframework.scheduling.annotation.Scheduled;
        @Component
        public class Both {
          @KafkaListener(topics = "orders")
          @Scheduled(fixedRate = 5000)
          public void onKafka(String message) { }
        }
        """);

    SemanticCallFacts facts = analyze(
        AnalysisContext.Framework.SPRING_BOOT, root,
        List.of(metadata("spring-listener-annotations"), metadata("spring-scheduling-annotations")));
    List<SemanticCallFacts.Fact> matches = entrypoints(facts, "demo.Both#onKafka");
    assertEquals(2, matches.size(), facts.frameworkFacts().toString());
    assertTrue(matches.stream().anyMatch(item -> item.modelRule().equals("spring-message-listener")));
    assertTrue(matches.stream().anyMatch(item -> item.modelRule().equals("spring-scheduled-task")));
  }

  /** I1b: two same-kind mechanisms on one method ⇒ two facts, distinct ids. */
  @Test
  void kafkaPlusRabbitEmitTwoDistinctFacts() throws IOException {
    Path root = springRoot("interaction-kafka-rabbit/src/main/java");
    write(root, "demo/TwoBrokers.java", """
        package demo;
        import org.springframework.stereotype.Component;
        import org.springframework.kafka.annotation.KafkaListener;
        import org.springframework.amqp.rabbit.annotation.RabbitListener;
        @Component
        public class TwoBrokers {
          @KafkaListener(topics = "orders")
          @RabbitListener(queues = "q")
          public void onMessage(String message) { }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.SPRING_BOOT, root, SPRING_LISTENER);
    List<SemanticCallFacts.Fact> matches = entrypoints(facts, "demo.TwoBrokers#onMessage");
    assertEquals(2, matches.size(), facts.frameworkFacts().toString());
    assertEquals(2, matches.stream().map(SemanticCallFacts.Fact::id).distinct().count());
    assertEquals(2, matches.stream()
        .map(item -> item.evidence().get(0).attributes().get("mechanism"))
        .distinct().count());
  }

  /** I2: a listener method that is also a route keeps both facts. */
  @Test
  void listenerThatIsAlsoRouteKeepsBothFacts() throws IOException {
    Path root = springRoot("interaction-route/src/main/java");
    write(root, "demo/RouteAndListener.java", """
        package demo;
        import org.springframework.web.bind.annotation.RestController;
        import org.springframework.web.bind.annotation.GetMapping;
        import org.springframework.kafka.annotation.KafkaListener;
        @RestController
        public class RouteAndListener {
          @GetMapping("/orders")
          @KafkaListener(topics = "orders")
          public String orders() { return "ok"; }
        }
        """);

    SemanticCallFacts facts = analyze(
        AnalysisContext.Framework.SPRING_BOOT, root,
        List.of(metadata("spring-web-annotations"), metadata("spring-listener-annotations")));
    List<SemanticCallFacts.Fact> matches = entrypoints(facts, "demo.RouteAndListener#orders");
    assertEquals(2, matches.size(), facts.frameworkFacts().toString());
    SemanticCallFacts.Fact route = matches.stream()
        .filter(item -> item.modelRule().equals("spring-unique-mvc-route"))
        .findFirst().orElseThrow();
    SemanticCallFacts.Fact listener = matches.stream()
        .filter(item -> item.modelRule().equals("spring-message-listener"))
        .findFirst().orElseThrow();
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, route.certainty());
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE, listener.certainty());
  }

  /** I3: a main inside a @Component class stays a plain main fact. */
  @Test
  void mainInsideComponentIsIndependentOfBeanFamilies() throws IOException {
    Path root = springRoot("interaction-main-component/src/main/java");
    write(root, "demo/ComponentApp.java", """
        package demo;
        import org.springframework.stereotype.Component;
        @Component
        public class ComponentApp {
          public static void main(String[] args) { }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.SPRING_BOOT, root, List.of());
    SemanticCallFacts.Fact fact = entrypoint(facts, "demo.ComponentApp#main");
    assertEquals("jvm-main-entrypoint", fact.modelRule());
    assertTrue(facts.frameworkFacts().stream()
        .filter(item -> item.callee().equals("demo.ComponentApp#main"))
        .allMatch(item -> item.relationshipType().equals("framework_entrypoint")),
        facts.frameworkFacts().toString());
  }

  /** N1: listener in a nested @Component type emits with the nested callee. */
  @Test
  void nestedComponentListenerEmits() throws IOException {
    Path root = springRoot("nested-component/src/main/java");
    write(root, "demo/Outer.java", """
        package demo;
        import org.springframework.stereotype.Component;
        import org.springframework.kafka.annotation.KafkaListener;
        public class Outer {
          @Component
          public static class Nested {
            @KafkaListener(topics = "orders")
            public void onKafka(String message) { }
          }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.SPRING_BOOT, root, SPRING_LISTENER);
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE,
        entrypoint(facts, "demo.Outer$Nested#onKafka").certainty());
  }

  /** N2: listener in a nested non-bean type ⇒ no fact. */
  @Test
  void nestedNonBeanListenerIsSuppressed() throws IOException {
    Path root = springRoot("nested-non-bean/src/main/java");
    write(root, "demo/Outer.java", """
        package demo;
        import org.springframework.kafka.annotation.KafkaListener;
        public class Outer {
          public static class Nested {
            @KafkaListener(topics = "orders")
            public void onKafka(String message) { }
          }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.SPRING_BOOT, root, SPRING_LISTENER);
    assertTrue(entrypoints(facts, "demo.Outer$Nested#onKafka").isEmpty(),
        facts.frameworkFacts().toString());
  }

  /** N3: @Observes in a nested @ApplicationScoped type emits. */
  @Test
  void nestedApplicationScopedObserverEmits() throws IOException {
    Path root = quarkusRoot("nested-observer/src/main/java");
    write(root, "demo/Outer.java", """
        package demo;
        import jakarta.enterprise.context.ApplicationScoped;
        import jakarta.enterprise.event.Observes;
        public class Outer {
          @ApplicationScoped
          public static class Nested {
            void onEvent(@Observes Object event) { }
          }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.QUARKUS, root, QUARKUS_OBSERVER);
    assertEquals(SemanticCallFacts.Certainty.POSSIBLE,
        entrypoint(facts, "demo.Outer$Nested#onEvent").certainty());
  }

  /** N4: a method-local class cannot be a CDI bean, so its observer declines. */
  @Test
  void localClassObserverDeclines() throws IOException {
    Path root = quarkusRoot("local-observer/src/main/java");
    write(root, "demo/LocalObserver.java", """
        package demo;
        import jakarta.enterprise.context.ApplicationScoped;
        import jakarta.enterprise.event.Observes;
        @ApplicationScoped
        public class LocalObserver {
          void dispatch() {
            @ApplicationScoped
            class Local {
              void onEvent(@Observes Object event) { }
            }
            new Local().onEvent(new Object());
          }
        }
        """);

    SemanticCallFacts facts = analyze(AnalysisContext.Framework.QUARKUS, root, QUARKUS_OBSERVER);
    assertTrue(facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("framework_entrypoint"))
        .noneMatch(item -> item.callee().contains("Local#onEvent")),
        facts.frameworkFacts().toString());
  }

  // ------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------

  private Path springRoot(String relative) {
    return temp.resolve(relative);
  }

  private Path quarkusRoot(String relative) {
    return temp.resolve(relative);
  }

  private SemanticCallFacts analyze(
      AnalysisContext.Framework framework,
      Path sourceRoot,
      List<AnalysisContext.FrameworkMetadata> metadata) {
    AnalysisContext.Builder builder = AnalysisContext.builder()
        .id(framework == AnalysisContext.Framework.QUARKUS ? "quarkus-production" : "spring-production")
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
    for (AnalysisContext.FrameworkMetadata item : metadata) {
      builder.frameworkMetadata(item);
    }
    return new JavaAnalysisAdapter().analyze(builder.build(), AnalysisGraph.empty());
  }

  /** Returns the entrypoint facts for one callee identity. */
  private static List<SemanticCallFacts.Fact> entrypoints(SemanticCallFacts facts, String callee) {
    return facts.frameworkFacts().stream()
        .filter(item -> item.relationshipType().equals("framework_entrypoint"))
        .filter(item -> item.callee().equals(callee))
        .toList();
  }

  /** Returns the single entrypoint fact for one callee identity. */
  private static SemanticCallFacts.Fact entrypoint(SemanticCallFacts facts, String callee) {
    List<SemanticCallFacts.Fact> matches = entrypoints(facts, callee);
    assertEquals(1, matches.size(), "expected exactly one entrypoint fact for " + callee);
    return matches.get(0);
  }

  private static void write(Path root, String relative, String source) throws IOException {
    Path path = root.resolve(relative);
    Files.createDirectories(path.getParent());
    Files.writeString(path, source);
  }
}
