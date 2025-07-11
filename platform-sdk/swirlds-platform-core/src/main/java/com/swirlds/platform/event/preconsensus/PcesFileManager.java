// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static com.swirlds.common.units.DataUnit.UNIT_BYTES;
import static com.swirlds.common.units.DataUnit.UNIT_MEGABYTES;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.utility.LongRunningAverage;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * This class provides the common functionality for writing preconsensus events to disk. It is used by the
 * {@link DefaultInlinePcesWriter}.
 */
public class PcesFileManager {
    /**
     * This constant can be used when the caller wants all events, regardless of the lower bound.
     */
    public static final long NO_LOWER_BOUND = -1;

    private static final Logger logger = LogManager.getLogger(PcesFileManager.class);

    /**
     * The current file that is being written to.
     */
    private PcesMutableFile currentMutableFile;

    /**
     * The current minimum ancient indicator required to be considered non-ancient. Only read and written on the handle
     * thread. Based on the birth round of an event.
     */
    private long nonAncientBoundary = 0;

    /**
     * The desired file size, in megabytes. Is not a hard limit, it's possible that we may exceed this value by a small
     * amount (we never stop in the middle of writing an event). It's also possible that we may create files that are
     * smaller than this limit.
     */
    private final int preferredFileSizeMegabytes;

    /**
     * When creating a new file, make sure that it has at least this much capacity between the upper bound and lower
     * bound for events after the first event written to the file.
     */
    private final int minimumSpan;

    /**
     * The minimum ancient indicator that we are required to keep around. Based on the birth round of an event.
     */
    private long minimumAncientIdentifierToStore;

    /**
     * A running average of the span utilization in each file. Span utilization is defined as the difference between the
     * highest ancient indicator of all events in the file and the minimum legal ancient indicator for that file.
     * Higher utilization is always better, as it means that we have a lower un-utilized span. Un-utilized span is
     * defined as the difference between the highest legal ancient indicator in a file and the highest actual ancient
     * identifier of all events in the file. The reason why we want to minimize un-utilized span is to reduce the
     * overlap between files, which in turn makes it faster to search for events with particular ancient indicator. The
     * purpose of this running average is to intelligently choose upper bound for each new file to minimize un-utilized
     * span while still meeting file size requirements.
     */
    private final LongRunningAverage averageSpanUtilization;

    /**
     * The previous span. Set to a constant at bootstrap time.
     */
    private long previousSpan;

    /**
     * If true then use {@link #bootstrapSpanOverlapFactor} to compute the upper bound new files. If false then use
     * {@link #spanOverlapFactor} to compute the upper bound for new files. Bootstrap mode is used until we create the
     * first file that exceeds the preferred file size.
     */
    private boolean bootstrapMode = true;

    /**
     * During bootstrap mode, multiply this value by the running average when deciding the upper bound for a new file
     * (i.e. the difference between the maximum and the minimum legal ancient indicator).
     */
    private final double bootstrapSpanOverlapFactor;

    /**
     * When not in boostrap mode, multiply this value by the running average when deciding the span for a new file (i.e.
     * the difference between the maximum and the minimum legal ancient indicator).
     */
    private final double spanOverlapFactor;

    /**
     * If true then all added events are new and need to be written to the stream. If false then all added events are
     * already durable and do not need to be written to the stream.
     */
    private boolean streamingNewEvents = false;

    /**
     * The type of writer to use
     */
    private final PcesFileWriterType pcesFileWriterType;

    /**
     * The current origin round.
     */
    private long currentOrigin;
    /**
     * The root directory where event files are stored.
     */
    private final Path databaseDirectory;
    /**
     * The minimum amount of time that must pass before a file becomes eligible for deletion.
     */
    private final Duration minimumRetentionPeriod;
    /**
     * Keeps track of the event stream files on disk.
     */
    private final PcesFileTracker files;
    /**
     * Provides the wall clock time.
     */
    private final Time time;
    /**
     * The size of all tracked files, in bytes.
     */
    private long totalFileByteCount = 0;

    final PcesFileManagerMetrics metrics;

