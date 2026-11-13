package com.projectlightpath.analysis.java;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** Reads bounded ServiceLoader metadata from dependency archives and files. */
final class ServiceLoaderDescriptorArchiveSource {
  private ServiceLoaderDescriptorArchiveSource() {}

  static void scan(
      Path archive,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      ServiceLoaderDescriptorSource.ScanState state) {
    Path normalized = archive.toAbsolutePath().normalize();
    if (!state.reserveArchive(normalized)) {
      return;
    }
    try {
      if (Files.size(normalized) > ServiceLoaderDescriptorSource.MAX_ARCHIVE_BYTES) {
        state.diagnostic(
            "archive_too_large", normalized.toString(),
            "archive exceeds " + ServiceLoaderDescriptorSource.MAX_ARCHIVE_BYTES + " bytes");
        return;
      }
    } catch (IOException error) {
      state.diagnostic("archive_read_error", normalized.toString(), message(error));
      return;
    }
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      List<? extends ZipEntry> entries;
      try (Stream<? extends ZipEntry> stream = zip.stream()) {
        entries = stream.limit(ServiceLoaderDescriptorSource.MAX_ARCHIVE_ENTRIES + 1L).toList();
      }
      if (entries.size() > ServiceLoaderDescriptorSource.MAX_ARCHIVE_ENTRIES) {
        state.diagnostic(
            "archive_entry_limit", normalized.toString(),
            "archive exceeds " + ServiceLoaderDescriptorSource.MAX_ARCHIVE_ENTRIES + " entries");
        entries = entries.subList(0, ServiceLoaderDescriptorSource.MAX_ARCHIVE_ENTRIES);
      }
      Set<String> entryNames = new HashSet<>();
      List<? extends ZipEntry> serviceEntries = entries.stream()
          .filter(entry -> !entry.isDirectory())
          .filter(entry -> entry.getName().startsWith(
              ServiceLoaderDescriptorSource.SERVICES_DIRECTORY + "/"))
          .filter(entry -> entryNames.add(entry.getName()))
          .sorted(java.util.Comparator.comparing(ZipEntry::getName))
          .toList();
      for (ZipEntry entry : serviceEntries) {
        byte[] bytes = readEntry(zip, entry, state, normalized + "!/" + entry.getName());
        if (bytes == null) {
          continue;
        }
        ServiceLoaderDescriptorSource.ProviderEntries parsed =
            ServiceLoaderDescriptorSource.providers(bytes);
        String serviceType = entry.getName().substring(
            ServiceLoaderDescriptorSource.SERVICES_DIRECTORY.length() + 1);
        if (ServiceLoaderDescriptorSource.BINARY_CLASS_NAME.matcher(serviceType).matches()) {
          descriptors.add(new ServiceLoaderDescriptorScanner.Descriptor(
              serviceType,
              parsed.providers(),
              archive + "!/" + entry.getName(),
              sha256(bytes),
              "meta_inf_services",
              parsed.malformedEntries()));
        }
      }
      entries.stream()
          .filter(entry -> !entry.isDirectory()
              && entry.getName().equals(ServiceLoaderDescriptorSource.MODULE_INFO))
          .findFirst()
          .ifPresent(entry -> {
            byte[] bytes = readEntry(zip, entry, state, normalized + "!/" + entry.getName());
            if (bytes != null) {
              ServiceLoaderDescriptorSource.addModuleBytes(
                  bytes, normalized + "!/" + entry.getName(), descriptors, state);
            }
          });
    } catch (ZipException error) {
      state.diagnostic("invalid_archive", normalized.toString(), message(error));
    } catch (IOException error) {
      state.diagnostic("archive_read_error", normalized.toString(), message(error));
    }
  }

  static byte[] readFile(
      Path path,
      ServiceLoaderDescriptorSource.ScanState state,
      String tooLargeCode,
      String readErrorCode,
      long maximumBytes) {
    try {
      if (Files.size(path) > maximumBytes) {
        state.diagnostic(
            tooLargeCode, path.toString(), "file exceeds " + maximumBytes + " bytes");
        return null;
      }
      try (InputStream input = Files.newInputStream(path)) {
        return readBounded(input, maximumBytes, state, tooLargeCode, path.toString());
      }
    } catch (IOException error) {
      state.diagnostic(readErrorCode, path.toString(), message(error));
      return null;
    }
  }

  private static byte[] readEntry(
      ZipFile zip,
      ZipEntry entry,
      ServiceLoaderDescriptorSource.ScanState state,
      String path) {
    long declaredSize = entry.getSize();
    long compressedSize = entry.getCompressedSize();
    if (declaredSize > ServiceLoaderDescriptorSource.MAX_ARCHIVE_ENTRY_BYTES
        || compressedSize > ServiceLoaderDescriptorSource.MAX_ARCHIVE_ENTRY_BYTES) {
      state.diagnostic(
          "archive_entry_too_large", path,
          "archive entry exceeds " + ServiceLoaderDescriptorSource.MAX_ARCHIVE_ENTRY_BYTES
              + " bytes");
      return null;
    }
    try (InputStream input = zip.getInputStream(entry)) {
      byte[] bytes = readBounded(
          input, ServiceLoaderDescriptorSource.MAX_ARCHIVE_ENTRY_BYTES, state,
          "archive_decompression_limit", path);
      if (bytes == null || !state.reserveDecompressed(bytes.length, path)) {
        return null;
      }
      return bytes;
    } catch (IOException error) {
      state.diagnostic("archive_entry_read_error", path, message(error));
      return null;
    }
  }

  private static byte[] readBounded(
      InputStream input,
      long maximumBytes,
      ServiceLoaderDescriptorSource.ScanState state,
      String limitCode,
      String path) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream(
        (int) Math.min(maximumBytes, 8_192));
    byte[] buffer = new byte[8_192];
    long total = 0;
    int count;
    while ((count = input.read(buffer)) >= 0) {
      total += count;
      if (total > maximumBytes) {
        state.diagnostic(limitCode, path, "read exceeds " + maximumBytes + " bytes");
        return null;
      }
      output.write(buffer, 0, count);
    }
    return output.toByteArray();
  }

  static String message(Exception error) {
    return error.getMessage() == null || error.getMessage().isBlank()
        ? error.getClass().getSimpleName() : error.getMessage();
  }

  static String sha256(byte[] bytes) {
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
}
