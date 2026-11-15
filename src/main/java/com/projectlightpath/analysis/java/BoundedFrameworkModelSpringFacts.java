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
      for (SourceMetadataScannerState.RepositoryMethod repositoryMethod
          : scanner.springRepositoryMethods(candidate.file())) {
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
      }
    }
    return facts;
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
