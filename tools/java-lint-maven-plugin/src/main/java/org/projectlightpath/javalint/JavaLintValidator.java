package org.projectlightpath.javalint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
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

/** Baseline-aware PMD/CPD report validation used by the Maven lint goal. */
public final class JavaLintValidator {
  public static final int CPD_MINIMUM_TOKENS = 100;

  private static final String MAVEN_NAMESPACE = "http://maven.apache.org/POM/4.0.0";
  private static final Pattern LOCATION_MESSAGE =
      Pattern.compile("\\bat line [0-9][0-9,]*\\b");
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

  public JavaLintValidator(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  /** Validates generated reports against the checked-in baseline. */
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

    JsonNode baseline = loadBaseline(baselineFile);
    Document pmd = parseXml(pmdReport);
    Document cpd = parseXml(cpdReport);
    List<Finding> pmdFindings = collectPmd(pmd);
    List<Finding> cpdFindings = collectCpd(cpd);
    Comparison pmdComparison = compareFingerprints(
        fingerprints(pmdFindings), baselineFingerprints(baseline, "pmd"));
    Comparison cpdComparison = compareFingerprints(
        fingerprints(cpdFindings), baselineFingerprints(baseline, "cpd"));
    List<String> metadataErrors = validateMetadata(
        pmd, cpd, baseline, rulesetText, effectivePom);
    List<String> reportErrors = new ArrayList<>();
    reportErrors.addAll(reportProcessingErrors(pmd, "PMD"));
    reportErrors.addAll(reportProcessingErrors(cpd, "CPD"));

    List<String> messages = new ArrayList<>();
    String mode = enforce ? "enforced" : "report-only";
    summarizePmd(messages, pmdFindings, mode);
    summarizeCpd(messages, cpdFindings, mode);
    messages.add("PMD baseline findings allowed: "
        + (pmdFindings.size() - pmdComparison.newFingerprints().size()));
    messages.add("PMD new findings: " + pmdComparison.newFingerprints().size()
        + "; resolved baseline findings: " + pmdComparison.resolvedFingerprints().size());
    messages.add("CPD baseline duplications allowed: "
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
      messages.add("java lint gate: PASS (no findings outside the reviewed baseline)");
    } else {
      messages.add("java lint report-only: PASS (baseline comparison was not enforced)");
    }
    return new ValidationReport(
        passed,
        findingFailures,
        configurationFailures,
        List.copyOf(messages));
  }

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

  static List<String> reportProcessingErrors(Document document, String reportName) {
    List<String> errors = new ArrayList<>();
    for (Element error : descendants(document.getDocumentElement(), "error")) {
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
      errors.add(reportName + " report processing error: "
          + (detail.isEmpty() ? "unspecified" : detail));
    }
    return errors;
  }

  static int effectiveCpdMinimumTokens(Path effectivePom) throws IOException {
    requireFile(effectivePom);
    Document document = parseXml(Files.readString(effectivePom, StandardCharsets.UTF_8));
    List<Element> plugins = new ArrayList<>();
    for (Element build : directChildren(document.getDocumentElement(), "build")) {
      for (Element pluginContainer : directChildren(build, "plugins")) {
        plugins.addAll(directChildren(pluginContainer, "plugin"));
      }
    }
    List<Element> matching = plugins.stream()
        .filter(plugin -> "maven-pmd-plugin".equals(childText(plugin, "artifactId")))
        .filter(plugin -> {
          String groupId = childText(plugin, "groupId");
          return groupId.isEmpty() || "org.apache.maven.plugins".equals(groupId);
        })
        .toList();
    if (matching.size() != 1) {
      throw new IllegalArgumentException(
          "java lint gate: effective POM must contain exactly one active maven-pmd-plugin");
    }
    Element configuration = directChildren(matching.get(0), "configuration").stream()
        .findFirst()
        .orElse(null);
    if (configuration == null) {
      return CPD_MINIMUM_TOKENS;
    }
    String configured = childText(configuration, "minimumTokens");
    if (configured.isEmpty()) {
      return CPD_MINIMUM_TOKENS;
    }
    try {
      int value = Integer.parseInt(configured);
      if (value <= 0) {
        throw new IllegalArgumentException(
            "java lint gate: effective CPD minimumTokens must be positive");
      }
      return value;
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(
          "java lint gate: effective CPD minimumTokens is not an integer: " + configured,
          error);
    }
  }

