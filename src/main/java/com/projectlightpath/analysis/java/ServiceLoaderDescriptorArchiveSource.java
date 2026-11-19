package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** Reads root-level ServiceLoader metadata from a dependency archive. */
final class ServiceLoaderDescriptorArchiveSource {
  /**
   * Bounds the eager central-directory allocation performed by {@link ZipFile}
   * construction. A mitigation, not the cure: the durable fix for eager-CEN
   * indexing is FLPATH-5124 (see also FLPATH-5123).
   */
  static final long MAX_ARCHIVE_BYTES = 512L * 1024 * 1024;

  private ServiceLoaderDescriptorArchiveSource() {}

  /**
   * Scans one archive into {@code descriptors}, which must be mutable: a
   * retention-budget rollback removes the descriptors this archive contributed.
   */
  static void scan(
      Path archive,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      ServiceLoaderDescriptorSource.ScanState state) {
    Path normalized = archive.toAbsolutePath().normalize();
    try {
      long size = Files.size(normalized);
      if (size > MAX_ARCHIVE_BYTES) {
        state.diagnostic("archive_size_limit", normalized.toString(),
            "archive is " + size + " bytes, above the " + MAX_ARCHIVE_BYTES
                + " byte archive size limit");
        return;
      }
    } catch (IOException error) {
      state.diagnostic("archive_read_error", normalized.toString(),
          ServiceLoaderDescriptorSource.message(error));
      return;
    }
    int descriptorStart = descriptors.size();
    long retainedBefore = state.retainedBytes();
    boolean complete = false;
    try (ZipFile zip = new ZipFile(normalized.toFile())) {
      ArchiveEntries entries = enumerate(zip, normalized, state);
      readDescriptors(zip, normalized, entries.descriptors(), descriptors, state);
      complete = readModule(
          zip, normalized, entries.module(), entries.versionedModule(), descriptors, state);
    } catch (ZipException error) {
      state.diagnostic("invalid_archive", normalized.toString(),
          ServiceLoaderDescriptorSource.message(error));
    } catch (IOException error) {
      state.diagnostic("archive_read_error", normalized.toString(),
          ServiceLoaderDescriptorSource.message(error));
    } finally {
      if (!complete) {
        descriptors.subList(descriptorStart, descriptors.size()).clear();
        state.restoreRetained(retainedBefore);
      }
    }
  }

  private static ArchiveEntries enumerate(
      ZipFile zip, Path archive, ServiceLoaderDescriptorSource.ScanState state) {
    NavigableMap<String, ZipEntry> descriptors = new TreeMap<>();
    ZipEntry module = null;
    String versionedModule = null;
    boolean complete = true;
    long retainedBefore = state.retainedBytes();
    Enumeration<? extends ZipEntry> entries = zip.entries();
    while (entries.hasMoreElements()) {
      ZipEntry entry = entries.nextElement();
      String entryPath = archive + "!/" + entry.getName();
      long entryBytes = ServiceLoaderDescriptorBudget.centralDirectoryBytes(entry);
      if (!state.tryEnumerate(entryPath, entryBytes)) {
        complete = false;
        break;
      }
      if (entry.isDirectory()) {
        continue;
      }
      if (ServiceLoaderDescriptorNestedArchiveSource.isWar(archive)) {
        ServiceLoaderDescriptorNestedArchiveSource.diagnoseSkippedEntry(
            entry.getName(), archive, state);
      }
      if (!retainDescriptor(descriptors, entry, entryPath, state)) {
        complete = false;
        break;
      }
      if (entry.getName().equals(ServiceLoaderDescriptorSource.MODULE_INFO) && module == null) {
        module = entry;
      }
      if (versionedModule == null && ServiceLoaderDescriptorEntryNames.isVersionedModuleInfo(
          entry.getName())) {
        versionedModule = entry.getName();
      }
    }
    if (!complete) {
      state.restoreRetained(retainedBefore);
      return new ArchiveEntries(List.of(), null, null);
    }
    return new ArchiveEntries(List.copyOf(descriptors.values()), module, versionedModule);
  }

  private static boolean retainDescriptor(
      NavigableMap<String, ZipEntry> descriptors,
      ZipEntry entry,
      String path,
      ServiceLoaderDescriptorSource.ScanState state) {
    String prefix = ServiceLoaderDescriptorSource.SERVICES_DIRECTORY + "/";
    String name = entry.getName();
    if (!name.startsWith(prefix)
        || !ServiceLoaderDescriptorAuthorization.isBinaryClassName(
            name.substring(prefix.length()))) {
      return true;
    }
    if (descriptors.containsKey(name)) {
      return true;
    }
    if (!state.tryRetain(path)) {
      return false;
    }
    descriptors.put(name, entry);
    return true;
  }

