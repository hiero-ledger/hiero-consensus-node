// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.framework.internal;

import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Boilerplate for implementing a blocking queue using another queue.
 *
 * @param <T>
 * 		the type of the element in the queue
 */
@SuppressWarnings({"NullableProblems", "SuspiciousToArrayCall"})
public abstract class AbstractBlockingQueue<T> implements BlockingQueue<T> {

    private final BlockingQueue<T> queue;

    protected AbstractBlockingQueue(final BlockingQueue<T> queue) {
        this.queue = queue;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean add(final T t) {
        return queue.add(t);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean offer(final T t) {
        return queue.offer(t);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T remove() {
        return queue.remove();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T poll() {
        return queue.poll();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T element() {
        return queue.element();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T peek() {
        return queue.peek();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void put(final T t) throws InterruptedException {
        queue.put(t);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean offer(final T t, final long timeout, final TimeUnit unit) throws InterruptedException {
        return queue.offer(t, timeout, unit);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T take() throws InterruptedException {
        return queue.take();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T poll(final long timeout, final TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int remainingCapacity() {
        return queue.remainingCapacity();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean remove(final Object o) {
        return queue.remove(o);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean containsAll(final Collection<?> c) {
        return queue.containsAll(c);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean addAll(final Collection<? extends T> c) {
        return queue.addAll(c);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean removeAll(final Collection<?> c) {
        return queue.removeAll(c);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean retainAll(final Collection<?> c) {
        return queue.retainAll(c);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        queue.clear();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int size() {
        return queue.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean contains(final Object o) {
        return queue.contains(o);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Iterator<T> iterator() {
        return queue.iterator();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object[] toArray() {
        return queue.toArray();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T1> T1[] toArray(final T1[] a) {
        return queue.toArray(a);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int drainTo(final Collection<? super T> c) {
        return queue.drainTo(c);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int drainTo(final Collection<? super T> c, final int maxElements) {
        return queue.drainTo(c, maxElements);
    }
}
