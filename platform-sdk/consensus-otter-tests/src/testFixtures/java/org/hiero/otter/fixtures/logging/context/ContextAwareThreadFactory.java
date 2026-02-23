// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.logging.context;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.apache.logging.log4j.ThreadContext;

/**
 * A {@link ThreadFactory} that applies a captured {@link ThreadContext} snapshot to every thread it
 * creates. Useful when building executors directly via {@link Executors}.
 */
public final class ContextAwareThreadFactory implements ThreadFactory {

    private final ThreadFactory delegate;
    private final NodeLoggingContext.ContextSnapshot snapshot;

    /**
     * Creates a factory that captures the current {@link ThreadContext} and applies it to each
     * thread created via {@link Executors} helpers.
     */
    public ContextAwareThreadFactory() {
        this(Executors.defaultThreadFactory());
    }

    /**
     * Creates a factory with a custom delegate. The current {@link ThreadContext} at construction
     * time is captured and propagated to all threads spawned via {@link #newThread(Runnable)}.
     *
     * @param delegate the delegate factory to use
     */
    public ContextAwareThreadFactory(@NonNull final ThreadFactory delegate) {
        this.delegate = requireNonNull(delegate, "delegate must not be null");
        this.snapshot = NodeLoggingContext.snapshot();
    }

    @Override
    public Thread newThread(@NonNull final Runnable runnable) {
        requireNonNull(runnable, "runnable must not be null");
        return delegate.newThread(() -> NodeLoggingContext.runWithSnapshot(snapshot, runnable));
    }
}
