package com.projectlightpath.analysis.java;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;

/**
 * Cumulative, scan-global byte budgets for ServiceLoader metadata.
 *
 * <p>Policy (FLPATH-5123): the shared entry-count cap is gone. Enumeration is
 * bounded by {@link #MAX_ENUMERATION_BYTES}, the ratified 2 GiB raw
 * directory-read budget from {@code POLICY-NUMBERS.md}. Retained descriptor
 * metadata is bounded by {@link #MAX_RETAINED_DESCRIPTOR_BYTES}. Both are
 * <strong>cumulative volumes for the whole scan</strong>, not live/peak bounds,
 * matching the existing cumulative decompression budget (16 MiB, unchanged).
 *
 * <p>Parity deliberately not adopted here: the prototype's 1 MiB per-entry
 * control (production never had one; see PLAN-5123 R1) and its spill quota
 * (production has no spill; R2). Provider-line payload amplification remains
 * bounded only by the shared decompression budget, a pre-existing property
 * (PLAN R11). The durable fix for eager JDK central-directory indexing is
 * FLPATH-5124.
 */
final class ServiceLoaderDescriptorBudget {
  /**
   * Ratified raw directory-read budget (2 GiB). The per-entry charge is a byte
   * estimate floored at {@link #MEASURED_RAW_BYTES_PER_ENTRY}, so ordinary
   * names are floor-dominated: {@code 2 GiB / 122 B} ~= 17.6M entries.
   */
  static final long MAX_ENUMERATION_BYTES = 2L * 1024 * 1024 * 1024;
  /** Measured raw directory-read cost per entry (POLICY-NUMBERS.md). */
  static final long MEASURED_RAW_BYTES_PER_ENTRY = 122L;
  /** ZIP central-directory fixed record header. */
  static final long ENUMERATION_RECORD_OVERHEAD_BYTES = 46L;
  /**
   * Cumulative retained-metadata budget: 16 MiB / 512 B = 32,768 descriptors
   * per analysis. Charged once per retained descriptor candidate (including
   * module-info provides) and restored when a source rolls back.
   */
  static final long MAX_RETAINED_DESCRIPTOR_BYTES = 16L * 1024 * 1024;
  /**
   * Conservative model of one retained descriptor plus its candidate slot
   * (two Strings, two Lists, path String, 64-char fingerprint, object headers).
   */
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
    return centralDirectoryBytes(
        entry.getName(), extra == null ? 0 : extra.length, entry.getComment());
  }

  /** Same formula as the {@link ZipEntry} overload for a parsed central record. */
  static long centralDirectoryBytes(String name, int extraLength, String comment) {
    long recordBytes = ENUMERATION_RECORD_OVERHEAD_BYTES
        + utf8Length(name)
        + Math.max(0, extraLength)
        + utf8Length(comment);
    return Math.max(MEASURED_RAW_BYTES_PER_ENTRY, recordBytes);
  }
}
