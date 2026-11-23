package com.projectlightpath.analysis.java;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live textual baseline for the annotation/type-shape families: runs the
 * production textual heuristics that are still in the adapter today and
 * emits one fact per probe, anchored to the javac facts' offsets in ordinal
 * order so {@link Evaluation} pairs both paths with the same expected sites.
 *
 * <p>Annotation names and arguments are parsed with the production
 * machinery, not a reimplementation: the token pattern replicates
 * {@code JavacFrameworkQualifierFamilies.TEXTUAL_ANNOTATION_NAME} (the
 * production capture site; {@code SourceMetadataScannerTextLayer02
 * .parameterType} strips with the same character class) and argument text
 * comes from {@code SourceMetadataScanner.parseAnnotation}, whose delimiter
 * matching is string-literal aware. The replicated pattern must stay in sync
 * with both production sites (documented residual in PLAN-26).
 */
final class TextualShapeBaseline {
  /**
   * The exact annotation-name pattern the production textual families use.
   * Keep in sync with JavacFrameworkQualifierFamilies.TEXTUAL_ANNOTATION_NAME
   * and SourceMetadataScannerTextLayer02.parameterType.
   */
  private static final Pattern ANNOTATION_TOKEN = Pattern.compile("@([A-Za-z_][\\w.]*)");

  private TextualShapeBaseline() {}

  static JavacResolverResult run(JavacResolverCell cell, JavacResolverResult javacResult) {
    List<JavacResolverFact> anchors = javacResult.facts();
    if (cell.textualProbes().size() > anchors.size()) {
      // A probe without a javac anchor means an emitter silently dropped a
      // site the fixture promised; that must be loud, not truncated.
      throw new IllegalStateException(
          "cell " + cell.id() + " has " + cell.textualProbes().size()
              + " textual probes but only " + anchors.size() + " javac facts");
    }
    List<JavacResolverFact> facts = new ArrayList<>();
    for (int index = 0; index < cell.textualProbes().size(); index++) {
      JavacResolverFact anchor = anchors.get(index);
      JavacResolverCell.TextualProbe probe = cell.textualProbes().get(index);
      facts.add(new JavacResolverFact(
          cell.id(),
          anchor.path(),
          anchor.topLevel(),
          anchor.line(),
          anchor.column(),
          anchor.offset(),
          textualValue(probe),
          "textual",
          probe.kind().name(),
          "",
          "DEFINITE",
          "",
          List.of(),
          "",
          ""));
    }
    return new JavacResolverResult(cell.id(), facts, List.of());
  }

  private static String textualValue(JavacResolverCell.TextualProbe probe) {
    return switch (probe.kind()) {
      case PARAMETER_TYPE -> SourceMetadataScanner.parameterType(probe.raw());
      case ANNOTATION_NAME_TOKEN -> annotationToken(probe.raw());
      case ANNOTATION_ARGUMENT_TEXT -> argumentText(probe.raw());
    };
  }

  /**
   * The production token for the annotation at the head of the raw text, or
   * "" when the production pattern cannot match there ($ and non-ASCII
   * names). The matcher runs over comment-stripped, literal-masked text —
   * the same pre-processing the production capture site applies — and is
   * anchored at the first '@' so a head miss can never silently return a
   * token from an unrelated later site.
   */
  private static String annotationToken(String raw) {
    Matcher matcher = headMatcher(raw);
    if (matcher == null) {
      return "";
    }
    return matcher.group(1);
  }

  /** The production parser's argument text for the head annotation. */
  private static String argumentText(String raw) {
    String source = SourceMetadataScanner.stripComments(raw);
    Matcher matcher = headMatcher(raw);
    if (matcher == null) {
      return "";
    }
    return SourceMetadataScanner.parseAnnotation(source, matcher).arguments();
  }

  /**
   * A production-pattern matcher anchored at the head '@' of the
   * comment-stripped, literal-masked raw text, or null when the production
   * pattern cannot match there.
   */
  private static Matcher headMatcher(String raw) {
    String masked = SourceMetadataScanner.maskStringLiterals(
        SourceMetadataScanner.stripComments(raw));
    int at = masked.indexOf('@');
    if (at < 0) {
      return null;
    }
    Matcher matcher = ANNOTATION_TOKEN.matcher(masked);
    if (!matcher.find(at) || matcher.start() != at) {
      return null;
    }
    return matcher;
  }
}
