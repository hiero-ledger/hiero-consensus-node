// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.internal;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;

/**
 * A default implementation of {@link ForkJoinWorkerThread}.
 */
public class DefaultForkJoinWorkerThread extends ForkJoinWorkerThread {

    /**
     * The runnable to run on startup.
     */
    private final Runnable onStartup;

    /**
     * Create a new instance of {@link DefaultForkJoinWorkerThread}.
     *
     * @param name                 the name of the thread
     * @param group                the thread group
     * @param pool                 the fork join pool
     * @param preserveThreadLocals whether to preserve thread locals
     * @param onStartup            the runnable to run on startup
     */
    public DefaultForkJoinWorkerThread(
            @NonNull final String name,
            @NonNull final ThreadGroup group,
            @NonNull final ForkJoinPool pool,
            final boolean preserveThreadLocals,
            @Nullable final Runnable onStartup) {
        super(group, Objects.requireNonNull(pool, "pool must not be null"), preserveThreadLocals);
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(group, "group must not be null");
        setName(name);
        this.onStartup = onStartup;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (onStartup != null) {
            onStartup.run();
        }
    }
}