    /**
     * Constructor
     *
     * @param platformContext the platform context
     */
    public PcesFileManager(
            @NonNull final PlatformContext platformContext,
            final long initialRound,
            final @NonNull Path databaseDirectory) {
        Objects.requireNonNull(platformContext, "platformConfig is required");
        Objects.requireNonNull(databaseDirectory, "databaseDirectory is required");
        final PcesConfig pcesConfig = platformContext.getConfiguration().getConfigData(PcesConfig.class);
        this.previousSpan = pcesConfig.bootstrapSpan();
        this.bootstrapSpanOverlapFactor = pcesConfig.bootstrapSpanOverlapFactor();
        this.spanOverlapFactor = pcesConfig.spanOverlapFactor();
        this.minimumSpan = pcesConfig.minimumSpan();
        this.preferredFileSizeMegabytes = pcesConfig.preferredFileSizeMegabytes();
        this.time = platformContext.getTime();
        this.minimumRetentionPeriod = pcesConfig.minimumRetentionPeriod();
        this.databaseDirectory = Objects.requireNonNull(databaseDirectory);
        try {
            this.files = PcesFileReader.readFilesFromDisk(
                    platformContext, databaseDirectory, initialRound, pcesConfig.permitGaps());
            this.currentOrigin = PcesUtilities.getInitialOrigin(files, initialRound);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }

        // performance of FILE_CHANNEL is 150x slower on MacOS, but marginally better on Linux; it is so bad on Mac
        // that basic tests cannot pass in some cases, so we need to make it system dependent, at same time allowing
        // override if needed
        if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            this.pcesFileWriterType = pcesConfig.macPcesFileWriterType();
        } else {
            this.pcesFileWriterType = pcesConfig.pcesFileWriterType();
        }
        this.metrics = new PcesFileManagerMetrics(platformContext.getMetrics());
        this.averageSpanUtilization = new LongRunningAverage(pcesConfig.spanUtilizationRunningAverageLength());
        this.totalFileByteCount = files.getTotalFileByteCount();
        metrics.initializeMetrics(files, platformContext.getTime());
        metrics.updateFileSizeMetrics(totalFileByteCount, files);
    }

    /**
     * Prior to this method being called, all events added to the preconsensus event stream are assumed to be events
     * read from the preconsensus event stream on disk. The events from the stream on disk are not re-written to the
     * disk, and are considered to be durable immediately upon ingest.
     */
    public void beginStreamingNewEvents() {
        if (streamingNewEvents) {
            logger.error(EXCEPTION.getMarker(), "beginStreamingNewEvents() called while already streaming new events");
        }
        streamingNewEvents = true;
    }

    /**
     * Inform the preconsensus event writer that a discontinuity has occurred in the preconsensus event stream.
     *
     * @param newOriginRound the round of the state that the new stream will be starting from
     */
    public void registerDiscontinuity(@NonNull final Long newOriginRound) {
        if (!streamingNewEvents) {
            logger.error(EXCEPTION.getMarker(), "registerDiscontinuity() called while replaying events");
        }

        RuntimeException memoException = null;
        if (currentMutableFile != null) {
            try {
                closeFile();
            } catch (RuntimeException e) {
                memoException = e;
            }
        }
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
        Optional.ofNullable(memoException).ifPresent(e -> {
            throw e;
        });
    }

    /**
     * Let the event writer know the current non-ancient event boundary. Ancient events will be ignored if added to the
     * event writer.
     *
     * @param nonAncientBoundary describes the boundary between ancient and non-ancient events
     */
    public void updateNonAncientEventBoundary(@NonNull final EventWindow nonAncientBoundary) {
        if (nonAncientBoundary.ancientThreshold() < this.nonAncientBoundary) {
            throw new IllegalArgumentException("Non-ancient boundary cannot be decreased. Current = "
                    + this.nonAncientBoundary + ", requested = " + nonAncientBoundary);
        }

        this.nonAncientBoundary = nonAncientBoundary.ancientThreshold();
    }

    /**
     * Set the minimum ancient indicator needed to be kept on disk.
     *
     * @param minimumAncientIdentifierToStore the minimum ancient indicator required to be stored on disk
     */
    public void setMinimumAncientIdentifierToStore(@NonNull final Long minimumAncientIdentifierToStore) {
        this.minimumAncientIdentifierToStore = minimumAncientIdentifierToStore;
        pruneOldFiles(minimumAncientIdentifierToStore);
    }

    /**
     * Indicates if the writer is currently streaming new events.
     *
     * @return {@code true} if the writer is currently streaming new events, {@code false} otherwise
     */
    public boolean isStreamingNewEvents() {
        return streamingNewEvents;
    }

    /**
     * Get the non-ancient boundary.
     *
     * @return the non-ancient boundary
     */
    public long getNonAncientBoundary() {
        return nonAncientBoundary;
    }

    /**
     * Close the current mutable file.
     */
    public void closeCurrentMutableFile() {
        if (currentMutableFile != null) {
            try {
                currentMutableFile.close();
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public long writeEvent(final PlatformEvent event) throws IOException {
        long start = time.nanoTime();
        long size = 0;
        try {
            size = currentMutableFile.writeEvent(event);
            return size;
        } finally {
            metrics.endFileWrite(size, getDurationInNanos(start));
        }
    }

    public void sync() throws IOException {
        long start = time.nanoTime();
        try {
            currentMutableFile.sync();
        } finally {
            metrics.endFileSync(getDurationInNanos(start));
        }
    }

    /**
     * Close the output file.
     * <p>
     * Should only be called if {@link #currentMutableFile} is not null.
     */
    private void closeFile() {
        try {
            previousSpan = currentMutableFile.getUtilizedSpan();
            if (!bootstrapMode) {
                averageSpanUtilization.add(previousSpan);
            }
            currentMutableFile.close();

            finishedWritingFile(currentMutableFile);
            currentMutableFile = null;

            // Not strictly required here, but not a bad place to ensure we delete
            // files incrementally (as opposed to deleting a bunch of files all at once).
            pruneOldFiles(minimumAncientIdentifierToStore);
        } catch (final IOException e) {
            throw new UncheckedIOException("unable to prune files", e);
        }
    }

    /**
     * Prepare the output stream for a particular event. May create a new file/stream if needed.
     *
     * @param eventToWrite the event that is about to be written
     */
    public void prepareOutputStream(@NonNull final PlatformEvent eventToWrite) throws IOException {
        if (currentMutableFile != null) {
            final boolean fileCanContainEvent = currentMutableFile.canContain(eventToWrite.getBirthRound());
            final boolean fileIsFull =
                    UNIT_BYTES.convertTo(currentMutableFile.fileSize(), UNIT_MEGABYTES) >= preferredFileSizeMegabytes;

            if (!fileCanContainEvent || fileIsFull) {
                closeFile();
            }

            if (fileIsFull) {
                bootstrapMode = false;
            }
        }

        // if the block above closed the file, then we need to create a new one
        if (currentMutableFile == null) {
            final long upperBound =
                    nonAncientBoundary + computeNewFileSpan(nonAncientBoundary, eventToWrite.getBirthRound());

            currentMutableFile =
                    getNextFileDescriptor(nonAncientBoundary, upperBound).getMutableFile(pcesFileWriterType);
        }
    }

    /**
     * Calculate the span for a new file that is about to be created.
     *
     * @param minimumLowerBound            the minimum lower bound that is legal to use for the new file
     * @param nextAncientIdentifierToWrite the ancient indicator of the next event that will be written
     */
    private long computeNewFileSpan(final long minimumLowerBound, final long nextAncientIdentifierToWrite) {

        final long basisSpan = (bootstrapMode || averageSpanUtilization.isEmpty())
                ? previousSpan
                : averageSpanUtilization.getAverage();

        final double overlapFactor = bootstrapMode ? bootstrapSpanOverlapFactor : spanOverlapFactor;

        final long desiredSpan = (long) (basisSpan * overlapFactor);

        final long minimumSpan = (nextAncientIdentifierToWrite + this.minimumSpan) - minimumLowerBound;

        return Math.max(desiredSpan, minimumSpan);
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
     * Prune old event files. Files are pruned if they are too old AND if they do not contain events with high enough
     * ancient indicators.
     *
     * @param lowerBoundToKeep the minimum ancient indicator that we need to keep in this store. It's possible that
     *                         this operation won't delete all files with events older than this value, but this
     *                         operation is guaranteed not to delete any files that may contain events with a higher
     *                         ancient indicator.
     */
    public void pruneOldFiles(final long lowerBoundToKeep) {

        if (!streamingNewEvents) {
            // Don't attempt to prune files until we are done replaying the event stream (at start up).
            // Files are being iterated on a different thread, and it isn't thread safe to prune files
            // while they are being iterated.
            return;
        }

        try {

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

            metrics.updateFileSizeMetrics(totalFileByteCount, files);
        } catch (final IOException e) {
            throw new UncheckedIOException("unable to prune old files", e);
        }
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
        metrics.updateFileSizeMetrics(totalFileByteCount, files);
    }
    /**
     * Returns the duration in nanos from the given start up to now as reported by time instance
     * @param startNanos the start of the duration to consider
     * @return the value in nanos of the duration, up to Integer.MAX_VALUE
     */
    private long getDurationInNanos(final long startNanos) {
        return time.nanoTime() - startNanos;
    }
}
