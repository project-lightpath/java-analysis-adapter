package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerReflectionSupport extends SourceMetadataScannerReflectionLayer06 {

  SourceMetadataScannerReflectionSupport(AnalysisContext context) {
    super(context);
  }
}
