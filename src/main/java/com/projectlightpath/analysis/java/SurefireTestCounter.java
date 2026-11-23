package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/**
 * Surefire test-count gate (#45 step 1, ported from
 * spike/scripts/check_test_count.py).
 *
 * <p>The adapter suite runs in three surefire executions (default, a large-archive
 * execution, and a sootup execution), each with its own reports directory. {@code mvn
 * test} prints one "Tests run" line per execution and neither line is the gate total,
 * so this gate sums the report files and enforces the recorded baseline.
 *
 * <p>Intentional divergence from the Python original: the Python script printed only
 * the first error and stopped; this gate prints every error line (an empty large lane
 * AND a sub-baseline total are both reported). Stdout and exit codes are identical.
 */
public final class SurefireTestCounter {
  /**
   * Python-port artifact: the original script's no-argument default. The Makefile
   * always passes the real recorded floor (534); this default exists only for direct
   * invocation and is pinned exactly by the no-argument boundary cell.
   */
  private static final int DEFAULT_MINIMUM = 302;

  private SurefireTestCounter() {}

  /**
   * Sums the tests attribute over every TEST-*.xml in the directory.
   *
   * @param directory surefire reports directory; missing counts as zero
   * @return total tests recorded in the reports
   * @throws IllegalStateException if a report exists but cannot be parsed or has no
   *     numeric tests attribute — a malformed report must fail loudly, naming the
   *     file, instead of silently undercounting
   */
  static long count(Path directory) {
    if (!Files.isDirectory(directory)) {
      return 0;
    }
    long total = 0;
    List<Path> reports = new ArrayList<>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "TEST-*.xml")) {
      entries.forEach(reports::add);
    } catch (IOException error) {
      throw new IllegalStateException("cannot list surefire reports: " + directory, error);
    }
    reports.sort(Path::compareTo);
    for (Path report : reports) {
      total += testsAttribute(report);
    }
    return total;
  }

  /** Summary lines reporting every lane and the baseline. */
  static List<String> summaryLines(long defaultCount, long largeCount, long sootupCount,
      long minimum) {
    return List.of("surefire tests: default=" + defaultCount + " large=" + largeCount
        + " sootup=" + sootupCount + " total=" + (defaultCount + largeCount + sootupCount)
        + " (recorded baseline " + minimum + ")");
  }

  /**
   * Error lines for empty lanes or a total below the baseline; an empty list means the
   * gate passes.
   */
  static List<String> verifyLines(long defaultCount, long largeCount, long sootupCount,
      long minimum) {
    List<String> errors = new ArrayList<>();
    if (largeCount < 1) {
      errors.add("ERROR: large-archive execution ran no tests");
    }
    if (sootupCount < 1) {
      errors.add("ERROR: sootup execution ran no tests");
    }
    if (defaultCount + largeCount + sootupCount < minimum) {
      errors.add("ERROR: test count decreased below the recorded baseline");
    }
    return errors;
  }

  /** Maps error lines to a process exit code: 0 clean, 1 any error. */
  static int exitCode(List<String> errorLines) {
    return errorLines.isEmpty() ? 0 : 1;
  }

  /**
   * Counts the three surefire lanes and enforces the baseline.
   *
   * @param args optionally one argument: the recorded baseline; a non-numeric argument
   *     is an error line, not a crash
   */
  public static void main(String[] args) {
    System.exit(run(args, Path.of("target")));
  }

  /**
   * Counts the three surefire lanes and enforces the baseline, returning the exit code.
   *
   * @param args optionally one argument: the recorded baseline (defaults to
   *     {@value #DEFAULT_MINIMUM}); a non-numeric argument produces an error line
   * @param base directory holding the three surefire report directories
   * @return 0 when the gate passes, 1 otherwise
   */
  static int run(String[] args, Path base) {
    long minimum = DEFAULT_MINIMUM;
    List<String> errors = new ArrayList<>();
    if (args.length > 0) {
      try {
        minimum = Long.parseLong(args[0]);
      } catch (NumberFormatException error) {
        errors.add("ERROR: baseline argument is not a number: " + args[0]);
      }
    }
    long defaultCount = count(base.resolve("surefire-reports"));
    long largeCount = count(base.resolve("surefire-reports-large"));
    long sootupCount = count(base.resolve("surefire-reports-sootup"));
    summaryLines(defaultCount, largeCount, sootupCount, minimum)
        .forEach(System.out::println);
    errors.addAll(verifyLines(defaultCount, largeCount, sootupCount, minimum));
    errors.forEach(System.err::println);
    return exitCode(errors);
  }

  private static long testsAttribute(Path report) {
    try {
      Document document = secureDocumentBuilder().parse(report.toFile());
      Element root = document.getDocumentElement();
      return Long.parseLong(root.getAttribute("tests"));
    } catch (IOException | ParserConfigurationException | SAXException
        | NumberFormatException error) {
      throw new IllegalStateException("cannot parse surefire report: " + report, error);
    }
  }

  /**
   * Hardened factory matching the repo convention (see the java-lint plugin's
   * secureDocumentBuilderFactory): surefire reports are files on disk this gate does
   * not author, so doctypes and external entities are disabled.
   */
  private static DocumentBuilder secureDocumentBuilder()
      throws ParserConfigurationException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    return factory.newDocumentBuilder();
  }
}
