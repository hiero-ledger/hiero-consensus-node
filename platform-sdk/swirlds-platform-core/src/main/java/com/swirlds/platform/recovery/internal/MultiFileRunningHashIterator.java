// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.recovery.internal;

import com.swirlds.common.io.IOIterator;
import com.swirlds.common.stream.RunningHashCalculatorForStream;
import java.io.IOException;
import java.util.Objects;
import org.hiero.consensus.model.event.CesEvent;

/**
 * A wrapper around {@link EventStreamMultiFileIterator}
 * that computes a running hash of the iterated events.
 */
public class MultiFileRunningHashIterator implements IOIterator<CesEvent> {
    private final EventStreamMultiFileIterator iterator;
    private final RunningHashCalculatorForStream<CesEvent> runningHashCalculator;

    /**
     * @param iterator
     * 		the iterator that reads event stream files
     * @throws NullPointerException in case {@code iterator} parameter is {@code null}
     */
    public MultiFileRunningHashIterator(final EventStreamMultiFileIterator iterator) {
        this.iterator = Objects.requireNonNull(iterator, "iterator must not be null");
        this.runningHashCalculator = new RunningHashCalculatorForStream<>();

        runningHashCalculator.setRunningHash(iterator.getStartHash());
        for (final CesEvent skippedEvent : iterator.getSkippedEvents()) {
            runningHashCalculator.addObject(skippedEvent);
        }
    }

    @Override
    public boolean hasNext() throws IOException {
        return iterator.hasNext();
    }

    @Override
    public CesEvent next() throws IOException {
        final CesEvent next = iterator.next();
        runningHashCalculator.addObject(next);
        return next;
    }

    @Override
    public CesEvent peek() throws IOException {
        return iterator.peek();
    }

    @Override
    public void close() {
        iterator.close();
    }

    /**
     * Get the number of bytes read from the disk.
     */
    public long getBytesRead() {
        return iterator.getBytesRead();
    }

    /**
     * Get the number of files that are damaged.
     */
    public long getDamagedFileCount() {
        return iterator.getDamagedFileCount();
    }
}
