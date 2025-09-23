// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.event.preconsensus.CommonPcesWriter;
import com.swirlds.platform.event.preconsensus.PcesConfig;
import com.swirlds.platform.event.preconsensus.PcesFile;
import com.swirlds.platform.event.preconsensus.PcesFileManager;
import com.swirlds.platform.event.preconsensus.PcesFileReader;
import com.swirlds.platform.event.preconsensus.PcesFileTracker;
import com.swirlds.platform.event.preconsensus.PcesMultiFileIterator;
import com.swirlds.platform.event.preconsensus.PcesUtilities;
import com.swirlds.platform.state.snapshot.SavedStateMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;

public final class SavedStateUtils {
    /** The temporary directory to move PCES files to while there are being filtered out */
    private static final String PCES_TEMPORARY_DIR = "pces-tmp";

    private SavedStateUtils() {
        // prevent instantiation
    }

    /**
     * Prepares the state for transplanting by removing future events from the PCES files.
     *
     * @param statePath the path to the state directory
     * @param platformContext the platform context
     * @return the number of events that were discarded due to being from a future round
     * @throws IOException if an I/O error occurs
     */
    public static int prepareStateForTransplant(
            @NonNull final Path statePath, @NonNull final PlatformContext platformContext) throws IOException {
        final Path pcesFiles = statePath.resolve(platformContext
                .getConfiguration()
                .getConfigData(PcesConfig.class)
                .databaseDirectory());
        final Path pcesTmp = statePath.resolve(PCES_TEMPORARY_DIR);

        // move the old files to a temporary directory
        Files.move(pcesFiles, pcesTmp, StandardCopyOption.REPLACE_EXISTING);

        final SavedStateMetadata stateMetadata =
                SavedStateMetadata.parse(statePath.resolve(SavedStateMetadata.FILE_NAME));

        final PcesFileTracker fileTracker = PcesFileReader.readAndResolveEventFilesFromDisk(
                platformContext.getConfiguration(),
                platformContext.getRecycleBin(),
                pcesTmp,
                stateMetadata.round(),
                false);

        final PcesMultiFileIterator eventIterator =
                fileTracker.getEventIterator(stateMetadata.minimumBirthRoundNonAncient(), stateMetadata.round());
        final CommonPcesWriter pcesWriter = new CommonPcesWriter(
                platformContext,
                new PcesFileManager(platformContext, new PcesFileTracker(), pcesFiles, stateMetadata.round()));
        pcesWriter.beginStreamingNewEvents();

        // Go through the events and write them to the new files, skipping any events that are from a future round
        int discardedEventCount = 0;
        while (eventIterator.hasNext()) {
            final PlatformEvent event = eventIterator.next();
            if (event.getBirthRound() > stateMetadata.round()) {
                discardedEventCount++;
                continue;
            }
            pcesWriter.prepareOutputStream(event);
            pcesWriter.getCurrentMutableFile().writeEvent(event);
        }
        pcesWriter.closeCurrentMutableFile();

        FileUtils.deleteDirectory(pcesTmp);

        return discardedEventCount;
    }

    /**
     * Scan the file system for event files and add them to the collection of tracked files.
     *
     * @param configuration the configuration
     * @param selfId Node's self id
     * @param minimumRound if gaps are permitted in sequence number
     * @param stateRound if gaps are permitted in sequence number
     * @return the files read from disk
     * @throws IOException if there is an error reading the files
     */
    public static List<Path> pcesFilesFromDisk(
            final @NonNull Configuration configuration,
            final @NonNull NodeId selfId,
            final long minimumRound,
            final long stateRound)
            throws IOException {
        final PcesFileTracker fileTracker =
                PcesFileReader.readFilesFromDisk(PcesUtilities.getDatabaseDirectory(configuration, selfId), false);

        final var allFiles = fileTracker.getFileIterator(minimumRound, stateRound);
        return Stream.generate(allFiles::next).map(PcesFile::getPath).toList();
    }
}
