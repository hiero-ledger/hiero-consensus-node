// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static com.swirlds.common.io.utility.FileUtils.executeAndRename;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STATE_TO_DISK;

import com.swirlds.common.context.PlatformContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;

/**
 * Operations for copying preconsensus event files. Is not fully thread safe, best effort only. Race conditions can
 * cause the copy to fail, but if it does fail it will fail atomically and in a way that is recoverable.
 */
public final class BestEffortPcesFileCopy {

    private static final Logger logger = LogManager.getLogger(BestEffortPcesFileCopy.class);

    /**
     * The number of times to attempt to copy the last PCES file. Access to this file is not really coordinated between
     * this logic and the code responsible for managing PCES file lifecycle, and so there is a small chance that the
     * file moves when we attempt to make a copy. However, this probability is fairly small, and it is very unlikely
     * that we will be unable to snatch a copy in time with a few retries.
     */
    private static final int COPY_PCES_MAX_RETRIES = 10;

    private BestEffortPcesFileCopy() {}

    /**
     * Copy preconsensus event files into the signed state directory. Copying these files is not thread safe and may
     * fail as a result. This method retries several times if a failure is encountered. Success is not guaranteed, but
     * success or failure is atomic and will not throw an exception.
     *
     * @param platformContext the platform context
     * @param selfId the id of this node
     * @param destinationDirectory the directory where the state is being written
     * @param lowerBound the lower bound of events that are not ancient, with respect to the state that is being
     * written
     * @param round the round of the state that is being written
     */
    public static void copyPcesFilesRetryOnFailure(
            @NonNull final PlatformContext platformContext,
            @NonNull final NodeId selfId,
            @NonNull final Path destinationDirectory,
            final long lowerBound,
            final long round) {

        final boolean copyPreconsensusStream = platformContext
                .getConfiguration()
                .getConfigData(PcesConfig.class)
                .copyRecentStreamToStateSnapshots();
        if (!copyPreconsensusStream) {
            // PCES copying is disabled
            return;
        }

        final Path pcesDestination =
                destinationDirectory.resolve("preconsensus-events").resolve(Long.toString(selfId.id()));

        int triesRemaining = COPY_PCES_MAX_RETRIES;
        while (triesRemaining > 0) {
            triesRemaining--;
            try {
                executeAndRename(
                        pcesDestination,
                        temporaryDirectory -> copyPcesFiles(platformContext, selfId, temporaryDirectory, lowerBound),
                        platformContext.getConfiguration());

                return;
            } catch (final IOException | UncheckedIOException e) {
                // Note: Files.walk() sometimes throws an UncheckedIOException (?!!), so we have to catch both.

                if (triesRemaining > 0) {
                    logger.warn(STATE_TO_DISK.getMarker(), "Unable to copy PCES files. Retrying.");
                } else {
                    logger.error(
                            EXCEPTION.getMarker(),
                            "Unable to copy the last PCES file after {} retries. "
                                    + "PCES files will not be written into the state snapshot for round {}.",
                            COPY_PCES_MAX_RETRIES,
                            round,
                            e);
                }
            }
        }
    }

    /**
     * Copy preconsensus event files into the signed state directory. These files are necessary for the platform to use
     * the state file as a starting point. Note: starting a node using the PCES files in the state directory does not
     * guarantee that there is no data loss (i.e. there may be transactions that reach consensus after the state
     * snapshot), but it does allow a node to start up and participate in gossip.
     *
     * <p>
     * This general strategy is not very elegant is very much a hack. But it will allow us to do migration testing using
     * real production states and streams, in the short term. In the longer term we should consider alternate and
     * cleaner strategies.
     *
     * @param platformContext the platform context
     * @param selfId the id of this node
     * @param destinationDirectory the directory where the PCES files should be written
     * @param lowerBound the lower bound of events that are not ancient, with respect to the state that is being
     * written
     */
    private static void copyPcesFiles(
            @NonNull final PlatformContext platformContext,
            @NonNull final NodeId selfId,
            @NonNull final Path destinationDirectory,
            final long lowerBound)
            throws IOException {

        final List<PcesFile> allFiles = gatherPcesFilesOnDisk(selfId, platformContext);
        if (allFiles.isEmpty()) {
            return;
        }

        // Sort by sequence number
        Collections.sort(allFiles);

        // Discard all files that either have an incorrect origin or that do not contain non-ancient events.
        final List<PcesFile> filesToCopy = getRequiredPcesFiles(allFiles, lowerBound);
        if (filesToCopy.isEmpty()) {
            return;
        }

        copyPcesFileList(filesToCopy, destinationDirectory);
    }

