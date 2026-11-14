package com.projectlightpath.analysis.java;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;

/** Records WAR metadata locations that are not scanned by this classpath reader. */
final class ServiceLoaderDescriptorNestedArchiveSource {
  private ServiceLoaderDescriptorNestedArchiveSource() {}

  static void diagnoseSkippedEntries(
      List<? extends ZipEntry> entries,
      Path war,
      ServiceLoaderDescriptorSource.ScanState state) {
    for (ZipEntry entry : entries) {
      if (entry.isDirectory()) {
        continue;
      }
      String name = entry.getName();
      if (containsSkippedMetadata(name)) {
        state.diagnostic(
            "war_nested_service_metadata_skipped",
            war + "!/" + name,
            "WAR-contained ServiceLoader metadata was not scanned");
      }
    }
  }

  static boolean isWar(Path archive) {
    return archive.getFileName() != null
        && archive.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".war");
  }

  private static boolean containsSkippedMetadata(String name) {
    return name.startsWith(ServiceLoaderDescriptorSource.WAR_CLASSES_DIRECTORY
        + ServiceLoaderDescriptorSource.SERVICES_DIRECTORY + "/")
        || name.equals(ServiceLoaderDescriptorSource.WAR_CLASSES_DIRECTORY
            + "module-info.class")
        || (name.startsWith(ServiceLoaderDescriptorSource.WAR_LIB_DIRECTORY)
            && nestedArchive(name));
  }

  private static boolean nestedArchive(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    return lower.endsWith(".jar") || lower.endsWith(".zip") || lower.endsWith(".war");
  }
}
