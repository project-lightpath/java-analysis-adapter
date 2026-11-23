package com.projectlightpath.analysis.java;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Shared bounded source model for the two first-class framework inputs.
 *
 * <p>It models only evidence that is explicit in source or in the selected
 * context. Container selection, proxy generation, and startup ordering remain
 * possible or unresolved when the corresponding runtime/build metadata is not
 * captured.
 */
abstract class BoundedFrameworkModelSpringFacts extends BoundedFrameworkModelDynamicFacts implements FrameworkModel {

  BoundedFrameworkModelSpringFacts(AnalysisContext.Framework framework) {
    super(framework);
  }


  /** Emits bounded Spring Data repository implementation candidates. */
  List<SemanticCallFacts.Fact> springRepositoryFacts(
      AnalysisContext context, SourceMetadataScanner scanner) {
    List<SemanticCallFacts.Fact> facts = new ArrayList<>();
    boolean metadataAvailable = context.metadataObserved("spring-data-repository-contract");
    List<SourceMetadataScannerState.BeanCandidate> candidates = scanner.beanCandidates(framework);
    for (SourceMetadataScannerState.BeanCandidate candidate : candidates) {
      if (!candidate.kind().equals("repository_interface")) {
        continue;
      }
      SemanticCallFacts.Certainty certainty = metadataAvailable
          ? SemanticCallFacts.Certainty.POSSIBLE
          : SemanticCallFacts.Certainty.UNRESOLVED;
      String repositoryContract = scanner.springRepositoryContract(candidate.file()).orElse("Repository");
      Map<String, String> fragmentTargets = fragmentTargets(scanner, candidate);
      Optional<SourceMetadataScannerState.RepositoryBaseClass> baseClass =
          scanner.springRepositoryBaseClass();
      for (SourceMetadataScannerState.RepositoryMethod repositoryMethod
          : scanner.springRepositoryMethods(candidate.file())) {
        if (fragmentTargets.containsKey(repositoryMethod.name())) {
          continue;
        }
        String methodName = repositoryMethod.name();
        String methodIdentity = repositoryMethod.identity();
        String id = springRepositoryFactId(scanner, candidates, candidate, methodIdentity);
        List<String> symbols = new ArrayList<>(List.of("extends " + repositoryContract));
        symbols.add(repositoryMethod.guaranteed()
            ? "CrudRepository#" + methodName
            : "declared " + methodIdentity);
        symbols.add(methodName);
        facts.add(SemanticCallFacts.Fact.framework(
            id,
            context,
            new SemanticCallFacts.Fact.FrameworkInput(
              "spring-data-proxy:" + candidate.file().typeName(),
              candidate.file().typeName() + "#" + methodIdentity,
                "generated_implementation",
                certainty,
                "spring-data-generated-repository",
                List.of(new SemanticCallFacts.Evidence(
                    "repository_interface",
                    candidate.file().displayPath(),
                    List.copyOf(new LinkedHashSet<>(symbols)),
                    null,
                    Map.of(
                        "generatedImplementation", "outside application source",
                        "repositoryContract", repositoryContract,
                        "repositoryMethod", methodIdentity))),
                "Spring Data implementation and proxy identity are generated outside application source.")));
        facts.add(delegationFact(
            context, scanner, candidates, candidate, repositoryMethod,
            repositoryContract, metadataAvailable, fragmentTargets, baseClass));
      }
      for (Map.Entry<String, String> fragment : fragmentTargets.entrySet()) {
        facts.add(fragmentDelegationFact(
            context, scanner, candidates, candidate, repositoryContract,
            metadataAvailable, fragment));
      }
    }
    return facts;
  }

