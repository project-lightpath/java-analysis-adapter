package com.projectlightpath.analysis.java;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * extraction.
 *
 * <p>Retention: the value deliberately holds no {@link SourceFile} and no
 * {@link Path}. It only holds derived data, so it never keeps its own weak key
 * reachable and the entry becomes collectible together with the scan's
 * {@code SourceFile}s. Callers that need file identity (source text, line
 * numbers, offset lookups) pass their {@code SourceFile} to the accessor.
 *
 * <p>Scope: the map is static, keyed by path, and correct under parallel scans
 * (fill is one synchronized get-validate-put; lazy slots synchronize on the
 * holder). Two scans reading the same path with <em>different</em> content
 * replace each other's entry, so they recompute rather than serve stale data.
 * Construction runs inside the cache lock, so first touch of different files
 * serializes; that keeps fill atomic and is free for the single-threaded
 * scanner.
 *
 * <p>Both the raw and the structural source identity must match for reuse.
 * Extraction copies text and line numbers out of the raw source, so a
 * structural match alone is not enough.
 */
final class SourceMetadataScannerFileExtracts {
  private static final Map<Path, SourceMetadataScannerFileExtracts> CACHE =
      Collections.synchronizedMap(new WeakHashMap<>());

  /** Field declaration captured once per file for visibility lookups. */
  record FieldCandidate(
      int fieldOffset,
      String sharedType,
      String declarators,
      String declaration,
      String fieldOwner) {}

  /** Field declaration captured once per file for initializer attribution. */
  record InitializerField(
      int matchStart, int equalsOffset, int end, int line, String name, String declaration) {}

  private final String rawSource;
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
  private List<InitializerField> initializerFields;
  private List<FieldCandidate> fieldCandidates;
  private List<String> importedNames;
  private Set<String> onDemandImportPackages;

  private SourceMetadataScannerFileExtracts(SourceFile file) {
    this.rawSource = file.source();
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
      if (cached != null
          && cached.structuralSource == file.structuralSource()
          && cached.rawSource == file.source()) {
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
      typeDeclarations = SourceMetadataScannerTextLayer08.extractTypeDeclarations(literalMaskedSource);
    }
    return typeDeclarations;
  }

  synchronized List<MethodInfo> methods(SourceFile file) {
    if (methods == null) {
      methods = SourceMetadataScannerTextLayer13.extractMethods(file, declarationMaskedSource());
    }
    return methods;
  }

  synchronized List<MethodInfo> constructors(SourceFile file) {
    if (constructors == null) {
      constructors =
          SourceMetadataScannerTextLayer11.extractConstructors(file, declarationMaskedSource());
    }
    return constructors;
  }

  synchronized List<AnonymousType> anonymousTypes() {
    if (anonymousTypes == null) {
      anonymousTypes = SourceMetadataScannerTextLayer15.extractAnonymousTypes(literalMaskedSource);
    }
    return anonymousTypes;
  }

  synchronized List<InitializerField> initializerFields(SourceFile file) {
    if (initializerFields == null) {
      initializerFields =
          SourceMetadataScannerTextLayer15.extractInitializerFields(file, literalMaskedSource);
    }
    return initializerFields;
  }

  synchronized List<FieldCandidate> fieldCandidates(SourceFile file) {
    if (fieldCandidates == null) {
      fieldCandidates =
          SourceMetadataScannerReflectionLayer02.extractFieldCandidates(file, literalMaskedSource);
    }
    return fieldCandidates;
  }

  synchronized List<String> importedNames() {
    if (importedNames == null) {
      importedNames = SourceMetadataScannerTextLayer06.extractImportedNames(literalMaskedSource);
    }
    return importedNames;
  }

  synchronized Set<String> onDemandImportPackages() {
    if (onDemandImportPackages == null) {
      onDemandImportPackages =
          SourceMetadataScannerTextLayer06.extractOnDemandImportPackages(literalMaskedSource);
    }
    return onDemandImportPackages;
  }
}
