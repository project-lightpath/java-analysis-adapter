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
abstract class BoundedFrameworkModelDynamicFacts extends BoundedFrameworkModelGeneratedFacts implements FrameworkModel {

  BoundedFrameworkModelDynamicFacts(AnalysisContext.Framework framework) {
    super(framework);
  }


  /** Preserves open-world dynamic proxy construction as unresolved evidence. */
  List<SemanticCallFacts.Fact> dynamicProxyFacts(
      AnalysisContext context, SourceMetadataScanner scanner) {
    List<SemanticCallFacts.Fact> facts = new ArrayList<>();
    Set<String> factIds = new LinkedHashSet<>();
    for (DynamicFeature feature : scanner.dynamicFeatures()) {
      if (feature.kind() != DynamicFeature.Kind.DYNAMIC_PROXY) {
        continue;
      }
      String targetOwner = feature.target().contains("#")
          ? feature.target().substring(0, feature.target().indexOf('#'))
          : feature.target();
      String caller = framework.id() + ":dynamic-proxy:" + targetOwner;
      String factId = uniqueFactId(
          factIds,
          dynamicProxyFactId(scanner, feature),
          dynamicFeatureIdentity(feature));
      facts.add(SemanticCallFacts.Fact.framework(
          factId,
          context,
          new SemanticCallFacts.Fact.FrameworkInput(
              caller,
              feature.target(),
              "proxy_dispatch",
              SemanticCallFacts.Certainty.UNRESOLVED,
              framework == AnalysisContext.Framework.QUARKUS ? "quarkus-unbounded-proxy" : "spring-open-reflection",
              List.of(new SemanticCallFacts.Evidence(
                  "dynamic_proxy_call",
                  feature.sourcePath(),
                  feature.symbols(),
                  feature.sourceLine() > 0 ? feature.sourceLine() : null,
                  dynamicProxyEvidenceAttributes(feature))),
              feature.limitation())));
    }
    return facts;
  }
}
