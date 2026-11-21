package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Production boundary for the seekable archive reader.
 *
 * <p>Raw directory I/O and spill are per-archive bounds; enumeration,
 * retention and decompression remain scan-global on
 * {@link ServiceLoaderDescriptorSource.ScanState}. Values are the ratified
 * FLPATH-5124 policy, not lab properties. The channel opener and cleanup hook
 * are package-private test seams.
 */
final class ServiceLoaderDescriptorArchiveConfig {
  static final long DEFAULT_MAX_RAW_DIRECTORY_BYTES = 2L * 1024 * 1024 * 1024;
  static final long DEFAULT_MAX_SPILL_BYTES = 2L * 1024 * 1024 * 1024;
  static final long DEFAULT_SORT_MEMORY_BYTES = 32L * 1024 * 1024;
  static final long DEFAULT_MERGE_RUN_BYTES = 16L * 1024 * 1024;

  @FunctionalInterface
  interface ChannelOpener {
    SeekableByteChannel open(Path path) throws IOException;
  }

  @FunctionalInterface
  interface Cleanup {
    void delete(Path path) throws IOException;
  }

  private final long maxRawDirectoryBytes;
  private final long maxSpillBytes;
  private final long sortMemoryBytes;
  private final long mergeRunBytes;
  private final Path tempRoot;
  private final ChannelOpener channelOpener;
  private final Cleanup cleanup;

  private ServiceLoaderDescriptorArchiveConfig(
      long maxRawDirectoryBytes,
      long maxSpillBytes,
      long sortMemoryBytes,
      long mergeRunBytes,
      Path tempRoot,
      ChannelOpener channelOpener,
      Cleanup cleanup) {
    if (maxRawDirectoryBytes <= 0
        || maxSpillBytes <= 0
        || sortMemoryBytes <= 0
        || mergeRunBytes <= 0) {
      throw new IllegalArgumentException("archive budgets must be positive");
    }
    this.maxRawDirectoryBytes = maxRawDirectoryBytes;
    this.maxSpillBytes = maxSpillBytes;
    this.sortMemoryBytes = sortMemoryBytes;
    this.mergeRunBytes = mergeRunBytes;
    this.tempRoot = tempRoot;
    this.channelOpener = channelOpener;
    this.cleanup = cleanup;
  }

  static ServiceLoaderDescriptorArchiveConfig defaults() {
    return new ServiceLoaderDescriptorArchiveConfig(
        DEFAULT_MAX_RAW_DIRECTORY_BYTES,
        DEFAULT_MAX_SPILL_BYTES,
        DEFAULT_SORT_MEMORY_BYTES,
        DEFAULT_MERGE_RUN_BYTES,
        null,
        path -> Files.newByteChannel(path, StandardOpenOption.READ),
        Files::deleteIfExists);
  }

  long maxRawDirectoryBytes() {
    return maxRawDirectoryBytes;
  }

  long maxSpillBytes() {
    return maxSpillBytes;
  }

  long sortMemoryBytes() {
    return sortMemoryBytes;
  }

  long mergeRunBytes() {
    return mergeRunBytes;
  }

  Path tempRoot() {
    return tempRoot;
  }

  ChannelOpener channelOpener() {
    return channelOpener;
  }

  Cleanup cleanup() {
    return cleanup;
  }

  ServiceLoaderDescriptorArchiveConfig withMaxRawDirectoryBytes(long value) {
    return new ServiceLoaderDescriptorArchiveConfig(
        value, maxSpillBytes, sortMemoryBytes, mergeRunBytes,
        tempRoot, channelOpener, cleanup);
  }

  ServiceLoaderDescriptorArchiveConfig withMaxSpillBytes(long value) {
    return new ServiceLoaderDescriptorArchiveConfig(
        maxRawDirectoryBytes, value, sortMemoryBytes, mergeRunBytes,
        tempRoot, channelOpener, cleanup);
  }

  ServiceLoaderDescriptorArchiveConfig withSortMemoryBytes(long value) {
    return new ServiceLoaderDescriptorArchiveConfig(
        maxRawDirectoryBytes, maxSpillBytes, value, mergeRunBytes,
        tempRoot, channelOpener, cleanup);
  }

  ServiceLoaderDescriptorArchiveConfig withMergeRunBytes(long value) {
    return new ServiceLoaderDescriptorArchiveConfig(
        maxRawDirectoryBytes, maxSpillBytes, sortMemoryBytes, value,
        tempRoot, channelOpener, cleanup);
  }

  ServiceLoaderDescriptorArchiveConfig withTempRoot(Path value) {
    return new ServiceLoaderDescriptorArchiveConfig(
        maxRawDirectoryBytes, maxSpillBytes, sortMemoryBytes, mergeRunBytes,
        value, channelOpener, cleanup);
  }

  ServiceLoaderDescriptorArchiveConfig withChannelOpener(ChannelOpener value) {
    return new ServiceLoaderDescriptorArchiveConfig(
        maxRawDirectoryBytes, maxSpillBytes, sortMemoryBytes, mergeRunBytes,
        tempRoot, value, cleanup);
  }

  ServiceLoaderDescriptorArchiveConfig withCleanup(Cleanup value) {
    return new ServiceLoaderDescriptorArchiveConfig(
        maxRawDirectoryBytes, maxSpillBytes, sortMemoryBytes, mergeRunBytes,
        tempRoot, channelOpener, value);
  }
}
