// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.handlers;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility class to unzip a zip file.
 * */
public final class UnzipUtility {
    private static final Logger log = LogManager.getLogger(UnzipUtility.class);

    private static final int BUFFER_SIZE = 4096;

    /** Hard limits to reduce Zip Slip / zip-bomb risk during extraction. */
    private static final int MAX_ENTRIES = 10_000;

    private static final long MAX_TOTAL_EXTRACTED_BYTES = 10L * 1024L * 1024L * 1024L; // 10 GiB
    private static final int MAX_DEPTH = 20;
    private static final int MAX_NAME_LENGTH = 4096;
    private static final long MAX_ENTRY_BYTES = 2L * 1024L * 1024L * 1024L; // 2 GiB

    private UnzipUtility() {}

    /**
     * Extracts (unzips) a zipped file from a byte array.
     * @param bytes the byte array containing the zipped file
     * @param dstDir the destination directory to extract the unzipped file to
     * @throws IOException if the destination does not exist and can't be created, or if the file can't be written
     */
    public static void unzip(@NonNull final byte[] bytes, @NonNull final Path dstDir) throws IOException {
        requireNonNull(bytes);
        requireNonNull(dstDir);

        try (final var zipIn = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            // Normalize the destination directory to avoid inconsistencies in containment checks.
            final Path resolvedDstDir = dstDir.toAbsolutePath().normalize();
            // Canonicalize once for defense-in-depth against symlink-based escapes.
            final Path canonicalDstDir =
                    resolvedDstDir.toFile().getCanonicalFile().toPath();

            ZipEntry entry = zipIn.getNextEntry();
            if (entry == null) {
                throw new IOException("No zip entry found in bytes");
            }

            int entryCount = 0;
            long totalExtractedBytes = 0L;
            while (entry != null) {
                entryCount++;
                if (entryCount > MAX_ENTRIES) {
                    throw new IOException("Zip archive contains too many entries (>" + MAX_ENTRIES + ")");
                }

                final String entryName = entry.getName();
                validateEntryName(entryName);

                final int depth = normalizedRelativePathDepth(entryName);
                if (depth > MAX_DEPTH) {
                    throw new IOException("Zip entry is nested too deeply (depth=" + depth + ", max=" + MAX_DEPTH
                            + "): " + entryName);
                }

                final Path filePath = resolvedDstDir.resolve(entryName).normalize();
                if (!filePath.startsWith(resolvedDstDir)) {
                    // Prevent Zip Slip attacks (normalized absolute-path containment check)
                    throw new IOException("Zip file entry is outside of the destination directory: " + filePath);
                }

                final File fileOrDir = filePath.toFile();
                final Path canonicalFilePath = fileOrDir.getCanonicalFile().toPath();
                if (!canonicalFilePath.startsWith(canonicalDstDir)) {
                    // Defense-in-depth against symlink tricks where normalized paths still appear contained.
                    throw new IOException("Zip file entry is outside of the destination directory: " + filePath);
                }
                final File directory = fileOrDir.getParentFile();
                if (!directory.exists() && !directory.mkdirs()) {
                    throw new IOException("Unable to create the parent directories for the file: " + fileOrDir);
                }

                if (!entry.isDirectory()) {
                    final long remainingTotal = MAX_TOTAL_EXTRACTED_BYTES - totalExtractedBytes;
                    final long written = extractSingleFileWithLimits(zipIn, filePath, MAX_ENTRY_BYTES, remainingTotal);
                    totalExtractedBytes += written;
                    log.info(" - Extracted update file {}", filePath);
                } else {
                    if (!fileOrDir.exists() && !fileOrDir.mkdirs()) {
                        throw new IOException("Unable to create assets sub-directory: " + fileOrDir);
                    }
                    log.info(" - Created assets sub-directory {}", fileOrDir);
                }
                zipIn.closeEntry();
                entry = zipIn.getNextEntry();
            }
        }
    }

    /**
     * Extracts a zip entry (file entry).
     *
     * @param inputStream Input stream of zip file content
     * @param filePath Output file name
     * @throws IOException if the file can't be written
     */
    public static void extractSingleFile(@NonNull ZipInputStream inputStream, @NonNull Path filePath)
            throws IOException {
        requireNonNull(inputStream);
        requireNonNull(filePath);

        extractSingleFileWithLimits(inputStream, filePath, MAX_ENTRY_BYTES, Long.MAX_VALUE);
    }

    private static long extractSingleFileWithLimits(
            @NonNull final ZipInputStream inputStream,
            @NonNull final Path filePath,
            final long maxEntryBytes,
            final long remainingTotalBytes)
            throws IOException {
        long written = 0L;
        try (var bos = new BufferedOutputStream(new FileOutputStream(filePath.toFile()))) {
            final var bytesIn = new byte[BUFFER_SIZE];
            int read;
            while ((read = inputStream.read(bytesIn)) != -1) {
                if (written + read > maxEntryBytes) {
                    throw new IOException(
                            "Zip entry exceeds max allowed size (" + maxEntryBytes + " bytes): " + filePath);
                }
                if (written + read > remainingTotalBytes) {
                    throw new IOException("Zip archive exceeds max allowed extracted size (" + MAX_TOTAL_EXTRACTED_BYTES
                            + " bytes): " + filePath);
                }
                bos.write(bytesIn, 0, read);
                written += read;
            }
        } catch (IOException e) {
            // Best-effort cleanup of partially written output
            try {
                final var f = filePath.toFile();
                if (f.exists() && !f.delete()) {
                    log.warn("Unable to delete partially extracted file {}", filePath);
                }
            } catch (Exception ignored) {
                // ignore cleanup failures
            }
            throw e;
        }
        return written;
    }

    private static void validateEntryName(final String name) throws IOException {
        if (name == null || name.isEmpty()) {
            throw new IOException("Zip entry has an empty name");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IOException("Zip entry name is too long (>" + MAX_NAME_LENGTH + " chars)");
        }

        // Reject null bytes and control characters (including newlines, tabs, DEL).
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            if (c == 0 || Character.isISOControl(c)) {
                throw new IOException("Zip entry name contains a control character");
            }
        }

        // Reject absolute paths and Windows drive-letter paths.
        if (name.startsWith("/") || name.startsWith("\\") || name.matches("^[A-Za-z]:[\\\\/].*")) {
            throw new IOException("Zip entry name is an absolute path");
        }

        // Reject backslashes to avoid platform-specific traversal quirks.
        if (name.indexOf('\\') != -1) {
            throw new IOException("Zip entry name contains invalid path separator");
        }

        // Reject obvious traversal attempts early (Zip Slip is also blocked via canonical-path check).
        if (name.equals("..") || name.startsWith("../") || name.contains("/../") || name.endsWith("/..")) {
            throw new IOException("Zip entry name contains path traversal");
        }
    }

    private static int normalizedRelativePathDepth(final String entryName) throws IOException {
        final var normalized = Path.of(entryName).normalize();
        if (normalized.isAbsolute()) {
            // Should already be handled by validateEntryName(), but keep this as defense-in-depth.
            throw new IOException("Zip entry name is an absolute path");
        }
        return normalized.getNameCount();
    }
}
