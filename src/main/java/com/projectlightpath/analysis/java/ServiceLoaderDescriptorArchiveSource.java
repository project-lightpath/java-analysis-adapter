package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** Reads root-level ServiceLoader metadata from a dependency archive. */
final class ServiceLoaderDescriptorArchiveSource {
  private ServiceLoaderDescriptorArchiveSource() {}

  static void scan(
      Path archive,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      ServiceLoaderDescriptorSource.ScanState state) {
    Path normalized = archive.toAbsolutePath().normalize();
    try (ZipFile zip = new ZipFile(normalized.toFile())) {
      ArchiveEntries entries = enumerate(zip, normalized, state);
      readDescriptors(zip, normalized, entries.descriptors(), descriptors, state);
      readModule(zip, normalized, entries.module(), descriptors, state);
    } catch (ZipException error) {
      state.diagnostic("invalid_archive", normalized.toString(),
          ServiceLoaderDescriptorSource.message(error));
    } catch (IOException error) {
      state.diagnostic("archive_read_error", normalized.toString(),
          ServiceLoaderDescriptorSource.message(error));
    }
  }

  private static ArchiveEntries enumerate(
      ZipFile zip, Path archive, ServiceLoaderDescriptorSource.ScanState state) {
    List<ZipEntry> descriptors = new ArrayList<>();
    ZipEntry module = null;
    Set<String> names = new HashSet<>();
    Enumeration<? extends ZipEntry> entries = zip.entries();
    while (entries.hasMoreElements()) {
      ZipEntry entry = entries.nextElement();
      String entryPath = archive + "!/" + entry.getName();
      if (!state.consumeEnumerated(entryPath)) {
        break;
      }
      if (entry.isDirectory()) {
        continue;
      }
      if (ServiceLoaderDescriptorNestedArchiveSource.isWar(archive)) {
        ServiceLoaderDescriptorNestedArchiveSource.diagnoseSkippedEntry(
            entry.getName(), archive, state);
      }
      if (entry.getName().startsWith(
              ServiceLoaderDescriptorSource.SERVICES_DIRECTORY + "/")
          && names.add(entry.getName())) {
        descriptors.add(entry);
      }
      if (entry.getName().equals(ServiceLoaderDescriptorSource.MODULE_INFO) && module == null) {
        module = entry;
      }
    }
    descriptors.sort(java.util.Comparator.comparing(ZipEntry::getName));
    return new ArchiveEntries(descriptors, module);
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

  private static void readModule(
      ZipFile zip,
      Path archive,
      ZipEntry module,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      ServiceLoaderDescriptorSource.ScanState state) {
    if (module == null) {
      return;
    }
    String modulePath = archive + "!/" + module.getName();
    if (!state.hasDecompressedBudget()) {
      state.diagnostic(
          "archive_decompression_limit", modulePath,
          "module-info.class could not be read after the archive decompression budget was exhausted");
      return;
    }
    byte[] bytes = readEntry(zip, module, state, modulePath);
    if (bytes != null) {
      ServiceLoaderDescriptorSource.addModuleBytes(bytes, modulePath, descriptors, state);
    }
  }

  private static void addDescriptor(
      ZipFile zip,
      Path archive,
      ZipEntry entry,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      ServiceLoaderDescriptorSource.ScanState state) {
    String serviceType = entry.getName().substring(
        ServiceLoaderDescriptorSource.SERVICES_DIRECTORY.length() + 1);
    if (!ServiceLoaderDescriptorSource.BINARY_CLASS_NAME.matcher(serviceType).matches()) {
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

  private record ArchiveEntries(List<ZipEntry> descriptors, ZipEntry module) {}

}
