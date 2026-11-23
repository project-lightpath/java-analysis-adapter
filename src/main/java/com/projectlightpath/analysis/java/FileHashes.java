package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Streamed file digests for the changed-coverage validator (#45 step 4): SHA-256 of a
 * report's content in 1 MB blocks, rendered as lowercase hex independent of the host
 * locale.
 */
final class FileHashes {

  private FileHashes() {}

  /** SHA-256 of a file's content, streamed. */
  static String sha256(Path file) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream stream = Files.newInputStream(file)) {
        byte[] block = new byte[1024 * 1024];
        int read;
        while ((read = stream.read(block)) != -1) {
          digest.update(block, 0, read);
        }
      }
      return hex(digest.digest());
    } catch (IOException | NoSuchAlgorithmException error) {
      throw new ChangedCoverageValidator.ValidationError("cannot read " + file + ": "
          + error.getMessage(), error);
    }
  }

  /** Lowercase hex, locale-independent. */
  static String hex(byte[] digest) {
    StringBuilder out = new StringBuilder();
    for (byte value : digest) {
      out.append(String.format(java.util.Locale.ROOT, "%02x", value));
    }
    return out.toString();
  }
}
