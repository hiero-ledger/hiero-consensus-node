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
            ZipEntry entry = zipIn.getNextEntry();

            if (entry == null) {
                throw new IOException("No zip entry found in bytes");
            }
            while (entry != null) {
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
                    extractSingleFile(zipIn, filePath);
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

        try (var bos = new BufferedOutputStream(new FileOutputStream(filePath.toFile()))) {
            final var bytesIn = new byte[BUFFER_SIZE];
            int read;
            while ((read = inputStream.read(bytesIn)) != -1) {
                bos.write(bytesIn, 0, read);
            }
        }
    }
}
