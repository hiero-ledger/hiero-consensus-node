// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.clpr;

import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared argument-parsing helpers for CLPR yahcli commands.
 *
 * <p>Covers the common flag idioms across the CLPR subcommand surface: required vs. optional
 * hex-encoded byte strings, {@code account-id} parsing ({@code shard.realm.num} or
 * {@code num}), {@code contract-id} parsing (same forms), and flat JSON-bundle field
 * extraction so {@code complete-*} commands can load identities from a generated
 * {@code .json} blob without manually wiring every flag.
 */
final class ClprArgs {
    private ClprArgs() {}

    private static final Set<PosixFilePermission> OWNER_RW =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    /**
     * Writes an identity bundle and its companion private-key file. Both targets are checked for
     * symlinks <em>before</em> anything is written, so a rejected target never leaves a stray key
     * behind. Existing files at either target are preserved (see {@link #writeSecure}). If the
     * bundle write fails after the key was written, the newly written key is removed so it cannot
     * be orphaned from a bundle it does not match.
     */
    static void writeBundleAndKey(
            final Path bundlePath, final String bundleJson, final Path keyPath, final String keyContent)
            throws IOException {
        requireNotSymlink(bundlePath);
        requireNotSymlink(keyPath);
        writeSecure(keyPath, keyContent);
        try {
            writeSecure(bundlePath, bundleJson);
        } catch (final IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(keyPath);
            } catch (final IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /**
     * Writes {@code content} to {@code path} with owner-only permissions (0600), refusing to
     * follow symlinks. An existing file is never overwritten or deleted: it is first moved aside
     * via {@link #backupIfExists(Path)}.
     */
    static void writeSecure(final Path path, final String content) throws IOException {
        final var backup = backupIfExists(path);
        if (backup != null) {
            System.out.println("Existing " + path + " preserved as " + backup);
        }
        final var perms = PosixFilePermissions.asFileAttribute(OWNER_RW);
        try (final var ch = FileChannel.open(
                path,
                Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS),
                perms)) {
            final var buf = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
            while (buf.hasRemaining()) {
                if (ch.write(buf) <= 0) {
                    throw new IOException("FileChannel.write made no progress on " + path);
                }
            }
        }
    }

    /**
     * Moves an existing regular file at {@code path} to {@code <name>.<epochMillis>.bak} in the
     * same directory (restricted to owner-only, since it may hold a private key) so no key
     * material is ever lost. Returns the backup path, or {@code null} if nothing existed.
     *
     * @throws IOException if {@code path} is a symlink or not a regular file, or the move fails
     */
    @Nullable
    static Path backupIfExists(final Path path) throws IOException {
        requireNotSymlink(path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to replace non-regular file: " + path);
        }
        final var name = path.getFileName().toString();
        var epoch = System.currentTimeMillis();
        var backup = path.resolveSibling(name + "." + epoch + ".bak");
        while (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
            backup = path.resolveSibling(name + "." + (++epoch) + ".bak");
        }
        // No REPLACE_EXISTING: fail rather than clobber a backup created concurrently.
        Files.move(path, backup);
        Files.setPosixFilePermissions(backup, OWNER_RW);
        return backup;
    }

    static void requireNotSymlink(final Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Refusing to write through symlink: " + path);
        }
    }

    static byte[] requiredBytes(final String flagName, @Nullable final String hex) {
        if (hex == null || hex.isBlank()) {
            throw new IllegalArgumentException("--" + flagName + " is required");
        }
        return parseHex(hex);
    }

    @Nullable
    static byte[] optionalBytes(@Nullable final String hex) {
        if (hex == null || hex.isBlank()) {
            return null;
        }
        return parseHex(hex);
    }

    static byte[] readBytesFile(final Path path) throws IOException {
        return Files.readAllBytes(path);
    }

    /**
     * Resolves bytes supplied either as a path to a binary file ({@code --<fileFlag>}) or as a
     * hex string ({@code --<hexFlag>}). The two flags are mutually exclusive. Returns
     * {@code null} when neither is given, so the caller decides whether the value is required.
     */
    @Nullable
    static byte[] optionalBytesFromFileOrHex(
            final String fileFlag, @Nullable final String file, final String hexFlag, @Nullable final String hex)
            throws IOException {
        final var fileProvided = file != null && !file.isBlank();
        final var hexProvided = hex != null && !hex.isBlank();
        if (fileProvided && hexProvided) {
            throw new IllegalArgumentException(
                    "Cannot specify both --" + fileFlag + " and --" + hexFlag + "; pick one.");
        }
        if (fileProvided) {
            return readBytesFile(Path.of(file));
        }
        return hexProvided ? parseHex(hex) : null;
    }

    /**
     * Reads a flat JSON file (one level deep, only string-valued fields) and returns a
     * {@code Map<fieldName, stringValue>}. Intended for parsing the identity bundles
     * produced by {@code generate-channel-identity} / {@code generate-connector-identity},
     * which have a fixed shape — keeps yahcli dependency-light by avoiding a real JSON library.
     */
    static Map<String, String> readFlatJsonStringFields(final Path path) throws IOException {
        final var content = Files.readString(path);
        final var pattern = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"([^\"]*)\"");
        final var matcher = pattern.matcher(content);
        final var result = new HashMap<String, String>();
        while (matcher.find()) {
            result.put(matcher.group(1), matcher.group(2));
        }
        return result;
    }

    static byte[] parseHex(final String raw) {
        var s = raw.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        if ((s.length() & 1) == 1) {
            throw new IllegalArgumentException("Hex string has an odd number of characters: " + raw);
        }
        final var out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            final int hi = Character.digit(s.charAt(2 * i), 16);
            final int lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("Invalid hex character in: " + raw);
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    static AccountID parseAccountId(final String literal) {
        final long[] parts = parseDotTriple(literal);
        return AccountID.newBuilder()
                .setShardNum(parts[0])
                .setRealmNum(parts[1])
                .setAccountNum(parts[2])
                .build();
    }

    static ContractID parseContractId(final String literal) {
        final long[] parts = parseDotTriple(literal);
        return ContractID.newBuilder()
                .setShardNum(parts[0])
                .setRealmNum(parts[1])
                .setContractNum(parts[2])
                .build();
    }

    private static long[] parseDotTriple(final String literal) {
        final var parts = literal.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Expected entity id of form <shard>.<realm>.<num> but got: " + literal);
        }
        try {
            return new long[] {Long.parseLong(parts[0]), Long.parseLong(parts[1]), Long.parseLong(parts[2])};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Entity id parts must be longs: " + literal, e);
        }
    }
}
