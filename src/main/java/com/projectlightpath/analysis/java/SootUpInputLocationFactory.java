package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
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
  private static final Map<AnalysisContext, DependencyResolution> DEPENDENCY_CACHE =
      new WeakHashMap<>();

  private SootUpInputLocationFactory() {}

  static Inputs createView(AnalysisContext context) {
    List<AnalysisInputLocation> locations = new ArrayList<>();
    locations.add(new JavaClassPathAnalysisInputLocation(
        context.applicationClasses().toString(), SourceType.Application));
    Map<AnalysisInputLocation, Path> dependencyEntries = new IdentityHashMap<>();
    Set<Path> dependencyPaths = new LinkedHashSet<>();
    for (Path dependency : dependencyResolution(context).entries()) {
      addDependencyLocation(dependency, locations, dependencyEntries, dependencyPaths);
    }
    if (!context.dependencyClasspath().isEmpty() && dependencyEntries.isEmpty()) {
      throw new IllegalArgumentException(
          "dependency classpath contains no valid entries for AnalysisContext " + context.id());
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

  /** Returns cached, bounded dependency entries and wildcard-limit diagnostics. */
  static DependencyResolution dependencyResolution(AnalysisContext context) {
    synchronized (DEPENDENCY_CACHE) {
      return DEPENDENCY_CACHE.computeIfAbsent(context,
          SootUpInputLocationFactory::resolveDependencies);
    }
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
    return Files.exists(path) && (Files.isDirectory(path) || PathUtils.isArchive(path));
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
    for (Path dependency : context.dependencyClasspath()) {
      Expansion expansion = expandDependencyPath(dependency);
      if (expansion.truncated()) {
        truncatedWildcards.add(dependency.toAbsolutePath().normalize());
      }
      for (Path expanded : expansion.entries()) {
        Path normalized = expanded.toAbsolutePath().normalize();
        if (isValidInputLocation(normalized)) {
          resolved.add(normalized);
        }
      }
    }
    return new DependencyResolution(List.copyOf(resolved), List.copyOf(truncatedWildcards));
  }

  private static Expansion expandDependencyPath(Path dependency) {
    Path normalized = dependency.toAbsolutePath().normalize();
    String value = normalized.toString();
    if (!value.endsWith("*")) {
      return new Expansion(List.of(normalized), false);
    }

    int wildcardIndex = value.indexOf('*');
    Path directory = normalized.getFileSystem().getPath(value.substring(0, wildcardIndex));
    if (!Files.isDirectory(directory)) {
      return new Expansion(List.of(), false);
    }
    List<Path> entries = new ArrayList<>();
    boolean truncated = false;
    try (var paths = Files.newDirectoryStream(directory)) {
      for (Path path : paths) {
        String fileName = path.getFileName().toString();
        if (Files.isRegularFile(path)
            && (fileName.endsWith(".jar") || fileName.endsWith(".JAR"))) {
          if (entries.size() >= MAX_DEPENDENCY_ENTRIES) {
            truncated = true;
            break;
          }
          entries.add(path.toAbsolutePath().normalize());
        }
      }
    } catch (IOException error) {
      throw new IllegalArgumentException(
          "cannot expand dependency classpath wildcard: " + dependency, error);
    }
    entries.sort(Comparator.comparing(Path::toString));
    return new Expansion(List.copyOf(entries), truncated);
  }

  record Inputs(JavaView view, Map<AnalysisInputLocation, Path> dependencyEntries) {}

  record DependencyResolution(List<Path> entries, List<Path> truncatedWildcards) {
    DependencyResolution {
      entries = List.copyOf(entries);
      truncatedWildcards = List.copyOf(truncatedWildcards);
    }
  }

  private record Expansion(List<Path> entries, boolean truncated) {}
}
