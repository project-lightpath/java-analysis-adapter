package com.projectlightpath.analysis.java;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Manual FLPATH-5124 boundedness probe; run with -Xmx128m. */
public final class SeekableZipBoundednessProbe {
  private SeekableZipBoundednessProbe() {}

  public static void main(String[] args) throws Exception {
    if (args.length >= 2 && "build".equals(args[0])) {
      Path archive = Path.of(args[1]);
      writeArchive(archive, Integer.parseInt(args[2]));
      System.out.println("built=" + archive + " bytes=" + Files.size(archive));
      return;
    }
    Path archive = Path.of(args[0]);
    int entries = Integer.parseInt(args[1]);
    AtomicLong peak = new AtomicLong(used());
    Thread sampler = new Thread(() -> {
      while (!Thread.currentThread().isInterrupted()) {
        peak.accumulateAndGet(used(), Math::max);
        try {
          Thread.sleep(5L);
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }, "heap-sampler");
    sampler.setDaemon(true);
    sampler.start();
    long scanStart = System.nanoTime();
    List<ServiceLoaderDescriptorScanner.Descriptor> descriptors = new ArrayList<>();
    ServiceLoaderDescriptorSource.ScanState state =
        new ServiceLoaderDescriptorSource.ScanState();
    ServiceLoaderDescriptorArchiveSource.scan(archive, descriptors, state);
    long scanMs = (System.nanoTime() - scanStart) / 1_000_000;
    sampler.interrupt();
    sampler.join();
    System.out.printf(
        "entries=%d scanMs=%d descriptors=%d diagnostics=%d heapMax=%d peakUsed=%d%n",
        entries, scanMs, descriptors.size(), state.diagnostics().size(),
        Runtime.getRuntime().maxMemory(), peak.get());
    for (ServiceLoaderDescriptorScanner.Diagnostic diagnostic : state.diagnostics()) {
      System.out.println("diagnostic=" + diagnostic.code() + " " + diagnostic.path());
    }
  }

  private static void writeArchive(Path archive, int entries) throws IOException {
    byte[] service = "demo.Impl\n".getBytes(StandardCharsets.UTF_8);
    try (ZipOutputStream output =
        new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(archive)))) {
      ZipEntry descriptor = new ZipEntry("META-INF/services/demo.Provider");
      output.putNextEntry(descriptor);
      output.write(service);
      output.closeEntry();
      for (int index = 0; index < entries; index++) {
        putStored(output, "noise/e-" + index, new byte[0]);
      }
    }
  }

  private static void putStored(ZipOutputStream output, String name, byte[] bytes)
      throws IOException {
    CRC32 crc = new CRC32();
    crc.update(bytes);
    ZipEntry entry = new ZipEntry(name);
    entry.setMethod(ZipEntry.STORED);
    entry.setSize(bytes.length);
    entry.setCompressedSize(bytes.length);
    entry.setCrc(crc.getValue());
    output.putNextEntry(entry);
    output.write(bytes);
    output.closeEntry();
  }

  private static long used() {
    long total = 0;
    for (var pool : ManagementFactory.getMemoryPoolMXBeans()) {
      if (pool.getType() == MemoryType.HEAP) {
        total += pool.getUsage().getUsed();
      }
    }
    return total;
  }
}
