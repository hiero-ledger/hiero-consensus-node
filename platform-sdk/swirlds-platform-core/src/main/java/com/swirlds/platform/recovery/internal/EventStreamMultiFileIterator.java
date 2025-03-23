// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.recovery.internal;

import com.swirlds.common.io.IOIterator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.event.CesEvent;

/**
 * <p>
 * Iterates over events in an event stream spanning multiple event stream files.
 * </p>
 *
 * <p>
 * This iterator validates the hashes of the events as it goes. It there is a gap in event stream files, or if an event
 * stream file contains corrupted data, this object will throw an IO exception when that data is reached.
 * </p>
 *
 * <p>
 * The exception to this rule is the final event stream file. The final file is permitted to abruptly terminate very end
 * (if, for example, a node crashed while writing it). All complete events at the beginning of the file are returned by
 * this iterator.
 * </p>
 */
public class EventStreamMultiFileIterator implements IOIterator<CesEvent> {

    private final Iterator<Path> fileIterator;
    private long fileCount;
    private long byteCount;
    private Hash startHash;
    private final List<CesEvent> skippedEvents;
    private EventStreamSingleFileIterator eventIterator;
    private long damagedFileCount = 0;

    /**
     * Create an iterator that walks over events in an event stream spanning multiple files, starting from the indicated
     * event stream bound.  The bound must occur in the event stream otherwise a NoSuchElementException will be thrown.
     *
     * @param fileIterator an iterator that returns ordered event stream files
     * @param bound        the lower bound of the events in the event stream to be walked by this iterator.
     * @throws IOException            if there is a problem reading the event stream
     * @throws NoSuchElementException if an event prior to or matching the bound can't be found
     */
    public EventStreamMultiFileIterator(
            @NonNull final Iterator<Path> fileIterator, @NonNull final EventStreamLowerBound bound) throws IOException {
        Objects.requireNonNull(bound, "the lower bound must not be null");
        this.fileIterator = Objects.requireNonNull(fileIterator, "the file iterator must not be null");
        this.startHash = null;
        this.skippedEvents = new ArrayList<>();

        // Remove events from before the requested bound
        while (hasNext() && bound.compareTo(peek()) < 0) {
            skippedEvents.add(next());
        }
    }

    /**
     * Create an iterator that walks over all events in a given directory starting from a given event stream bound. The bound must occur in the event stream otherwise a NoSuchElementException will be thrown.
     *
     * @param eventStreamDirectory the directory in question
     * @param bound                the bound for the events in the event stream to be walked by this iterator
     * @throws IOException            if there is a problem reading the event stream
     * @throws NoSuchElementException if an event prior to or matching the bound can't be found
     */
    public EventStreamMultiFileIterator(
            @NonNull final Path eventStreamDirectory, @NonNull final EventStreamLowerBound bound) throws IOException {
        this(new EventStreamPathIterator(eventStreamDirectory, bound), bound);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        if (eventIterator != null) {
            eventIterator.close();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasNext() throws IOException {
        if (eventIterator == null || !eventIterator.hasNext()) {
            if (!fileIterator.hasNext()) {
                // No more files to open
                return false;
            }

            final Hash previousHash = eventIterator == null ? null : eventIterator.getEndHash();

            final Path nextFile = fileIterator.next();
            fileCount++;
            final boolean toleratePartialFile = !fileIterator.hasNext();
            if (eventIterator != null) {
                // if we are done with the previous file, close it
                byteCount += eventIterator.getBytesRead();
                eventIterator.close();
                if (eventIterator.isDamaged()) {
                    damagedFileCount++;
                }
            }
            eventIterator = new EventStreamSingleFileIterator(nextFile, toleratePartialFile);
            if (startHash == null) {
                // when opening the first file, save the starting hash
                startHash = eventIterator.getStartHash();
            }

            if (previousHash != null && !eventIterator.getStartHash().equals(previousHash)) {
                throw new IOException("missing event stream file prior to `%s` detected".formatted(nextFile));
            }
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
        return eventIterator.peek();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CesEvent next() throws IOException {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        return eventIterator.next();
    }

    /**
     * @return the running hash at the start of the first file
     */
    public Hash getStartHash() {
        return startHash;
    }

    /**
     * @return events that have been read from a file but are not returned by {@link #next()} because they are prior to
     * the event stream bound.
     */
    public List<CesEvent> getSkippedEvents() {
        return skippedEvents;
    }

    /**
     * Get the number of files that have been opened so far.
     *
     * @return the number of files opened so far
     */
    public long getFileCount() {
        return fileCount;
    }

    /**
     * Get the number of bytes read from all files so far.
     */
    public long getBytesRead() {
        return byteCount + eventIterator.getBytesRead();
    }

    /**
     * Get the number of files that are damaged.
     */
    public long getDamagedFileCount() {
        long damageCount = damagedFileCount;
        if (eventIterator != null && eventIterator.isDamaged()) {
            damageCount++;
        }

        return damageCount;
    }
}
