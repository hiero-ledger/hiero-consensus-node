// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.internal;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.function.Supplier;

/**
 * A default implementation of {@link ForkJoinWorkerThreadFactory}.
 */
public class DefaultForkJoinWorkerThreadFactory implements ForkJoinWorkerThreadFactory {

    /**
     * Whether to preserve thread locals.
     */
    public static final boolean PRESERVE_THREAD_LOCALS = true;

    /**
     * The thread group.
     */
    private final ThreadGroup threadGroup;

    /**
     * The thread name factory.
     */
    private final Supplier<String> threadNameFactory;

    /**
     * The runnable to run on startup.
     */
    private final Runnable onStartup;

    /**
     * Create a new instance of {@link DefaultForkJoinWorkerThreadFactory}.
     *
     * @param threadGroup       the thread group
     * @param threadNameFactory the thread name factory
     * @param onStartup         the runnable to run on startup
     */
    public DefaultForkJoinWorkerThreadFactory(
            @NonNull final ThreadGroup threadGroup,
            @NonNull final Supplier<String> threadNameFactory,
            @Nullable final Runnable onStartup) {
        this.threadGroup = Objects.requireNonNull(threadGroup, "threadGroup must not be null");
        this.threadNameFactory = Objects.requireNonNull(threadNameFactory, "threadNameFactory must not be null");
        this.onStartup = onStartup;
    }

    @Override
    @NonNull
    public ForkJoinWorkerThread newThread(@NonNull final ForkJoinPool pool) {
        return new DefaultForkJoinWorkerThread(
                threadNameFactory.get(), threadGroup, pool, PRESERVE_THREAD_LOCALS, onStartup);
    }

    /**
     * Create a new instance of {@link DefaultForkJoinWorkerThreadFactory}.
     *
     * @param threadNamePrefix the thread name prefix
     * @return the new instance
     */
    @NonNull
    public static Supplier<String> createThreadNameFactory(@NonNull final String threadNamePrefix) {
        return DefaultThreadFactory.createThreadNameFactory(threadNamePrefix);
    }
}
