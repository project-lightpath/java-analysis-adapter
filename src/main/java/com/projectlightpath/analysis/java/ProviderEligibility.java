package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source-visible instantiation check for a ServiceLoader provider.
 *
 * <p>ServiceLoader requires a public no-argument constructor and a concrete
 * class; interfaces, abstract classes and types whose only declared
 * constructors take parameters cannot be instantiated. The check abstains only
 * on positive evidence and returns true when the provider source is not
 * selected.
 */
final class ProviderEligibility {
  private ProviderEligibility() {}

  /** True unless the selected source proves the provider is not instantiable. */
  static boolean eligible(SourceMetadataScanner scanner, String owner) {
    return scanner.sourceFile(owner)
        .map(file -> eligibleInFile(file, owner))
        .orElse(true);
  }

  private static boolean eligibleInFile(
      SourceMetadataScanner.SourceFile file, String owner) {
    String simpleName = owner.substring(owner.lastIndexOf('.') + 1);
    String source = ServiceLoaderSourceMasker.mask(file.structuralSource());
    for (SourceMetadataScannerState.TypeInfo type : SourceMetadataScanner.typeDeclarations(file)) {
      if (!type.name().equals(simpleName)
          || !SourceMetadataScanner.typeContaining(file, type.start()).equals(owner)) {
        continue;
      }
      if (type.kind().equals("interface")
          || header(source, type.start()).contains("abstract")) {
        return false;
      }
      List<String> constructors = constructors(source, type, simpleName);
      return constructors.isEmpty() || constructors.stream().anyMatch(String::isBlank);
    }
    return true;
  }

  private static String header(String source, int typeStart) {
    int boundary = typeStart - 1;
    while (boundary >= 0 && ";{}".indexOf(source.charAt(boundary)) < 0) {
      boundary--;
    }
    return source.substring(boundary + 1, typeStart);
  }

  private static List<String> constructors(
      String source, SourceMetadataScannerState.TypeInfo type, String simpleName) {
    String body = source.substring(type.start(), type.end() + 1);
    Matcher matcher = Pattern.compile(
        "(?:^|[;{}])\\s*(?:(?:public|protected|private)\\s+)?"
            + Pattern.quote(simpleName) + "\\s*\\(([^)]*)\\)")
        .matcher(body);
    List<String> parameters = new ArrayList<>();
    while (matcher.find()) {
      parameters.add(matcher.group(1).trim());
    }
    return parameters;
  }
}
