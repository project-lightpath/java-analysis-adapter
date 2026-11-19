package com.projectlightpath.analysis.java;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;

/**
 * Byte-denominated ServiceLoader enumeration and retention budgets.
 *
 * <p>The policy numbers come from {@code POLICY-NUMBERS.md} on
 * {@code spike/flpath-5078-prototype}: 2 GiB raw directory-read budget, 16 MiB
 * decompressed and 1 MiB per-entry (the latter two stay in
 * {@link ServiceLoaderDescriptorSource} / unchanged read code). Enumeration
 * cost is a byte estimate floored at the measured raw cost, so ordinary names
 * are dominated by the floor: {@code 2 GiB / 122 B} entries. Retained metadata
 * is a separate cumulative budget so deleting the shared 1,024-entry cap does
 * not leave the candidate and result maps unbounded.
 *
 * <p>The durable fix for eager JDK central-directory indexing is FLPATH-5124.
 */
final class ServiceLoaderDescriptorBudget {
  static final long MAX_ENUMERATION_BYTES = 2L * 1024 * 1024 * 1024;
  static final long MEASURED_RAW_BYTES_PER_ENTRY = 122L;
  static final long ENUMERATION_RECORD_OVERHEAD_BYTES = 46L;
  static final long MAX_RETAINED_DESCRIPTOR_BYTES = 16L * 1024 * 1024;
  static final long RETAINED_DESCRIPTOR_BYTES = 512L;

  private ServiceLoaderDescriptorBudget() {}

  static long utf8Length(String value) {
    return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
  }

  /** Estimated enumeration cost of one directory entry (name bytes, floored). */
  static long directoryEntryBytes(Path path) {
    String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
    return Math.max(
        MEASURED_RAW_BYTES_PER_ENTRY,
        ENUMERATION_RECORD_OVERHEAD_BYTES + utf8Length(name));
  }

  /**
   * Estimated ZIP central-directory bytes consumed by one entry: fixed record
   * header, UTF-8 name, extra field and comment, floored at the measured raw
   * read cost. This is an estimate for legacy-encoded names, never below the
   * floor.
   */
  static long centralDirectoryBytes(ZipEntry entry) {
    byte[] extra = entry.getExtra();
    long recordBytes = ENUMERATION_RECORD_OVERHEAD_BYTES
        + utf8Length(entry.getName())
        + (extra == null ? 0 : extra.length)
        + utf8Length(entry.getComment());
    return Math.max(MEASURED_RAW_BYTES_PER_ENTRY, recordBytes);
  }
}