  /** Emits the delegation fact for one inherited fragment method. */
  private SemanticCallFacts.Fact fragmentDelegationFact(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      List<SourceMetadataScannerState.BeanCandidate> candidates,
      SourceMetadataScannerState.BeanCandidate candidate,
      String repositoryContract,
      boolean metadataAvailable,
      Map.Entry<String, String> fragment) {
    String caller = candidate.file().typeName() + "#" + fragment.getKey();
    String target = fragment.getValue();
    return SemanticCallFacts.Fact.framework(
        delegationFactId(scanner, candidates, candidate, fragment.getKey(), target),
        context,
        new SemanticCallFacts.Fact.FrameworkInput(
            caller,
            target,
            "delegated_implementation",
            metadataAvailable
                ? SemanticCallFacts.Certainty.POSSIBLE
                : SemanticCallFacts.Certainty.UNRESOLVED,
            "spring-data-delegation",
            List.of(new SemanticCallFacts.Evidence(
                "spring_data_backing_selection",
                candidate.file().displayPath(),
                List.of("extends " + repositoryContract, "fragment " + fragment.getKey()),
                null,
                Map.of(
                    "repositoryContract", repositoryContract,
                    "repositoryMethod", fragment.getKey(),
                    "backingTarget", target))),
            "Fragment backing is an application-declared implementation;"
                + " proxy composition order at runtime is not captured."));
  }

  /** Emits the delegation fact from one repository method to its runtime backing. */
  private SemanticCallFacts.Fact delegationFact(
      AnalysisContext context,
      SourceMetadataScanner scanner,
      List<SourceMetadataScannerState.BeanCandidate> candidates,
      SourceMetadataScannerState.BeanCandidate candidate,
      SourceMetadataScannerState.RepositoryMethod repositoryMethod,
      String repositoryContract,
      boolean metadataAvailable,
      Map<String, String> fragmentTargets,
      Optional<SourceMetadataScannerState.RepositoryBaseClass> baseClass) {
    String methodIdentity = repositoryMethod.identity();
    String caller = candidate.file().typeName() + "#" + methodIdentity;
    String fragmentImplementation = fragmentTargets.get(repositoryMethod.name());
    boolean reactive = reactiveContract(repositoryContract);
    String target;
    SemanticCallFacts.Certainty certainty;
    String limitation;
    String diagnosticCode = null;
    if (fragmentImplementation != null) {
      target = fragmentImplementation;
      certainty = metadataAvailable
          ? SemanticCallFacts.Certainty.POSSIBLE
          : SemanticCallFacts.Certainty.UNRESOLVED;
      limitation = "Fragment backing is an application-declared implementation;"
          + " proxy composition order at runtime is not captured.";
    } else if (reactive) {
      target = "spring-data-proxy:" + candidate.file().typeName() + "#" + methodIdentity;
      certainty = SemanticCallFacts.Certainty.UNRESOLVED;
      limitation = "Reactive repository backing is module-specific and outside"
          + " the analyzed dependencies; no backing class can be selected.";
    } else if (repositoryMethod.guaranteed() && baseClass.isPresent()) {
      target = baseClass.get().configuredType() + "#" + repositoryMethod.name();
      if (baseClass.get().applicationScoped()) {
        certainty = metadataAvailable
            ? SemanticCallFacts.Certainty.POSSIBLE
            : SemanticCallFacts.Certainty.UNRESOLVED;
        limitation = "The configured repository base class overrides the default"
            + " backing; exact method resolution inside it is not proven.";
      } else {
        certainty = SemanticCallFacts.Certainty.UNRESOLVED;
        diagnosticCode = "spring_data_backing_selection_out_of_scope";
        limitation = "The configured repository base class is outside the"
            + " analysis scope; no backing can be selected.";
      }
    } else if (repositoryMethod.guaranteed()) {
      target = "org.springframework.data.jpa.repository.support.SimpleJpaRepository#"
          + repositoryMethod.name();
      certainty = metadataAvailable
          ? SemanticCallFacts.Certainty.DEFINITE
          : SemanticCallFacts.Certainty.UNRESOLVED;
      limitation = null;
    } else {
      target = "org.springframework.data.jpa.repository.query.PartTreeJpaQuery#execute";
      certainty = metadataAvailable
          ? SemanticCallFacts.Certainty.POSSIBLE
          : SemanticCallFacts.Certainty.UNRESOLVED;
      limitation = "Derived query execution goes through the query-derivation"
          + " machinery; the generated query is not visible statically.";
    }
    Map<String, String> attributes = new HashMap<>(Map.of(
        "repositoryContract", repositoryContract,
        "repositoryMethod", methodIdentity,
        "backingTarget", target));
    if (diagnosticCode != null) {
      attributes.put("diagnosticCode", diagnosticCode);
    }
    return SemanticCallFacts.Fact.framework(
        delegationFactId(scanner, candidates, candidate, methodIdentity, target),
        context,
        new SemanticCallFacts.Fact.FrameworkInput(
            caller,
            target,
            "delegated_implementation",
            certainty,
            "spring-data-delegation",
            List.of(new SemanticCallFacts.Evidence(
                "spring_data_backing_selection",
                candidate.file().displayPath(),
                List.copyOf(new LinkedHashSet<>(List.of(
                    "extends " + repositoryContract,
                    repositoryMethod.guaranteed()
                        ? "CrudRepository#" + repositoryMethod.name()
                        : "declared " + methodIdentity,
                    "backing " + target))),
                null,
                Map.copyOf(attributes))),
            limitation));
  }

