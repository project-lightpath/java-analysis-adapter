package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Git-tree file inspection for the contract-fixture validator (#45 step 4): lstat
 * semantics with {@link LinkOption#NOFOLLOW_LINKS} on every read, the stat file-type
 * bits, the user/group/other permission bits, and the git blob SHA-1 over content or link
 * target. Ported from validate_contract_fixtures.py's git_blob and lstat handling.
 */
final class GitTreeFiles {

  private GitTreeFiles() {}

  /** The git blob SHA-1: "blob <len>\0" over content, or the link target for symlinks. */
  static String gitBlob(Path path) {
    byte[] data;
    try {
      data = Files.isSymbolicLink(path)
          ? Files.readSymbolicLink(path).toString()
              .getBytes(java.nio.charset.StandardCharsets.UTF_8)
          : Files.readAllBytes(path);
    } catch (IOException error) {
      throw new ContractFixtureValidator.ValidationError("cannot read " + path + ": "
          + error.getMessage(), error);
    }
    return sha1(("blob " + data.length + "\0").getBytes(
        java.nio.charset.StandardCharsets.UTF_8), data);
  }

  /**
   * The full stat mode, never following symlinks, through the Unix filesystem view:
   * unlike the POSIX permission view, it carries the file-type bits and the
   * setuid/setgid/sticky bits, matching Python's os.lstat.
   */
  static long statMode(Path path, String entryPath) {
    try {
      return (Integer) Files.getAttribute(path, "unix:mode",
          LinkOption.NOFOLLOW_LINKS);
    } catch (IOException | UnsupportedOperationException error) {
      throw new ContractFixtureValidator.ValidationError(
          "cannot read the mode for VPE tree file: " + entryPath, error);
    }
  }

  /** The stat file-type bits: symlink, regular, directory, or 0 for other shapes. */
  static long fileType(Path path, String entryPath) {
    return statMode(path, entryPath) & 0170000L;
  }

  /** All twelve permission bits (rwx plus setuid/setgid/sticky), like S_IMODE. */
  static long permissionBits(Path path, String entryPath) {
    return statMode(path, entryPath) & 07777L;
  }

  static byte[] readBytes(Path file) {
    try {
      return Files.readAllBytes(file);
    } catch (IOException error) {
      throw new ContractFixtureValidator.ValidationError("cannot read " + file + ": "
          + error.getMessage(), error);
    }
  }

  static String sha256(byte[] data) {
    try {
      return hex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException error) {
      throw new ContractFixtureValidator.ValidationError("SHA-256 is unavailable",
          error);
    }
  }

  private static String sha1(byte[] header, byte[] data) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-1");
      digest.update(header);
      digest.update(data);
      return hex(digest.digest());
    } catch (NoSuchAlgorithmException error) {
      throw new ContractFixtureValidator.ValidationError("SHA-1 is unavailable", error);
    }
  }

  static String hex(byte[] digest) {
    StringBuilder out = new StringBuilder();
    for (byte value : digest) {
      out.append(String.format(java.util.Locale.ROOT, "%02x", value));
    }
    return out.toString();
  }
}
