// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.event.preconsensus.BestEffortPcesFileCopy;
import com.swirlds.platform.event.preconsensus.CommonPcesWriter;
import com.swirlds.platform.event.preconsensus.PcesConfig;
import com.swirlds.platform.event.preconsensus.PcesFileManager;
import com.swirlds.platform.event.preconsensus.PcesFileReader;
import com.swirlds.platform.event.preconsensus.PcesFileTracker;
import com.swirlds.platform.event.preconsensus.PcesMultiFileIterator;
import com.swirlds.platform.state.snapshot.SavedStateMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
     * Takes a point-in-time snapshot of the PCES files.
     * <p>
     * At invocation time, this method creates a temporary, read-only copy of the
     * current PCES files on disk, filtered according to the provided bounds. The returned
     * {@code Stream<Path>} iterates over the paths of those temporary copies. The snapshot
     * is consistent for the lifetime of the stream, even if source files change afterward.
     * </p>
     *
     * <p><strong>Lifecycle:</strong> The temporary snapshot (i.e., the directory containing
     * the copied files) is deleted as soon as the stream is closed or has finished
     * processing. Callers must not cache or store any {@code Path} from this stream for use
     * after the stream has been consumed/closed, as the files will no longer exist.</p>
     *
     * @param configuration configuration used to locate and filter PCES files
     * @param selfId the node's self ID
     * @param lowerBound the lowest (inclusive) event bound considered “non-ancient” relative
     *                   to the state being written
     * @param stateRound the round of the state being written; used as an upper bound.
     *                   Due to file boundary alignment, the final file in the set may also
     *                   contain events with birth rounds greater than {@code stateRound}.
     *                   If a strict upper bound is required, the caller must filter records
     *                   within the file accordingly.
     * @return a finite stream of {@code Path}s for the temporary snapshot files; closing the
     *         stream removes the snapshot from disk
     * @apiNote Use in a try-with-resources to ensure prompt cleanup:
     * <pre>{@code
     * try (Stream<Path> files = snapshotPcesFiles(cfg, selfId, lowerBound, stateRound)) {
     *     files.forEach(this::process);
     * } // snapshot directory is deleted here
     * }</pre>
     * @throws IOException if snapshot creation, enumeration, or cleanup fails
     */
    public static Stream<Path> pcesSnapshot(
            final @NonNull Configuration configuration,
            final @NonNull NodeId selfId,
            final long lowerBound,
            final long stateRound)
            throws IOException {
        final Path destinationDirectory =
                Files.createTempDirectory(PCES_TEMPORARY_DIR).resolve(stateRound + "");
        BestEffortPcesFileCopy.copyPcesFilesRetryOnFailure(
                configuration, selfId, destinationDirectory, lowerBound, stateRound);
        final Stream<Path> list = Files.walk(destinationDirectory);
        return list.filter(Files::isRegularFile).onClose(() -> {
            try {
                if (destinationDirectory.toFile().exists()) {
                    FileUtils.deleteDirectory(destinationDirectory);
                }
            } catch (IOException e) {
                // Do nothing, the dir is temporal it will be deleted when jvm exits
            }
        });
    }
}
