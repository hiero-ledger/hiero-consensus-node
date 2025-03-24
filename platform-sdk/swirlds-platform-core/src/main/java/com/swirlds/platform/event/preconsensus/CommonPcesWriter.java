// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static com.swirlds.common.units.DataUnit.UNIT_BYTES;
import static com.swirlds.common.units.DataUnit.UNIT_MEGABYTES;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.utility.LongRunningAverage;
import com.swirlds.platform.eventhandling.EventConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * This class provides the common functionality for writing preconsensus events to disk. It is used by the
 * {@link DefaultInlinePcesWriter}.
 */
public class CommonPcesWriter {
    private static final Logger logger = LogManager.getLogger(CommonPcesWriter.class);

    /**
     *  If {@code true} {@code FileChannel} is used to write to the file and if {@code false} {@code OutputStream} is used.
     */
    private static final boolean USE_FILE_CHANNEL_WRITER = false;

    /**
     * Keeps track of the event stream files on disk.
     */
    private final PcesFileManager fileManager;

    /**
     * The current file that is being written to.
     */
    private PcesMutableFile currentMutableFile;

    /**
     * The current minimum ancient indicator required to be considered non-ancient. Only read and written on the handle
     * thread. Either a round or generation depending on the {@link AncientMode}.
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
     * The minimum ancient indicator that we are required to keep around. Will be either a birth round or a generation,
     * depending on the {@link AncientMode}.
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
     * The type of the PCES file. There are currently two types: one bound by generations and one bound by birth rounds.
     * The original type of files are bound by generations. The new type of files are bound by birth rounds. Once
     * migration has been completed to birth round bound files, support for the generation bound files will be removed.
     */
    private final AncientMode fileType;

    private final boolean syncEveryEvent;

    /**
     * Constructor
     *
     * @param platformContext the platform context
     * @param fileManager     manages all PCES files currently on disk
     * @param syncEveryEvent  whether to sync the file after every event
     */
    public CommonPcesWriter(
            @NonNull final PlatformContext platformContext,
            @NonNull final PcesFileManager fileManager,
            final boolean syncEveryEvent) {
        Objects.requireNonNull(platformContext, "platformContext is required");
        this.fileManager = Objects.requireNonNull(fileManager, "fileManager is required");
        this.syncEveryEvent = syncEveryEvent;

        final PcesConfig pcesConfig = platformContext.getConfiguration().getConfigData(PcesConfig.class);
        final EventConfig eventConfig = platformContext.getConfiguration().getConfigData(EventConfig.class);

        previousSpan = pcesConfig.bootstrapSpan();
        bootstrapSpanOverlapFactor = pcesConfig.bootstrapSpanOverlapFactor();
        spanOverlapFactor = pcesConfig.spanOverlapFactor();
        minimumSpan = pcesConfig.minimumSpan();
        preferredFileSizeMegabytes = pcesConfig.preferredFileSizeMegabytes();

        averageSpanUtilization = new LongRunningAverage(pcesConfig.spanUtilizationRunningAverageLength());

        fileType = eventConfig.useBirthRoundAncientThreshold()
                ? AncientMode.BIRTH_ROUND_THRESHOLD
                : AncientMode.GENERATION_THRESHOLD;
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
     * @return {@code true} if this method call resulted in the current file being closed
     */
    public boolean registerDiscontinuity(@NonNull final Long newOriginRound) {
        if (!streamingNewEvents) {
            logger.error(EXCEPTION.getMarker(), "registerDiscontinuity() called while replaying events");
        }

        try {
            if (currentMutableFile != null) {
                closeFile();
                return true;
            }
        } finally {
            fileManager.registerDiscontinuity(newOriginRound);
        }
        return false;
    }

    /**
     * Let the event writer know the current non-ancient event boundary. Ancient events will be ignored if added to the
     * event writer.
     *
     * @param nonAncientBoundary describes the boundary between ancient and non-ancient events
     */
    public void updateNonAncientEventBoundary(@NonNull final EventWindow nonAncientBoundary) {
        if (nonAncientBoundary.getAncientThreshold() < this.nonAncientBoundary) {
            throw new IllegalArgumentException("Non-ancient boundary cannot be decreased. Current = "
                    + this.nonAncientBoundary + ", requested = " + nonAncientBoundary);
        }

        this.nonAncientBoundary = nonAncientBoundary.getAncientThreshold();
    }

    /**
     * Set the minimum ancient indicator needed to be kept on disk.
     *
     * @param minimumAncientIdentifierToStore the minimum ancient indicator required to be stored on disk
     */
    public void setMinimumAncientIdentifierToStore(@NonNull final Long minimumAncientIdentifierToStore) {
        this.minimumAncientIdentifierToStore = minimumAncientIdentifierToStore;
        pruneOldFiles();
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

            fileManager.finishedWritingFile(currentMutableFile);
            currentMutableFile = null;

            // Not strictly required here, but not a bad place to ensure we delete
            // files incrementally (as opposed to deleting a bunch of files all at once).
            pruneOldFiles();
        } catch (final IOException e) {
            throw new UncheckedIOException("unable to prune files", e);
        }
    }

    /**
     * Delete old files from the disk.
     */
    private void pruneOldFiles() {
        if (!streamingNewEvents) {
            // Don't attempt to prune files until we are done replaying the event stream (at start up).
            // Files are being iterated on a different thread, and it isn't thread safe to prune files
            // while they are being iterated.
            return;
        }

        try {
            fileManager.pruneOldFiles(minimumAncientIdentifierToStore);
        } catch (final IOException e) {
            throw new UncheckedIOException("unable to prune old files", e);
        }
    }

    /**
     * Prepare the output stream for a particular event. May create a new file/stream if needed.
     *
     * @param eventToWrite the event that is about to be written
     * @return true if this method call resulted in the current file being closed
     */
    public boolean prepareOutputStream(@NonNull final PlatformEvent eventToWrite) throws IOException {
        boolean fileClosed = false;
        if (currentMutableFile != null) {
            final boolean fileCanContainEvent =
                    currentMutableFile.canContain(eventToWrite.getAncientIndicator(fileType));
            final boolean fileIsFull =
                    UNIT_BYTES.convertTo(currentMutableFile.fileSize(), UNIT_MEGABYTES) >= preferredFileSizeMegabytes;

            if (!fileCanContainEvent || fileIsFull) {
                closeFile();
                fileClosed = true;
            }

            if (fileIsFull) {
                bootstrapMode = false;
            }
        }

        // if the block above closed the file, then we need to create a new one
        if (currentMutableFile == null) {
            final long upperBound = nonAncientBoundary
                    + computeNewFileSpan(nonAncientBoundary, eventToWrite.getAncientIndicator(fileType));

            currentMutableFile = fileManager
                    .getNextFileDescriptor(nonAncientBoundary, upperBound)
                    .getMutableFile(USE_FILE_CHANNEL_WRITER, syncEveryEvent);
        }

        return fileClosed;
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
     * Indicates if the writer is currently streaming new events.
     *
     * @return {@code true} if the writer is currently streaming new events, {@code false} otherwise
     */
    public boolean isStreamingNewEvents() {
        return streamingNewEvents;
    }

    /**
     * Get the type of the PCES file read from configuration.
     *
     * @return the type of the PCES file
     */
    public AncientMode getFileType() {
        return fileType;
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
     * Get the current mutable file.
     *
     * @return the current mutable file
     */
    public PcesMutableFile getCurrentMutableFile() {
        return currentMutableFile;
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
}