  private static void readDescriptors(
      ZipFile zip,
      Path archive,
      List<ZipEntry> entries,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      ServiceLoaderDescriptorSource.ScanState state) {
    for (ZipEntry entry : entries) {
      if (!state.hasDecompressedBudget()) {
        state.diagnostic(
            "archive_decompression_limit", archive.toString(),
            "archive decompression budget exhausted before all entries were read");
        break;
      }
      addDescriptor(zip, archive, entry, descriptors, state);
    }
  }

  private static boolean readModule(
      ZipFile zip,
      Path archive,
      ZipEntry module,
      String versionedModule,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      ServiceLoaderDescriptorSource.ScanState state) {
    if (module == null) {
      if (versionedModule != null) {
        // Diagnostic-only variant: reading versioned descriptors with version
        // selection is a recorded follow-up. One per archive, name check only.
        state.diagnostic(
            "versioned_module_info_unsupported", archive.toString(), versionedModule);
      }
      return true;
    }
    String modulePath = archive + "!/" + module.getName();
    if (!state.hasDecompressedBudget()) {
      state.diagnostic(
          "archive_decompression_limit", modulePath,
          "module-info.class could not be read after the archive decompression budget was exhausted");
      return true;
    }
    byte[] bytes = readEntry(zip, module, state, modulePath);
    if (bytes == null) {
      return true;
    }
    return ServiceLoaderDescriptorSource.addModuleBytes(
        bytes, modulePath, descriptors, state);
  }

  private static void addDescriptor(
      ZipFile zip,
      Path archive,
      ZipEntry entry,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      ServiceLoaderDescriptorSource.ScanState state) {
    String serviceType = entry.getName().substring(
        ServiceLoaderDescriptorSource.SERVICES_DIRECTORY.length() + 1);
    if (!ServiceLoaderDescriptorAuthorization.isBinaryClassName(serviceType)) {
      return;
    }
    String path = archive + "!/" + entry.getName();
    try (InputStream input = zip.getInputStream(entry)) {
      byte[] bytes = readEntry(input, state, path);
      if (bytes == null) {
        return;
      }
      ServiceLoaderDescriptorSource.ProviderEntries parsed =
          ServiceLoaderDescriptorSource.providers(bytes);
      descriptors.add(new ServiceLoaderDescriptorScanner.Descriptor(
          serviceType,
          parsed.providers(),
          path,
          ServiceLoaderDescriptorSource.sha256(bytes),
          "meta_inf_services",
          parsed.malformedEntries()));
    } catch (IOException error) {
      state.diagnostic(
          "archive_entry_read_error", path, ServiceLoaderDescriptorSource.message(error));
    }
  }

  private static byte[] readEntry(
      ZipFile zip,
      ZipEntry entry,
      ServiceLoaderDescriptorSource.ScanState state,
      String path) {
    long remaining = state.remainingDecompressed();
    if (remaining <= 0) {
      return null;
    }
    try (InputStream input = zip.getInputStream(entry)) {
      return readEntry(input, state, path);
    } catch (IOException error) {
      state.diagnostic(
          "archive_entry_read_error", path, ServiceLoaderDescriptorSource.message(error));
      return null;
    }
  }

  static byte[] readEntry(
      InputStream input,
      ServiceLoaderDescriptorSource.ScanState state,
      String path) throws IOException {
    long maximumBytes = state.remainingDecompressed();
    ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
    byte[] buffer = new byte[8192];
    long total = 0;
    while (total < maximumBytes) {
      int requested = (int) Math.min(buffer.length, maximumBytes - total);
      int count = input.read(buffer, 0, requested);
      if (count < 0) {
        return output.toByteArray();
      }
      if (count == 0) {
        continue;
      }
      if (!state.consumeDecompressed(count, path)) {
        return null;
      }
      output.write(buffer, 0, count);
      total += count;
    }
    if (input.read() >= 0) {
      state.diagnostic(
          "archive_decompression_limit", path,
          "archive entry exceeds the available decompression budget");
      return null;
    }
    return output.toByteArray();
  }

  private record ArchiveEntries(List<ZipEntry> descriptors, ZipEntry module, String versionedModule) {}

}
