// SPDX-License-Identifier: Apache-2.0
package com.swirlds.fcqueue.internal;

import com.swirlds.common.FastCopyable;
import com.swirlds.fcqueue.SlowMockFCQueue;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import org.hiero.base.crypto.SerializableHashable;

/**
 * An iterator for FCQueue, starts at the head of the given queue, ends at the tail of the given queue
 *
 * @param <E>
 * 		the type of elements in the FCQueue
 */
public class FCQueueNodeIterator<E extends FastCopyable & SerializableHashable> implements Iterator<FCQueueNode<E>> {
    /** the node whose element should be returned the next time this.next() is called */
    protected FCQueueNode<E> current;

    /** the tail of this queue, and so the last node that this iterator will return */
    private final FCQueueNode<E> stopNode;

    /** the queue that this is iterating over.  This is used to fail-fast when it changes during iteration. */
    private final SlowMockFCQueue<E> queue;

    /**
     * the number of times the queue has changed, as of the moment this iterator was created. Fail fast if it
     * changes
     */
    private final int numChanges;

    /**
     * start this iterator at the head of the given queue
     *
     * @param queue
     * 		the queue to iterate over
     * @param head
     * 		the head of the queue
     * @param tail
     * 		the tail of the queue
     */
    public FCQueueNodeIterator(final SlowMockFCQueue<E> queue, final FCQueueNode<E> head, final FCQueueNode<E> tail) {
        this.queue = queue;
        this.numChanges = queue.getNumChanges();
        this.current = head;
        this.stopNode = tail;
    }

    /**
     * Returns {@code true} if the iteration has more elements.
     * (In other words, returns {@code true} if {@link #next} would
     * return an element rather than throwing an exception.)
     *
     * @return {@code true} if the iteration has more elements
     */
    @Override
    public boolean hasNext() {
        return current != null;
    }

    /**
     * Returns the next element in the iteration.
     *
     * @return the next element in the iteration
     * @throws NoSuchElementException
     * 		if the iteration has no more elements
     * @throws ConcurrentModificationException
     * 		if the queue changes (such as with add, remove, clear, delete) since this iterator was created. This isn't
     * 		guaranteed to be thrown in that case, but it makes a best effort, so this should only be used for catching
     * 		bugs, but shouldn't be relied on.
     */
    @Override
    public FCQueueNode<E> next() {
        final FCQueueNode<E> old = current;

        if (current == null) {
            throw new NoSuchElementException();
        }

        if (numChanges != queue.getNumChanges()) {
            throw new ConcurrentModificationException();
        }

        if (current == stopNode) {
            current = null;
        } else {
            current = nextNode();
        }

        return old;
    }

    /**
     * This always throws an UnsupportedOperationException, because an FCQueue is not designed to allow removal of any
     * elements other than the head. That is necessary to make fast copies to be fast.
     *
     * @throws UnsupportedOperationException
     * 		if the {@code remove}
     * 		operation is not supported by this iterator
     */
    @Override
    public void remove() {
        throw new UnsupportedOperationException("FCQueue elements can only be removed at the head");
    }

    FCQueueNode<E> nextNode() {
        return current.getTowardTail();
    }
}
