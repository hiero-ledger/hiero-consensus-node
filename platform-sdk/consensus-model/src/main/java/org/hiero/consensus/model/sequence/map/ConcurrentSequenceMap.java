// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.sequence.map;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.ToLongFunction;
import org.hiero.base.concurrent.locks.IndexLock;
import org.hiero.base.concurrent.locks.Locks;

/**
 * A thread safe implementation of {@link SequenceMap}.
 *
 * @param <K> the type of the key
 * @param <V> the type of the value
 */
public class ConcurrentSequenceMap<K, V> extends AbstractSequenceMap<K, V> {

    private static final int MAX_PARALLELISM = 1024;
    private final AtomicLong firstSequenceNumberInWindow;
    private final int parallelism;

    /**
     * When inserting data into this data structure, it is critical that data is not inserted after the data's sequence
     * number has been purged (as this would lead to a memory leak). Whenever new data is inserted, acquire a lock that
     * prevents concurrent purging of that sequence number.
     */
    private final IndexLock lock;

    private final Lock windowLock = new ReentrantLock();

    /**
     * Construct a thread safe {@link SequenceMap} that does not permit expansion.
     *
     * @param firstSequenceNumberInWindow the lowest allowed sequence number
     * @param sequenceNumberCapacity      the number of sequence numbers permitted to exist in this data structure. E.g.
     *                                    if the lowest allowed sequence number is 100 and the capacity is 10, then
     *                                    values with a sequence number between 100 and 109 (inclusive) will be allowed,
     *                                    and any value with a sequence number outside that range will be rejected.
     * @param getSequenceNumberFromKey    a method that extracts the sequence number from a key
     */
    public ConcurrentSequenceMap(
            final long firstSequenceNumberInWindow,
            final int sequenceNumberCapacity,
            @NonNull final ToLongFunction<K> getSequenceNumberFromKey) {

        this(firstSequenceNumberInWindow, sequenceNumberCapacity, false, getSequenceNumberFromKey);
    }

    /**
     * Construct a thread safe {@link SequenceMap}.
     *
     * @param firstSequenceNumberInWindow the lowest allowed sequence number
     * @param sequenceNumberCapacity      the number of sequence numbers permitted to exist in this data structure. E.g.
     *                                    if the lowest allowed sequence number is 100 and the capacity is 10, then
     *                                    values with a sequence number between 100 and 109 (inclusive) will be allowed,
     *                                    and any value with a sequence number outside that range will be rejected.
     * @param allowExpansion              if true, then instead of rejecting elements with a sequence number higher than
     *                                    the allowed by the current capacity, increase capacity and then insert the
     *                                    element. Does not expand if the sequence number is too low to fit in the
     *                                    current capacity.
     * @param getSequenceNumberFromKey    a method that extracts the sequence number from a key
     */
    public ConcurrentSequenceMap(
            final long firstSequenceNumberInWindow,
            final int sequenceNumberCapacity,
            final boolean allowExpansion,
            @NonNull final ToLongFunction<K> getSequenceNumberFromKey) {

        super(firstSequenceNumberInWindow, sequenceNumberCapacity, allowExpansion, getSequenceNumberFromKey);

        parallelism = Math.min(MAX_PARALLELISM, sequenceNumberCapacity);
        lock = Locks.createIndexLock(parallelism);

        this.firstSequenceNumberInWindow = new AtomicLong(firstSequenceNumberInWindow);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void lockSequenceNumber(final long sequenceNumber) {
        lock.lock(sequenceNumber);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void unlockSequenceNumber(final long sequenceNumber) {
        lock.unlock(sequenceNumber);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void fullLock() {
        lock.fullyLock();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void fullUnlock() {
        lock.fullyUnlock();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected int getFullLockThreshold() {
        return parallelism;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void windowLock() {
        windowLock.lock();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void windowUnlock() {
        windowLock.unlock();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getFirstSequenceNumberInWindow() {
        return firstSequenceNumberInWindow.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void setFirstSequenceNumberInWindow(final long firstSequenceNumberInWindow) {
        this.firstSequenceNumberInWindow.set(firstSequenceNumberInWindow);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Map<K, V> buildDataMap() {
        return new ConcurrentHashMap<>();
    }
}
