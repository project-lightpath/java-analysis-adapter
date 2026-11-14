package org.projectlightpath.javalint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** PMD/CPD report validation used by the Maven lint goal. */
public final class JavaLintValidator {
  /** Reviewed CPD policy threshold in tokens. */
  public static final int CPD_MINIMUM_TOKENS = 100;

  private static final String PMD_PLUGIN_ARTIFACT_ID = "maven-pmd-plugin";
  private static final String PMD_PLUGIN_GROUP_ID = "org.apache.maven.plugins";
  private static final int DEFAULT_PMD_MINIMUM_PRIORITY = 5;
  private static final boolean DEFAULT_PMD_INCLUDE_TESTS = false;
  private static final int ENFORCED_PMD_MINIMUM_PRIORITY = 3;
  private static final String ENFORCED_PMD_VERSION = "7.17.0";
  private static final List<String> ENFORCED_RULESETS = List.of("config/pmd-production.xml");
  private static final List<String> ENFORCED_SOURCE_SCOPE = List.of("src/main/java");
  private static final Pattern LOCATION_MESSAGE =
      Pattern.compile("\\bat line [0-9][0-9,]*\\b");
  private static final Pattern PMD_SUPPRESSION_MARKER = Pattern.compile("\\bNOPMD\\b");
  private static final Set<String> REQUIRED_RULES = Set.of(
      "CognitiveComplexity",
      "CyclomaticComplexity",
      "NPathComplexity",
      "NcssCount",
      "ExcessiveParameterList",
      "AvoidCatchingGenericException",
      "PreserveStackTrace",
      "UnusedPrivateMethod",
      "CommentRequired");
  private static final Set<String> CLASSIFICATIONS = Set.of(
      "maintainability",
      "intentional",
      "high-confidence-defect",
      "config-noise");
  private static final ObjectMapper JSON = new ObjectMapper()
      .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

  private final Path root;

