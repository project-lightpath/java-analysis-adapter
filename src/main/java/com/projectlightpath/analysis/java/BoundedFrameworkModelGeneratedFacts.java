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
abstract class BoundedFrameworkModelGeneratedFacts extends BoundedFrameworkModelSelectionFacts implements FrameworkModel {

  BoundedFrameworkModelGeneratedFacts(AnalysisContext.Framework framework) {
    super(framework);
  }


  /** Emits Quarkus generated-source relationships when metadata supports them. */
  List<SemanticCallFacts.Fact> generatedFacts(
      AnalysisContext context, SourceMetadataScanner scanner) {
    if (framework != AnalysisContext.Framework.QUARKUS) {
      return List.of();
    }
    List<SemanticCallFacts.Fact> facts = new ArrayList<>();
    boolean metadataAvailable = context.metadataObserved("quarkus-generated-source-root");
    for (SourceMetadataScannerState.GeneratedMethod generated : scanner.generatedMethods()) {
      SemanticCallFacts.Certainty certainty = metadataAvailable
          ? SemanticCallFacts.Certainty.DEFINITE
          : SemanticCallFacts.Certainty.UNRESOLVED;
      String callee = generated.file().typeName() + "#" + generated.method().name();
      facts.add(SemanticCallFacts.Fact.framework(
          generatedFactId(scanner, generated),
          context,
          new SemanticCallFacts.Fact.FrameworkInput(
              "quarkus:generated-source:" + generated.file().typeName(),
              callee,
              "generated_implementation",
              certainty,
              "quarkus-generated-source-binding",
              List.of(new SemanticCallFacts.Evidence(
                  "generated_source",
                  generated.file().displayPath(),
                  List.of(generated.file().simpleTypeName() + "#" + generated.method().name()),
                  generated.method().line(),
                  generatedRootAttributes(context, generated))),
              certainty == SemanticCallFacts.Certainty.DEFINITE
                  ? null
                  : "Generated source was found, but the selected generated-source metadata is incomplete.")));
    }
    return facts;
  }


  /** Carries the selected generated root and its handoff fingerprint into source evidence. */
  static Map<String, String> generatedRootAttributes(
      AnalysisContext context,
      SourceMetadataScannerState.GeneratedMethod generated) {
    Map<String, String> attributes = new HashMap<>();
    Optional<AnalysisContext.SourceRoot> root = AnalysisContext.mostSpecificRoot(
        generated.file().path(), context.includedSourceRoots().stream()
            .filter(AnalysisContext.SourceRoot::generated)
            .toList());
    attributes.put("sourceRootRequired", root.map(item -> Boolean.toString(item.required())).orElse("true"));
    root.ifPresent(item -> {
      attributes.put("sourceRoot", item.declaredPath());
      attributes.put("sourceRootIncluded", Boolean.toString(item.included()));
      attributes.put("sourceRootScope", item.scope().id());
      if (item.fingerprint() != null && !item.fingerprint().isBlank()) {
        attributes.put("sourceRootFingerprint", item.fingerprint());
      }
    });
    return Map.copyOf(attributes);
  }
}
