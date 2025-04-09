// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.event.preconsensus.PcesUtilities.getDatabaseDirectory;
import static org.hiero.consensus.model.event.AncientMode.BIRTH_ROUND_THRESHOLD;
import static org.hiero.consensus.model.event.AncientMode.GENERATION_THRESHOLD;

import com.swirlds.base.time.Time;
import com.swirlds.base.units.UnitConstants;
import com.swirlds.common.context.PlatformContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.config.EventConfig;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.node.NodeId;

/**
 * <p>
 * This object tracks a group of event files.
 * </p>
 *
 * <p>
 * This object is not thread safe.
 * </p>
 */
public class PcesFileManager {

    private static final Logger logger = LogManager.getLogger(PcesFileManager.class);

    /**
     * This constant can be used when the caller wants all events, regardless of the lower bound.
     */
    public static final long NO_LOWER_BOUND = -1;

    /**
     * Provides the wall clock time.
     */
    private final Time time;

    private final PcesMetrics metrics;

    /**
     * The root directory where event files are stored.
     */
    private final Path databaseDirectory;

    /**
     * The current origin round.
     */
    private long currentOrigin;

    /**
     * The minimum amount of time that must pass before a file becomes eligible for deletion.
     */
    private final Duration minimumRetentionPeriod;

    /**
     * The size of all tracked files, in bytes.
     */
    private long totalFileByteCount = 0;

    private final PcesFileTracker files;

    /**
     * The PCES file type for new files.
     */
    private final AncientMode newFileType;

    /**
     * Constructor
     *
     * @param platformContext the platform context for this node
     * @param files           the files to track
     * @param selfId          the ID of this node
     * @param startingRound   the round number of the initial state of the system
     * @throws IOException if there is an error reading the files
     */
    public PcesFileManager(
            @NonNull final PlatformContext platformContext,
            @NonNull final PcesFileTracker files,
            @NonNull final NodeId selfId,
            final long startingRound)
            throws IOException {

        Objects.requireNonNull(platformContext);
        Objects.requireNonNull(selfId);

        if (startingRound < 0) {
            throw new IllegalArgumentException("starting round must be non-negative");
        }

        final PcesConfig preconsensusEventStreamConfig =
                platformContext.getConfiguration().getConfigData(PcesConfig.class);

        this.time = platformContext.getTime();
        this.files = Objects.requireNonNull(files);
        this.metrics = new PcesMetrics(platformContext.getMetrics());
        this.minimumRetentionPeriod = preconsensusEventStreamConfig.minimumRetentionPeriod();
        this.databaseDirectory = getDatabaseDirectory(platformContext, selfId);

        this.currentOrigin = PcesUtilities.getInitialOrigin(files, startingRound);

        this.newFileType = platformContext
                        .getConfiguration()
                        .getConfigData(EventConfig.class)
                        .useBirthRoundAncientThreshold()
                ? BIRTH_ROUND_THRESHOLD
                : GENERATION_THRESHOLD;

        initializeMetrics();
    }

    /**
     * Initialize metrics given the files currently on disk.
     */
    private void initializeMetrics() throws IOException {
        totalFileByteCount = files.getTotalFileByteCount();

        if (files.getFileCount() > 0) {
            metrics.getPreconsensusEventFileOldestIdentifier()
                    .set(files.getFirstFile().getLowerBound());
            metrics.getPreconsensusEventFileYoungestIdentifier()
                    .set(files.getLastFile().getUpperBound());
            final Duration age = Duration.between(files.getFirstFile().getTimestamp(), time.now());
            metrics.getPreconsensusEventFileOldestSeconds().set(age.toSeconds());
        } else {
            metrics.getPreconsensusEventFileOldestIdentifier().set(NO_LOWER_BOUND);
            metrics.getPreconsensusEventFileYoungestIdentifier().set(NO_LOWER_BOUND);
            metrics.getPreconsensusEventFileOldestSeconds().set(0);
        }
        updateFileSizeMetrics();
    }

    /**
     * Get the sequence number that should be allocated next.
     *
     * @return the sequence number that should be allocated next
     */
    private long getNextSequenceNumber() {
        if (files.getFileCount() == 0) {
            return 0;
        }
        return files.getLastFile().getSequenceNumber() + 1;
    }

    /**
     * Register a discontinuity in the stream.
     *
     * @param newOriginRound the new origin for stream files written after this method is called
     */
    public void registerDiscontinuity(final long newOriginRound) {
        if (newOriginRound <= currentOrigin) {
            throw new IllegalArgumentException("New origin round must be greater than the current origin round. "
                    + "Current origin round: " + currentOrigin + ", new origin round: " + newOriginRound);
        }

        final PcesFile lastFile = files.getFileCount() > 0 ? files.getLastFile() : null;

        logger.info(
                STARTUP.getMarker(),
                "Due to recent operations on this node, the local preconsensus event stream"
                        + " will have a discontinuity. The last file with the old origin round is {}. "
                        + "All future files will have an origin round of {}.",
                lastFile,
                newOriginRound);

        currentOrigin = newOriginRound;
    }