    /**
     * Get the preconsensus files that we need to copy to a state. We need any file that has a matching origin and that
     * contains non-ancient events (w.r.t. the state).
     *
     * @param allFiles all PCES files on disk
     * @param lowerBound the lower bound of events that are not ancient, with respect to the state that is being
     * written
     * @return the list of files to copy
     */
    @NonNull
    private static List<PcesFile> getRequiredPcesFiles(@NonNull final List<PcesFile> allFiles, final long lowerBound) {

        final List<PcesFile> filesToCopy = new ArrayList<>();
        final PcesFile lastFile = allFiles.get(allFiles.size() - 1);
        for (final PcesFile file : allFiles) {
            if (file.getOrigin() == lastFile.getOrigin() && file.getUpperBound() >= lowerBound) {
                filesToCopy.add(file);
            }
        }

        if (filesToCopy.isEmpty()) {
            logger.warn(
                    STATE_TO_DISK.getMarker(),
                    "No preconsensus event files meeting specified criteria found to copy. Lower bound: {}",
                    lowerBound);
        } else if (filesToCopy.size() == 1) {
            logger.info(
                    STATE_TO_DISK.getMarker(),
                    """
                            Found 1 preconsensus event file meeting specified criteria to copy.
                                Lower bound: {}
                                File: {}
                            """,
                    lowerBound,
                    filesToCopy.get(0).getPath());
        } else {
            logger.info(
                    STATE_TO_DISK.getMarker(),
                    """
                            Found {} preconsensus event files meeting specified criteria to copy.
                                Lower bound: {}
                                First file to copy: {}
                                Last file to copy: {}
                            """,
                    filesToCopy.size(),
                    lowerBound,
                    filesToCopy.get(0).getPath(),
                    filesToCopy.get(filesToCopy.size() - 1).getPath());
        }

        return filesToCopy;
    }

    /**
     * Gather all PCES files on disk.
     *
     * @param selfId the id of this node
     * @param platformContext the platform context
     * @return a list of all PCES files on disk
     */
    @NonNull
    private static List<PcesFile> gatherPcesFilesOnDisk(
            @NonNull final NodeId selfId, @NonNull final PlatformContext platformContext) throws IOException {
        final List<PcesFile> allFiles = new ArrayList<>();
        final Path preconsensusEventStreamDirectory =
                PcesUtilities.getDatabaseDirectory(platformContext.getConfiguration(), selfId);
        try (final Stream<Path> stream = Files.walk(preconsensusEventStreamDirectory)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                try {
                    allFiles.add(PcesFile.of(path));
                } catch (final IOException e) {
                    // Ignore, this will get thrown for each file that is not a PCES file
                }
            });
        }

        if (allFiles.isEmpty()) {
            logger.warn(STATE_TO_DISK.getMarker(), "No preconsensus event files found to copy");
        } else if (allFiles.size() == 1) {
            logger.info(
                    STATE_TO_DISK.getMarker(),
                    """
                            Found 1 preconsensus file on disk.
                                File: {}""",
                    allFiles.get(0).getPath());
        } else {
            logger.info(
                    STATE_TO_DISK.getMarker(),
                    """
                            Found {} preconsensus files on disk.
                                First file: {}
                                Last file: {}""",
                    allFiles.size(),
                    allFiles.get(0).getPath(),
                    allFiles.get(allFiles.size() - 1).getPath());
        }

        return allFiles;
    }

    /**
     * Copy a list of preconsensus event files into a directory.
     *
     * @param filesToCopy the files to copy
     * @param pcesDestination the directory where the files should be copied
     */
    private static void copyPcesFileList(@NonNull final List<PcesFile> filesToCopy, @NonNull final Path pcesDestination)
            throws IOException {
        logger.info(STATE_TO_DISK.getMarker(), "Copying {} preconsensus event file(s)", filesToCopy.size());

        // Copy files from newest to oldest. Newer files are more likely to be modified concurrently by the
        // PCES file writer and are more likely to fail. If we fail to copy files, it's better to fail early
        // so that we can retry again more quickly.
        for (int index = filesToCopy.size() - 1; index >= 0; index--) {
            final PcesFile file = filesToCopy.get(index);
            Files.copy(file.getPath(), pcesDestination.resolve(file.getFileName()));
        }

        logger.info(STATE_TO_DISK.getMarker(), "Finished copying {} preconsensus event file(s)", filesToCopy.size());
    }
}
