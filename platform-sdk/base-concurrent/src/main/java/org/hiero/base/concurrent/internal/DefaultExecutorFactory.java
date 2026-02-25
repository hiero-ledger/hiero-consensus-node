// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.internal;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;
import org.hiero.base.concurrent.ExecutorFactory;

/**
 * A default implementation of {@link ExecutorFactory}.
 */
public class DefaultExecutorFactory implements ExecutorFactory {

    /**
     * The thread factory for creating threads for executors.
     */
    private final ThreadFactory executorThreadFactory;

    /**
     * The thread factory for creating threads for scheduled executors.
     */
    private final ThreadFactory scheduledExecutorThreadFactory;

    /**
     * The thread factory for creating single threads.
     */
    private final ThreadFactory singleThreadFactory;

    /**
     * The thread factory for creating fork join worker threads.
     */
    private final ForkJoinWorkerThreadFactory forkJoinWorkerThreadFactory;

    /**
     * The uncaught exception handler for threads.
     */
    private final UncaughtExceptionHandler handler;

    /**
     * Create a new instance of {@link DefaultExecutorFactory}.
     *
     * @param singleThreadFactory            the thread factory for creating single threads
     * @param executorThreadFactory          the thread factory for creating threads for executors
     * @param scheduledExecutorThreadFactory the thread factory for creating threads for scheduled executors
     * @param forkJoinWorkerThreadFactory    the thread factory for creating fork join worker threads
     * @param handler                        the uncaught exception handler for threads
     */
    public DefaultExecutorFactory(
            @NonNull final ThreadFactory singleThreadFactory,
            @NonNull final ThreadFactory executorThreadFactory,
            @NonNull final ThreadFactory scheduledExecutorThreadFactory,
            @NonNull final ForkJoinWorkerThreadFactory forkJoinWorkerThreadFactory,
            @NonNull final UncaughtExceptionHandler handler) {
        this.singleThreadFactory = Objects.requireNonNull(singleThreadFactory, "singleThreadFactory must not be null");
        this.executorThreadFactory =
                Objects.requireNonNull(executorThreadFactory, "executorThreadFactory must not be null");
        this.scheduledExecutorThreadFactory = Objects.requireNonNull(
                scheduledExecutorThreadFactory, "scheduledExecutorThreadFactory must not be null");
        this.forkJoinWorkerThreadFactory =
                Objects.requireNonNull(forkJoinWorkerThreadFactory, "forkJoinWorkerThreadFactory must not be null");
        this.handler = Objects.requireNonNull(handler, "handler must not be null");
    }

    @Override
    public ForkJoinPool createForkJoinPool(int parallelism) {
        if (parallelism <= 0) {
            throw new IllegalArgumentException("parallelism must be greater than 0");
        }
        return new ForkJoinPool(parallelism, forkJoinWorkerThreadFactory, handler, true);
    }

    @Override
    public ExecutorService createExecutorService(int threadCount) {
        if (threadCount <= 0) {
            throw new IllegalArgumentException("threadCount must be greater than 0");
        }
        return Executors.newFixedThreadPool(threadCount, executorThreadFactory);
    }

    @Override
    public ScheduledExecutorService createScheduledExecutorService(int threadCount) {
        return Executors.newScheduledThreadPool(threadCount, scheduledExecutorThreadFactory);
    }

    @NonNull
    @Override
    public Thread createThread(@NonNull Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable must not be null");
        return singleThreadFactory.newThread(runnable);
    }

    /**
     * Create a new instance of {@link DefaultExecutorFactory}.
     *
     * @param groupName        the name for all thread groups
     * @param onStartup        the runnable to run on thread startup
     * @param exceptionHandler the uncaught exception handler for threads
     * @return the new instance
     */
    @NonNull
    public static DefaultExecutorFactory create(
            @NonNull final String groupName,
            @Nullable final Runnable onStartup,
            @NonNull final UncaughtExceptionHandler exceptionHandler) {
        Objects.requireNonNull(groupName, "groupName must not be null");
        final ThreadGroup group = new ThreadGroup(groupName);

        final Supplier<String> singleThreadNameFactory = DefaultThreadFactory.createThreadNameFactory("SingleThread");
        final ThreadFactory singleThreadFactory =
                new DefaultThreadFactory(group, singleThreadNameFactory, exceptionHandler, onStartup);

        final Supplier<String> executorThreadNameFactory =
                DefaultThreadFactory.createThreadNameFactory(groupName + "PoolThread");
        final ThreadFactory executorThreadFactory =
                new DefaultThreadFactory(group, executorThreadNameFactory, exceptionHandler, onStartup);

        final Supplier<String> scheduledExecutorThreadNameFactory =
                DefaultThreadFactory.createThreadNameFactory(groupName + "ScheduledPoolThread");
        final ThreadFactory scheduledExecutorThreadFactory =
                new DefaultThreadFactory(group, scheduledExecutorThreadNameFactory, exceptionHandler, onStartup);

        final Supplier<String> forkJoinThreadNameFactory =
                DefaultForkJoinWorkerThreadFactory.createThreadNameFactory(groupName + "ForkJoinThread");
        final ForkJoinWorkerThreadFactory forkJoinThreadFactory =
                new DefaultForkJoinWorkerThreadFactory(group, forkJoinThreadNameFactory, onStartup);
        return new DefaultExecutorFactory(
                singleThreadFactory,
                executorThreadFactory,
                scheduledExecutorThreadFactory,
                forkJoinThreadFactory,
                exceptionHandler);
    }
}
