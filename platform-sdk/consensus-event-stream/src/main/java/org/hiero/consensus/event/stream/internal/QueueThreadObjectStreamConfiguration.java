// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.stream.internal;

import org.hiero.base.crypto.RunningHashable;
import org.hiero.consensus.concurrent.framework.config.QueueThreadConfiguration;
import org.hiero.consensus.concurrent.framework.config.ThreadNamingConfiguration;
import org.hiero.consensus.concurrent.manager.ThreadManager;
import org.hiero.consensus.event.stream.LinkedObjectStream;

/**
 * Configures and builds {@link QueueThreadObjectStream} instances.
 *
 * @param <T> the type of the object in the stream
 */
public class QueueThreadObjectStreamConfiguration<T extends RunningHashable> {

    private final QueueThreadConfiguration<T> queueThreadConfiguration;
    private LinkedObjectStream<T> forwardTo;

    /**
     * @param threadManager responsible for managing thread lifecycles
     * @param queueName     name of the queue
     */
    public QueueThreadObjectStreamConfiguration(final ThreadManager threadManager, final String queueName) {
        queueThreadConfiguration = new QueueThreadConfiguration<>(threadManager, queueName);
    }

    /**
     * Build a new thread.
     */
    public QueueThreadObjectStream<T> build() {
        if (forwardTo == null) {
            throw new NullPointerException("forwardTo is null");
        }

        return new QueueThreadObjectStream<>(this);
    }

    /**
     * Set the object stream to forward values to.
     */
    public LinkedObjectStream<T> getForwardTo() {
        return forwardTo;
    }

    /**
     * Get the object stream to forward values to.
     *
     * @return this object
     */
    public QueueThreadObjectStreamConfiguration<T> setForwardTo(final LinkedObjectStream<T> forwardTo) {
        this.forwardTo = forwardTo;
        return this;
    }

    /**
     * Get the capacity for created threads.
     */
    public int getCapacity() {
        return queueThreadConfiguration.getCapacity();
    }

    /**
     * Set the capacity for created threads.
     *
     * @return this object
     */
    public QueueThreadObjectStreamConfiguration<T> setCapacity(final int capacity) {
        queueThreadConfiguration.setCapacity(capacity);
        return this;
    }

    /**
     * Get the maximum buffer size for created threads. Buffer size is not the same as queue capacity, it has to do with
     * the buffer that is used when draining the queue.
     */
    public int getMaxBufferSize() {
        return queueThreadConfiguration.getMaxBufferSize();
    }

    /**
     * Set the maximum buffer size for created threads. Buffer size is not the same as queue capacity, it has to do with
     * the buffer that is used when draining the queue.
     *
     * @return this object
     */
    public QueueThreadObjectStreamConfiguration<T> setMaxBufferSize(final int maxBufferSize) {
        queueThreadConfiguration.setMaxBufferSize(maxBufferSize);
        return this;
    }

    /**
     * Get the the thread group that new threads will be created in.
     */
    public ThreadGroup getThreadGroup() {
        return queueThreadConfiguration.getThreadGroup();
    }

    /**
     * Set the the thread group that new threads will be created in.
     *
     * @return this object
     */
    public QueueThreadObjectStreamConfiguration<T> setThreadGroup(final ThreadGroup threadGroup) {
        queueThreadConfiguration.setThreadGroup(threadGroup);
        return this;
    }

    /**
     * Get the daemon behavior of new threads.
     */
    public boolean isDaemon() {
        return queueThreadConfiguration.isDaemon();
    }

    /**
     * Set the daemon behavior of new threads.
     *
     * @return this object
     */
    public QueueThreadObjectStreamConfiguration<T> setDaemon(final boolean daemon) {
        queueThreadConfiguration.setDaemon(daemon);
        return this;
    }

    /**
     * Get the priority of new threads.
     */
    public int getPriority() {
        return queueThreadConfiguration.getPriority();
    }

    /**
     * Set the priority of new threads.
     *
     * @return this object
     */
    public QueueThreadObjectStreamConfiguration<T> setPriority(final int priority) {
        queueThreadConfiguration.setPriority(priority);
        return this;
    }

    /**
     * Get the class loader for new threads.
     */
    public ClassLoader getContextClassLoader() {
        return queueThreadConfiguration.getContextClassLoader();
    }

    /**
     * Set the class loader for new threads.
     *
     * @return this object
     */
    public QueueThreadObjectStreamConfiguration<T> setContextClassLoader(final ClassLoader contextClassLoader) {
        queueThreadConfiguration.setContextClassLoader(contextClassLoader);
        return this;
    }

    /**
     * Get the exception handler for new threads.
     */
    public Thread.UncaughtExceptionHandler getExceptionHandler() {
        return queueThreadConfiguration.getExceptionHandler();
    }

    /**
     * Set the exception handler for new threads.
     *
     * @return this object
     */
    public QueueThreadObjectStreamConfiguration<T> setExceptionHandler(
            final Thread.UncaughtExceptionHandler exceptionHandler) {
        queueThreadConfiguration.setExceptionHandler(exceptionHandler);
        return this;
    }

    /**
     * Intentionally package private. Get the underlying queue thread configuration.
     */
    QueueThreadConfiguration<T> getQueueThreadConfiguration() {
        return queueThreadConfiguration;
    }

    public QueueThreadObjectStreamConfiguration<T> setThreadNamingConfiguration(
            final ThreadNamingConfiguration threadNamingConfiguration) {
        queueThreadConfiguration.setThreadNameProvider(threadNamingConfiguration);
        return this;
    }
}
