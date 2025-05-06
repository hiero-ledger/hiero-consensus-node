// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.event.preconsensus.PcesUtilities.compactPreconsensusEventFile;
import static com.swirlds.platform.event.preconsensus.PcesUtilities.fileSanityChecks;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.utility.RecycleBin;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.ValueReference;
import org.hiero.consensus.model.event.AncientMode;

/**
 * This class is responsible for reading event files from disk and adding them to the collection of tracked files.
 */
public class PcesFileReader {
    private static final Logger logger = LogManager.getLogger(PcesFileReader.class);

    /**
     * Hidden constructor.
     */
    private PcesFileReader() {}

    /**
     * Scan the file system for event files and add them to the collection of tracked files.
     *
     * @param platformContext   the platform context
     * @param databaseDirectory the directory to scan for files
     * @param startingRound     the round to start reading from
     * @param permitGaps        if gaps are permitted in sequence number
     * @param typeToRead        the type of file to read, files of other types will be ignored
     * @return the files read from disk
     * @throws IOException if there is an error reading the files
     */
    public static PcesFileTracker readFilesFromDisk(
            @NonNull final PlatformContext platformContext,
            @NonNull final Path databaseDirectory,
            final long startingRound,
            final boolean permitGaps,
            final AncientMode typeToRead)
            throws IOException {

        Objects.requireNonNull(platformContext);
        Objects.requireNonNull(databaseDirectory);

        final PcesFileTracker files = new PcesFileTracker(typeToRead);

        try (final Stream<Path> fileStream = Files.walk(databaseDirectory)) {
            fileStream
                    .filter(f -> !Files.isDirectory(f))
                    .map(PcesUtilities::parseFile)
                    .filter(Objects::nonNull)
                    .filter(f -> f.getFileType() == typeToRead)
                    .sorted()
                    .forEachOrdered(buildFileHandler(files, permitGaps));
        }

        final PcesConfig preconsensusEventStreamConfig =
                platformContext.getConfiguration().getConfigData(PcesConfig.class);
        final boolean doInitialSpanCompaction = preconsensusEventStreamConfig.compactLastFileOnStartup();

        if (files.getFileCount() != 0 && doInitialSpanCompaction) {
            compactSpanOfLastFile(files);
        }

        resolveDiscontinuities(databaseDirectory, files, platformContext.getRecycleBin(), startingRound);

        return files;
    }

    /**
     * It's possible (if not probable) that the node was shut down prior to the last file being closed and having its
     * span compaction completed. This method performs that compaction if necessary.
     */
    private static void compactSpanOfLastFile(@NonNull final PcesFileTracker files) {
        Objects.requireNonNull(files);

        final PcesFile lastFile = files.getFile(files.getFileCount() - 1);

        final long previousMaximumBound;
        if (files.getFileCount() > 1) {
            final PcesFile secondToLastFile = files.getFile(files.getFileCount() - 2);
            previousMaximumBound = secondToLastFile.getUpperBound();
        } else {
            previousMaximumBound = 0;
        }

        final PcesFile compactedFile = compactPreconsensusEventFile(lastFile, previousMaximumBound);
        files.setFile(files.getFileCount() - 1, compactedFile);
    }

    /**
     * Build a handler for new files parsed from disk. Does basic sanity checks on the files, and adds them to the file
     * list if they are valid.
     *
     * @param permitGaps if gaps are permitted in sequence number
     * @return the handler
     */
    @NonNull
    private static Consumer<PcesFile> buildFileHandler(@NonNull final PcesFileTracker files, final boolean permitGaps) {
        final ValueReference<Long> previousSequenceNumber = new ValueReference<>(-1L);
        final ValueReference<Long> previousMinimumBound = new ValueReference<>(-1L);
        final ValueReference<Long> previousMaximumBound = new ValueReference<>(-1L);
        final ValueReference<Long> previousOrigin = new ValueReference<>(-1L);
        final ValueReference<Instant> previousTimestamp = new ValueReference<>();

        return descriptor -> {
            if (previousSequenceNumber.getValue() != -1) {
                fileSanityChecks(
                        permitGaps,
                        previousSequenceNumber.getValue(),
                        previousMinimumBound.getValue(),
                        previousMaximumBound.getValue(),
                        previousOrigin.getValue(),
                        previousTimestamp.getValue(),
                        descriptor);
            }

            previousSequenceNumber.setValue(descriptor.getSequenceNumber());
            previousMinimumBound.setValue(descriptor.getLowerBound());
            previousMaximumBound.setValue(descriptor.getUpperBound());
            previousTimestamp.setValue(descriptor.getTimestamp());

            // If the sequence number is good then add it to the collection of tracked files
            files.addFile(descriptor);
        };
    }

    /**
     * If there is a discontinuity in the stream after the location where we will begin streaming, delete all files that
     * come after the discontinuity.
     *
     * @param databaseDirectory the directory where PCES files are stored
     * @param files             the files that have been read from disk
     * @param recycleBin the recycleBin
     * @param startingRound     the round the system is starting from
     * @throws IOException if there is an error deleting files
     */
    private static void resolveDiscontinuities(
            @NonNull final Path databaseDirectory,
            @NonNull final PcesFileTracker files,
            @NonNull final RecycleBin recycleBin,
            final long startingRound)
            throws IOException {

        final long initialOrigin = PcesUtilities.getInitialOrigin(files, startingRound);

        final int firstRelevantFileIndex = files.getFirstRelevantFileIndex(startingRound);
        int firstIndexToDelete = firstRelevantFileIndex + 1;
        for (; firstIndexToDelete < files.getFileCount(); firstIndexToDelete++) {
            final PcesFile file = files.getFile(firstIndexToDelete);
            if (file.getOrigin() != initialOrigin) {
                // as soon as we find a file that has a different origin, this and all subsequent files must be deleted
                break;
            }
        }

        if (firstIndexToDelete == files.getFileCount()) {
            // No discontinuities were detected
            return;
        }

        final PcesFile lastUndeletedFile = firstIndexToDelete > 0 ? files.getFile(firstIndexToDelete - 1) : null;

        logger.warn(
                STARTUP.getMarker(),
                """
                        Discontinuity detected in the preconsensus event stream. Purging {} file(s).
                            Last undeleted file: {}
                            First deleted file:  {}
                            Last deleted file:   {}""",
                files.getFileCount() - firstIndexToDelete,
                lastUndeletedFile,
                files.getFile(firstIndexToDelete),
                files.getLastFile());

        // Delete files in reverse order so that if we crash we don't leave gaps in the sequence number if we crash.
        while (files.getFileCount() > firstIndexToDelete) {
            files.removeLastFile().deleteFile(databaseDirectory, recycleBin);
        }
    }
}
