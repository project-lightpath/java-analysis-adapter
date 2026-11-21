package com.projectlightpath.analysis.java;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reads root-level ServiceLoader metadata from a dependency archive. */
final class ServiceLoaderDescriptorArchiveSource {
  /**
   * Bounds the archive size accepted by the adapter. The seekable reader bounds
   * central-directory memory independently; this limit protects the rest of the
   * analysis from unbounded archive inputs.
   */
  static final long MAX_ARCHIVE_BYTES = 512L * 1024 * 1024;

  private ServiceLoaderDescriptorArchiveSource() {}

  static void scan(
      Path archive,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      ServiceLoaderDescriptorSource.ScanState state) {
    scan(archive, descriptors, state, ServiceLoaderDescriptorArchiveConfig.defaults());
  }

  /**
   * Scans one archive into {@code descriptors}, which must be mutable: a
   * retention-budget rollback removes the descriptors this archive contributed.
   */
  static void scan(
      Path archive,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      ServiceLoaderDescriptorSource.ScanState state,
      ServiceLoaderDescriptorArchiveConfig config) {
    Path normalized = archive.toAbsolutePath().normalize();
    long size;
    try {
      size = Files.size(normalized);
    } catch (IOException error) {
      state.diagnostic(
          "archive_read_error", normalized.toString(), sanitized(error, config));
      return;
    }
    if (size > MAX_ARCHIVE_BYTES) {
      state.diagnostic("archive_size_limit", normalized.toString(),
          "archive is " + size + " bytes, above the " + MAX_ARCHIVE_BYTES
              + " byte archive size limit");
      return;
    }
    int descriptorStart = descriptors.size();
    ServiceLoaderDescriptorSource.ScanState.RetentionSnapshot retained =
        state.retentionSnapshot();
    boolean complete = false;
    ServiceLoaderDescriptorZipDirectory directory = null;
    ServiceLoaderDescriptorSpill spill = null;
    try {
      SeekableByteChannel channel = config.channelOpener().open(normalized);
      size = channel.size();
      if (size > MAX_ARCHIVE_BYTES) {
        channel.close();
        state.diagnostic("archive_size_limit", normalized.toString(),
            "archive is " + size + " bytes, above the " + MAX_ARCHIVE_BYTES
                + " byte archive size limit");
        return;
      }
      directory = new ServiceLoaderDescriptorZipDirectory(
          channel, size, config, state);
      spill = new ServiceLoaderDescriptorSpill(config);
      ArchiveEntries entries = enumerate(normalized, directory, spill, state, config);
      if (!entries.complete()) {
        return;
      }
      readDescriptors(
          directory, normalized, entries.descriptors(), descriptors, state, config);
      complete = readModule(
          directory, normalized, entries.module(), entries.versionedModule(),
          descriptors, state, config);
    } catch (ServiceLoaderDescriptorZipDirectory.ZipFormatException error) {
      state.diagnostic("invalid_archive", normalized.toString(), sanitized(error, config));
    } catch (ServiceLoaderDescriptorZipDirectory.RawIoLimitException error) {
      state.diagnostic(
          "archive_raw_io_limit", normalized.toString(), sanitized(error, config));
    } catch (ServiceLoaderDescriptorSpill.SpillLimitException error) {
      state.diagnostic(
          "archive_spill_limit", normalized.toString(), sanitized(error, config));
    } catch (ServiceLoaderDescriptorSpill.SpillException error) {
      state.diagnostic(
          "archive_spill_error", normalized.toString(), sanitized(error, config));
    } catch (InterruptedIOException | java.nio.channels.ClosedByInterruptException error) {
      state.markInterrupted();
      Thread.interrupted();
      state.diagnostic(
          "descriptor_scan_interrupted", normalized.toString(), sanitized(error, config));
    } catch (IOException error) {
      state.diagnostic(
          "archive_read_error", normalized.toString(), sanitized(error, config));
    } finally {
      if (!complete) {
        descriptors.subList(descriptorStart, descriptors.size()).clear();
        state.restoreRetention(retained);
      }
      close(directory, spill, normalized, state, config);
    }
  }

  private static void close(
      ServiceLoaderDescriptorZipDirectory directory,
      ServiceLoaderDescriptorSpill spill,
      Path archive,
      ServiceLoaderDescriptorSource.ScanState state,
      ServiceLoaderDescriptorArchiveConfig config) {
    if (directory != null) {
      try {
        directory.close();
      } catch (IOException error) {
        state.diagnostic(
            "archive_read_error", archive.toString(), sanitized(error, config));
      }
    }
    if (spill != null) {
      try {
        spill.close();
      } catch (IOException error) {
        state.diagnostic(
            "archive_spill_error", archive.toString(), sanitized(error, config));
      }
    }
  }

