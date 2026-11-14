package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Responsibility layer for the source-backed Java metadata scanner. */
abstract class SourceMetadataScannerTextHeaderSupport extends SourceMetadataScannerState {

  SourceMetadataScannerTextHeaderSupport(AnalysisContext context) {
    super(context);
  }



static final class HeaderDepth {
    private int angle;
    private int parentheses;
    private int brackets;

    boolean consume(char character) {
      switch (character) {
        case '<' -> angle++;
        case '>' -> angle = Math.max(0, angle - 1);
        case '(' -> parentheses++;
        case ')' -> parentheses = Math.max(0, parentheses - 1);
        case '[' -> brackets++;
        case ']' -> brackets = Math.max(0, brackets - 1);
        case '{' -> { return angle == 0 && parentheses == 0 && brackets == 0; }
        default -> { }
      }
      return false;
    }

    boolean topLevel() {
      return angle == 0 && parentheses == 0 && brackets == 0;
    }
  }
}
