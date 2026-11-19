package com.projectlightpath.analysis.java;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.projectlightpath.analysis.java.SourceMetadataScannerState.AnonymousType;
import com.projectlightpath.analysis.java.SourceMetadataScannerState.MethodInfo;
import com.projectlightpath.analysis.java.SourceMetadataScannerState.SourceFile;
import com.projectlightpath.analysis.java.SourceMetadataScannerState.TypeInfo;

/**
 * Per-file extracts reused across one analysis run.
 *
 * <p>The reflection and framework scans ask the same questions about the same
 * file many times. Recomputing a whole-file literal mask, a brace-depth scan or
 * a field extent per question made extraction quadratic in the number of call
 * sites (issue #11). This holder computes each extract once per file.
 *
 * <p>Freshness: an entry is reused only while its {@code structuralSource} is
 * the <em>same string instance</em> as the caller's. {@code read} builds that
 * string once per scan and {@code stripComments} always returns a new string, so
 * one run reuses entries and a re-read of the same path cannot reuse stale
 * extraction. The map is weakly keyed so entries are collectible once no equal
 * path is reachable.
 */
final class SourceMetadataScannerFileExtracts {
  private static final Map<Path, SourceMetadataScannerFileExtracts> CACHE =
      Collections.synchronizedMap(new WeakHashMap<>());

  private final SourceFile file;
  private final String structuralSource;
  private final String literalMaskedSource;
  private final int[] bracePositions;
  private final int[] braceDepths;
  private final Map<Integer, Integer> fieldInitializerEnds = new HashMap<>();
  private String declarationMaskedSource;
  private List<TypeInfo> typeDeclarations;
  private List<MethodInfo> methods;
  private List<MethodInfo> constructors;
  private List<AnonymousType> anonymousTypes;

  private SourceMetadataScannerFileExtracts(SourceFile file) {
    this.file = file;
    this.structuralSource = file.structuralSource();
    this.literalMaskedSource = JavaSourceText.maskStringLiterals(structuralSource);
    List<Integer> positions = new ArrayList<>();
    List<Integer> depths = new ArrayList<>();
    int depth = 0;
    for (int index = 0; index < literalMaskedSource.length(); index++) {
      char character = literalMaskedSource.charAt(index);
      if (character == '{') {
        depth++;
      } else if (character == '}') {
        depth = Math.max(0, depth - 1);
      } else {
        continue;
      }
      positions.add(index);
      depths.add(depth);
    }
    bracePositions = positions.stream().mapToInt(Integer::intValue).toArray();
    braceDepths = depths.stream().mapToInt(Integer::intValue).toArray();
  }

  /** Returns the extracts for one file, reusing the entry for this run. */
  static SourceMetadataScannerFileExtracts of(SourceFile file) {
    synchronized (CACHE) {
      SourceMetadataScannerFileExtracts cached = CACHE.get(file.path());
      if (cached != null && cached.structuralSource == file.structuralSource()) {
        return cached;
      }
      SourceMetadataScannerFileExtracts created = new SourceMetadataScannerFileExtracts(file);
      CACHE.put(file.path(), created);
      return created;
    }
  }

  /** Whole-file structural source with string, char and text-block contents blanked. */
  String literalMaskedSource() {
    return literalMaskedSource;
  }

  /** Whole-file source with annotation arguments and literal contents blanked. */
  synchronized String declarationMaskedSource() {
    if (declarationMaskedSource == null) {
      declarationMaskedSource = JavaSourceText.maskStringLiterals(
          SourceMetadataScannerTextLayer07.maskAnnotationArguments(structuralSource));
    }
    return declarationMaskedSource;
  }

  /** Brace depth over the literal-masked source before {@code index}. */
  int braceDepthAt(int index) {
    int limit = Math.min(index, literalMaskedSource.length());
    int low = 0;
    int high = bracePositions.length - 1;
    int selected = -1;
    while (low <= high) {
      int middle = (low + high) >>> 1;
      if (bracePositions[middle] < limit) {
        selected = middle;
        low = middle + 1;
      } else {
        high = middle - 1;
      }
    }
    return selected < 0 ? 0 : braceDepths[selected];
  }

  /** Memoized extent of the field initializer starting at {@code start}. */
  synchronized int fieldInitializerEnd(int start) {
    Integer cached = fieldInitializerEnds.get(start);
    if (cached != null) {
      return cached;
    }
    int end = SourceMetadataScannerTextLayer03.fieldInitializerEnd(literalMaskedSource, start);
    fieldInitializerEnds.put(start, end);
    return end;
  }

  synchronized List<TypeInfo> typeDeclarations() {
    if (typeDeclarations == null) {
      typeDeclarations = SourceMetadataScannerTextLayer08.extractTypeDeclarations(file);
    }
    return typeDeclarations;
  }

  synchronized List<MethodInfo> methods() {
    if (methods == null) {
      methods = SourceMetadataScannerTextLayer13.extractMethods(file, declarationMaskedSource());
    }
    return methods;
  }

  synchronized List<MethodInfo> constructors() {
    if (constructors == null) {
      constructors = SourceMetadataScannerTextLayer11.extractConstructors(file);
    }
    return constructors;
  }

  synchronized List<AnonymousType> anonymousTypes() {
    if (anonymousTypes == null) {
      anonymousTypes = SourceMetadataScannerTextLayer15.extractAnonymousTypes(file);
    }
    return anonymousTypes;
  }
}
