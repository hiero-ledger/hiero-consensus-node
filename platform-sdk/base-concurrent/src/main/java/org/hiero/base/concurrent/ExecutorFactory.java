// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import org.hiero.base.concurrent.internal.DefaultExecutorFactory;

/**
 * A factory for creating executors and threads.
 */
public interface ExecutorFactory {

    /**
     * Create a ForkJoinPool with the given parallelism.
     *
     * @param parallelism the parallelism
     * @return the ForkJoinPool
     */
    @NonNull
    ForkJoinPool createForkJoinPool(int parallelism);

    /**
     * Create an ExecutorService with the given thread count.
     *
     * @param threadCount the thread count
     * @return the ExecutorService
     */
    @NonNull
    ExecutorService createExecutorService(int threadCount);

    /**
     * Create a ScheduledExecutorService with the given thread count.
     *
     * @param threadCount the thread count
     * @return the ScheduledExecutorService
     */
    ScheduledExecutorService createScheduledExecutorService(int threadCount);

    /**
     * Create a not started thread with the given runnable.
     *
     * @param runnable the runnable
     * @return the not started thread
     * @deprecated all usage should be migrated to {@link #createExecutorService(int)}
     */
    @Deprecated
    @NonNull
    Thread createThread(@NonNull Runnable runnable);

    /**
     * Creates a new instance of {@link ExecutorFactory}.
     *
     * @param groupName        the thread group name
     * @param onStartup        the on startup runnable
     * @param exceptionHandler the exception handler
     * @return the new instance of {@link ExecutorFactory}
     */
    @NonNull
    static ExecutorFactory create(
            @NonNull final String groupName,
            @Nullable final Runnable onStartup,
            @NonNull final UncaughtExceptionHandler exceptionHandler) {
        return DefaultExecutorFactory.create(groupName, onStartup, exceptionHandler);
    }

    /**
     * Creates a new instance of {@link ExecutorFactory}.
     *
     * @param groupName        the thread group name
     * @param exceptionHandler the exception handler
     * @return the new instance of {@link ExecutorFactory}
     */
    static ExecutorFactory create(
            @NonNull final String groupName, @NonNull final UncaughtExceptionHandler exceptionHandler) {
        return create(groupName, null, exceptionHandler);
    }
}
