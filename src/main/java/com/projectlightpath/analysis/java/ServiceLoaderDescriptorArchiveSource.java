package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Path;
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
      List<? extends ZipEntry> entries = zip.stream().toList();
      Set<String> names = new HashSet<>();
      entries.stream()
          .filter(entry -> !entry.isDirectory())
          .filter(entry -> entry.getName().startsWith(
              ServiceLoaderDescriptorSource.SERVICES_DIRECTORY + "/"))
          .filter(entry -> names.add(entry.getName()))
          .sorted(java.util.Comparator.comparing(ZipEntry::getName))
          .forEach(entry -> {
            if (!state.hasDecompressedBudget()) {
              state.diagnostic(
                  "archive_decompression_limit", normalized.toString(),
                  "archive decompression budget exhausted before all entries were read");
            } else {
              addDescriptor(zip, normalized, entry, descriptors, state);
            }
          });
      ZipEntry moduleEntry = entries.stream()
          .filter(entry -> !entry.isDirectory()
              && entry.getName().equals(ServiceLoaderDescriptorSource.MODULE_INFO))
          .findFirst()
          .orElse(null);
      if (moduleEntry != null) {
        String modulePath = normalized + "!/" + moduleEntry.getName();
        if (!state.hasDecompressedBudget()) {
          state.diagnostic(
              "archive_decompression_limit", modulePath,
              "module-info.class could not be read after the archive decompression budget was exhausted");
        } else {
          byte[] bytes = readEntry(zip, moduleEntry, state, modulePath);
          if (bytes != null) {
            ServiceLoaderDescriptorSource.addModuleBytes(bytes, modulePath, descriptors, state);
          }
        }
      }
      if (ServiceLoaderDescriptorNestedArchiveSource.isWar(normalized)) {
        ServiceLoaderDescriptorNestedArchiveSource.diagnoseSkippedEntries(
            entries, normalized, state);
      }
    } catch (ZipException error) {
      state.diagnostic("invalid_archive", normalized.toString(),
          ServiceLoaderDescriptorSource.message(error));
    } catch (IOException error) {
      state.diagnostic("archive_read_error", normalized.toString(),
          ServiceLoaderDescriptorSource.message(error));
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

  private static byte[] readEntry(
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

}
