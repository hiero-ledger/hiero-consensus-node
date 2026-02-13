// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pces.impl.test.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.hiero.consensus.io.IOIterator;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.pces.impl.common.PcesFile;
import org.hiero.consensus.pces.impl.common.PcesMultiFileIterator;
import org.hiero.consensus.pces.impl.common.PcesUtilities;

/**
 * Factory for creating an {@link IOIterator} over {@link PlatformEvent}s from PCES files on disk.
 */
public final class PcesFileIteratorFactory {

    private PcesFileIteratorFactory() {}

    /**
     * Scans the given directory for PCES files and returns an IOIterator
     * over all PlatformEvents contained in those files, ordered by file
     * sequence number.
     *
     * @param directory path to scan for .pces files
     * @return IOIterator over all events
     * @throws IOException if an I/O error occurs
     */
    @NonNull
    public static IOIterator<PlatformEvent> createIterator(@NonNull final Path directory) throws IOException {
        return createIterator(directory, 0);
    }

    /**
     * Overload accepting a lower-bound birth round filter.
     *
     * @param directory  path to scan for .pces files
     * @param lowerBound minimum birth round for events
     * @return IOIterator over all events with birth round &gt;= lowerBound
     * @throws IOException if an I/O error occurs
     */
    @NonNull
    public static IOIterator<PlatformEvent> createIterator(@NonNull final Path directory, final long lowerBound)
            throws IOException {
        final List<PcesFile> files;
        PcesUtilities.compactPreconsensusEventFiles(directory);
        try (final Stream<Path> stream = Files.walk(directory)) {
            files = stream.filter(p -> p.toString().endsWith(".pces"))
                    .map(p -> {
                        try {
                            return PcesFile.of(p);
                        } catch (final IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .sorted(Comparator.comparingLong(PcesFile::getSequenceNumber))
                    .toList();
        }
        return new PcesMultiFileIterator(lowerBound, files.iterator());
    }
}