  static Comparison compareFingerprints(List<String> current, List<String> baseline) {
    Map<String, Integer> currentCounts = counts(current);
    Map<String, Integer> baselineCounts = counts(baseline);
    List<String> newFingerprints = difference(currentCounts, baselineCounts);
    List<String> resolvedFingerprints = difference(baselineCounts, currentCounts);
    return new Comparison(newFingerprints, resolvedFingerprints);
  }

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

  private List<String> validateMetadata(
      Document pmd,
      Document cpd,
      JsonNode baseline,
      String rulesetText,
      Path effectivePom) throws IOException {
    List<String> errors = new ArrayList<>();
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
    String reportCpdVersion = cpd.getDocumentElement().getAttribute("pmdVersion");
    if (!reportCpdVersion.equals(cpdBaseline.path("pmdVersion").asText())) {
      errors.add("CPD version changed: report=" + quote(reportCpdVersion)
          + " baseline=" + quote(cpdBaseline.path("pmdVersion").asText()));
    }
    int effectiveMinimumTokens = effectiveCpdMinimumTokens(effectivePom);
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
    return baseline;
  }

  private List<String> baselineFingerprints(JsonNode baseline, String section) {
    List<String> fingerprints = new ArrayList<>();
    baseline.path(section).path("findings")
        .forEach(entry -> fingerprints.add(entry.path("fingerprint").asText()));
    return fingerprints;
  }

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

  private void summarizeCpd(List<String> messages, List<Finding> findings, String mode) {
    messages.add("CPD " + mode + " duplications: " + findings.size());
    if (!findings.isEmpty()) {
      messages.add("  token counts: " + findings.stream()
          .map(finding -> String.valueOf(finding.details().get("tokens")))
          .collect(java.util.stream.Collectors.joining(", ")));
    }
  }

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
            if (line.contains("@SuppressWarnings")) {
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

  private static Document parseXml(Path path) throws IOException {
    try {
      Document document = secureDocumentBuilderFactory().newDocumentBuilder().parse(path.toFile());
      document.getDocumentElement().normalize();
      return document;
    } catch (ParserConfigurationException | SAXException error) {
      throw new IOException("java lint gate: invalid XML report " + path, error);
    }
  }

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

  private static String childText(Element root, String name) {
    return directChildren(root, name).stream()
        .findFirst()
        .map(Node::getTextContent)
        .map(String::trim)
        .orElse("");
  }

  private static String localName(Node node) {
    String localName = node.getLocalName();
    if (localName != null) {
      return localName;
    }
    String name = node.getNodeName();
    int separator = name.indexOf(':');
    return separator >= 0 ? name.substring(separator + 1) : name;
  }

  private static String attributeOr(Element element, String name, String fallback) {
    String value = element.getAttribute(name);
    return value.isEmpty() ? fallback : value;
  }

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

  private static String normalizeMessage(String message) {
    return LOCATION_MESSAGE.matcher(normalizeWhitespace(message)).replaceAll("at line <line>");
  }

  private static String normalizeWhitespace(String value) {
    return value == null ? "" : value.trim().replaceAll("\\s+", " ");
  }

  private static String canonicalJson(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot serialize lint fingerprint", error);
    }
  }

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

  private static Map<String, Integer> counts(List<String> values) {
    Map<String, Integer> counts = new HashMap<>();
    values.forEach(value -> counts.merge(value, 1, Integer::sum));
    return counts;
  }

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

  private static List<String> fingerprints(List<Finding> findings) {
    return findings.stream().map(Finding::fingerprint).toList();
  }

  private static void requireFile(Path path) throws IOException {
    if (!Files.isRegularFile(path)) {
      throw new IOException("java lint gate: missing file " + path);
    }
  }

  private static String quote(String value) {
    return "'" + value + "'";
  }

  private record Finding(Map<String, Object> details) {
    private String fingerprint() {
      return sha256(canonicalJson(details));
    }
  }

  static record Comparison(List<String> newFingerprints, List<String> resolvedFingerprints) {}

  /** Result returned to the Maven goal and unit tests. */
  public record ValidationReport(
      boolean passed,
      int newFindingCount,
      int configurationErrorCount,
      List<String> messages) {
    String failureMessage() {
      return "Java lint gate failed: " + newFindingCount + " new finding(s), "
          + configurationErrorCount + " report/configuration error(s)";
    }
  }
}
