// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import com.swirlds.base.time.Time;
import com.swirlds.common.io.IOIterator;
import com.swirlds.platform.state.snapshot.SavedStateMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * This class helps to remove future events from PCES files.
 */
public final class PcesEventFilter {
    private final long freezeRound;
    private final long minimumBirthRoundNonAncient;
    private Time time = Time.getCurrent();
    /**
     * Removes future events from the PCES files.
     *
     * @param savedStatePath the path to the saved state file
     * @throws IOException if an I/O error occurs
     */
    private PcesEventFilter(Path savedStatePath) throws IOException {
        final SavedStateMetadata stateMetadata =
                SavedStateMetadata.parse(savedStatePath.resolve(SavedStateMetadata.FILE_NAME));
        this.freezeRound = stateMetadata.round();
        this.minimumBirthRoundNonAncient = stateMetadata.minimumBirthRoundNonAncient();
    }

    /**
     * Creates an instance of this filter future events from the PCES files.
     *
     * @param savedStatePath the path to the saved state file
     * @throws IOException if an I/O error occurs
     */
    public static PcesEventFilter create(Path savedStatePath) throws IOException {
        return new PcesEventFilter(savedStatePath);
    }

    @NonNull
    public PcesEventFilter with(@NonNull final Time time) {
        this.time = time;
        return this;
    }

    /**
     * Removes future events from the PCES files in pcesFilePath.
     * The result is saved as a new file in outputPath.
     * If outputPath does not exist, creates it.
     *
     * @param pcesFilesPath  the path to the pces directory
     * @param outputPath         the path where the new pces files is to be written to
     * @return the number of events that were discarded due to being from a future round
     * @throws IOException if an I/O error occurs
     */
    public long filter(@NonNull final Path pcesFilesPath, @NonNull final Path outputPath) throws IOException {
        Objects.requireNonNull(pcesFilesPath, "pcesFilesPath cannot be null");
        Objects.requireNonNull(outputPath, "outputPath cannot be null");
        Files.createDirectories(outputPath);
        final PcesFileTracker initialPcesFiles = PcesFileReader.readFilesFromDisk(pcesFilesPath, true);

        final IOIterator<PlatformEvent> eventIterator =
                initialPcesFiles.getEventIterator(minimumBirthRoundNonAncient, freezeRound);

        final PcesFile preconsensusEventFile = PcesFile.of(
                time.now(), 0, minimumBirthRoundNonAncient, freezeRound, minimumBirthRoundNonAncient, outputPath);
        final PcesMutableFile mutableFile = preconsensusEventFile.getMutableFile(PcesFileWriterType.OUTPUT_STREAM);

        // Go through the events and write them to the new files, skipping any events that are from a future round
        long discardedEventCount = 0;
        while (eventIterator.hasNext()) {
            final PlatformEvent event = eventIterator.next();
            if (event.getBirthRound() > freezeRound) {
                discardedEventCount++;
                continue;
            }
            mutableFile.writeEvent(event);
        }
        mutableFile.sync();
        mutableFile.close();
        return discardedEventCount;
    }
}
