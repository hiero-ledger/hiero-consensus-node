// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.internal.impl;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A thread factory for creating threads for the base modules. All threads created by this factory are daemon threads
 * and have a low priority.
 */
class BaseExecutorThreadFactory implements ThreadFactory {

    /**
     * The number of threads created by this factory.
     */
    private AtomicLong threadNumber = new AtomicLong(1);

    /**
     * Constructs a new factory.
     */
    protected BaseExecutorThreadFactory() {}

    @Override
    public Thread newThread(@NonNull final Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable must not be null");
        final String name = "BaseExecutor-" + threadNumber.getAndIncrement();
        final Thread thread = new Thread(runnable, name);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.setDaemon(true);
        return thread;
    }
}
