// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.exports;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;

/**
 * Minimal utility denoting the compression algorithm used throughout the project and utility
 * methods for reading the compressed files.
 */
public class FileCompressionUtils {

    private FileCompressionUtils() {}

    public static byte[] readUncompressedFileBytes(final String fileLoc) throws IOException {
        try (final var fin = new GZIPInputStream(new FileInputStream(fileLoc));
                final var byteArrayOutputStream = new ByteArrayOutputStream()) {
            final var buffer = new byte[1024];
            int len;
            while ((len = fin.read(buffer)) > 0) {
                byteArrayOutputStream.write(buffer, 0, len);
            }
            return byteArrayOutputStream.toByteArray();
        } catch (IOException e) {
            throw new IOException("Error reading file " + fileLoc, e);
        }
    }
}
