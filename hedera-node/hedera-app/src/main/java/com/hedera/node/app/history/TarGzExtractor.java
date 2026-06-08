// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Extracts {@code .tar.gz} archives using native Java tar parsing,
 * with security limits aligned with {@code UnzipUtility}.
 */
public final class TarGzExtractor {
    private static final Logger log = LogManager.getLogger(TarGzExtractor.class);

    // Security limits
    private static final int MAX_ENTRIES = 10_000;
    private static final long MAX_TOTAL_BYTES = 10L * 1024L * 1024L * 1024L; // 10 GiB
    private static final long MAX_ENTRY_BYTES = 4L * 1024L * 1024L * 1024L; // 4 GiB
    private static final int MAX_DEPTH = 20;
    private static final int MAX_NAME_LENGTH = 4096;
    private static final int EXTRACT_BUFFER_SIZE = 8192;

    // Tar format constants
    private static final int BLOCK_SIZE = 512;
    private static final int NAME_OFFSET = 0;
    private static final int NAME_LENGTH = 100;
    private static final int SIZE_OFFSET = 124;
    private static final int SIZE_LENGTH = 12;
    private static final int TYPEFLAG_OFFSET = 156;
    private static final int MAGIC_OFFSET = 257;
    private static final int PREFIX_OFFSET = 345;
    private static final int PREFIX_LENGTH = 155;

    // Type flag values
    private static final byte TYPEFLAG_DIRECTORY = '5';
    private static final byte TYPEFLAG_SYMLINK = '2';
    private static final byte TYPEFLAG_LINK = '1';
    private static final byte TYPEFLAG_GNU_LONGNAME = 'L';

    private TarGzExtractor() {}

    /**
     * Extracts a {@code .tar.gz} archive to the given destination directory.
     *
     * @param tarGzPath path to the {@code .tar.gz} file
     * @param destDir   directory to extract into
     * @throws IOException on I/O error or if any security limit is exceeded
     */
    public static void extract(@NonNull final Path tarGzPath, @NonNull final Path destDir) throws IOException {
        requireNonNull(tarGzPath);
        requireNonNull(destDir);
        final Path resolvedDestDir = destDir.toAbsolutePath().normalize();
        final Path canonicalDestDir =
                resolvedDestDir.toFile().getCanonicalFile().toPath();

        try (var fis = new FileInputStream(tarGzPath.toFile());
                var gis = new GZIPInputStream(fis)) {
            int entryCount = 0;
            long totalExtractedBytes = 0L;
            String longName = null;

            while (true) {
                final byte[] header = gis.readNBytes(BLOCK_SIZE);
                if (header.length < BLOCK_SIZE || isAllZeros(header)) {
                    break;
                }

                final byte typeFlag = header[TYPEFLAG_OFFSET];
                final long entrySize = parseOctalSize(header);

                // Handle GNU long name extension: data is the real name, next header is the entry
                if (typeFlag == TYPEFLAG_GNU_LONGNAME) {
                    longName = readLongName(gis, entrySize);
                    continue;
                }

                // Skip PAX extended headers
                if (typeFlag == 'x' || typeFlag == 'g' || typeFlag == 'X') {
                    skipDataBlocks(gis, entrySize);
                    continue;
                }

                entryCount++;
                if (entryCount > MAX_ENTRIES) {
                    throw new IOException("Tar archive contains too many entries (>" + MAX_ENTRIES + ")");
                }

                final String entryName;
                if (longName != null) {
                    entryName = longName;
                    longName = null;
                } else {
                    entryName = parseTarName(header);
                }

                validateEntryName(entryName);

                final int depth = Path.of(entryName).normalize().getNameCount();
                if (depth > MAX_DEPTH) {
                    throw new IOException(
                            "Tar entry nested too deeply (depth=" + depth + ", max=" + MAX_DEPTH + "): " + entryName);
                }

                // Reject symbolic and hard links for security
                if (typeFlag == TYPEFLAG_SYMLINK || typeFlag == TYPEFLAG_LINK) {
                    log.warn("Skipping symbolic/hard link in tar archive: {}", entryName);
                    skipDataBlocks(gis, entrySize);
                    continue;
                }

                final Path filePath = resolvedDestDir.resolve(entryName).normalize();
                if (!filePath.startsWith(resolvedDestDir)) {
                    throw new IOException("Tar entry is outside destination directory: " + entryName);
                }

                final Path canonicalPath = filePath.toFile().getCanonicalFile().toPath();
                if (!canonicalPath.startsWith(canonicalDestDir)) {
                    throw new IOException("Tar entry is outside destination directory (symlink): " + entryName);
                }

                if (typeFlag == TYPEFLAG_DIRECTORY) {
                    Files.createDirectories(filePath);
                    skipDataBlocks(gis, entrySize);
                } else {
                    Files.createDirectories(filePath.getParent());
                    final long remaining = MAX_TOTAL_BYTES - totalExtractedBytes;
                    totalExtractedBytes += extractEntry(gis, filePath, entrySize, remaining);
                }
            }
        }
    }

    // --- Entry extraction ---

