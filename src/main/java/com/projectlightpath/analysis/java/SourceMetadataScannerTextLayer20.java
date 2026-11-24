package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerTextLayer20 extends SourceMetadataScannerTextLayer19 {

  SourceMetadataScannerTextLayer20(AnalysisContext context) {
    super(context);
  }



  /** Parses method or constructor parameters and their qualifier annotations. */
  static List<Parameter> parameters(String source) {
    return parameters(null, source);
  }

  /** Parses injection parameters with framework-specific qualifier resolution. */
static List<Parameter> parameters(
      AnalysisContext.Framework framework, SourceFile file, String source) {
    return framework == AnalysisContext.Framework.SPRING_BOOT
        ? parameters(file, source)
        : parameters(source);
  }


  List<String> declaredOwnerCandidates(
      String normalized, SourceFile sourceFile, int offset) {
    return normalized.contains(".")
        ? declaredTypeCandidates(normalized, sourceFile, offset)
        : samePackageTypeCandidates(normalized, sourceFile);
  }
}
