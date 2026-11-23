package com.projectlightpath.analysis.java;

import static com.projectlightpath.analysis.java.ManualIntegrationJson.elements;
import static com.projectlightpath.analysis.java.ManualIntegrationJson.idSet;
import static com.projectlightpath.analysis.java.ManualIntegrationJson.positive;
import static com.projectlightpath.analysis.java.ManualIntegrationJson.text;
import static com.projectlightpath.analysis.java.ManualIntegrationJson.truthy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Handoff rules of the manual-integration validator (#45 step 3): the AnalysisContext
 * pins, the SemanticCallFacts provenance and certainty rules, link integrity, and the
 * Spring route shape. Ported from the corresponding blocks of
 * spike/scripts/validate_manual_integration.py.
 */
final class ManualIntegrationRules {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final List<String> PROVENANCE_FIELDS = List.of("sourceRepository",
      "sourceRevision", "sourceFingerprint", "generatedSourceFingerprint",
      "dependencyFingerprint", "buildFingerprint", "toolchainFingerprint",
      "analyzerFingerprint", "gitRevision", "providerRuntime", "profileDigest",
      "preparedAt");

  private final ObjectNode context;
  private final ObjectNode facts;
  private final String expectedRevision;

  ManualIntegrationRules(ObjectNode context, ObjectNode facts, String expectedRevision) {
    this.context = context;
    this.facts = facts;
    this.expectedRevision = expectedRevision;
  }

  /** The validated handoff pieces the evidence document is built from. */
  record Handoff(String contextId, JsonNode metadata, List<JsonNode> springRoute) {}

  /** Runs every rule in the Python original's order and returns the handoff. */
  Handoff checkAll() {
    String contextId = requireContext();
    JsonNode metadata = requireFactsHeader(contextId);
    requireProvenance();
    List<JsonNode> providerFacts = elements(facts.path("providerFacts"));
    List<JsonNode> frameworkFacts = elements(facts.path("frameworkFacts"));
    requireFactSets(providerFacts, frameworkFacts, contextId);
    requireLinks(providerFacts, frameworkFacts);
    List<JsonNode> springRoute = requireSpringRoute(frameworkFacts, contextId);
    requireNoSyntheticProviderEntrypoints(providerFacts);
    return new Handoff(contextId, metadata, springRoute);
  }

  private String requireContext() {
    require("0.1.0".equals(text(context, "schemaVersion")),
        "unsupported AnalysisContext schema");
    require(context.path("status").isMissingNode(),
        "manual context output must be the direct AnalysisContext, not a report wrapper");
    String contextId = text(context, "id");
    require(contextId != null && !contextId.isEmpty(),
        "AnalysisContext id is missing");
    require(expectedRevision.equals(text(context, "sourceRevision")),
        "AnalysisContext source revision is not pinned");
    require("production".equals(text(context, "analysisScope")),
        "manual handoff must use production scope");
    require(!elements(context.path("applicationClasses")).isEmpty(),
        "compiled application classes are missing");
    require(!elements(context.path("dependencyClasspath")).isEmpty(),
        "dependency classpath is missing");
    require(truthy(context.path("build").path("artifact")),
        "build artifact is missing");
    return contextId;
  }

  private JsonNode requireFactsHeader(String contextId) {
    require("SemanticCallFacts".equals(text(facts, "artifact")),
        "unexpected facts artifact");
    require(contextId.equals(text(facts, "contextId")), "facts/context IDs differ");
    // Python crashed on a missing framework when building the evidence document; the
    // port rejects here instead, with the same exit code.
    require(text(facts, "framework") != null, "facts/context frameworks differ");
    require(java.util.Objects.equals(text(facts, "framework"),
        text(context, "framework")), "facts/context frameworks differ");
    JsonNode metadata = facts.path("runMetadata");
    require(contextId.equals(text(metadata, "contextId")),
        "run metadata context ID differs");
    require("SootUp".equals(text(metadata, "providerName")), "unexpected provider");
    require("3.0.1".equals(text(metadata, "providerVersion")),
        "unexpected SootUp version");
    require(positive(metadata.path("graphMethodCount")), "provider graph has no methods");
    require(positive(metadata.path("graphCallCount")), "provider graph has no calls");
    return metadata;
  }

  private void requireProvenance() {
    JsonNode provenance = facts.path("runMetadata").path("provenance");
    for (String field : PROVENANCE_FIELDS) {
      require(truthy(provenance.path(field)),
          "provenance field is missing: " + field);
    }
    require(expectedRevision.equals(text(provenance, "sourceRevision")),
        "facts source revision is not pinned");
    JsonNode handoff = facts.path("runMetadata").path("handoff");
    require(java.util.Objects.equals(text(handoff, "buildFingerprint"),
        text(provenance, "buildFingerprint")),
        "build fingerprint was not preserved");
    require(java.util.Objects.equals(text(handoff, "buildArtifact"),
        text(context.path("build"), "artifact")),
        "build artifact was not preserved");
  }

  private void requireFactSets(List<JsonNode> providerFacts, List<JsonNode> frameworkFacts,
      String contextId) {
    require(!providerFacts.isEmpty(), "provider facts are empty");
    require(!frameworkFacts.isEmpty(), "framework facts are empty");
    List<JsonNode> allFacts = new ArrayList<>(providerFacts);
    allFacts.addAll(frameworkFacts);
    // Python's substring predicates crashed on non-string caller/callee/id values; the
    // port rejects those shapes up front instead of skipping them.
    for (JsonNode fact : allFacts) {
      for (String field : List.of("caller", "callee", "id")) {
        JsonNode value = fact.path(field);
        require(value.isMissingNode() || value.isTextual(),
            "fact " + field + " must be a string when present");
      }
    }
    for (JsonNode fact : providerFacts) {
      require("provider".equals(text(fact, "factSet")), "provider fact set was mixed");
    }
    for (JsonNode fact : frameworkFacts) {
      require("framework".equals(text(fact, "factSet")), "framework fact set was mixed");
    }
    for (JsonNode fact : allFacts) {
      require(contextId.equals(text(fact, "contextId")),
          "fact context provenance is inconsistent");
    }
    Set<String> certaintyStates = new LinkedHashSet<>();
    for (JsonNode fact : allFacts) {
      certaintyStates.add(text(fact, "certainty"));
    }
    require(certaintyStates.containsAll(Set.of("definite", "possible")),
        "definite/possible certainty states were not preserved");
    // A HashSet (not Set.of) tolerates a null member: a missing certainty must reject
    // with the diagnostic, not throw before require can report.
    require(new LinkedHashSet<>(Set.of("definite", "possible", "unresolved"))
        .containsAll(certaintyStates),
        "unknown certainty state was emitted");
    for (JsonNode fact : allFacts) {
      if ("unresolved".equals(text(fact, "certainty"))) {
        require("abstained".equals(text(fact, "status")),
            "unresolved fact was emitted: " + text(fact, "id"));
      }
    }
  }

  private void requireLinks(List<JsonNode> providerFacts, List<JsonNode> frameworkFacts) {
    // Python used facts.get("links", []): absent is fine, but a non-array crashes its
    // iteration.
    JsonNode links = facts.path("links");
    require(links.isMissingNode() || links.isArray(), "facts links must be an array");
    Set<String> providerIds = idSet(providerFacts);
    Set<String> frameworkIds = idSet(frameworkFacts);
    for (JsonNode link : elements(links)) {
      require(frameworkIds.contains(text(link, "frameworkFactId")),
          "link points to an unknown framework fact");
      require(providerIds.contains(text(link, "providerFactId")),
          "link points to an unknown provider fact");
      require(link.path("certaintyPreserved").isBoolean()
          && link.path("certaintyPreserved").asBoolean(),
          "fact link did not preserve certainty");
    }
  }

  private List<JsonNode> requireSpringRoute(List<JsonNode> frameworkFacts,
      String contextId) {
    ObjectNode routeCriteria = MAPPER.createObjectNode();
    routeCriteria.put("httpMethod", "POST");
    routeCriteria.put("pathTemplate", "/api/transfers");
    routeCriteria.putArray("consumes");
    routeCriteria.putArray("produces");
    routeCriteria.putArray("headers");
    routeCriteria.putArray("parameters");
    routeCriteria.put("activeAnalysisContext", contextId);
    List<JsonNode> springRoute = new ArrayList<>();
    for (JsonNode fact : frameworkFacts) {
      if ("framework_entrypoint".equals(text(fact, "relationshipType"))
          && "external HTTP POST /api/transfers".equals(text(fact, "caller"))
          && "com.redhat.lightwell.controller.TransferController#transfer"
              .equals(text(fact, "callee"))
          && routeCriteria.equals(fact.path("selectionCriteria"))) {
        springRoute.add(fact);
      }
    }
    require(!springRoute.isEmpty(),
        "exact Spring MVC POST /api/transfers route evidence is missing");
    for (JsonNode fact : springRoute) {
      require("not_evidence_of_absence".equals(text(fact, "providerCallerAbsence")),
          "route provider-caller absence semantics were not preserved");
    }
    return springRoute;
  }

  private void requireNoSyntheticProviderEntrypoints(List<JsonNode> providerFacts) {
    for (JsonNode fact : providerFacts) {
      require(!"framework_entrypoint".equals(text(fact, "relationshipType")),
          "synthetic provider framework-entrypoint facts were emitted");
    }
  }

  // -------------------------------------------------------------- helpers

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new ManualIntegrationValidator.ValidationError(message);
    }
  }
}
