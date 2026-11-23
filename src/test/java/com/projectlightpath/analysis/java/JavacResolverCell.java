package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.Comparator;
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
    BaselineMode baselineMode,
    List<TextualProbe> textualProbes,
    List<ExpectedSite> expectedSites) {

  JavacResolverCell {
    sources = Map.copyOf(sources);
    extraUnits = List.copyOf(extraUnits);
    textualProbes = List.copyOf(textualProbes);
    expectedSites = List.copyOf(expectedSites);
  }

  /**
   * Pins each expected site to the javac site at the same ordinal. javac is the
   * authority; the textual baseline is then scored at those source positions.
   */
  JavacResolverCell alignedTo(JavacResolverResult result) {
    List<Object> sites = new ArrayList<>();
    sites.addAll(result.facts());
    sites.addAll(result.abstentions());
    sites.sort(Comparator.comparingInt(JavacResolverCell::offset));
    List<ExpectedSite> aligned = new ArrayList<>();
    for (int index = 0; index < expectedSites.size(); index++) {
      ExpectedSite site = expectedSites.get(index);
      int offset = site.offset() >= 0
          ? site.offset()
          : (index < sites.size() ? offset(sites.get(index)) : -1);
      aligned.add(new ExpectedSite(site.expectation(), site.serviceType(), offset));
    }
    return new JavacResolverCell(
        id, family, siteFile, owner, method, release, sources, extraUnits,
        baselineMode, textualProbes, aligned);
  }

  private static int offset(Object site) {
    if (site instanceof JavacResolverFact fact) {
      return fact.offset();
    }
    return ((JavacResolverAbstention) site).offset();
  }

  enum Family {
    PRECEDENCE, STATIC_OWNER, DOLLAR_OWNER, INCOMPLETE,
    ANNOTATION_NAME, ANNOTATION_ARGUMENT, TYPE_USE, TYPE_SHAPE, REFLECTION_SOURCE_BOUND
  }

  /**
   * How the textual baseline is measured for one cell. FROZEN reads the
   * pre-removal JSON capture; LIVE_TEXTUAL runs the production textual
   * heuristics now; NONE records that no textual measurement exists.
   */
  enum BaselineMode { FROZEN, LIVE_TEXTUAL, NONE }

  boolean baselineAvailable() {
    return baselineMode != BaselineMode.NONE;
  }

  /**
   * One live-textual probe: the raw declaration text handed to the production
   * heuristic, in the same ordinal order as the cell's javac facts.
   */
  record TextualProbe(Kind kind, String raw) {

    /** Which production textual heuristic the raw text is measured with. */
    enum Kind { PARAMETER_TYPE, ANNOTATION_NAME_TOKEN, ANNOTATION_ARGUMENT_TEXT }
  }

  enum Expectation {
    DEFINITE, POSSIBLE, AMBIGUOUS, MISSING_INPUT, SHADOWED, NO_FACT,
    NON_LITERAL_FORNAME, UNRESOLVED_FORNAME
  }

  /** One expected load site. An offset of -1 is filled from the javac result. */
  record ExpectedSite(Expectation expectation, String serviceType, int offset) {
    ExpectedSite(Expectation expectation, String serviceType) {
      this(expectation, serviceType, -1);
    }
  }
}
