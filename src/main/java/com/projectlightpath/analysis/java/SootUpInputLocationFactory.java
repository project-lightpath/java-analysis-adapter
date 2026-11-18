package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.inputlocation.FileType;
import sootup.core.model.SourceType;
import sootup.core.util.PathUtils;
import sootup.interceptors.BytecodeBodyInterceptors;
import sootup.java.bytecode.frontend.inputlocation.DefaultRuntimeAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation;
import sootup.java.core.views.JavaView;

/** Builds SootUp input locations while retaining exact dependency ownership. */
final class SootUpInputLocationFactory {
  static final int MAX_DEPENDENCY_ENTRIES = 1024;

  private SootUpInputLocationFactory() {}

  static Inputs createView(AnalysisContext context) {
    return createView(context, dependencyResolution(context));
  }

  static Inputs createView(AnalysisContext context, DependencyResolution resolution) {
    List<AnalysisInputLocation> locations = new ArrayList<>();
    locations.add(new JavaClassPathAnalysisInputLocation(
        context.applicationClasses().toString(), SourceType.Application));
    Map<AnalysisInputLocation, Path> dependencyEntries = new IdentityHashMap<>();
    Set<Path> dependencyPaths = new LinkedHashSet<>();
    if (!resolution.truncatedWildcards().isEmpty()) {
      throw new IllegalArgumentException(
          "dependency_enumeration_limit: dependency wildcard expansion exceeds "
              + MAX_DEPENDENCY_ENTRIES + " entries: " + resolution.truncatedWildcards());
    }
    for (Path dependency : resolution.entries()) {
      addDependencyLocation(dependency, locations, dependencyEntries, dependencyPaths);
    }
    if (!context.dependencyClasspath().isEmpty() && dependencyEntries.isEmpty()) {
      throw new IllegalArgumentException(
          "dependency classpath contains no valid entries for AnalysisContext " + context.id()
              + ": " + resolution.skipped());
    }
    locations.add(new DefaultRuntimeAnalysisInputLocation(SourceType.Library));
    JavaView view = new JavaView(locations);
    mapContainedWarLocations(view, dependencyEntries);
    return new Inputs(view, dependencyEntries);
  }

  /** Returns the concrete dependency entries selected from the analysis classpath. */
  static List<Path> resolvedDependencyEntries(AnalysisContext context) {
    return dependencyResolution(context).entries();
  }

  /** Resolves a fresh immutable membership snapshot for one invocation. */
  static DependencyResolution dependencyResolution(AnalysisContext context) {
    return resolveDependencies(context);
  }

  private static void addDependencyLocation(
      Path dependency,
      List<AnalysisInputLocation> locations,
      Map<AnalysisInputLocation, Path> dependencyEntries,
      Set<Path> dependencyPaths) {
    Path normalized = dependency.toAbsolutePath().normalize();
    if (!isValidInputLocation(normalized) || !dependencyPaths.add(normalized)) {
      return;
    }
    AnalysisInputLocation location = PathBasedAnalysisInputLocation.create(
        normalized,
        SourceType.Library,
        BytecodeBodyInterceptors.Default.getBodyInterceptors());
    locations.add(location);
    dependencyEntries.put(location, normalized);
  }

  private static boolean isValidInputLocation(Path path) {
    return Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        && (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
            || (!Files.isSymbolicLink(path) && PathUtils.isArchive(path)));
  }

  /** Associates WAR-contained class sources with the outer WAR dependency entry. */
  private static void mapContainedWarLocations(
      JavaView view, Map<AnalysisInputLocation, Path> dependencyEntries) {
    List<Map.Entry<AnalysisInputLocation, Path>> wars = dependencyEntries.entrySet().stream()
        .filter(entry -> PathUtils.hasExtension(entry.getValue(), FileType.WAR))
        .toList();
    for (Map.Entry<AnalysisInputLocation, Path> war : wars) {
      try (var sources = war.getKey().getClassSources(view)) {
        sources.forEach(source -> dependencyEntries.put(
            source.getAnalysisInputLocation(), war.getValue()));
      }
    }
  }