  private static ArchiveEntries enumerate(
      Path archive,
      ServiceLoaderDescriptorZipDirectory directory,
      ServiceLoaderDescriptorSpill spill,
      ServiceLoaderDescriptorSource.ScanState state,
      ServiceLoaderDescriptorArchiveConfig config) throws IOException {
    Set<String> retainedNames = new HashSet<>();
    List<ServiceLoaderDescriptorZipEntry> selected = new ArrayList<>();
    ServiceLoaderDescriptorZipEntry[] module = new ServiceLoaderDescriptorZipEntry[1];
    String[] versionedModule = new String[1];
    boolean[] complete = {true};
    ServiceLoaderDescriptorSpill.Sort selectedSort = spill.new Sort(
        Comparator.comparing(ServiceLoaderDescriptorZipEntry::name)
            .thenComparingLong(ServiceLoaderDescriptorZipEntry::local));
    ServiceLoaderDescriptorSpill.Sort rangeSort;
    try {
      rangeSort = spill.new Sort(
          Comparator.comparingLong(ServiceLoaderDescriptorZipEntry::local)
              .thenComparingLong(ServiceLoaderDescriptorZipEntry::end));
    } catch (IOException error) {
      closeSort(selectedSort, archive, state, config);
      throw error;
    }
    try {
      directory.walk(entry -> {
        String entryPath = archive + "!/" + entry.name();
        long entryBytes = ServiceLoaderDescriptorBudget.centralDirectoryBytes(
            entry.name(), entry.extraLength(), entry.comment());
        if (!state.tryEnumerate(entryPath, entryBytes)) {
          complete[0] = false;
          throw new StopWalk();
        }
        rangeSort.add(entry);
        if (entry.directory()) {
          return;
        }
        if (ServiceLoaderDescriptorNestedArchiveSource.isWar(archive)) {
          ServiceLoaderDescriptorNestedArchiveSource.diagnoseSkippedEntry(
              entry.name(), archive, state);
        }
        String prefix = ServiceLoaderDescriptorSource.SERVICES_DIRECTORY + "/";
        if (entry.name().startsWith(prefix)
            && ServiceLoaderDescriptorAuthorization.isBinaryClassName(
                entry.name().substring(prefix.length()))
            && !retainedNames.contains(entry.name())) {
          if (!state.tryRetain(entryPath)) {
            complete[0] = false;
            throw new StopWalk();
          }
          long nameBytes = ServiceLoaderDescriptorBudget.utf8Length(entry.name()) + 48;
          if (!state.tryRetainName(entryPath, nameBytes)) {
            state.releaseRetained();
            complete[0] = false;
            throw new StopWalk();
          }
          retainedNames.add(entry.name());
          selectedSort.add(entry.comment().isEmpty() ? entry : new ServiceLoaderDescriptorZipEntry(
              entry.name(), entry.flags(), entry.method(), entry.crc(),
              entry.compressed(), entry.length(), entry.local(), entry.data(),
              entry.end(), entry.extraLength(), ""));
        }
        if (entry.name().equals(ServiceLoaderDescriptorSource.MODULE_INFO)
            && module[0] == null) {
          module[0] = entry;
        }
        if (versionedModule[0] == null
            && ServiceLoaderDescriptorEntryNames.isVersionedModuleInfo(entry.name())) {
          versionedModule[0] = entry.name();
        }
      });
      if (!complete[0]) {
        return new ArchiveEntries(List.of(), null, null, false);
      }
      rangeSort.finish();
      ServiceLoaderDescriptorZipEntry previous = null;
      ServiceLoaderDescriptorZipEntry range;
      while ((range = rangeSort.next()) != null) {
        if (previous != null
            && !(previous.local() == range.local() && previous.end() == range.end())
            && previous.end() > range.local()) {
          throw new ServiceLoaderDescriptorZipDirectory.ZipFormatException(
              "referenced_entry_overlap");
        }
        previous = range;
      }
      selectedSort.finish();
      ServiceLoaderDescriptorZipEntry entry;
      while ((entry = selectedSort.next()) != null) {
        selected.add(entry);
      }
      return new ArchiveEntries(List.copyOf(selected), module[0], versionedModule[0], true);
    } catch (StopWalk stop) {
      return new ArchiveEntries(List.of(), null, null, false);
    } finally {
      closeSort(selectedSort, archive, state, config);
      closeSort(rangeSort, archive, state, config);
    }
  }

  private static void closeSort(
      ServiceLoaderDescriptorSpill.Sort sort,
      Path archive,
      ServiceLoaderDescriptorSource.ScanState state,
      ServiceLoaderDescriptorArchiveConfig config) {
    try {
      sort.close();
    } catch (IOException error) {
      state.diagnostic(
          "archive_spill_error", archive.toString(), sanitized(error, config));
    }
  }

