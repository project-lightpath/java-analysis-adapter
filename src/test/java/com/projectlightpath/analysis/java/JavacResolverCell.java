package com.projectlightpath.analysis.java;

import java.util.List;
import java.util.Map;

/** One compiler-vs-textual accuracy cell for the javac resolver spike. */
record JavacResolverCell(
    String id,
    Family family,
    String siteFile,
    String owner,
    String method,
    int release,
    Map<String, String> sources,
    List<Map<String, String>> extraUnits,
    boolean baselineAvailable,
    List<ExpectedSite> expectedSites) {

  JavacResolverCell {
    sources = Map.copyOf(sources);
    extraUnits = List.copyOf(extraUnits);
    expectedSites = List.copyOf(expectedSites);
  }

  enum Family { PRECEDENCE, STATIC_OWNER, DOLLAR_OWNER, INCOMPLETE }

  enum Expectation { DEFINITE, POSSIBLE, AMBIGUOUS, MISSING_INPUT, SHADOWED }

  /** One expected load site, in source-offset order. */
  record ExpectedSite(Expectation expectation, String serviceType) {}
}
