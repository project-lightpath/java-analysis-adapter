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
  private SootUpInputLocationFactory() {}

  static Inputs createView(AnalysisContext context) {
    List<AnalysisInputLocation> locations = new ArrayList<>();
    locations.add(new JavaClassPathAnalysisInputLocation(
        context.applicationClasses().toString(), SourceType.Application));
    Map<AnalysisInputLocation, Path> dependencyEntries = new IdentityHashMap<>();
    List<Path> resolvedDependencies = resolvedDependencyEntries(context);
    for (Path dependency : resolvedDependencies) {
      addDependencyLocation(dependency, locations, dependencyEntries);
    }
    if (!context.dependencyClasspath().isEmpty() && resolvedDependencies.isEmpty()) {
      throw new IllegalArgumentException(
          "dependency classpath contains no valid entries for AnalysisContext " + context.id());
    }
    locations.add(new DefaultRuntimeAnalysisInputLocation(SourceType.Library));
    JavaView view = new JavaView(locations);
    mapContainedWarLocations(view, dependencyEntries);
    return new Inputs(view, dependencyEntries);
  }

  /** Returns the concrete, validated dependency inputs selected by SootUp. */
  static List<Path> resolvedDependencyEntries(AnalysisContext context) {
    Set<Path> resolved = new LinkedHashSet<>();
    for (Path dependency : context.dependencyClasspath()) {
      for (Path expanded : expandDependencyPath(dependency)) {
        Path normalized = expanded.toAbsolutePath().normalize();
        if (isValidInputLocation(normalized)) {
          resolved.add(normalized);
        }
      }
    }
    return List.copyOf(resolved);
  }

  private static void addDependencyLocation(
      Path dependency,
      List<AnalysisInputLocation> locations,
      Map<AnalysisInputLocation, Path> dependencyEntries) {
    AnalysisInputLocation location = PathBasedAnalysisInputLocation.create(
        dependency,
        SourceType.Library,
        BytecodeBodyInterceptors.Default.getBodyInterceptors());
    locations.add(location);
    dependencyEntries.put(location, dependency);
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
  private static List<Path> expandDependencyPath(Path dependency) {
    Path normalized = dependency.toAbsolutePath().normalize();
    String value = normalized.toString();
    if (!value.endsWith("*")) {
      return List.of(normalized);
    }

    int wildcardIndex = value.indexOf('*');
    Path directory = normalized.getFileSystem().getPath(value.substring(0, wildcardIndex));
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    List<Path> entries = new ArrayList<>();
    try (var paths = Files.newDirectoryStream(directory)) {
      for (Path path : paths) {
        String fileName = path.getFileName().toString();
        if (Files.isRegularFile(path)
            && (fileName.endsWith(".jar") || fileName.endsWith(".JAR"))) {
          entries.add(path.toAbsolutePath().normalize());
        }
      }
    } catch (IOException error) {
      throw new IllegalArgumentException(
          "cannot expand dependency classpath wildcard: " + dependency, error);
    }
    entries.sort(Comparator.comparing(Path::toString));
    return List.copyOf(entries);
  }

  record Inputs(JavaView view, Map<AnalysisInputLocation, Path> dependencyEntries) {}
}
