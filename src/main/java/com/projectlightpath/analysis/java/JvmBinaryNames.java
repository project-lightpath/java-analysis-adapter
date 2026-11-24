package com.projectlightpath.analysis.java;

import java.util.Optional;

/**
 * Behavior-neutral RED stub for the canonical→binary name seam (issue #60).
 * The real implementation resolves binary names through the javac index, the
 * file-set top-level declarations, and the segment boundary rule; this stub
 * passes the canonical spelling through unchanged so the RED commit changes
 * no production behavior.
 */
final class JvmBinaryNames {
  private JvmBinaryNames() {}

  /** Returns the binary name of one resolved canonical type spelling. */
  static Optional<String> binaryName(SourceMetadataScanner scanner, String canonical) {
    return Optional.ofNullable(canonical);
  }
}