    /**
     * Create a new event file descriptor for the next event file, and start tracking it. (Note, this method doesn't
     * actually open the file, it just permits the file to be opened by the caller.)
     *
     * @param lowerBound the lower bound that can be stored in the file
     * @param upperBound the upper bound that can be stored in the file
     * @return a new event file descriptor
     */
    public @NonNull PcesFile getNextFileDescriptor(final long lowerBound, final long upperBound) {

        if (lowerBound > upperBound) {
            throw new IllegalArgumentException("lower bound must be less than or equal to the upper bound");
        }

        final long lowerBoundForFile;
        final long upperBoundForFile;

        if (files.getFileCount() == 0) {
            // This is the first file
            lowerBoundForFile = lowerBound;
            upperBoundForFile = upperBound;
        } else {
            // This is not the first file, min/max values are constrained to only increase
            lowerBoundForFile = Math.max(lowerBound, files.getLastFile().getLowerBound());
            upperBoundForFile = Math.max(upperBound, files.getLastFile().getUpperBound());
        }

        final PcesFile descriptor = PcesFile.of(
                newFileType,
                time.now(),
                getNextSequenceNumber(),
                lowerBoundForFile,
                upperBoundForFile,
                currentOrigin,
                databaseDirectory);

        if (files.getFileCount() > 0) {
            // There are never enough sanity checks. This is the same sanity check that is run when we parse
            // the files from disk, so if it doesn't pass now it's not going to pass when we read the files.
            final PcesFile previousFile = files.getLastFile();
            PcesUtilities.fileSanityChecks(
                    false,
                    previousFile.getSequenceNumber(),
                    previousFile.getLowerBound(),
                    previousFile.getUpperBound(),
                    currentOrigin,
                    previousFile.getTimestamp(),
                    descriptor);
        }

        files.addFile(descriptor);
        metrics.getPreconsensusEventFileYoungestIdentifier().set(descriptor.getUpperBound());

        return descriptor;
    }

    /**
     * The event file writer calls this method when it finishes writing an event file.
     *
     * @param file the file that has been completely written
     */
    public void finishedWritingFile(@NonNull final PcesMutableFile file) {
        final long previousFileUpperBound;
        if (files.getFileCount() == 1) {
            previousFileUpperBound = 0;
        } else {
            previousFileUpperBound = files.getFile(files.getFileCount() - 2).getUpperBound();
        }

        // Compress the span of the file. Reduces overlap between files.
        final PcesFile compressedDescriptor = file.compressSpan(previousFileUpperBound);
        files.setFile(files.getFileCount() - 1, compressedDescriptor);

        // Update metrics
        totalFileByteCount += file.fileSize();
        metrics.getPreconsensusEventFileRate().cycle();
        metrics.getPreconsensusEventAverageFileSpan().update(file.getSpan());
        metrics.getPreconsensusEventAverageUnUtilizedFileSpan().update(file.getUnUtilizedSpan());
        updateFileSizeMetrics();
    }

    /**
     * Prune old event files. Files are pruned if they are too old AND if they do not contain events with high enough
     * ancient indicators.
     *
     * @param lowerBoundToKeep the minimum ancient indicator that we need to keep in this store. It's possible that
     *                         this operation won't delete all files with events older than this value, but this
     *                         operation is guaranteed not to delete any files that may contain events with a higher
     *                         ancient indicator.
     * @throws IOException if there is an error deleting files
     */
    public void pruneOldFiles(final long lowerBoundToKeep) throws IOException {
        final Instant minimumTimestamp = time.now().minus(minimumRetentionPeriod);

        while (files.getFileCount() > 0
                && files.getFirstFile().getUpperBound() < lowerBoundToKeep
                && files.getFirstFile().getTimestamp().isBefore(minimumTimestamp)) {

            final PcesFile file = files.removeFirstFile();
            totalFileByteCount -= Files.size(file.getPath());
            file.deleteFile(databaseDirectory);
        }

        if (files.getFileCount() > 0) {
            metrics.getPreconsensusEventFileOldestIdentifier()
                    .set(files.getFirstFile().getLowerBound());
            final Duration age = Duration.between(files.getFirstFile().getTimestamp(), time.now());
            metrics.getPreconsensusEventFileOldestSeconds().set(age.toSeconds());
        }

        updateFileSizeMetrics();
    }

    /**
     * Update metrics with the latest data on file size.
     */
    private void updateFileSizeMetrics() {
        metrics.getPreconsensusEventFileCount().set(files.getFileCount());
        metrics.getPreconsensusEventFileTotalSizeGB().set(totalFileByteCount * UnitConstants.BYTES_TO_GIBIBYTES);

        if (files.getFileCount() > 0) {
            metrics.getPreconsensusEventFileAverageSizeMB()
                    .set(((double) totalFileByteCount) / files.getFileCount() * UnitConstants.BYTES_TO_MEBIBYTES);
        }
    }
}
