package com.projectlightpath.analysis.java;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import javax.lang.model.element.Element;

/**
 * The member-scoped javac diagnostics machinery (issue #76, extracted
 * from {@code JavacSourceSymbolIndex} per the PMD GodClass measurement
 * — behavior identical, placement documented in the commit): the
 * member source-position recording and the line-span join over the
 * recorded error rows.
 */
final class JavacMemberDiagnostics {
  private JavacMemberDiagnostics() {}

  /**
   * The member's source line span: synthetic members have no tree
   * (null position); an implicit constructor's end position is
   * NOPOSITION (a point span at its start); a non-positive start is
   * treated as unknown (defensive — the #76 spike never observed one).
   */
  @SuppressWarnings("removal")
  static JavacSourceSymbolIndex.MemberPosition position(
      Trees trees, Element element, CompilationUnitTree unit) {
    Tree tree = trees.getTree(element);
    if (tree == null) {
      return null;
    }
    SourcePositions positions = trees.getSourcePositions();
    long start = positions.getStartPosition(unit, tree);
    long end = positions.getEndPosition(unit, tree);
    if (start < 0) {
      return null;
    }
    long startLine = unit.getLineMap().getLineNumber(start);
    if (startLine < 1) {
      return null;
    }
    return end < 0
        ? JavacSourceSymbolIndex.MemberPosition.point(startLine)
        : new JavacSourceSymbolIndex.MemberPosition(
            startLine, unit.getLineMap().getLineNumber(end));
  }

  /**
   * The member-scoped javac error codes: the codes of the rows whose
   * line lies within the member's inclusive span, deduped by code at
   * join time (the record-time rows keep every (line, code) pair). A
   * row with a NON-POSITIVE line (NOPOSITION) joins every member-level
   * lookup for the file — never silently dropped. An UNKNOWN member
   * position (null, or a non-positive line) falls back to the
   * file-level set: the conservative #62 behavior.
   */
  static List<String> memberCodes(
      Map<Path, List<JavacSourceSymbolIndex.ErrorCodeRow>> errorLinesByFile,
      Map<Path, List<String>> errorCodesByFile,
      Path sourceFile,
      JavacSourceSymbolIndex.MemberInfo member) {
    if (sourceFile == null) {
      return List.of();
    }
    Path normalized = sourceFile.toAbsolutePath().normalize();
    // A null member is the unknown-input arm of the same contract as an
    // unknown position: the conservative file-level set, never a
    // silent under-report (no caller passes null today — defensive
    // symmetry, review round 1, finding 5).
    JavacSourceSymbolIndex.MemberPosition position = member == null
        ? null : member.position();
    if (position == null || position.startLine() < 1) {
      return errorCodesByFile.getOrDefault(normalized, List.of());
    }
    List<JavacSourceSymbolIndex.ErrorCodeRow> rows =
        errorLinesByFile.getOrDefault(normalized, List.of());
    if (rows.isEmpty()) {
      return List.of();
    }
    LinkedHashSet<String> codes = new LinkedHashSet<>();
    for (JavacSourceSymbolIndex.ErrorCodeRow row : rows) {
      if (row.line() < 1
          || (row.line() >= position.startLine() && row.line() <= position.endLine())) {
        codes.add(row.code());
      }
    }
    return List.copyOf(codes);
  }

  /** Deep-copies the per-file error-row lists (no live aliases escape). */
  static Map<Path, List<JavacSourceSymbolIndex.ErrorCodeRow>> copyOfRows(
      Map<Path, List<JavacSourceSymbolIndex.ErrorCodeRow>> rows) {
    Map<Path, List<JavacSourceSymbolIndex.ErrorCodeRow>> result = new LinkedHashMap<>();
    for (Map.Entry<Path, List<JavacSourceSymbolIndex.ErrorCodeRow>> entry
        : rows.entrySet()) {
      result.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Map.copyOf(result);
  }
}
