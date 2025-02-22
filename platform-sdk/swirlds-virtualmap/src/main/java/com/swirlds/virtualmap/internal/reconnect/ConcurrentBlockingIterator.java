// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import com.swirlds.virtualmap.internal.merkle.VirtualRootNode;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This iterator was designed specifically for use with {@link VirtualRootNode} during reconnect on the
 * learner, but in concept could be adapted for more general use. This class enables a publisher/subscriber
 * approach to an iterator. New items are added via {@link #supply(Object)}, while items are
 * consumed using {@link #next()}. {@link #hasNext()} will return true until there are no more items to
 * consume and will block if there are no items and {@link #close()} has not been called. Critically,
 * you must call {@link #close()} for the iterator to ever return {@code false} from {@link #hasNext()} or
 * throw {@link NoSuchElementException} from {@link #next()}. If new elements are supplied faster than they
 * are consumed, then {@link #supply(Object)} will block until space becomes available. The
 * constructor allows you to define what the size of the inner buffer is.
 *
 * @param <T>
 * 		The type
 */
public class ConcurrentBlockingIterator<T> implements Iterator<T> {

    /**
     * The underlying buffer of items into which elements are placed prior to removal.
     */
    private final LinkedBlockingQueue<T> buffer;

    /**
     * Indicates that the array has been closed. Once closed, new elements cannot be
     * supplied but existing elements in the buffer can still be consumed.
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Contains a reference to the next element. This is basically a temporary variable.
     */
    private T next;

    /**
     * Create a new {@link ConcurrentBlockingIterator}.
     *
     * @param bufferSize
     * 		The size of the internal buffer. Must be greater than 0.
     */
    public ConcurrentBlockingIterator(final int bufferSize) {
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be greater than zero");
        }
        this.buffer = new LinkedBlockingQueue<>(bufferSize);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasNext() {
        if (next != null) {
            return true;
        }

        // Busy loop. This will lead to really high performance at the cost of a CPU, but since
        // we're in the middle of some pretty serious reconnect stuff, we should allow that.
        // There is no timeout here, the iterator will wait forever (or till closed) for the
        // next elements to be supplied. During reconnects, if no element (dirty leaf from
        // the teacher) is provided in certain period, the whole reconnect thread group will
        // be interrupted, there is no need to have explicit timeouts here
        try {
            // if closed and buffer.poll != null || !closed
            boolean isOpen = !closed.get();
            while (((next = buffer.poll(10, TimeUnit.MILLISECONDS)) == null) && isOpen) {
                isOpen = !closed.get();
            }
        } catch (final InterruptedException e) {
            throw new RuntimeException("Concurrent iterator is interrupted", e);
        }

        return next != null;
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
     * 		If interrupted while waiting
     */
    public void supply(final T element) throws InterruptedException {
        if (closed.get()) {
            throw new IllegalStateException("Cannot supply elements to a closed ConcurrentBlockingIterator");
        }

        Objects.requireNonNull(element);
        buffer.put(element);
    }

    /**
     * Closes the iterator. After closing, no elements may be added, but existing elements in the iterator
     * can still be retrieved.
     */
    public void close() {
        closed.set(true);
    }
}
