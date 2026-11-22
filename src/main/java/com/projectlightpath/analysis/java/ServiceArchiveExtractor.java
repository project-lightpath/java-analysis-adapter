package com.projectlightpath.analysis.java;

import java.nio.file.Path;
import java.util.List;

/**
 * Behavior-neutral compile stub for RED_02: safe source-archive extraction
 * (issue #42 WI-2). Every method throws; the GREEN_02 commit replaces this
 * with the bounded extractor built on the #34 seekable ZIP reader.
 */
final class ServiceArchiveExtractor {
  private ServiceArchiveExtractor() {}

  static final class Extraction implements AutoCloseable {
    Path root() {
      throw new UnsupportedOperationException("not implemented");
    }

    List<Path> archiveRoots() {
      throw new UnsupportedOperationException("not implemented");
    }

    List<String> declaredPaths() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void close() {
      throw new UnsupportedOperationException("not implemented");
    }
  }

  static Extraction extract(List<Path> archives) {
    throw new UnsupportedOperationException("not implemented");
  }

  static Extraction extract(List<Path> archives, long maxExtractedBytes, Path tempRootParent) {
    throw new UnsupportedOperationException("not implemented");
  }
}