  /** Creates a validator rooted at the Maven project directory. */
  public JavaLintValidator(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  /** Validates generated reports against the checked-in baseline, when present. */
  public ValidationReport validate(boolean enforce) throws IOException {
    Path ruleset = root.resolve("config/pmd-production.xml");
    Path baselineFile = root.resolve("config/java-lint-baseline.json");
    Path pmdReport = root.resolve("target/pmd.xml");
    Path cpdReport = root.resolve("target/cpd.xml");
    Path effectivePom = root.resolve("target/java-lint-effective-pom.xml");

    requireFile(ruleset);
    String rulesetText = Files.readString(ruleset, StandardCharsets.UTF_8);
    List<String> missingRules = REQUIRED_RULES.stream()
        .filter(rule -> !rulesetText.contains(rule))
        .sorted()
        .toList();
    if (!missingRules.isEmpty()) {
      throw new IllegalArgumentException(
          "java lint gate: ruleset lost required rules: " + String.join(", ", missingRules));
    }
    requireFile(pmdReport);
    requireFile(cpdReport);

    boolean baselinePresent = Files.exists(baselineFile);
    JsonNode baseline = baselinePresent ? loadBaseline(baselineFile) : emptyBaseline();
    Document pmd = parseXml(pmdReport);
    Document cpd = parseXml(cpdReport);
    EffectivePmdConfiguration effectivePmd = effectivePmdConfiguration(effectivePom, root);
    List<Finding> pmdFindings = collectPmd(pmd);
    List<Finding> cpdFindings = collectCpd(cpd);
    Comparison pmdComparison = compareFingerprints(
        fingerprints(pmdFindings), baselineFingerprints(baseline, "pmd"));
    Comparison cpdComparison = compareFingerprints(
        fingerprints(cpdFindings), baselineFingerprints(baseline, "cpd"));
    List<String> metadataErrors = validateMetadata(
        pmd, cpd, baseline, rulesetText, effectivePmd, baselinePresent);
    List<String> reportErrors = new ArrayList<>();
    reportErrors.addAll(reportProcessingErrors(pmd, "PMD"));
    reportErrors.addAll(reportProcessingErrors(cpd, "CPD"));

    List<String> messages = new ArrayList<>();
    String mode = enforce ? "enforced" : "report-only";
    summarizePmd(messages, pmdFindings, mode);
    summarizeCpd(messages, cpdFindings, mode);
    String baselineLabel = baselinePresent ? "baseline" : "empty baseline (no baseline file)";
    messages.add("PMD " + baselineLabel + " findings allowed: "
        + (pmdFindings.size() - pmdComparison.newFingerprints().size()));
    messages.add("PMD new findings: " + pmdComparison.newFingerprints().size()
        + "; resolved baseline findings: " + pmdComparison.resolvedFingerprints().size());
    messages.add("CPD " + baselineLabel + " duplications allowed: "
        + (cpdFindings.size() - cpdComparison.newFingerprints().size()));
    messages.add("CPD new duplications: " + cpdComparison.newFingerprints().size()
        + "; resolved baseline duplications: " + cpdComparison.resolvedFingerprints().size());
    printNewFindings(messages, "PMD", pmdFindings, pmdComparison.newFingerprints());
    printNewFindings(messages, "CPD", cpdFindings, cpdComparison.newFingerprints());
    metadataErrors.forEach(error -> messages.add("Lint baseline metadata error: " + error));
    reportErrors.forEach(error -> messages.add("Lint report processing error: " + error));
    reviewInventory(messages);

    int findingFailures = pmdComparison.newFingerprints().size()
        + cpdComparison.newFingerprints().size();
    int configurationFailures = metadataErrors.size() + reportErrors.size();
    boolean passed = configurationFailures == 0 && (!enforce || findingFailures == 0);
    if (configurationFailures > 0) {
      messages.add("java lint gate: FAIL (" + configurationFailures
          + " report/configuration error(s))");
    } else if (enforce && findingFailures > 0) {
      messages.add("java lint gate: FAIL (" + findingFailures + " new finding(s))");
    } else if (enforce) {
      messages.add(baselinePresent
          ? "java lint gate: PASS (no findings outside the reviewed baseline)"
          : "java lint gate: PASS (no PMD/CPD findings)");
    } else {
      messages.add("java lint report-only: PASS (baseline comparison was not enforced)");
    }
    return new ValidationReport(
        passed,
        findingFailures,
        configurationFailures,
        List.copyOf(messages));
  }

  /** Parses a small XML fixture with external entity resolution disabled. */
  static Document parseXml(String xml) {
    try {
      DocumentBuilderFactory factory = secureDocumentBuilderFactory();
      Document document = factory.newDocumentBuilder().parse(
          new org.xml.sax.InputSource(new java.io.StringReader(xml)));
      document.getDocumentElement().normalize();
      return document;
    } catch (IOException | ParserConfigurationException | SAXException error) {
      throw new IllegalArgumentException("invalid XML fixture", error);
    }
  }

  /** Creates a line-stable fingerprint for one PMD violation. */
  static String pmdFingerprint(Element file, Element violation) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("file", relativeSourcePath(Path.of("/"), file.getAttribute("name")));
    details.put("rule", attributeOr(violation, "rule", "unknown"));
    details.put("class", violation.getAttribute("class"));
    details.put("method", violation.getAttribute("method"));
    details.put("variable", violation.getAttribute("variable"));
    details.put("message", normalizeMessage(violation.getTextContent()));
    return sha256(canonicalJson(details));
  }

  /** Creates a line-stable fingerprint for one CPD duplication. */
  static String cpdFingerprint(Element duplication) {
    List<String> files = directChildren(duplication, "file").stream()
        .map(file -> file.getAttribute("path"))
        .map(path -> relativeSourcePath(Path.of("/"), path))
        .sorted()
        .toList();
    String fragment = directChildren(duplication, "codefragment").stream()
        .findFirst()
        .map(Node::getTextContent)
        .orElse("");
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("tokens", duplication.getAttribute("tokens"));
    details.put("files", files);
    details.put("fragmentSha256", sha256(normalizeWhitespace(fragment)));
    return sha256(canonicalJson(details));
  }

  /** Returns processing and configuration errors embedded in a PMD/CPD report. */
  static List<String> reportProcessingErrors(Document document, String reportName) {
    List<String> errors = new ArrayList<>();
    for (String errorElement : List.of("error", "configerror")) {
      for (Element error : descendants(document.getDocumentElement(), errorElement)) {
        String detail = normalizeWhitespace(error.getTextContent());
        Map<String, String> attributes = new TreeMap<>();
        NamedNodeMap nodeAttributes = error.getAttributes();
        for (int index = 0; index < nodeAttributes.getLength(); index++) {
          Node attribute = nodeAttributes.item(index);
          attributes.put(attribute.getNodeName(), attribute.getNodeValue());
        }
        if (!attributes.isEmpty()) {
          String formatted = attributes.entrySet().stream()
              .map(entry -> entry.getKey() + "='" + entry.getValue() + "'")
              .collect(java.util.stream.Collectors.joining(" "));
          detail = detail.isEmpty() ? formatted : detail + " (" + formatted + ")";
        }
        String kind = "configerror".equals(errorElement)
            ? "configuration error"
            : "processing error";
        errors.add(reportName + " report " + kind + ": "
            + (detail.isEmpty() ? "unspecified" : detail));
      }
    }
    return errors;
  }

