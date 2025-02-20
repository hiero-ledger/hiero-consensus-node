// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.consensus.EventWindow;
import com.swirlds.platform.event.PlatformEvent;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This object is responsible for writing events to the database.
 */
public class DefaultPcesWriter implements PcesWriter {

    private static final Logger logger = LogManager.getLogger(DefaultPcesWriter.class);

    /**
     * The highest event sequence number that has been written to the stream (but possibly not yet flushed).
     */
    private long lastWrittenEvent = -1;

    /**
     * The highest event sequence number that has been durably flushed to disk.
     */
    private long lastFlushedEvent = -1;

    private final CommonPcesWriter commonPcesWriter;

    /**
     * A collection of outstanding flush requests
     * <p>
     * Each flush request is a sequence number that needs to be flushed to disk as soon as possible.
     */
    private final Deque<Long> flushRequests = new ArrayDeque<>();

    /**
     * Constructor
     *
     * @param platformContext the platform context
     * @param fileManager     manages all preconsensus event stream files currently on disk
     */
    public DefaultPcesWriter(
            @NonNull final PlatformContext platformContext, @NonNull final PcesFileManager fileManager) {
        Objects.requireNonNull(platformContext, "platformContext is required");
        Objects.requireNonNull(fileManager, "fileManager is required");

        commonPcesWriter = new CommonPcesWriter(platformContext, fileManager, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void beginStreamingNewEvents() {
        commonPcesWriter.beginStreamingNewEvents();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public Long writeEvent(@NonNull final PlatformEvent event) {
        if (event.getStreamSequenceNumber() == PlatformEvent.NO_STREAM_SEQUENCE_NUMBER) {
            throw new IllegalStateException("Event must have a valid stream sequence number");
        }

        // if we aren't streaming new events yet, assume that the given event is already durable
        if (!commonPcesWriter.isStreamingNewEvents()) {
            lastWrittenEvent = event.getStreamSequenceNumber();
            lastFlushedEvent = event.getStreamSequenceNumber();
            return event.getStreamSequenceNumber();
        }

        // don't do anything with ancient events
        if (event.getAncientIndicator(commonPcesWriter.getFileType()) < commonPcesWriter.getNonAncientBoundary()) {
            return null;
        }

        try {
            final boolean fileClosed = commonPcesWriter.prepareOutputStream(event);
            commonPcesWriter.getCurrentMutableFile().writeEvent(event);
            lastWrittenEvent = event.getStreamSequenceNumber();

            final boolean flushPerformed = processFlushRequests();

            return fileClosed || flushPerformed ? lastFlushedEvent : null;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public Long registerDiscontinuity(@NonNull final Long newOriginRound) {
        final boolean fileClosed = commonPcesWriter.registerDiscontinuity(newOriginRound);
        if (fileClosed) {
            lastFlushedEvent = lastWrittenEvent;
        }
        return lastFlushedEvent;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public Long submitFlushRequest(@NonNull final Long sequenceNumber) {
        flushRequests.add(sequenceNumber);

        return processFlushRequests() ? lastFlushedEvent : null;
    }

    /**
     * Consider outstanding flush requests and perform a flush if needed.
     *
     * @return true if a flush was performed, otherwise false
     */
    private boolean processFlushRequests() {
        boolean flushRequired = false;
        while (!flushRequests.isEmpty() && flushRequests.peekFirst() <= lastWrittenEvent) {
            final long flushRequest = flushRequests.removeFirst();

            if (flushRequest > lastFlushedEvent) {
                flushRequired = true;
            }
        }

        if (flushRequired) {
            if (commonPcesWriter.getCurrentMutableFile() == null) {
                logger.error(EXCEPTION.getMarker(), "Flush required, but no file is open. This should never happen");
            }

            try {
                commonPcesWriter.getCurrentMutableFile().flush();
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }

            lastFlushedEvent = lastWrittenEvent;
        }

        return flushRequired;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateNonAncientEventBoundary(@NonNull final EventWindow nonAncientBoundary) {
        commonPcesWriter.updateNonAncientEventBoundary(nonAncientBoundary);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setMinimumAncientIdentifierToStore(@NonNull final Long minimumAncientIdentifierToStore) {
        commonPcesWriter.setMinimumAncientIdentifierToStore(minimumAncientIdentifierToStore);
    }

    /**
     * Close the current mutable file.
     */
    public void closeCurrentMutableFile() {
        commonPcesWriter.closeCurrentMutableFile();
    }
}
