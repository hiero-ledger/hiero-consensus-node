// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.exports;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

/**
 * Minimal utility denoting the compression algorithm used throughout the project and utility
 * methods for reading the compressed files.
 */
public class FileCompressionUtils {

    /**
     * Maximum allowed decompressed payload size when inflating a GZIP file into memory.
     *
     * <p>Note: Since this utility returns a {@code byte[]}, the effective in-memory limit cannot
     * exceed {@link Integer#MAX_VALUE}. Any configured limit above that is still enforced as
     * {@code Integer.MAX_VALUE - 8} to avoid integer/array overflow issues.
     */
    private static final long MAX_DECOMPRESSED_BYTES = 10L * 1024L * 1024L * 1024L; // 10 GiB

    private static final long MAX_IN_MEMORY_BYTES = Math.min(MAX_DECOMPRESSED_BYTES, (long) Integer.MAX_VALUE - 8L);

    private FileCompressionUtils() {}

    /**
     * Reads and inflates a GZIP file into memory, after validating the file path is within the given
     * authorized directory.
     *
     * <p>This method defends against path traversal by normalizing and resolving the input path
     * against an authorized root, and by validating the resulting real path is within that root.
     * It also checks the file exists and is readable before attempting to open it.
     *
     * @param authorizedDir the authorized root directory the file must reside within
     * @param fileLoc the (relative or absolute) location of the gzip file to read
     * @return the uncompressed bytes from the gzip file
     * @throws IOException if the path is invalid, outside the authorized directory, missing,
     *     unreadable, or if decompression fails
     */
    public static byte[] readUncompressedFileBytes(final Path authorizedDir, final String fileLoc) throws IOException {
        Objects.requireNonNull(authorizedDir, "authorizedDir must not be null");
        Objects.requireNonNull(fileLoc, "fileLoc must not be null");

        final var root = authorizedDir.toAbsolutePath().normalize();
        final var candidate = Path.of(fileLoc);

        // Interpret relative paths carefully:
        // - Some callers pass a filename relative to authorizedDir (e.g. "foo.rcd.gz")
        // - Others pass a path already rooted under authorizedDir (e.g. "streams/foo.rcd.gz")
        // We accept either, as long as the resulting real path is within authorizedDir.
        final Path resolved;
        if (candidate.isAbsolute()) {
            resolved = candidate.toAbsolutePath().normalize();
        } else {
            final var fromCwd = candidate.toAbsolutePath().normalize();
            final var underRoot = root.resolve(candidate).normalize();
            resolved = fromCwd.startsWith(root) ? fromCwd : underRoot;
        }

        if (!Files.exists(resolved)) {
            throw new IOException("File does not exist: " + resolved);
        }
        if (!Files.isRegularFile(resolved)) {
            throw new IOException("Path is not a regular file: " + resolved);
        }
        if (!Files.isReadable(resolved)) {
            throw new IOException("File is not readable: " + resolved);
        }

        // Validate that the real file path is within the real authorized directory to prevent
        // traversal and symlink-escape attacks.
        final Path rootReal;
        final Path fileReal;
        try {
            rootReal = root.toRealPath();
            fileReal = resolved.toRealPath();
        } catch (final IOException e) {
            throw new IOException(
                    "Failed to resolve real paths for authorized root '" + root + "' and file '" + resolved + "'", e);
        }

        if (!fileReal.startsWith(rootReal)) {
            throw new IOException("File path '" + fileReal + "' is outside authorized directory '" + rootReal + "'");
        }

        try (final var in = Files.newInputStream(fileReal);
                final var buffered = new BufferedInputStream(in);
                final var byteArrayOutputStream = new ByteArrayOutputStream()) {
            validateGzipHeader(buffered, fileReal.toString());
            try (final var fin = new GZIPInputStream(buffered)) {
                final var buffer = new byte[1024];
                int len;
                long total = 0L;
                while ((len = fin.read(buffer)) > 0) {
                    // Prevent unbounded in-memory expansion (a.k.a. "zip bomb" / decompression bomb).
                    if (total + len > MAX_IN_MEMORY_BYTES) {
                        throw new IOException("Decompressed data exceeds max allowed size (" + MAX_IN_MEMORY_BYTES
                                + " bytes) for file " + fileReal);
                    }
                    byteArrayOutputStream.write(buffer, 0, len);
                    total += len;
                }
            }
            return byteArrayOutputStream.toByteArray();
        } catch (IOException e) {
            throw new IOException("Error reading file " + resolved, e);
        }
    }

    private static void validateGzipHeader(final BufferedInputStream bufferedInputStream, final String fileLoc)
            throws IOException {
        // GZIP header: ID1=0x1f, ID2=0x8b, CM=0x08 (deflate)
        bufferedInputStream.mark(3);
        final int id1 = bufferedInputStream.read();
        final int id2 = bufferedInputStream.read();
        final int cm = bufferedInputStream.read();
        bufferedInputStream.reset();

        if (id1 == -1 || id2 == -1 || cm == -1) {
            throw new IOException("File is empty or too short to be a valid GZIP file: " + fileLoc);
        }
        if (id1 != 0x1f || id2 != 0x8b || cm != 0x08) {
            throw new IOException("Invalid GZIP header for file " + fileLoc);
        }
    }
}