  /** Reads the effective PMD plugin configuration emitted by Maven. */
  static EffectivePmdConfiguration effectivePmdConfiguration(
      Path effectivePom, Path projectRoot) throws IOException {
    requireFile(effectivePom);
    Document document = parseXml(Files.readString(effectivePom, StandardCharsets.UTF_8));
    Element plugin = effectivePmdPlugin(document);
    Element configuration = directChildren(plugin, "configuration").stream()
        .findFirst()
        .orElse(null);

    List<String> rulesets = configuredPaths(configuration, "rulesets", "ruleset", projectRoot);
    int minimumPriority = configuredInteger(
        configuration, "minimumPriority", DEFAULT_PMD_MINIMUM_PRIORITY);
    boolean includeTests = configuredBoolean(
        configuration, "includeTests", DEFAULT_PMD_INCLUDE_TESTS);
    int minimumTokens = configuredInteger(configuration, "minimumTokens", CPD_MINIMUM_TOKENS);
    List<String> sourceScope = new ArrayList<>(configuredPaths(
        configuration, "sourceDirectories", "sourceDirectory", projectRoot));
    if (sourceScope.isEmpty()) {
      Element build = directChildren(document.getDocumentElement(), "build").stream()
          .findFirst()
          .orElse(null);
      if (build != null) {
        addConfiguredPath(sourceScope, childText(build, "sourceDirectory"), projectRoot);
        if (includeTests) {
          addConfiguredPath(sourceScope, childText(build, "testSourceDirectory"), projectRoot);
        }
      }
    }
    return new EffectivePmdConfiguration(
        rulesets, minimumPriority, includeTests, sourceScope, minimumTokens);
  }

  /** Returns the effective CPD token threshold from Maven's PMD configuration. */
  static int effectiveCpdMinimumTokens(Path effectivePom) throws IOException {
    return effectivePmdConfiguration(effectivePom, Path.of("/")).minimumTokens();
  }

  /** Finds the single active PMD plugin in Maven's effective build. */
  private static Element effectivePmdPlugin(Document document) {
    List<Element> plugins = new ArrayList<>();
    for (Element build : directChildren(document.getDocumentElement(), "build")) {
      for (Element pluginContainer : directChildren(build, "plugins")) {
        plugins.addAll(directChildren(pluginContainer, "plugin"));
      }
    }
    List<Element> matching = plugins.stream()
        .filter(plugin -> PMD_PLUGIN_ARTIFACT_ID.equals(childText(plugin, "artifactId")))
        .filter(plugin -> {
          String groupId = childText(plugin, "groupId");
          return groupId.isEmpty() || PMD_PLUGIN_GROUP_ID.equals(groupId);
        })
        .toList();
    if (matching.size() != 1) {
      throw new IllegalArgumentException(
          "java lint gate: effective POM must contain exactly one active maven-pmd-plugin");
    }
    return matching.get(0);
  }

  /** Reads and normalizes a path-valued list from plugin configuration. */
  private static List<String> configuredPaths(
      Element configuration,
      String containerName,
      String valueName,
      Path projectRoot) {
    if (configuration == null) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    for (Element container : directChildren(configuration, containerName)) {
      for (Element value : directChildren(container, valueName)) {
        addConfiguredPath(values, value.getTextContent(), projectRoot);
      }
    }
    return List.copyOf(values);
  }

  /** Adds one effective-POM path in repository-relative form when possible. */
  private static void addConfiguredPath(List<String> values, String value, Path projectRoot) {
    if (value == null || value.isBlank()) {
      return;
    }
    try {
      Path root = projectRoot.toAbsolutePath().normalize();
      Path path = Path.of(value.trim());
      Path normalized = path.isAbsolute() ? path.normalize() : root.resolve(path).normalize();
      if (normalized.startsWith(root)) {
        values.add(root.relativize(normalized).toString().replace('\\', '/'));
      } else {
        values.add(normalized.toString().replace('\\', '/'));
      }
    } catch (InvalidPathException error) {
      values.add(value.trim().replace('\\', '/'));
    }
  }

