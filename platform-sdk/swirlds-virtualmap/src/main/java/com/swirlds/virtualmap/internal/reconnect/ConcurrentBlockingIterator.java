// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static org.hiero.base.concurrent.SpinThenParkBackoff.backOff;

import com.swirlds.virtualmap.VirtualMap;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.hiero.base.concurrent.SpinThenParkBackoff;
import org.hiero.base.concurrent.jctools.queues.MpscVarHandleArrayQueue;

/**
 * This iterator was designed specifically for use with {@link VirtualMap} during reconnect on the
 * learner, but in concept could be adapted for more general use. This class enables a publisher/subscriber
 * approach to an iterator. New items are added via {@link #supply(Object)}, while items are
 * consumed using {@link #next()}. {@link #hasNext()} will return true until there are no more items to
 * consume and will block if there are no items and {@link #close()} has not been called. Critically,
 * you must call {@link #close()} for the iterator to ever return {@code false} from {@link #hasNext()} or
 * throw {@link NoSuchElementException} from {@link #next()}. If new elements are supplied faster than they
 * are consumed, then {@link #supply(Object)} will block until space becomes available. The
 * constructor allows you to define what the size of the inner buffer is.
 *
 * <p>Elements are passed through a lock-free {@link MpscVarHandleArrayQueue}, a preallocated ring buffer, so
 * no node is allocated per element and no lock is taken. Both sides wait with {@link SpinThenParkBackoff}: a
 * supplier while the buffer is full, a consumer while it's empty. Elements may be supplied from any thread,
 * but only one thread may call {@link #hasNext()} and {@link #next()}: a second consumer silently corrupts
 * the buffer.
 *
 * @param <T>
 * 		The type
 */
public class ConcurrentBlockingIterator<T> implements Iterator<T> {

    // Park caps for SpinThenParkBackoff. A supplier waits for the consumer to take an element, which frees a
    // slot right away, so it parks for at most 1µs << 7 (128µs) to notice it quickly. A consumer waits for new
    // elements, which may take a while, so it parks for up to 1µs << 10 (about 1ms) to keep idle wake-ups cheap.
    // When it's late, elements only wait in the buffer for a moment; this costs no throughput, because the
    // consumer is never idle when it's the slower side
    private static final int SUPPLIER_MAX_PARK_SHIFT = 7;
    private static final int CONSUMER_MAX_PARK_SHIFT = 10;

    /**
     * The underlying buffer of items into which elements are placed prior to removal. Any thread may
     * {@code offer}, only the consuming thread may {@code poll} or {@code relaxedPoll}.
     */
    private final MpscVarHandleArrayQueue<T> buffer;

    /**
     * Indicates that the iterator has been closed. Once closed, new elements cannot be
     * supplied but existing elements in the buffer can still be consumed.
     */
    private volatile boolean closed;

    /**
     * Contains a reference to the next element. This is basically a temporary variable.
     */
    private T next;

    /**
     * Create a new {@link ConcurrentBlockingIterator}.
     *
     * @param bufferSize
     * 		The size of the internal buffer. Must be greater than 0. The buffer is a ring buffer that indexes
     * 		its slots with a bit mask, so its actual size is the next power of two {@code >= bufferSize}. All its
     * 		slots are allocated here
     */
    public ConcurrentBlockingIterator(final int bufferSize) {
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be greater than zero");
        }
        this.buffer = new MpscVarHandleArrayQueue<>(bufferSize);
    }

    /**
     * {@inheritDoc}
     *
     * <p>There is no timeout here, the iterator will wait forever (or till closed) for the next elements to be
     * supplied. During reconnects, if no element (dirty leaf from the teacher) is provided in certain period, the
     * whole reconnect thread group will be interrupted, there is no need to have explicit timeouts here.
     *
     * @throws RuntimeException if the current thread is interrupted, even if an element is available. The
     * 		interrupted flag is cleared
     */
    @Override
    public boolean hasNext() {
        if (next != null) {
            return true;
        }

        int backoffStep = 0;
        while (true) {
            // Like LinkedBlockingQueue.poll(timeout, unit): an interrupt fails the call, even if there is an element
            if (Thread.interrupted()) {
                throw new RuntimeException("Concurrent iterator is interrupted", new InterruptedException());
            }
            // relaxedPoll() returns null, instead of spinning, when a supplier has claimed a slot but not yet
            // published its element. The loop simply retries
            next = buffer.relaxedPoll();
            if (next != null) {
                return true;
            }
            if (closed) {
                // poll(), not relaxedPoll(): returns null only when the buffer is truly empty, so an element
                // supplied before close() is never missed
                next = buffer.poll();
                return next != null;
            }
            backoffStep = backOff(backoffStep, CONSUMER_MAX_PARK_SHIFT);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T next() {
        if (next == null && !hasNext()) {
            throw new NoSuchElementException();
        }

        assert next != null : "Unexpected null next";
        final T ret = next;
        next = null;
        return ret;
    }

    /**
     * Supplies a new element to the iterator, blocking if the internal buffer is full.
     *
     * @param element
     * 		The element to add. Cannot be null.
     * @throws InterruptedException
     * 		If interrupted, on entry or while waiting
     * @throws IllegalStateException
     * 		If the iterator is closed, on entry or while waiting
     */
    public void supply(final T element) throws InterruptedException {
        if (closed) {
            throw new IllegalStateException("Cannot supply elements to a closed ConcurrentBlockingIterator");
        }

        Objects.requireNonNull(element);
        int backoffStep = 0;
        while (true) {
            // Like LinkedBlockingQueue.put(): an interrupt fails the call, even if there is room
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            if (buffer.offer(element)) {
                return;
            }
            // The iterator is closed when reconnect is finished or aborted, e.g. when hashing fails and the
            // consumer stops. No slot may ever be freed then, so fail instead of waiting forever
            if (closed) {
                throw new IllegalStateException("ConcurrentBlockingIterator was closed while waiting for space");
            }
            backoffStep = backOff(backoffStep, SUPPLIER_MAX_PARK_SHIFT);
        }
    }

    /**
     * @return the approximate number of supplied elements not yet consumed. Use for metrics and logs only
     */
    public int size() {
        return buffer.size();
    }

    /**
     * Closes the iterator. After closing, no elements may be added, but existing elements in the iterator
     * can still be retrieved. A thread blocked in {@link #supply(Object)} on a full buffer fails with
     * {@link IllegalStateException}.
     */
    public void close() {
        closed = true;
    }
}
