package com.projectlightpath.analysis.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Set;

/**
 * Git-tree file inspection for the contract-fixture validator (#45 step 4): lstat
 * semantics with {@link LinkOption#NOFOLLOW_LINKS} on every read, the stat file-type
 * bits, the user/group/other permission bits, and the git blob SHA-1 over content or link
 * target. Ported from validate_contract_fixtures.py's git_blob and lstat handling.
 */
final class GitTreeFiles {

  /** The POSIX permission bits each reported permission contributes. */
  private static final Map<PosixFilePermission, Integer> PERMISSION_BITS = Map.of(
      PosixFilePermission.OWNER_READ, 0400,
      PosixFilePermission.OWNER_WRITE, 0200,
      PosixFilePermission.OWNER_EXECUTE, 0100,
      PosixFilePermission.GROUP_READ, 0040,
      PosixFilePermission.GROUP_WRITE, 0020,
      PosixFilePermission.GROUP_EXECUTE, 0010,
      PosixFilePermission.OTHERS_READ, 0004,
      PosixFilePermission.OTHERS_WRITE, 0002,
      PosixFilePermission.OTHERS_EXECUTE, 0001);

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

  /** The stat file-type bits: symlink, regular, directory, or 0 for other shapes. */
  static long fileType(Path path) {
    if (Files.isSymbolicLink(path)) {
      return 0120000L;
    }
    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      return 0100000L;
    }
    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      return 040000L;
    }
    return 0;
  }

  /** The user/group/other rwx permission bits, never following symlinks. */
  static long permissionBits(Path path, String entryPath) {
    try {
      Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path,
          LinkOption.NOFOLLOW_LINKS);
      long bits = 0;
      for (Map.Entry<PosixFilePermission, Integer> entry : PERMISSION_BITS.entrySet()) {
        if (permissions.contains(entry.getKey())) {
          bits |= entry.getValue();
        }
      }
      return bits;
    } catch (IOException | UnsupportedOperationException error) {
      throw new ContractFixtureValidator.ValidationError(
          "cannot read permissions for VPE tree file: " + entryPath, error);
    }
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
      out.append(String.format("%02x", value));
    }
    return out.toString();
  }
}