  /** Reads a positive integer plugin setting or its Maven default. */
  private static int configuredInteger(Element configuration, String name, int defaultValue) {
    String configured = configuration == null ? "" : childText(configuration, name);
    if (configured.isEmpty()) {
      return defaultValue;
    }
    try {
      int value = Integer.parseInt(configured);
      if (value <= 0) {
        throw new IllegalArgumentException(
            "java lint gate: effective " + name + " must be positive");
      }
      return value;
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(
          "java lint gate: effective " + name + " is not an integer: " + configured,
          error);
    }
  }

  /** Reads a boolean plugin setting or its Maven default. */
  private static boolean configuredBoolean(Element configuration, String name, boolean defaultValue) {
    String configured = configuration == null ? "" : childText(configuration, name);
    if (configured.isEmpty()) {
      return defaultValue;
    }
    if (!"true".equalsIgnoreCase(configured) && !"false".equalsIgnoreCase(configured)) {
      throw new IllegalArgumentException(
          "java lint gate: effective " + name + " is not boolean: " + configured);
    }
    return Boolean.parseBoolean(configured);
  }

  /** Compares current and reviewed fingerprints as multisets. */
  static Comparison compareFingerprints(List<String> current, List<String> baseline) {
    Map<String, Integer> currentCounts = counts(current);
    Map<String, Integer> baselineCounts = counts(baseline);
    List<String> newFingerprints = difference(currentCounts, baselineCounts);
    List<String> resolvedFingerprints = difference(baselineCounts, currentCounts);
    return new Comparison(newFingerprints, resolvedFingerprints);
  }

