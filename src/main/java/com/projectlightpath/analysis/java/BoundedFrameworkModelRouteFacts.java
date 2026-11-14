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
abstract class BoundedFrameworkModelRouteFacts extends BoundedFrameworkModelTypeMatching implements FrameworkModel {

  BoundedFrameworkModelRouteFacts(AnalysisContext.Framework framework) {
    super(framework);
  }


  /** Resolves bounded REST route candidates and their registration certainty. */
  List<SemanticCallFacts.Fact> routeFacts(
      AnalysisContext context, SourceMetadataScanner scanner) {
    List<SourceMetadataScannerState.Route> routes = scanner.routes(framework);
    Map<String, Integer> mappingCounts = new HashMap<>();
    for (SourceMetadataScannerState.Route route : routes) {
      mappingCounts.merge(route.httpMethod() + "\u0000" + route.path(), 1, Integer::sum);
    }
    List<SemanticCallFacts.Fact> facts = new ArrayList<>();
    for (SourceMetadataScannerState.Route route : routes) {
      String metadataId = framework == AnalysisContext.Framework.QUARKUS
          ? "quarkus-rest-annotations"
          : "spring-web-annotations";
      String registrationMetadataId = framework == AnalysisContext.Framework.QUARKUS
          ? "quarkus-rest-registration"
          : "spring-mvc-handler-mappings";
      boolean metadataAvailable = context.metadataObserved(metadataId);
      boolean registrationMetadataAvailable = context.metadataObserved(registrationMetadataId);
      int mappingCount = mappingCounts.get(route.httpMethod() + "\u0000" + route.path());
      // AnalysisContext currently records registration metadata availability, not
      // candidate-level handler rows. Presence alone must not promote a route.
      SemanticCallFacts.Certainty certainty = !metadataAvailable
          ? SemanticCallFacts.Certainty.UNRESOLVED
          : SemanticCallFacts.Certainty.POSSIBLE;
      String callee = route.file().typeName() + "#" + route.method().name();
      String id = routeFactId(route);
      String rule = framework == AnalysisContext.Framework.QUARKUS
          ? "quarkus-unique-rest-route"
          : "spring-unique-mvc-route";
      String limitation = !metadataAvailable
          ? "Route annotations were found, but the selected source/framework metadata is incomplete."
          : mappingCount != 1
              ? "Multiple bounded handlers share this HTTP method and path; runtime selection remains possible."
              : "Route annotations identify a bounded candidate, but route-level registration metadata is not matched to this candidate.";
      SemanticCallFacts.RouteSelectionCriteria selection = new SemanticCallFacts.RouteSelectionCriteria(
          route.httpMethod(),
          route.path(),
          route.consumes(),
          route.produces(),
          route.headers(),
          route.parameters(),
          context.id());
      SemanticCallFacts.Fact fact = SemanticCallFacts.Fact.framework(
          id,
          context,
          new SemanticCallFacts.Fact.FrameworkInput(
              "external HTTP " + route.httpMethod() + " " + route.path(),
              callee,
              "framework_entrypoint",
              certainty,
              rule,
              List.of(new SemanticCallFacts.Evidence(
                  "route_annotations",
                  route.file().displayPath(),
                  route.symbols(),
                  route.method().line(),
                  Map.of(
                      "annotationMetadata", metadataId,
                      "registrationMetadata", registrationMetadataId,
                      "registrationMetadataObserved", Boolean.toString(registrationMetadataAvailable),
                      "registeredRouteMapping", Boolean.toString(certainty == SemanticCallFacts.Certainty.DEFINITE),
                      "routeCandidate", "true"))),
              limitation));
      facts.add(fact.withFrameworkDetails(
          "registered_route_mapping",
          "potential_request_match",
          "not_evidence_of_absence",
          selection,
          null));
    }
    return facts;
  }
}
