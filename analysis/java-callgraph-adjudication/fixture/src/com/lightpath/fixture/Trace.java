package com.lightpath.fixture;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Hand instrumentation: every method whose execution matters for ground truth
 * starts with Trace.log("scenario:Class.method"). Truth comes only from this
 * runtime trace, never from a static tool's output.
 */
public final class Trace {
    private static final Path OUT = Path.of(System.getenv().getOrDefault("TRACE_OUT", "trace.log"));

    private Trace() {
    }

    public static void log(String tag) {
        try (BufferedWriter w = Files.newBufferedWriter(OUT, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(tag);
            w.newLine();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