  /** Collects normalized PMD findings from a report document. */
  private List<Finding> collectPmd(Document document) {
    List<Finding> findings = new ArrayList<>();
    for (Element file : descendants(document.getDocumentElement(), "file")) {
      for (Element violation : descendants(file, "violation")) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("file", relativeSourcePath(root, attributeOr(file, "name", "unknown")));
        details.put("rule", attributeOr(violation, "rule", "unknown"));
        details.put("class", violation.getAttribute("class"));
        details.put("method", violation.getAttribute("method"));
        details.put("variable", violation.getAttribute("variable"));
        details.put("message", normalizeMessage(violation.getTextContent()));
        findings.add(new Finding(details));
      }
    }
    return findings;
  }

  /** Collects normalized CPD duplications from a report document. */
  private List<Finding> collectCpd(Document document) {
    List<Finding> findings = new ArrayList<>();
    for (Element duplication : descendants(document.getDocumentElement(), "duplication")) {
      List<String> files = directChildren(duplication, "file").stream()
          .map(file -> relativeSourcePath(root, file.getAttribute("path")))
          .sorted()
          .toList();
      String fragment = directChildren(duplication, "codefragment").stream()
          .findFirst()
          .map(Node::getTextContent)
          .orElse("");
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("tokens", duplication.getAttribute("tokens"));
      details.put("files", files);
      details.put("fragmentSha256", sha256(normalizeWhitespace(fragment)));
      findings.add(new Finding(details));
    }
    return findings;
  }

  /** Compares report and effective-POM metadata with the active lint contract. */
  private List<String> validateMetadata(
      Document pmd,
      Document cpd,
      JsonNode baseline,
      String rulesetText,
      EffectivePmdConfiguration effectivePmd,
      boolean baselinePresent) {
    List<String> errors = new ArrayList<>();
    if (!baselinePresent) {
      return validateNoBaselineMetadata(pmd, cpd, effectivePmd);
    }
    JsonNode pmdBaseline = baseline.path("pmd");
    JsonNode cpdBaseline = baseline.path("cpd");
    String reportPmdVersion = pmd.getDocumentElement().getAttribute("version");
    if (!reportPmdVersion.equals(pmdBaseline.path("pmdVersion").asText())) {
      errors.add("PMD version changed: report=" + quote(reportPmdVersion)
          + " baseline=" + quote(pmdBaseline.path("pmdVersion").asText()));
    }
    String rulesetDigest = sha256(rulesetText);
    if (!rulesetDigest.equals(pmdBaseline.path("rulesetSha256").asText())) {
      errors.add("PMD ruleset changed; review and intentionally refresh the baseline");
    }
    List<String> reviewedRulesets = jsonStringList(pmdBaseline, "rulesets");
    if (!reviewedRulesets.equals(effectivePmd.rulesets())) {
      errors.add("PMD rule selection changed: effective Maven=" + effectivePmd.rulesets()
          + "; baseline=" + reviewedRulesets);
    }
    int reviewedPriority = pmdBaseline.path("minimumPriority").asInt(Integer.MIN_VALUE);
    if (reviewedPriority != effectivePmd.minimumPriority()) {
      errors.add("PMD minimum priority changed: effective Maven="
          + effectivePmd.minimumPriority() + "; baseline=" + reviewedPriority);
    }
    boolean reviewedIncludeTests = pmdBaseline.path("includeTests").asBoolean(true);
    if (reviewedIncludeTests != effectivePmd.includeTests()) {
      errors.add("PMD test-source scope changed: effective Maven includeTests="
          + effectivePmd.includeTests() + "; baseline=" + reviewedIncludeTests);
    }
    List<String> reviewedSourceScope = jsonStringList(pmdBaseline, "sourceScope");
    if (!reviewedSourceScope.equals(effectivePmd.sourceScope())) {
      errors.add("PMD source scope changed: effective Maven=" + effectivePmd.sourceScope()
          + "; baseline=" + reviewedSourceScope);
    }
    String reportCpdVersion = cpd.getDocumentElement().getAttribute("pmdVersion");
    if (!reportCpdVersion.equals(cpdBaseline.path("pmdVersion").asText())) {
      errors.add("CPD version changed: report=" + quote(reportCpdVersion)
          + " baseline=" + quote(cpdBaseline.path("pmdVersion").asText()));
    }
    int effectiveMinimumTokens = effectivePmd.minimumTokens();
    if (effectiveMinimumTokens != CPD_MINIMUM_TOKENS) {
      errors.add("CPD minimum token configuration changed: effective Maven="
          + effectiveMinimumTokens + "; policy=" + CPD_MINIMUM_TOKENS);
    }
    if (cpdBaseline.path("minimumTokens").asInt(Integer.MIN_VALUE) != effectiveMinimumTokens) {
      errors.add("CPD minimum token configuration is inconsistent with the reviewed baseline: "
          + "effective Maven=" + effectiveMinimumTokens + "; baseline="
          + cpdBaseline.path("minimumTokens").asText());
    }
    return errors;
  }

  /** Enforces the production lint contract when no historical baseline remains. */
  private static List<String> validateNoBaselineMetadata(
      Document pmd, Document cpd, EffectivePmdConfiguration effectivePmd) {
    List<String> errors = new ArrayList<>();
    if (!ENFORCED_PMD_VERSION.equals(pmd.getDocumentElement().getAttribute("version"))) {
      errors.add("PMD version must be " + quote(ENFORCED_PMD_VERSION));
    }
    if (!ENFORCED_PMD_VERSION.equals(cpd.getDocumentElement().getAttribute("pmdVersion"))) {
      errors.add("CPD version must be " + quote(ENFORCED_PMD_VERSION));
    }
    if (effectivePmd.minimumPriority() != ENFORCED_PMD_MINIMUM_PRIORITY) {
      errors.add("PMD minimum priority must be " + ENFORCED_PMD_MINIMUM_PRIORITY);
    }
    if (effectivePmd.includeTests() != DEFAULT_PMD_INCLUDE_TESTS) {
      errors.add("PMD test-source scope must exclude tests");
    }
    if (!ENFORCED_RULESETS.equals(effectivePmd.rulesets())) {
      errors.add("PMD rule selection must use " + ENFORCED_RULESETS);
    }
    if (!ENFORCED_SOURCE_SCOPE.equals(effectivePmd.sourceScope())) {
      errors.add("PMD source scope must be " + ENFORCED_SOURCE_SCOPE);
    }
    if (effectivePmd.minimumTokens() != CPD_MINIMUM_TOKENS) {
      errors.add("CPD minimum token configuration must be " + CPD_MINIMUM_TOKENS);
    }
    return errors;
  }

  /** Reads a JSON string array while preserving its reviewed order. */
  private static List<String> jsonStringList(JsonNode object, String field) {
    List<String> values = new ArrayList<>();
    object.path(field).forEach(value -> values.add(value.asText()));
    return List.copyOf(values);
  }

  /** Loads and validates the structural shape of the reviewed baseline. */
  private JsonNode loadBaseline(Path baselineFile) throws IOException {
    requireFile(baselineFile);
    JsonNode baseline;
    try {
      baseline = JSON.readTree(Files.readString(baselineFile, StandardCharsets.UTF_8));
    } catch (JsonProcessingException error) {
      throw new IOException("java lint gate: invalid baseline " + baselineFile, error);
    }
    if (baseline == null || baseline.path("schemaVersion").asInt(Integer.MIN_VALUE) != 1) {
      throw new IllegalArgumentException("java lint gate: unsupported baseline schemaVersion");
    }
    for (String section : List.of("pmd", "cpd")) {
      JsonNode sectionNode = baseline.get(section);
      if (sectionNode == null || !sectionNode.isObject()) {
        throw new IllegalArgumentException(
            "java lint gate: baseline section " + section + " is missing");
      }
      JsonNode entries = sectionNode.get("findings");
      if (entries == null || !entries.isArray()) {
        throw new IllegalArgumentException(
            "java lint gate: baseline section " + section + ".findings is missing");
      }
      for (JsonNode entry : entries) {
        if (!entry.isObject() || entry.path("fingerprint").asText().isBlank()) {
          throw new IllegalArgumentException(
              "java lint gate: malformed " + section + " baseline entry");
        }
        if (!CLASSIFICATIONS.contains(entry.path("classification").asText())) {
          throw new IllegalArgumentException(
              "java lint gate: invalid " + section + " baseline classification");
        }
        if (entry.path("rationale").asText().isBlank()) {
          throw new IllegalArgumentException("java lint gate: baseline rationale is required");
        }
      }
    }
    JsonNode pmd = baseline.path("pmd");
    requireBaselineText(pmd, "pmdVersion");
    requireBaselineText(pmd, "rulesetSha256");
    requireBaselineInteger(pmd, "minimumPriority");
    requireBaselineBoolean(pmd, "includeTests");
    requireBaselineStringList(pmd, "rulesets");
    requireBaselineStringList(pmd, "sourceScope");
    JsonNode cpd = baseline.path("cpd");
    requireBaselineText(cpd, "pmdVersion");
    requireBaselineInteger(cpd, "minimumTokens");
    return baseline;
  }

  /** Supplies an empty fingerprint inventory after the reviewed baseline is retired. */
  private static JsonNode emptyBaseline() {
    ObjectNode baseline = JSON.createObjectNode();
    baseline.putObject("pmd").putArray("findings");
    baseline.putObject("cpd").putArray("findings");
    return baseline;
  }

  /** Requires one non-empty textual baseline metadata field. */
  private static void requireBaselineText(JsonNode object, String field) {
    if (object.path(field).asText().isBlank()) {
      throw new IllegalArgumentException("java lint gate: baseline metadata " + field
          + " is required");
    }
  }

  /** Requires one integral baseline metadata field. */
  private static void requireBaselineInteger(JsonNode object, String field) {
    if (!object.path(field).isIntegralNumber()) {
      throw new IllegalArgumentException("java lint gate: baseline metadata " + field
          + " must be an integer");
    }
  }

  /** Requires one boolean baseline metadata field. */
  private static void requireBaselineBoolean(JsonNode object, String field) {
    if (!object.path(field).isBoolean()) {
      throw new IllegalArgumentException("java lint gate: baseline metadata " + field
          + " must be boolean");
    }
  }

  /** Requires one non-empty-string-array baseline metadata field. */
  private static void requireBaselineStringList(JsonNode object, String field) {
    JsonNode values = object.get(field);
    if (values == null || !values.isArray()) {
      throw new IllegalArgumentException("java lint gate: baseline metadata " + field
          + " must be a list of non-empty strings");
    }
    for (JsonNode value : values) {
      if (!value.isTextual() || value.asText().isBlank()) {
        throw new IllegalArgumentException("java lint gate: baseline metadata " + field
            + " must be a list of non-empty strings");
      }
    }
  }

  /** Extracts the reviewed fingerprints for one report type. */
  private List<String> baselineFingerprints(JsonNode baseline, String section) {
    List<String> fingerprints = new ArrayList<>();
    baseline.path(section).path("findings")
        .forEach(entry -> fingerprints.add(entry.path("fingerprint").asText()));
    return fingerprints;
  }

  /** Adds a compact PMD inventory to the Maven log. */
  private void summarizePmd(List<String> messages, List<Finding> findings, String mode) {
    Map<String, Long> byRule = findings.stream()
        .map(Finding::details)
        .map(details -> String.valueOf(details.get("rule")))
        .collect(java.util.stream.Collectors.groupingBy(
            value -> value, TreeMap::new, java.util.stream.Collectors.counting()));
    Map<String, Long> byFile = findings.stream()
        .map(Finding::details)
        .map(details -> String.valueOf(details.get("file")))
        .collect(java.util.stream.Collectors.groupingBy(
            value -> value, TreeMap::new, java.util.stream.Collectors.counting()));
    messages.add("PMD " + mode + " findings: " + findings.size());
    byRule.forEach((rule, count) -> messages.add("  " + rule + ": " + count));
    if (!byFile.isEmpty()) {
      messages.add("PMD " + mode + " files with findings:");
      byFile.forEach((file, count) -> messages.add("  " + file + ": " + count));
    }
  }

  /** Adds a compact CPD inventory to the Maven log. */
  private void summarizeCpd(List<String> messages, List<Finding> findings, String mode) {
    messages.add("CPD " + mode + " duplications: " + findings.size());
    if (!findings.isEmpty()) {
      messages.add("  token counts: " + findings.stream()
          .map(finding -> String.valueOf(finding.details().get("tokens")))
          .collect(java.util.stream.Collectors.joining(", ")));
    }
  }

  /** Adds details for findings not covered by the reviewed baseline. */
  private void printNewFindings(
      List<String> messages,
      String label,
      List<Finding> findings,
      List<String> newFingerprints) {
    if (newFingerprints.isEmpty()) {
      return;
    }
    Map<String, Finding> byFingerprint = new HashMap<>();
    findings.forEach(finding -> byFingerprint.put(finding.fingerprint(), finding));
    messages.add(label + " findings outside baseline:");
    for (String fingerprint : newFingerprints) {
      messages.add("  " + canonicalJson(byFingerprint.get(fingerprint).details()));
    }
  }

  /** Inventories production-source suppressions and placeholder comments. */
  private void reviewInventory(List<String> messages) throws IOException {
    Path sourceRoot = root.resolve("src/main/java");
    List<String> suppressions = new ArrayList<>();
    List<String> placeholders = new ArrayList<>();
    if (Files.isDirectory(sourceRoot)) {
      try (Stream<Path> sources = Files.walk(sourceRoot)) {
        for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
          List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
          for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            String relative = root.relativize(source).toString().replace('\\', '/');
            if (containsSuppressionMarker(line)) {
              suppressions.add(relative + ":" + (index + 1));
            }
            if (line.matches(".*\\b(TODO|FIXME|XXX|HACK)\\b.*")) {
              placeholders.add(relative + ":" + (index + 1));
            }
          }
        }
      }
    }
    messages.add("production suppression inventory: " + suppressions.size());
    messages.add("placeholder-comment inventory: " + placeholders.size());
    if (!suppressions.isEmpty()) {
      messages.add("  suppressions for manual review: " + String.join(", ", suppressions));
    }
    if (!placeholders.isEmpty()) {
      messages.add("  comment markers for manual review: " + String.join(", ", placeholders));
    }
  }

  /** Returns whether a production source line contains a PMD suppression marker. */
  static boolean containsSuppressionMarker(String line) {
    return line.contains("@SuppressWarnings") || PMD_SUPPRESSION_MARKER.matcher(line).find();
  }

  /** Parses a report file with external entity resolution disabled. */
  private static Document parseXml(Path path) throws IOException {
    try {
      Document document = secureDocumentBuilderFactory().newDocumentBuilder().parse(path.toFile());
      document.getDocumentElement().normalize();
      return document;
    } catch (ParserConfigurationException | SAXException error) {
      throw new IOException("java lint gate: invalid XML report " + path, error);
    }
  }

  /** Creates the hardened XML parser factory used for reports and fixtures. */
  private static DocumentBuilderFactory secureDocumentBuilderFactory()
      throws ParserConfigurationException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    return factory;
  }

  /** Returns all descendant elements matching a local XML name. */
  private static List<Element> descendants(Element root, String name) {
    List<Element> result = new ArrayList<>();
    NodeList children = root.getElementsByTagNameNS("*", name);
    for (int index = 0; index < children.getLength(); index++) {
      result.add((Element) children.item(index));
    }
    if (children.getLength() == 0) {
      NodeList fallback = root.getElementsByTagName(name);
      for (int index = 0; index < fallback.getLength(); index++) {
        result.add((Element) fallback.item(index));
      }
    }
    return result;
  }

  /** Returns direct child elements matching a local XML name. */
  private static List<Element> directChildren(Element root, String name) {
    List<Element> result = new ArrayList<>();
    NodeList children = root.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      Node child = children.item(index);
      if (child.getNodeType() == Node.ELEMENT_NODE && name.equals(localName(child))) {
        result.add((Element) child);
      }
    }
    return result;
  }

  /** Reads trimmed text from the first matching direct child. */
  private static String childText(Element root, String name) {
    return directChildren(root, name).stream()
        .findFirst()
        .map(Node::getTextContent)
        .map(String::trim)
        .orElse("");
  }

  /** Resolves an XML local name for namespace-aware and plain fixtures. */
  private static String localName(Node node) {
    String localName = node.getLocalName();
    if (localName != null) {
      return localName;
    }
    String name = node.getNodeName();
    int separator = name.indexOf(':');
    return separator >= 0 ? name.substring(separator + 1) : name;
  }

  /** Reads an XML attribute, returning a fallback for absent values. */
  private static String attributeOr(Element element, String name, String fallback) {
    String value = element.getAttribute(name);
    return value.isEmpty() ? fallback : value;
  }

  /** Normalizes a report path to a stable slash-separated representation. */
  private static String relativeSourcePath(Path root, String pathText) {
    try {
      Path path = Path.of(pathText);
      if (path.isAbsolute()) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (normalizedPath.startsWith(normalizedRoot)) {
          path = normalizedRoot.relativize(normalizedPath);
        }
      }
      return path.toString().replace('\\', '/');
    } catch (InvalidPathException error) {
      return pathText.replace('\\', '/');
    }
  }

  /** Removes source-line drift from PMD messages before fingerprinting. */
  private static String normalizeMessage(String message) {
    return LOCATION_MESSAGE.matcher(normalizeWhitespace(message)).replaceAll("at line <line>");
  }

  /** Collapses report whitespace to a stable single-line value. */
  private static String normalizeWhitespace(String value) {
    return value == null ? "" : value.trim().replaceAll("\\s+", " ");
  }

  /** Serializes fingerprint details with deterministic key ordering. */
  private static String canonicalJson(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot serialize lint fingerprint", error);
    }
  }

  /** Returns the lowercase SHA-256 digest used for stable fingerprints. */
  private static String sha256(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(digest.length * 2);
      for (byte item : digest) {
        result.append(String.format("%02x", item & 0xff));
      }
      return result.toString();
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  /** Counts duplicate fingerprints for multiset comparison. */
  private static Map<String, Integer> counts(List<String> values) {
    Map<String, Integer> counts = new HashMap<>();
    values.forEach(value -> counts.merge(value, 1, Integer::sum));
    return counts;
  }

  /** Computes the sorted multiset difference between two fingerprint maps. */
  private static List<String> difference(Map<String, Integer> left, Map<String, Integer> right) {
    List<String> result = new ArrayList<>();
    left.forEach((value, count) -> {
      int difference = count - right.getOrDefault(value, 0);
      for (int index = 0; index < difference; index++) {
        result.add(value);
      }
    });
    Collections.sort(result);
    return result;
  }

  /** Extracts fingerprints from normalized findings. */
  private static List<String> fingerprints(List<Finding> findings) {
    return findings.stream().map(Finding::fingerprint).toList();
  }

  /** Fails early when a required gate input is absent. */
  private static void requireFile(Path path) throws IOException {
    if (!Files.isRegularFile(path)) {
      throw new IOException("java lint gate: missing file " + path);
    }
  }

  /** Formats a metadata value for a concise diagnostic. */
  private static String quote(String value) {
    return "'" + value + "'";
  }

  private record Finding(Map<String, Object> details) {
    /** Computes the fingerprint for this normalized finding. */
    private String fingerprint() {
      return sha256(canonicalJson(details));
    }
  }

  /** Effective PMD settings that must remain bound to the reviewed baseline. */
  static record EffectivePmdConfiguration(
      List<String> rulesets,
      int minimumPriority,
      boolean includeTests,
      List<String> sourceScope,
      int minimumTokens) {}

  /** Multiset delta between the current report and the reviewed baseline. */
  static record Comparison(List<String> newFingerprints, List<String> resolvedFingerprints) {}

  /** Result returned to the Maven goal and unit tests. */
  public record ValidationReport(
      boolean passed,
      int newFindingCount,
      int configurationErrorCount,
      List<String> messages) {
    /** Returns the concise failure summary used by Maven. */
    String failureMessage() {
      return "Java lint gate failed: " + newFindingCount + " new finding(s), "
          + configurationErrorCount + " report/configuration error(s)";
    }
  }
}
