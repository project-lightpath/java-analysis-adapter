package com.projectlightpath.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** FLPATH-5124 spill and external-sort behavior. */
class ServiceLoaderDescriptorSpillTest {
  @TempDir Path temp;

  @Test
  void externalSortPreservesOrderAndCleansUp() throws IOException {
    Path root = Files.createDirectory(temp.resolve("sort"));
    ServiceLoaderDescriptorArchiveConfig config =
        ServiceLoaderDescriptorArchiveConfig.defaults()
            .withTempRoot(root)
            .withSortMemoryBytes(1)
            .withMergeRunBytes(1)
            .withMaxSpillBytes(1024 * 1024);
    List<String> names = new ArrayList<>();
    try (ServiceLoaderDescriptorSpill spill = new ServiceLoaderDescriptorSpill(config)) {
      ServiceLoaderDescriptorSpill.Sort sort = spill.new Sort(
          Comparator.comparing(ServiceLoaderDescriptorZipEntry::name));
      for (String name : List.of("c", "a", "b", "e", "d")) {
        sort.add(entry(name));
      }
      sort.finish();
      ServiceLoaderDescriptorZipEntry item;
      while ((item = sort.next()) != null) {
        names.add(item.name());
      }
      sort.close();
      assertTrue(spill.peakTapeFiles() >= 1);
    }
    assertEquals(List.of("a", "b", "c", "d", "e"), names);
    assertTrue(empty(root), Files.list(root).toList().toString());
  }

  @Test
  void spillQuotaIsEnforcedBeforeWrite() throws IOException {
    Path root = Files.createDirectory(temp.resolve("quota"));
    ServiceLoaderDescriptorArchiveConfig config =
        ServiceLoaderDescriptorArchiveConfig.defaults()
            .withTempRoot(root)
            .withSortMemoryBytes(1)
            .withMaxSpillBytes(1);
    try (ServiceLoaderDescriptorSpill spill = new ServiceLoaderDescriptorSpill(config)) {
      ServiceLoaderDescriptorSpill.Sort sort = spill.new Sort(
          Comparator.comparing(ServiceLoaderDescriptorZipEntry::name));
      assertThrows(
          ServiceLoaderDescriptorSpill.SpillLimitException.class,
          () -> sort.add(entry("a")));
      sort.close();
    }
    assertTrue(empty(root), Files.list(root).toList().toString());
  }

  @Test
  void interruptedSortFailsFast() throws IOException {
    Path root = Files.createDirectory(temp.resolve("interrupt"));
    ServiceLoaderDescriptorArchiveConfig config =
        ServiceLoaderDescriptorArchiveConfig.defaults().withTempRoot(root);
    try (ServiceLoaderDescriptorSpill spill = new ServiceLoaderDescriptorSpill(config)) {
      ServiceLoaderDescriptorSpill.Sort sort = spill.new Sort(
          Comparator.comparing(ServiceLoaderDescriptorZipEntry::name));
      Thread.currentThread().interrupt();
      try {
        assertThrows(InterruptedIOException.class, () -> sort.add(entry("a")));
      } finally {
        Thread.interrupted();
      }
      sort.close();
    }
    assertTrue(empty(root), Files.list(root).toList().toString());
  }

  private static ServiceLoaderDescriptorZipEntry entry(String name) {
    return new ServiceLoaderDescriptorZipEntry(
        name, 0, 8, 0, 0, 0, 0, 0, 0, 0, "");
  }

  private static boolean empty(Path directory) throws IOException {
    try (var paths = Files.list(directory)) {
      return paths.findAny().isEmpty();
    }
  }
}