  /** Maps fragment method names to their application Impl target for one repository. */
  private Map<String, String> fragmentTargets(
      SourceMetadataScanner scanner, SourceMetadataScannerState.BeanCandidate candidate) {
    Map<String, String> result = new HashMap<>();
    for (SourceMetadataScannerState.RepositoryFragment fragment : scanner.springRepositoryFragments()) {
      if (!fragment.repositoryType().equals(candidate.file().typeName())) {
        continue;
      }
      for (String methodName : fragment.methodNames()) {
        result.put(methodName, fragment.implementationType() + "#" + methodName);
      }
    }
    return result;
  }

  private static boolean reactiveContract(String repositoryContract) {
    return repositoryContract.startsWith("Reactive") || repositoryContract.startsWith("RxJava");
  }

  /** Emits bounded Spring lifecycle callback candidates. */
  List<SemanticCallFacts.Fact> springLifecycleFacts(
      AnalysisContext context, SourceMetadataScanner scanner) {
    List<SemanticCallFacts.Fact> facts = new ArrayList<>();
    boolean sourceMetadataAvailable = context.metadataObserved("spring-bean-annotations");
    SemanticCallFacts.Certainty certainty = sourceMetadataAvailable
        ? SemanticCallFacts.Certainty.POSSIBLE
        : SemanticCallFacts.Certainty.UNRESOLVED;
    for (SourceMetadataScannerState.LifecycleMethod lifecycle : scanner.springLifecycleMethods()) {
      SourceMetadataScannerState.SourceFile file = lifecycle.file();
      SourceMetadataScannerState.MethodInfo method = lifecycle.method();
      String declaringType = lifecycle.declaringType();
      boolean runner = lifecycle.kind().equals("runner");
      List<String> symbols = new ArrayList<>(lifecycle.annotationSymbols());
      if (!runner) {
        symbols.add("@Bean " + method.name());
      } else {
        symbols.add("implements CommandLineRunner");
        symbols.add("run(String...)");
      }
      facts.add(SemanticCallFacts.Fact.framework(
          lifecycleFactId(scanner, file, method, declaringType, lifecycle.kind()),
          context,
          new SemanticCallFacts.Fact.FrameworkInput(
              runner ? "Spring Boot application lifecycle" : "Spring ApplicationContext",
              declaringType + "#" + method.name(),
              "lifecycle_callback",
              certainty,
              "spring-lifecycle-callback",
              List.of(new SemanticCallFacts.Evidence(
                  runner ? "lifecycle_interface" : "bean_factory_annotation",
                  file.displayPath(),
                  List.copyOf(new LinkedHashSet<>(symbols)),
                  method.line(),
                  Map.of())),
              runner
                  ? "Startup ordering and conditional bean activation are not captured."
                  : "Conditional configuration and exact ApplicationContext construction are not captured.")));
    }
    return facts;
  }
}