    private static long extractEntry(
            @NonNull final InputStream is,
            @NonNull final Path filePath,
            final long entrySize,
            final long remainingTotalBytes)
            throws IOException {
        long written = 0L;
        try (var bos = new BufferedOutputStream(new FileOutputStream(filePath.toFile()))) {
            final var buffer = new byte[EXTRACT_BUFFER_SIZE];
            long bytesLeft = entrySize;
            while (bytesLeft > 0) {
                final int toRead = (int) Math.min(buffer.length, bytesLeft);
                final int read = is.read(buffer, 0, toRead);
                if (read == -1) {
                    throw new IOException("Unexpected end of tar archive while extracting: " + filePath);
                }
                if (written + read > MAX_ENTRY_BYTES) {
                    throw new IOException("Tar entry exceeds max size (" + MAX_ENTRY_BYTES + " bytes): " + filePath);
                }
                if (written + read > remainingTotalBytes) {
                    throw new IOException(
                            "Tar archive exceeds max total extracted size (" + MAX_TOTAL_BYTES + " bytes)");
                }
                bos.write(buffer, 0, read);
                written += read;
                bytesLeft -= read;
            }
        } catch (final IOException e) {
            // Best-effort cleanup of partially written output
            try {
                final var f = filePath.toFile();
                if (f.exists() && !f.delete()) {
                    log.warn("Unable to delete partially extracted file {}", filePath);
                }
            } catch (final Exception ignored) {
                // ignore cleanup failures
            }
            throw e;
        }
        skipPadding(is, entrySize);
        return written;
    }

    // --- Header parsing ---

    private static boolean isAllZeros(@NonNull final byte[] block) {
        for (final byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String parseTarName(@NonNull final byte[] header) {
        final String magic = new String(header, MAGIC_OFFSET, 5, StandardCharsets.US_ASCII);
        final String prefix;
        if ("ustar".equals(magic)) {
            prefix = extractNullTerminatedString(header, PREFIX_OFFSET, PREFIX_LENGTH);
        } else {
            prefix = "";
        }
        final String name = extractNullTerminatedString(header, NAME_OFFSET, NAME_LENGTH);
        if (!prefix.isEmpty()) {
            return prefix + "/" + name;
        }
        return name;
    }

    private static String extractNullTerminatedString(
            @NonNull final byte[] block, final int offset, final int maxLength) {
        int end = offset;
        for (int i = offset; i < offset + maxLength; i++) {
            if (block[i] == 0) {
                break;
            }
            end = i + 1;
        }
        return new String(block, offset, end - offset, StandardCharsets.US_ASCII);
    }

    private static long parseOctalSize(@NonNull final byte[] header) {
        // GNU binary size encoding: high bit set in first byte
        if ((header[SIZE_OFFSET] & 0x80) != 0) {
            long size = 0;
            for (int i = SIZE_OFFSET + 1; i < SIZE_OFFSET + SIZE_LENGTH; i++) {
                size = (size << 8) | (header[i] & 0xFF);
            }
            return size;
        }
        final String octal =
                extractNullTerminatedString(header, SIZE_OFFSET, SIZE_LENGTH).trim();
        if (octal.isEmpty()) {
            return 0;
        }
        return Long.parseLong(octal, 8);
    }

    private static String readLongName(@NonNull final InputStream is, final long size) throws IOException {
        final byte[] nameBytes = is.readNBytes((int) size);
        if (nameBytes.length < size) {
            throw new IOException("Unexpected end of tar archive while reading long name");
        }
        skipPadding(is, size);
        // Remove trailing null bytes
        int end = nameBytes.length;
        while (end > 0 && nameBytes[end - 1] == 0) {
            end--;
        }
        return new String(nameBytes, 0, end, StandardCharsets.US_ASCII);
    }

    // --- Stream positioning ---

    private static void skipDataBlocks(@NonNull final InputStream is, final long dataSize) throws IOException {
        final long padding = (BLOCK_SIZE - (dataSize % BLOCK_SIZE)) % BLOCK_SIZE;
        long toSkip = dataSize + padding;
        while (toSkip > 0) {
            final long skipped = is.skip(toSkip);
            if (skipped > 0) {
                toSkip -= skipped;
            } else {
                if (is.read() == -1) {
                    break;
                }
                toSkip--;
            }
        }
    }

    private static void skipPadding(@NonNull final InputStream is, final long dataSize) throws IOException {
        final long padding = (BLOCK_SIZE - (dataSize % BLOCK_SIZE)) % BLOCK_SIZE;
        long toSkip = padding;
        while (toSkip > 0) {
            final long skipped = is.skip(toSkip);
            if (skipped > 0) {
                toSkip -= skipped;
            } else {
                if (is.read() == -1) {
                    break;
                }
                toSkip--;
            }
        }
    }

    // --- Entry name validation ---

    private static void validateEntryName(@NonNull final String name) throws IOException {
        if (name == null || name.isEmpty()) {
            throw new IOException("Tar entry has an empty name");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IOException("Tar entry name is too long (>" + MAX_NAME_LENGTH + " chars)");
        }
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            if (c == 0 || Character.isISOControl(c)) {
                throw new IOException("Tar entry name contains a control character");
            }
        }
        if (name.startsWith("/") || name.startsWith("\\") || name.matches("^[A-Za-z]:[\\\\/].*")) {
            throw new IOException("Tar entry name is an absolute path");
        }
        if (name.indexOf('\\') != -1) {
            throw new IOException("Tar entry name contains invalid path separator");
        }
        if (name.equals("..") || name.startsWith("../") || name.contains("/../") || name.endsWith("/..")) {
            throw new IOException("Tar entry name contains path traversal");
        }
    }
}
