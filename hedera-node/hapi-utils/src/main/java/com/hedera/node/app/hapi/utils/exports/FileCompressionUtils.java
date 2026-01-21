// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.exports;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
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

    public static byte[] readUncompressedFileBytes(final String fileLoc) throws IOException {
        try (final var fis = new FileInputStream(fileLoc);
                final var buffered = new BufferedInputStream(fis);
                final var byteArrayOutputStream = new ByteArrayOutputStream()) {
            validateGzipHeader(buffered, fileLoc);
            try (final var fin = new GZIPInputStream(buffered)) {
                final var buffer = new byte[8192];
                int len;
                long total = 0L;
                while ((len = fin.read(buffer)) > 0) {
                    // Prevent unbounded in-memory expansion (a.k.a. "zip bomb" / decompression bomb).
                    if (total + len > MAX_IN_MEMORY_BYTES) {
                        throw new IOException("Decompressed data exceeds max allowed size (" + MAX_IN_MEMORY_BYTES
                                + " bytes) for file " + fileLoc);
                    }
                    byteArrayOutputStream.write(buffer, 0, len);
                    total += len;
                }
            }
            return byteArrayOutputStream.toByteArray();
        } catch (IOException e) {
            throw new IOException("Error reading file " + fileLoc, e);
        }
    }

    private static void validateGzipHeader(final BufferedInputStream in, final String fileLoc) throws IOException {
        // GZIP header: ID1=0x1f, ID2=0x8b, CM=0x08 (deflate)
        in.mark(3);
        final int id1 = in.read();
        final int id2 = in.read();
        final int cm = in.read();
        in.reset();

        if (id1 == -1 || id2 == -1 || cm == -1) {
            throw new IOException("File is empty or too short to be a valid GZIP file: " + fileLoc);
        }
        if (id1 != 0x1f || id2 != 0x8b || cm != 0x08) {
            throw new IOException("Invalid GZIP header for file " + fileLoc);
        }
    }
}
