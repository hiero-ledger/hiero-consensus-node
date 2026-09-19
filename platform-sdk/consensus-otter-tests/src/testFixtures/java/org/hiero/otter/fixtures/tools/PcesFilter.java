// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.tools;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.hiero.consensus.io.IOIterator;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.pces.config.PcesFileWriterType;
import org.hiero.consensus.pces.impl.common.PcesFile;
import org.hiero.consensus.pces.impl.common.PcesMutableFile;

/**
 * Copies a preconsensus event stream (PCES) from a source directory to a target directory,
 * skipping events the supplied {@link Predicate} rejects. Signatures, hashes, and event
 * contents are preserved verbatim; only the set of events is changed.
 *
 * <p>Intended for building asymmetric fixtures that reproduce the mainnet post-restart
 * stake-threshold-flip scenario: starting from a single complete PCES produced by the real
 * platform, split it into per-node PCES variants that are each independently insufficient
 * for a fame decision on some post-freeze round but whose union across the online subset is
 * just sufficient once gossip merges them during OBSERVING.
 *
 * <p>Layout assumption: the source PCES directory is the on-disk layout the platform writes,
 * e.g. {@code <root>/<yyyy>/<MM>/<dd>/<timestamp>_seq<N>_minr<a>_maxr<b>_orgn<c>.pces}.
 * The filter walks all {@code *.pces} files under the source root, opens each via
 * {@link PcesFile#of(Path)}, iterates its events in order, and writes accepted events to a
 * <em>single</em> output file at the target root whose span covers the accepted events'
 * birth-round range. This is simpler than mirroring the source's file structure and is
 * sufficient for the platform's replay path (which iterates all {@code *.pces} files under
 * the PCES root).
 */
public final class PcesFilter {

    private PcesFilter() {
        // Utility class.
    }

    /**
     * Filter every {@code *.pces} file under {@code sourcePcesRoot}, keeping only events for
     * which {@code keep} returns {@code true}, and write the accepted events to a single new
     * PCES file under {@code targetPcesRoot}. The target directory must not already exist.
     *
     * @param sourcePcesRoot the source PCES root (must contain at least one {@code *.pces}
     *                       file, possibly nested under yyyy/MM/dd sub-directories)
     * @param targetPcesRoot the target PCES root; must not already exist. Created by this
     *                       method along with any needed date sub-directories
     * @param keep           predicate applied to every event; only those returning
     *                       {@code true} are copied to the target
     * @return the {@link FilterResult} summarising how many events were read/kept and the
     *         birth-round span of the accepted events
     * @throws IOException if any file operation fails
     */
    @NonNull
    public static FilterResult filter(
            @NonNull final Path sourcePcesRoot,
            @NonNull final Path targetPcesRoot,
            @NonNull final Predicate<PlatformEvent> keep)
            throws IOException {
        requireNonNull(sourcePcesRoot);
        requireNonNull(targetPcesRoot);
        requireNonNull(keep);

        if (!Files.isDirectory(sourcePcesRoot)) {
            throw new IOException("Source PCES root is not a directory: " + sourcePcesRoot);
        }
        if (Files.exists(targetPcesRoot)) {
            throw new IOException("Target PCES root already exists: " + targetPcesRoot);
        }

        final List<Path> sourceFiles = collectPcesFiles(sourcePcesRoot);
        if (sourceFiles.isEmpty()) {
            throw new IOException("No .pces files found under " + sourcePcesRoot);
        }

        // Two-pass: first pass tallies min/max birth-round of accepted events so we can size
        // the target file's span; second pass writes them. This keeps everything in a single
        // output file, which the platform's replay path is happy with.
        long minBirthRound = Long.MAX_VALUE;
        long maxBirthRound = Long.MIN_VALUE;
        long readCount = 0;
        long keptCount = 0;
        long origin = 0;
        Instant firstTimestamp = Instant.EPOCH;
        long firstSequenceNumber = 0;

        for (int i = 0; i < sourceFiles.size(); i++) {
            final PcesFile sourceDescriptor = PcesFile.of(sourceFiles.get(i));
            if (i == 0) {
                firstTimestamp = sourceDescriptor.getTimestamp();
                firstSequenceNumber = sourceDescriptor.getSequenceNumber();
                origin = sourceDescriptor.getOrigin();
            }
            try (final IOIterator<PlatformEvent> it = sourceDescriptor.iterator(Long.MIN_VALUE)) {
                while (it.hasNext()) {
                    final PlatformEvent event = it.next();
                    readCount++;
                    if (keep.test(event)) {
                        keptCount++;
                        minBirthRound = Math.min(minBirthRound, event.getBirthRound());
                        maxBirthRound = Math.max(maxBirthRound, event.getBirthRound());
                    }
                }
            }
        }

        if (keptCount == 0) {
            throw new IOException("Filter rejected every event; nothing to write to " + targetPcesRoot);
        }

        // Create the single output file. Reuse the source's first-file timestamp so the on-
        // disk directory layout matches the platform's expected yyyy/MM/dd pattern.
        Files.createDirectories(targetPcesRoot);
        final PcesFile targetDescriptor =
                PcesFile.of(firstTimestamp, firstSequenceNumber, minBirthRound, maxBirthRound, origin, targetPcesRoot);

        try (final AutoCloseablePcesMutableFile out =
                new AutoCloseablePcesMutableFile(targetDescriptor.getMutableFile(PcesFileWriterType.OUTPUT_STREAM))) {
            for (final Path sourceFile : sourceFiles) {
                final PcesFile sourceDescriptor = PcesFile.of(sourceFile);
                try (final IOIterator<PlatformEvent> it = sourceDescriptor.iterator(Long.MIN_VALUE)) {
                    while (it.hasNext()) {
                        final PlatformEvent event = it.next();
                        if (keep.test(event)) {
                            out.file.writeEvent(event);
                        }
                    }
                }
            }
            out.file.flush();
        }

        return new FilterResult(readCount, keptCount, minBirthRound, maxBirthRound);
    }

    /**
     * Recursively find all {@code *.pces} files under {@code root}, sorted by sequence
     * number (extracted from the filename) so events are visited in the same order the
     * platform would replay them.
     */
    @NonNull
    private static List<Path> collectPcesFiles(@NonNull final Path root) throws IOException {
        try (final Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(PcesFile.EVENT_FILE_EXTENSION))
                    .sorted(Comparator.comparingLong(PcesFilter::sequenceNumberOf))
                    .toList();
        }
    }

    private static long sequenceNumberOf(@NonNull final Path path) {
        try {
            return PcesFile.of(path).getSequenceNumber();
        } catch (final IOException e) {
            throw new RuntimeException("Cannot parse PCES filename " + path, e);
        }
    }

    /**
     * Summary returned by {@link #filter(Path, Path, Predicate)}.
     */
    public record FilterResult(long eventsRead, long eventsKept, long minBirthRoundKept, long maxBirthRoundKept) {}

    /** {@link PcesMutableFile} doesn't implement {@link AutoCloseable}; this wraps it. */
    private static final class AutoCloseablePcesMutableFile implements AutoCloseable {
        private final PcesMutableFile file;

        private AutoCloseablePcesMutableFile(@NonNull final PcesMutableFile file) {
            this.file = requireNonNull(file);
        }

        @Override
        public void close() throws IOException {
            file.close();
        }
    }
}
