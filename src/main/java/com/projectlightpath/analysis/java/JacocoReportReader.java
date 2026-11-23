package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * JaCoCo report reading for the changed-coverage validator (#45 step 4): executable and
 * covered line sets keyed by source key, from sourcefile entries plus the class
 * sourcefilename fallback, through a hardened DOM parser. Ported from
 * validate_changed_coverage.py's jacoco_lines.
 */
final class JacocoReportReader {

  /** Executable and covered line sets, keyed by source key. */
  record JacocoCoverage(Map<String, Set<Long>> executable, Map<String, Set<Long>> covered) {}

  private JacocoReportReader() {}

  /** Reads executable and covered lines from a JaCoCo report (hardened DOM). */
  static JacocoCoverage jacocoLines(Path report, String sourceRoot) {
    Document document = parseReport(report);
    Map<String, Set<Long>> executable = new LinkedHashMap<>();
    Map<String, Set<Long>> covered = new LinkedHashMap<>();
    for (Element pack : elementsOf(document, "package")) {
      readPackage(pack, report, sourceRoot, executable, covered);
    }
    return new JacocoCoverage(executable, covered);
  }

  private static void readPackage(Element pack, Path report, String sourceRoot,
      Map<String, Set<Long>> executable, Map<String, Set<Long>> covered) {
    String packageName = pack.getAttribute("name");
    for (Element sourcefile : childElements(pack, "sourcefile")) {
      String name = requiredAttribute(sourcefile, "name", report);
      for (Element line : childElements(sourcefile, "line")) {
        addLine(executable, covered, sourceKey(packageName, name, sourceRoot), line,
            report);
      }
    }
    // Sourcefile nodes normally union nested classes; keep class parsing as a
    // fallback and union it into the same line sets when present.
    for (Element clazz : childElements(pack, "class")) {
      readClassLines(clazz, packageName, report, sourceRoot, executable, covered);
    }
  }

  private static void readClassLines(Element clazz, String packageName, Path report,
      String sourceRoot, Map<String, Set<Long>> executable, Map<String, Set<Long>> covered) {
    String name = clazz.getAttribute("sourcefilename");
    if (name.isEmpty()) {
      return;
    }
    for (Element lines : childElements(clazz, "lines")) {
      for (Element line : childElements(lines, "line")) {
        addLine(executable, covered, sourceKey(packageName, name, sourceRoot), line,
            report);
      }
    }
  }

  /** Joins source root, dotted package, and file name, dropping empty parts. */
  static String sourceKey(String pkg, String name, String sourceRoot) {
    // Python: package.strip("/").replace(".", "/") — slashes only, no whitespace.
    String stripped = pkg;
    while (stripped.startsWith("/")) {
      stripped = stripped.substring(1);
    }
    while (stripped.endsWith("/") && !stripped.isEmpty()) {
      stripped = stripped.substring(0, stripped.length() - 1);
    }
    String packagePath = stripped.replace(".", "/");
    StringBuilder key = new StringBuilder();
    if (!sourceRoot.isEmpty()) {
      key.append(sourceRoot);
    }
    if (!packagePath.isEmpty()) {
      if (key.length() > 0) {
        key.append('/');
      }
      key.append(packagePath);
    }
    if (!name.isEmpty()) {
      if (key.length() > 0) {
        key.append('/');
      }
      key.append(name);
    }
    return key.toString();
  }

  private static void addLine(Map<String, Set<Long>> executable,
      Map<String, Set<Long>> covered, String key, Element line, Path report) {
    long number = longAttribute(line, "nr", report);
    String coveredInstructions = line.getAttribute("ci");
    long ci = coveredInstructions.isEmpty() ? 0
        : parseLong(coveredInstructions, "ci", report);
    executable.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(number);
    if (ci > 0) {
      covered.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(number);
    }
  }

  private static Document parseReport(Path report) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      // Standard JaCoCo reports open with a DOCTYPE referencing report.dtd; the
      // declaration is allowed, but every external resource (the DTD, entities,
      // schemas) stays blocked: loading is disabled and the resolver answers any
      // stray lookup with an empty source.
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      DocumentBuilder builder = factory.newDocumentBuilder();
      builder.setEntityResolver((publicId, systemId) -> new org.xml.sax.InputSource(
          new java.io.StringReader("")));
      return builder.parse(report.toFile());
    } catch (IOException | ParserConfigurationException | SAXException error) {
      throw new ChangedCoverageValidator.ValidationError(
          "cannot parse JaCoCo report: " + report + " (" + error.getMessage() + ")",
          error);
    }
  }

  private static List<Element> elementsOf(Document document, String tag) {
    List<Element> elements = new ArrayList<>();
    NodeList nodes = document.getElementsByTagName(tag);
    for (int i = 0; i < nodes.getLength(); i++) {
      elements.add((Element) nodes.item(i));
    }
    return elements;
  }

  private static List<Element> childElements(Element parent, String tag) {
    List<Element> elements = new ArrayList<>();
    NodeList nodes = parent.getElementsByTagName(tag);
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      if (node.getParentNode() == parent) {
        elements.add((Element) node);
      }
    }
    return elements;
  }

  private static String requiredAttribute(Element element, String name, Path report) {
    String value = element.getAttribute(name);
    if (value.isEmpty()) {
      throw new ChangedCoverageValidator.ValidationError("cannot parse JaCoCo report: "
          + report + " (missing " + name + " attribute)");
    }
    return value;
  }

  private static long longAttribute(Element element, String name, Path report) {
    return parseLong(requiredAttribute(element, name, report), name, report);
  }

  private static long parseLong(String value, String field, Path report) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException error) {
      throw new ChangedCoverageValidator.ValidationError("cannot parse JaCoCo report: "
          + report + " (non-numeric " + field + ": " + value + ")", error);
    }
  }

  /** SHA-256 of a file's content, streamed. */
  static String sha256(Path file) {
    return hash(file, "SHA-256");
  }

  private static String hash(Path file, String algorithm) {
    try {
      MessageDigest digest = MessageDigest.getInstance(algorithm);
      try (InputStream stream = Files.newInputStream(file)) {
        byte[] block = new byte[1024 * 1024];
        int read;
        while ((read = stream.read(block)) != -1) {
          digest.update(block, 0, read);
        }
      }
      return hex(digest.digest());
    } catch (IOException | NoSuchAlgorithmException error) {
      throw new ChangedCoverageValidator.ValidationError("cannot read " + file + ": "
          + error.getMessage(), error);
    }
  }

  static String hex(byte[] digest) {
    StringBuilder out = new StringBuilder();
    for (byte value : digest) {
      out.append(String.format(java.util.Locale.ROOT, "%02x", value));
    }
    return out.toString();
  }
}
