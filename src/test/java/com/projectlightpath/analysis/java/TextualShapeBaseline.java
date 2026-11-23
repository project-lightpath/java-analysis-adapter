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
 * <p>The annotation token pattern is replicated from
 * {@code SourceMetadataScannerTextLayer02.parameterType}; the two sites must
 * stay in sync (documented residual in PLAN-26).
 */
final class TextualShapeBaseline {
  /**
   * The exact annotation-token regex used by the production textual heuristic.
   * Keep in sync with SourceMetadataScannerTextLayer02.parameterType.
   */
  private static final Pattern ANNOTATION_TOKEN = Pattern.compile("@[A-Za-z_][\\w.]*");

  private TextualShapeBaseline() {}

  static JavacResolverResult run(JavacResolverCell cell, JavacResolverResult javacResult) {
    List<JavacResolverFact> anchors = javacResult.facts();
    List<JavacResolverFact> facts = new ArrayList<>();
    for (int index = 0;
        index < cell.textualProbes().size() && index < anchors.size();
        index++) {
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

  private static String annotationToken(String raw) {
    Matcher matcher = ANNOTATION_TOKEN.matcher(raw);
    if (!matcher.find()) {
      return "";
    }
    return matcher.group().substring(1);
  }

  private static String argumentText(String raw) {
    int open = raw.indexOf('(');
    int close = raw.lastIndexOf(')');
    if (open < 0 || close <= open) {
      return "";
    }
    return raw.substring(open + 1, close).replaceAll("\\s+", "");
  }
}
