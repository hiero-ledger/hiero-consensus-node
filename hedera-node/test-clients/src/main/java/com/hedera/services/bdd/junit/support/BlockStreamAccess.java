// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support;

import static com.hedera.node.app.hapi.utils.exports.recordstreaming.RecordStreamingUtils.SIDECAR_ONLY_TOKEN;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Central utility for accessing blocks created by tests.
 */
public enum BlockStreamAccess {
    BLOCK_STREAM_ACCESS;

    private static final Logger log = LogManager.getLogger(BlockStreamAccess.class);

    /**
     * Reads all files matching the marker file pattern from the given path
     * and returns the latest marker file with the highest block number.
     *
     * @param path the path to read blocks from
     * @return the ascending set of block marker file numbers
     * @throws UncheckedIOException if an I/O error occurs
     */
    public static Set<Long> getAllMarkerFileNumbers(@NonNull final Path path) {
        try (final var stream = Files.walk(path)) {
            return stream.map(BlockStreamAccess::extractMarkerFileNumber)
                    .filter(num -> num != -1)
                    .sorted()
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (IOException e) {
            log.error("Failed to read blocks from path {}", path, e);
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Extracts the number from the given marker file.
     *
     * @param path the file name
     * @return the block number, or -1 if it cannot be extracted
     */
    private static long extractMarkerFileNumber(@NonNull final Path path) {
        final var fileName = path.getFileName().toString();

        if (!fileName.endsWith(".mf")) {
            return -1;
        }

        try {
            int i = fileName.indexOf(".mf");
            return Long.parseLong(fileName.substring(0, i));
        } catch (Exception ignore) {
        }
        return -1;
    }

    /**
     * Checks if the given file is a block marker file.
     *
     * @param file the file
     * @return true if the file is a block marker file, false otherwise
     */
    public static boolean isBlockMarkerFile(@NonNull final File file) {
        return file.isFile()
                && file.getName().endsWith(".mf")
                && !file.getName().contains(SIDECAR_ONLY_TOKEN);
    }
}
