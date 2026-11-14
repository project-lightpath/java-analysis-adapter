package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
          .forEach(entry -> addDescriptor(zip, normalized, entry, descriptors, state));
      if (ServiceLoaderDescriptorNestedArchiveSource.isWar(normalized)) {
        ServiceLoaderDescriptorNestedArchiveSource.diagnoseSkippedEntries(
            entries, normalized, state);
      }
    } catch (ZipException error) {
      state.diagnostic("invalid_archive", normalized.toString(), message(error));
    } catch (IOException error) {
      state.diagnostic("archive_read_error", normalized.toString(), message(error));
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
      byte[] bytes = input.readAllBytes();
      ServiceLoaderDescriptorSource.ProviderEntries parsed =
          ServiceLoaderDescriptorSource.providers(bytes);
      descriptors.add(new ServiceLoaderDescriptorScanner.Descriptor(
          serviceType,
          parsed.providers(),
          path,
          sha256(bytes),
          "meta_inf_services",
          parsed.malformedEntries()));
    } catch (IOException error) {
      state.diagnostic("archive_entry_read_error", path, message(error));
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder result = new StringBuilder(digest.length * 2);
      for (byte value : digest) {
        result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
        result.append(Character.forDigit(value & 0x0f, 16));
      }
      return result.toString();
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  private static String message(Exception error) {
    return error.getMessage() == null || error.getMessage().isBlank()
        ? error.getClass().getSimpleName() : error.getMessage();
  }
}