  private static void readDescriptors(
      ServiceLoaderDescriptorZipDirectory directory,
      Path archive,
      List<ServiceLoaderDescriptorZipEntry> entries,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      ServiceLoaderDescriptorSource.ScanState state,
      ServiceLoaderDescriptorArchiveConfig config) throws IOException {
    int processed = 0;
    for (ServiceLoaderDescriptorZipEntry entry : entries) {
      if (!state.hasDecompressedBudget()) {
        state.diagnostic(
            "archive_decompression_limit", archive.toString(),
            "archive decompression budget exhausted before all entries were read");
        break;
      }
      int descriptorStart = descriptors.size();
      addDescriptor(directory, archive, entry, descriptors, state, config);
      if (descriptors.size() == descriptorStart) {
        release(state, entry);
      }
      processed++;
    }
    for (int index = processed; index < entries.size(); index++) {
      release(state, entries.get(index));
    }
  }

  private static void release(
      ServiceLoaderDescriptorSource.ScanState state,
      ServiceLoaderDescriptorZipEntry entry) {
    state.releaseRetained();
    state.releaseRetainedName(
        ServiceLoaderDescriptorBudget.utf8Length(entry.name()) + 48);
  }

  private static boolean readModule(
      ServiceLoaderDescriptorZipDirectory directory,
      Path archive,
      ServiceLoaderDescriptorZipEntry module,
      String versionedModule,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      ServiceLoaderDescriptorSource.ScanState state,
      ServiceLoaderDescriptorArchiveConfig config) throws IOException {
    if (module == null) {
      if (versionedModule != null) {
        state.diagnostic(
            "versioned_module_info_unsupported", archive.toString(), versionedModule);
      }
      return true;
    }
    String modulePath = archive + "!/" + module.name();
    if (!state.hasDecompressedBudget()) {
      state.diagnostic(
          "archive_decompression_limit", modulePath,
          "module-info.class could not be read after the archive decompression budget was exhausted");
      return true;
    }
    try (InputStream input = directory.stream(module, state, modulePath)) {
      byte[] bytes = readEntry(input, state, modulePath);
      if (bytes == null) {
        return true;
      }
      return ServiceLoaderDescriptorSource.addModuleBytes(
          bytes, modulePath, descriptors, state);
    } catch (InterruptedIOException | ServiceLoaderDescriptorZipDirectory.RawIoLimitException error) {
      throw error;
    } catch (IOException error) {
      state.diagnostic(
          "archive_entry_read_error", modulePath, sanitized(error, config));
      return true;
    }
  }

  private static void addDescriptor(
      ServiceLoaderDescriptorZipDirectory directory,
      Path archive,
      ServiceLoaderDescriptorZipEntry entry,
      List<ServiceLoaderDescriptorScanner.Descriptor> descriptors,
      ServiceLoaderDescriptorSource.ScanState state,
      ServiceLoaderDescriptorArchiveConfig config) throws IOException {
    String serviceType = entry.name().substring(
        ServiceLoaderDescriptorSource.SERVICES_DIRECTORY.length() + 1);
    if (!ServiceLoaderDescriptorAuthorization.isBinaryClassName(serviceType)) {
      return;
    }
    String path = archive + "!/" + entry.name();
    try (InputStream input = directory.stream(entry, state, path)) {
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
    } catch (InterruptedIOException | ServiceLoaderDescriptorZipDirectory.RawIoLimitException error) {
      throw error;
    } catch (IOException error) {
      state.diagnostic(
          "archive_entry_read_error", path, sanitized(error, config));
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

  static String sanitized(Exception error, ServiceLoaderDescriptorArchiveConfig config) {
    String message = error.getMessage();
    if (message == null || message.isBlank()) {
      message = error.getClass().getSimpleName();
    }
    Path root = config.tempRoot() == null
        ? Path.of(System.getProperty("java.io.tmpdir"))
        : config.tempRoot();
    message = message.replace(root.toString(), "<temp>");
    int marker = message.indexOf("flpath-5124-spill-");
    while (marker >= 0) {
      int end = marker;
      while (end < message.length() && !Character.isWhitespace(message.charAt(end))) {
        end++;
      }
      message = message.substring(0, marker) + "<temp>" + message.substring(end);
      marker = message.indexOf("flpath-5124-spill-");
    }
    return error.getClass().getSimpleName() + ": " + message;
  }

  private record ArchiveEntries(
      List<ServiceLoaderDescriptorZipEntry> descriptors,
      ServiceLoaderDescriptorZipEntry module,
      String versionedModule,
      boolean complete) {}

  private static final class StopWalk extends RuntimeException {
    private static final long serialVersionUID = 1L;

    StopWalk() {
      super(null, null, false, false);
    }
  }
}
