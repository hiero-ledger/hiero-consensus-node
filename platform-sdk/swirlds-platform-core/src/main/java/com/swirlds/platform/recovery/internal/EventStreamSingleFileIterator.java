// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.recovery.internal;

import com.swirlds.common.io.IOIterator;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.event.CesEvent;
import org.hiero.consensus.model.io.SelfSerializable;

/**
 * An iterator that walks over events in a single event stream file.
 */
public class EventStreamSingleFileIterator implements IOIterator<CesEvent> {

    private final ObjectStreamIterator<SelfSerializable> iterator;

    private final Hash startHash;
    private Hash endHash;
    private boolean finished;
    private final boolean toleratePartialFile;

    /**
     * The path to the file being iterated over
     */
    private final Path filePath;

    /**
     * Create an iterator that walks over an event stream file.
     *
     * @param objectStreamFile    the file
     * @param toleratePartialFile if true then allow the event stream file to end abruptly (possibly mid-event), and
     *                            return all events that are complete within the stream. If false then throw if the file
     *                            is incomplete.
     * @throws IOException if there is an error reading the file
     */
    public EventStreamSingleFileIterator(final Path objectStreamFile, final boolean toleratePartialFile)
            throws IOException {

        this.toleratePartialFile = toleratePartialFile;
        this.filePath = objectStreamFile;

        this.iterator = new ObjectStreamIterator<>(new FileInputStream(objectStreamFile.toFile()), toleratePartialFile);

        // First thing in the stream is a hash
        if (!iterator.hasNext()) {
            this.startHash = null;
            throw new IOException("event stream file `%s` has no objects".formatted(filePath));
        }

        final SelfSerializable firstObject = iterator.next();
        if (firstObject != null && firstObject.getClassId() != Hash.CLASS_ID) {
            throw new IOException(
                    "Illegal object in event stream file `%s` at position 0, expected a Hash: ".formatted(filePath)
                            + firstObject.getClass());
        }
        this.startHash = (Hash) firstObject;

        // An event stream is required to have at least 1 event to be considered valid
        if (!hasNext()) {
            throw new IOException("event stream file `%s` does not contain any events".formatted(filePath));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        iterator.close();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasNext() throws IOException {
        if (!iterator.hasNext()) {
            finished = true;
            if (endHash == null && !toleratePartialFile) {
                throw new IOException("file `%s` terminates early".formatted(filePath));
            }
            return false;
        }

        if (endHash != null || !iterator.hasNext()) {
            finished = true;
            return false;
        }

        final SelfSerializable next = iterator.peek();
        if (next == null) {
            finished = true;
            throw new IOException("null object in the event stream file `%s`".formatted(filePath));
        }

        if (next.getClassId() == Hash.CLASS_ID) {
            finished = true;
            endHash = (Hash) next;
            return false;
        }

        if (next.getClassId() != CesEvent.CLASS_ID) {
            throw new IOException(
                    "Invalid object type found in event stream file `%s`: ".formatted(filePath) + next.getClass());
        }

        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CesEvent peek() throws IOException {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        return (CesEvent) iterator.peek();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CesEvent next() throws IOException {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        return (CesEvent) iterator.next();
    }

    /**
     * Get the hash at the start of the event stream.
     */
    public Hash getStartHash() {
        return startHash;
    }

    /**
     * Get the hash at the end of the event stream.
     */
    public Hash getEndHash() {
        return endHash;
    }

    /**
     * Get the number of bytes read from the stream so far.
     */
    public long getBytesRead() {
        return iterator.getBytesRead();
    }

    /**
     * Is this file damaged? A file can be damaged if the JVM is killed abruptly while data is being written, or if the
     * file is closed before the final hash has been written.
     *
     * @return true if this file is damaged
     */
    public boolean isDamaged() {
        return finished && endHash == null;
    }
}
