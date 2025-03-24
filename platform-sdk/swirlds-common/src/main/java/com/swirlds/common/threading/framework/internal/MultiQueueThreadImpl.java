// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.framework.internal;

import com.swirlds.common.threading.framework.BlockingQueueInserter;
import com.swirlds.common.threading.framework.MultiQueueThread;
import com.swirlds.common.threading.framework.QueueThread;
import com.swirlds.common.threading.framework.ThreadSeed;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import org.hiero.consensus.model.utility.interrupt.InterruptableConsumer;

/**
 * A wrapper around a {@link QueueThread} that provides boilerplate
 * for handling multiple types of data in the same queue.
 */
public class MultiQueueThreadImpl implements MultiQueueThread {

    /**
     * The underlying queue thread.
     */
    private final QueueThread<Object> queueThread;

    /**
     * A map of data type to handler for that type.
     */
    private final Map<Class<?>, Consumer<Object>> subHandlers;

    /**
     * Implements an insertion queue for this multi queue thread. Since insertion queues don't actually
     * have different behavior based on type, we can "cheat" and use just a single instance regardless
     * of the number of data types. The compiler will give us compile time safety, and type erasure
     * will make everything magically work at runtime. This is probably the first time type erasure
     * has ever been convenient for something.
     */
    private final BlockingQueueInserter<Object> blockingQueueInserter;

    /**
     * Build a new multi thread queue.
     *
     * @param subHandlers
     * 		handlers for each data type
     * @param queueThreadBuilder
     * 		a function that builds a queue thread
     */
    public MultiQueueThreadImpl(
            Map<Class<?>, Consumer<Object>> subHandlers,
            final Function<InterruptableConsumer<Object>, QueueThread<Object>> queueThreadBuilder) {

        this.subHandlers = Objects.requireNonNull(subHandlers);
        this.queueThread = queueThreadBuilder.apply(this::handle);

        this.blockingQueueInserter = buildQueueInserter();
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    @Override
    public <T> BlockingQueueInserter<T> getInserter(final Class<T> clazz) {
        Objects.requireNonNull(clazz, "null classes not supported");
        if (!subHandlers.containsKey(clazz)) {
            throw new IllegalStateException("no handler for " + clazz);
        }
        return (BlockingQueueInserter<T>) blockingQueueInserter;
    }

    /**
     * Handle an object from the queue.
     *
     * @param object
     * 		the object to be handled
     */
    private void handle(final Object object) {
        Objects.requireNonNull(object, "null objects not supported");
        final Class<?> clazz = object.getClass();
        final Consumer<Object> handler = subHandlers.get(clazz);
        if (handler == null) {
            throw new IllegalStateException("no handler for " + clazz);
        }
        handler.accept(object);
    }

    /**
     * Build the queue inserter. We will reuse this for all data types, since this object's only purpose
     * is to have the compiler give us some compile time safety checks, and type erasure makes this trick
     * work at runtime.
     */
    private BlockingQueueInserter<Object> buildQueueInserter() {
        return new BlockingQueueInserter<>() {
            @Override
            public boolean add(final Object o) {
                return queueThread.add(o);
            }

            @Override
            public boolean offer(final Object o) {
                return queueThread.offer(o);
            }

            @Override
            public boolean offer(final Object o, final long timeout, final TimeUnit unit) throws InterruptedException {
                return queueThread.offer(o, timeout, unit);
            }

            @Override
            public void put(final Object o) throws InterruptedException {
                queueThread.put(o);
            }
        };
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        queueThread.start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean stop() {
        return queueThread.stop();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean stop(final StopBehavior behavior) {
        return queueThread.stop(behavior);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean pause() {
        return queueThread.pause();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean resume() {
        return queueThread.resume();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void join() throws InterruptedException {
        queueThread.join();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void join(final long millis) throws InterruptedException {
        queueThread.join(millis);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void join(final long millis, final int nanos) throws InterruptedException {
        queueThread.join(millis, nanos);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return queueThread.getName();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ThreadSeed buildSeed() {
        return queueThread.buildSeed();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean interrupt() {
        return queueThread.interrupt();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isAlive() {
        return queueThread.isAlive();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Status getStatus() {
        return queueThread.getStatus();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isHanging() {
        return queueThread.isHanging();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        queueThread.clear();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void waitUntilNotBusy() throws InterruptedException {
        queueThread.waitUntilNotBusy();
    }
}
