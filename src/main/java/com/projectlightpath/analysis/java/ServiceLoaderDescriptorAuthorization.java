package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/** Authorizes descriptor paths against the selected analysis inputs. */
final class ServiceLoaderDescriptorAuthorization {
  private ServiceLoaderDescriptorAuthorization() {}

  /**
   * Linear binary class-name validation. Replaces a nested-repetition Pattern
   * whose per-segment recursion stack-overflowed on deeply dotted names
   * (CWE-674); the same semantics are evaluated without backtracking.
   */
  static boolean isBinaryClassName(String value) {
    for (String segment : value.split("\\.", -1)) {
      if (segment.isEmpty()) {
        return false;
      }
      int[] codePoints = segment.codePoints().toArray();
      if (!Character.isJavaIdentifierStart(codePoints[0])) {
        return false;
      }
      for (int index = 1; index < codePoints.length; index++) {
        if (!Character.isJavaIdentifierPart(codePoints[index])) {
          return false;
        }
      }
    }
    return !value.isEmpty();
  }

  /** Anchors before appending derived components; canonical paths are authorization only. */
  static boolean derivedDirectory(
      Path anchor, String relative, ServiceLoaderDescriptorSource.ScanState state) {
    Path candidate = anchor.resolve(relative);
    if (Files.notExists(candidate, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    try {
      Path expected = anchor.toRealPath().resolve(relative);
      if (!Files.isSymbolicLink(candidate) && candidate.toRealPath().equals(expected)) {
        return true;
      }
      state.diagnostic("descriptor_path_outside_context", candidate.toString(),
          "derived metadata directory escapes its selected input anchor or is a symbolic link");
    } catch (IOException | UnsupportedOperationException | SecurityException error) {
      state.diagnostic("descriptor_directory_read_error", candidate.toString(),
          ServiceLoaderDescriptorSource.message(error));
    }
    return false;
  }

  static boolean readableDirectory(Path directory, ServiceLoaderDescriptorSource.ScanState state) {
    if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      return true;
    }
    if (!Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
      state.diagnostic("descriptor_directory_read_error", directory.toString(),
          "selected metadata directory is not a non-symbolic directory");
    }
    return false;
  }

  static boolean serviceFileCandidate(Path path, ServiceLoaderDescriptorSource.ScanState state) {
    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      return true;
    }
    if (path.getFileName() != null
        && isBinaryClassName(path.getFileName().toString())) {
      state.diagnostic("descriptor_read_error", path.toString(),
          "service descriptor is not a non-symbolic regular file");
    }
    return false;
  }

  /**
   * Authorizes descriptor paths against the selected analysis inputs.
   *
   * <p>Issue #69 decision record: an explicitly selected OBSERVED metadata
   * path (the {@code explicitlySelected} fallback below) OVERRIDES
   * root-scope exclusion BY DESIGN. The owner's rationale: when a CVE lands
   * in a test library, the analysis must be able to answer "is this
   * reachable from production code?" — which requires SEEING the selected
   * location, not blindness. Exclusion (#68) governs what is PUBLISHED as
   * production facts; explicit selection governs what can be SEEN for
   * investigation, and everything seen carries its provenance label
   * ({@code descriptorProvenance} always, plus
   * {@code descriptorRootScope} when the path falls under a declared
   * root, on the descriptor evidence) — the label is what makes this
   * override never-silent.
   */
  static boolean authorizedPath(
      AnalysisContext context, Path path, List<Path> dependencyEntries) {
    Path normalized = path.toAbsolutePath().normalize();
    if (under(normalized, context.applicationClasses())) {
      return true;
    }
    for (AnalysisContext.SourceRoot root : context.includedSourceRoots()) {
      if (under(normalized, root.path())) {
        return true;
      }
    }
    for (Path dependency : dependencyEntries) {
      if (under(normalized, dependency)) {
        return true;
      }
    }
    return ServiceLoaderDescriptorProvenance.explicitlySelected(context, normalized);
  }

  static boolean sameRealPath(Path left, Path right) {
    try {
      return left.toRealPath().equals(right.toRealPath());
    } catch (IOException | SecurityException error) {
      return false;
    }
  }

  static boolean under(Path path, Path root) {
    try {
      Path realPath = path.toRealPath();
      Path realRoot = root.toRealPath();
      return Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS)
          ? realPath.startsWith(realRoot)
          : realPath.equals(realRoot);
    } catch (IOException | SecurityException error) {
      return false;
    }
  }
}
