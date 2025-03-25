// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.framework.internal;

import com.swirlds.common.threading.framework.QueueThread;
import com.swirlds.common.threading.framework.Stoppable;
import com.swirlds.common.threading.framework.config.QueueThreadMetricsConfiguration;
import com.swirlds.common.threading.manager.ThreadManager;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.hiero.consensus.model.utility.interrupt.InterruptableConsumer;
import org.hiero.consensus.model.utility.interrupt.InterruptableRunnable;

/**
 * Boilerplate getters, setters, and configuration for queue thread configuration.
 *
 * @param <C> the type of the class extending this class
 * @param <T> the type of the objects in the queue
 */
public abstract class AbstractQueueThreadConfiguration<C extends AbstractQueueThreadConfiguration<C, T>, T>
        extends AbstractStoppableThreadConfiguration<C, InterruptableRunnable> {

    public static final int DEFAULT_CAPACITY = 100;
    public static final int DEFAULT_MAX_BUFFER_SIZE = 10_000;
    public static final int UNLIMITED_CAPACITY = -1;

    /**
     * The maximum capacity of the queue. If -1 then there is no maximum capacity.
     */
    private int capacity = DEFAULT_CAPACITY;

    /**
     * The maximum size of the buffer used to drain the queue.
     */
    private int maxBufferSize = DEFAULT_MAX_BUFFER_SIZE;

    /**
     * The method used to handle items from the queue.
     */
    private InterruptableConsumer<T> handler;

    /** An initialized queue to use. */
    private BlockingQueue<T> queue;

    private QueueThreadMetricsConfiguration metricsConfiguration;

    /**
     * The callback to run periodically when the queue is idle.
     */
    private InterruptableRunnable idleCallback;

    /**
     * The callback to run whenever a batch of elements has been handled. No callback is called if the callback is
     * null.
     */
    private InterruptableRunnable batchHandledCallback;

    /**
     * When waiting for work, the amount of time to block.
     */
    private Duration waitForWorkDuration = Duration.ofMillis(10);

    protected AbstractQueueThreadConfiguration(final ThreadManager threadManager) {
        super(threadManager);

        // Queue threads are not interruptable by default
        setStopBehavior(Stoppable.StopBehavior.BLOCKING);
    }

    /**
     * Copy constructor.
     *
     * @param that the configuration to copy
     */
    protected AbstractQueueThreadConfiguration(final AbstractQueueThreadConfiguration<C, T> that) {
        super(that);

        this.capacity = that.capacity;
        this.maxBufferSize = that.maxBufferSize;
        this.handler = that.handler;
        this.queue = that.queue;
        this.metricsConfiguration = that.metricsConfiguration;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public abstract AbstractQueueThreadConfiguration<C, T> copy();

    protected QueueThread<T> buildQueueThread(final boolean start) {
        final QueueThread<T> thread = new QueueThreadImpl<>(this);

        if (start) {
            thread.start();
        }

        return thread;
    }

    /**
     * Get the capacity for created threads. If -1 then the queue has no maximum capacity.
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * Set the capacity for created threads.
     *
     * @return this object
     */
    @SuppressWarnings("unchecked")
    public C setCapacity(final int capacity) {
        throwIfImmutable();
        this.capacity = capacity;
        return (C) this;
    }

    /**
     * Set the capacity to unlimited.
     *
     * @return this object
     */
    @SuppressWarnings("unchecked")
    public C setUnlimitedCapacity() {
        throwIfImmutable();
        this.capacity = UNLIMITED_CAPACITY;
        return (C) this;
    }

    /**
     * Get the maximum buffer size for created threads. Buffer size is not the same as queue capacity, it has to do with
     * the buffer that is used when draining the queue.
     */
    public int getMaxBufferSize() {
        return maxBufferSize;
    }

    /**
     * Set the maximum buffer size for created threads. Buffer size is not the same as queue capacity, it has to do with
     * the buffer that is used when draining the queue.
     *
     * @return this object
     */
    @SuppressWarnings("unchecked")
    public C setMaxBufferSize(final int maxBufferSize) {
        throwIfImmutable();
        this.maxBufferSize = maxBufferSize;
        return (C) this;
    }

    /**
     * Get the handler method that will be called against every item in the queue.
     */
    protected InterruptableConsumer<T> getHandler() {
        return handler;
    }

    /**
     * Set the handler method that will be called against every item in the queue.
     *
     * @return this object
     */
    @SuppressWarnings("unchecked")
    protected C setHandler(final InterruptableConsumer<T> handler) {
        throwIfImmutable();
        this.handler = handler;
        return (C) this;
    }

    /**
     * Set the idle callback that will be called periodically when the queue is empty.
     *
     * @return this object
     */
    @SuppressWarnings("unchecked")
    public C setIdleCallback(@Nullable final InterruptableRunnable idleCallback) {
        this.idleCallback = idleCallback;
        return (C) this;
    }

    /**
     * Get the idle callback that will be called periodically when the queue is empty.
     */
    @Nullable
    public InterruptableRunnable getIdleCallback() {
        return idleCallback;
    }

    /**
     * Get a callback that should be invoked, if non-null, whenever a batch of elements has been handled.
     */
    @Nullable
    public InterruptableRunnable getBatchHandledCallback() {
        return batchHandledCallback;
    }

    /**
     * Set a callback that should be invoked whenever a batch of elements has been handled.
     *
     * @return this object
     */
    @SuppressWarnings("unchecked")
    public C setBatchHandledCallback(@Nullable final InterruptableRunnable batchHandledCallback) {
        this.batchHandledCallback = batchHandledCallback;
        return (C) this;
    }

    /**
     * Get the amount of time that the thread blocks while waiting for work.
     */
    @NonNull
    public Duration getWaitForWorkDuration() {
        return waitForWorkDuration;
    }

    /**
     * Set the amount of time that the thread blocks while waiting for work.
     *
     * @param waitForWorkDuration the amount of time to wait
     * @return this object
     */
    @SuppressWarnings("unchecked")
    public C setWaitForWorkDuration(@NonNull final Duration waitForWorkDuration) {
        this.waitForWorkDuration = Objects.requireNonNull(waitForWorkDuration);
        return (C) this;
    }

    /**
     * Gets the queue specified by the user, or null if none has been specified.
     */
    public BlockingQueue<T> getQueue() {
        return queue;
    }

    /**
     * Sets the initialized queue. The default is a {@link LinkedBlockingQueue}.
     *
     * <p>
     * Note: Please be cautious if a custom queue implementation is used with metrics enabled, and make sure that the
     * {@code size()} method guarantees O(1). Otherwise, it will lead to significant performance loss during the metric
     * sampling.
     * </p>
     *
     * @param queue the initialized queue
     * @return this object
     */
    @SuppressWarnings("unchecked")
    public C setQueue(final BlockingQueue<T> queue) {
        throwIfImmutable();
        this.queue = queue;
        return (C) this;
    }

    public QueueThreadMetricsConfiguration getMetricsConfiguration() {
        return metricsConfiguration;
    }

    @SuppressWarnings("unchecked")
    public C setMetricsConfiguration(final QueueThreadMetricsConfiguration metricsConfiguration) {
        this.metricsConfiguration = metricsConfiguration;
        return (C) this;
    }
}
