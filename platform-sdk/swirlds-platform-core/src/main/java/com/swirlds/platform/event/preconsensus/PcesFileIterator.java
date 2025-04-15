// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import com.hedera.hapi.platform.event.GossipEvent;
import com.swirlds.common.io.IOIterator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Iterates over the events in a single preconsensus event file.
 */
public class PcesFileIterator implements IOIterator<PlatformEvent> {

    private final long lowerBound;
    private final AncientMode fileType;
    private final SerializableDataInputStream stream;
    private boolean hasPartialEvent = false;
    private PlatformEvent next;
    private boolean streamClosed = false;
    private PcesFileVersion fileVersion;

    /**
     * Create a new iterator that walks over events in a preconsensus event file.
     *
     * @param fileDescriptor describes a preconsensus event file
     * @param lowerBound     the lower bound for all events to be returned, corresponds to either generation or birth
     *                       round depending on the {@link PcesFile} type
     * @param fileType       the type of file to read
     */
    public PcesFileIterator(
            @NonNull final PcesFile fileDescriptor, final long lowerBound, @NonNull final AncientMode fileType)
            throws IOException {

        this.lowerBound = lowerBound;
        this.fileType = Objects.requireNonNull(fileType);
        stream = new SerializableDataInputStream(new BufferedInputStream(
                new FileInputStream(fileDescriptor.getPath().toFile())));

        try {
            final int fileVersionNumber = stream.readInt();
            fileVersion = PcesFileVersion.fromVersionNumber(fileVersionNumber);
            if (fileVersion == null) {
                throw new IOException("unsupported file version: " + fileVersionNumber);
            }
        } catch (final EOFException e) {
            // Empty file. Possible if the node crashed right after it created this file.
            closeFile();
        }
    }

    /**
     * Find the next event that should be returned.
     */
    private void findNext() throws IOException {
        while (next == null && !streamClosed) {
            if (stream.available() == 0) {
                closeFile();
                return;
            }

            try {
                final PlatformEvent candidate =
                        switch (fileVersion) {
                            case PROTOBUF_EVENTS -> new PlatformEvent(stream.readPbjRecord(GossipEvent.PROTOBUF));
                        };
                if (fileType.selectIndicator(candidate) >= lowerBound) {
                    next = candidate;
                }
            } catch (final IOException e) {
                // We started parsing an event but couldn't find enough bytes to finish it.
                // This is possible (if not likely) when a node is shut down abruptly.
                hasPartialEvent = true;
                closeFile();
            } catch (final NullPointerException e) {
                // The PlatformEvent constructor can throw this if the event is malformed.
                hasPartialEvent = true;
                closeFile();
                throw new IOException("GossipEvent read from the file is malformed", e);
            }
        }
    }

    private void closeFile() throws IOException {
        stream.close();
        streamClosed = true;
    }

    /**
     * If true then this file contained a partial event. If false then the last event in the file was fully written when
     * the file was closed.
     */
    public boolean hasPartialEvent() {
        return hasPartialEvent;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasNext() throws IOException {
        findNext();
        return next != null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public PlatformEvent next() throws IOException {
        if (!hasNext()) {
            throw new NoSuchElementException("no files remain in this iterator");
        }
        try {
            return next;
        } finally {
            next = null;
        }
    }
}
