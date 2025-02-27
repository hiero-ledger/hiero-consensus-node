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
 * <p>
 * This class was copied from hedera-mono-service/src/main/java/com/hedera/node/app/service/mono/utils/UnzipUtility.java
 * Once we are wholly migrated to the modularized services, we can remove the other location.
 * */
public final class UnzipUtility {
    private static final Logger log = LogManager.getLogger(UnzipUtility.class);

    private static final int BUFFER_SIZE = 4096;

    // these constants are used to prevent Zip Bomb Attacks
    private static final int THRESHOLD_ENTRIES = 10000; // max # of entries to allow in zip files
    private static final int THRESHOLD_ZIP_SIZE = 1000000000; // 1 GB - max allowed total size of all uncompressed files
    private static final int THRESHOLD_ENTRY_SIZE = 100000000; // 100 MB - max allowed size of one uncompressed file
    // max allowed ratio between uncompressed and compressed file size
    private static final double THRESHOLD_RATIO = 100;

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

        int totalSizeArchive = 0; // total size of the zip archive once uncompressed
        int totalEntryArchive = 0; // total number of entries in the zip archive

        try (final var zipIn = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry = zipIn.getNextEntry();

            if (entry == null) {
                throw new IOException("No zip entry found in bytes");
            }
            while (entry != null) {
                totalEntryArchive++;
                if (totalEntryArchive > THRESHOLD_ENTRIES) {
                    throw new IOException("Zip file entry count exceeds threshold: " + THRESHOLD_ENTRIES);
                }
                Path filePath = dstDir.resolve(entry.getName());
                final File fileOrDir = filePath.toFile();
                final String canonicalPath = fileOrDir.getCanonicalPath();
                if (!canonicalPath.startsWith(dstDir.toFile().getCanonicalPath())) {
                    // prevent Zip Slip attack
                    throw new IOException("Zip file entry is outside of the destination directory: " + filePath);
                }
                final File directory = fileOrDir.getParentFile();
                if (!directory.exists() && !directory.mkdirs()) {
                    throw new IOException("Unable to create the parent directories for the file: " + fileOrDir);
                }

                if (!entry.isDirectory()) {
                    totalSizeArchive += extractSingleFile(zipIn, filePath, entry.getCompressedSize());
                    if (totalSizeArchive > THRESHOLD_ZIP_SIZE) {
                        throw new IOException("Zip file size exceeds threshold: " + THRESHOLD_ZIP_SIZE);
                    }
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
     * @param compressedSize Size of this zip entry while still compressed in bytes
     * @return Size of this zip entry once uncompressed
     * @throws IOException if the file can't be written
     */
    public static int extractSingleFile(
            @NonNull ZipInputStream inputStream, @NonNull Path filePath, long compressedSize) throws IOException {
        requireNonNull(inputStream);
        requireNonNull(filePath);
        int totalSizeEntry = 0; // size of this zip entry once uncompressed

        try (var bos = new BufferedOutputStream(new FileOutputStream(filePath.toFile()))) {
            final var bytesIn = new byte[BUFFER_SIZE];
            int read;
            while ((read = inputStream.read(bytesIn)) != -1) {
                totalSizeEntry += read;
                if (totalSizeEntry > THRESHOLD_ENTRY_SIZE) {
                    // the uncompressed file size is too large, could be a zip bomb attack
                    throw new IOException("Zip bomb attack detected, aborting unzip!");
                }
                if ((double) totalSizeEntry / compressedSize > THRESHOLD_RATIO) {
                    // the uncompressed file size is too large compared to the compressed size,
                    // could be a zip bomb attack
                    throw new IOException("Zip bomb attack detected, aborting unzip!");
                }
                bos.write(bytesIn, 0, read);
            }
        }
        return totalSizeEntry;
    }
}