  /** Expands SootUp-compatible trailing wildcards into concrete JAR entries. */
  private static DependencyResolution resolveDependencies(AnalysisContext context) {
    Set<Path> resolved = new LinkedHashSet<>();
    List<Path> truncatedWildcards = new ArrayList<>();
    List<Skipped> skipped = new ArrayList<>();
    Set<String> reported = new LinkedHashSet<>();
    for (Path dependency : context.dependencyClasspath()) {
      Expansion expansion = expandDependencyPath(dependency, skipped, reported);
      if (expansion.truncated()) {
        truncatedWildcards.add(dependency.toAbsolutePath().normalize());
        continue;
      }
      for (Path expanded : expansion.entries()) {
        Path normalized = expanded.toAbsolutePath().normalize();
        if (isValidInputLocation(normalized)) {
          resolved.add(normalized);
        } else {
          report(skipped, reported, "dependency_classpath_entry_skipped", normalized,
              "dependency classpath entry is not a usable input location");
        }
      }
    }
    List<Skipped> ordered = skipped.stream()
        .sorted(java.util.Comparator
            .comparing((Skipped skip) -> skip.path().toString())
            .thenComparing(Skipped::code))
        .toList();
    return new DependencyResolution(
        List.copyOf(resolved), List.copyOf(truncatedWildcards), ordered);
  }

  private static void report(
      List<Skipped> skipped, Set<String> reported, String code, Path path, String detail) {
    if (reported.add(code + "|" + path)) {
      skipped.add(new Skipped(code, path, detail));
    }
  }

  private static Expansion expandDependencyPath(
      Path dependency, List<Skipped> skipped, Set<String> reported) {
    Path normalized = dependency.toAbsolutePath().normalize();
    String value = normalized.toString();
    if (!value.endsWith("*")) {
      return new Expansion(List.of(normalized), false);
    }

    // The wildcard must be the whole last element: deps/* expands deps, while
    // deps/*/* targets the literal directory "deps/*" and deps/foo* is a
    // literal path, not a prefix expansion of deps.
    Path name = normalized.getFileName();
    if (name == null || !"*".equals(name.toString())) {
      return new Expansion(List.of(normalized), false);
    }
    Path directory = normalized.getParent();
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      report(skipped, reported, "dependency_wildcard_directory_unavailable", normalized,
          "dependency wildcard directory does not exist or is not a directory");
      return new Expansion(List.of(), false);
    }
    NavigableSet<Path> entries = new TreeSet<>();
    boolean truncated = false;
    int enumeratedEntries = 0;
    try (var paths = Files.newDirectoryStream(directory)) {
      for (Path path : paths) {
        if (enumeratedEntries >= MAX_DEPENDENCY_ENTRIES) {
          truncated = true;
          break;
        }
        enumeratedEntries++;
        String fileName = path.getFileName().toString();
        boolean jarNamed = fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".jar");
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && jarNamed) {
          ServiceLoaderDescriptorSource.retainSmallest(
              entries, path, MAX_DEPENDENCY_ENTRIES);
        } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            && PathUtils.isArchive(path)) {
          report(skipped, reported, "dependency_wildcard_archive_skipped",
              path.toAbsolutePath().normalize(),
              "supported archive is not expanded by the wildcard classpath");
        } else if (Files.isSymbolicLink(path) && jarNamed) {
          report(skipped, reported, "dependency_classpath_entry_skipped",
              path.toAbsolutePath().normalize(), "symbolic link is not followed");
        }
      }
    } catch (IOException error) {
      throw new IllegalArgumentException(
          "cannot expand dependency classpath wildcard: " + dependency, error);
    }
    return new Expansion(
        entries.stream().map(path -> path.toAbsolutePath().normalize()).toList(), truncated);
  }

  record Inputs(JavaView view, Map<AnalysisInputLocation, Path> dependencyEntries) {}

  record DependencyResolution(
      List<Path> entries,
      List<Path> truncatedWildcards,
      List<Skipped> skipped) {
    DependencyResolution {
      entries = List.copyOf(entries);
      truncatedWildcards = List.copyOf(truncatedWildcards);
      skipped = List.copyOf(skipped);
    }

    /** Backward-compatible shape for callers that only read entries. */
    DependencyResolution(List<Path> entries, List<Path> truncatedWildcards) {
      this(entries, truncatedWildcards, List.of());
    }
  }

  /** One classpath input the resolution dropped, with the reason. */
  record Skipped(String code, Path path, String detail) {}

  private record Expansion(List<Path> entries, boolean truncated) {}
}
