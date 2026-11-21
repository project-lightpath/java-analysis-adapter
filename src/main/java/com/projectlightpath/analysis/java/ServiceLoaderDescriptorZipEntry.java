package com.projectlightpath.analysis.java;

/**
 * One validated central-directory record.
 *
 * <p>{@code extraLength} and {@code comment} are retained for the
 * enumeration-budget charge; {@code local}, {@code data} and {@code end} are
 * absolute file offsets.
 */
record ServiceLoaderDescriptorZipEntry(
    String name,
    int flags,
    int method,
    long crc,
    long compressed,
    long length,
    long local,
    long data,
    long end,
    int extraLength,
    String comment) {

  boolean directory() {
    return name.endsWith("/");
  }

  boolean deflated() {
    return method == 8;
  }
}
