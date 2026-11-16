// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.pipeline;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fails a pipeline benchmark when a component throws on a pipeline thread. An exception there reaches only the thread's
 * uncaught exception handler, never the benchmark thread; without this collector, a benchmark whose pipeline drops
 * events after an exception still passes.
 *
 * <p>Install it with {@code WiringModelBuilder#withUncaughtExceptionHandler} or on a thread pool, and call
 * {@link #throwIfAny()} after every invocation. It is separate from the {@link PipelineTimer}, because a handler cannot
 * tell which input failed.
 */
public final class UncaughtExceptionCollector implements Thread.UncaughtExceptionHandler {

    /** The first exception, with the name of its thread, or {@code null} if none was thrown. */
    private final AtomicReference<Uncaught> first = new AtomicReference<>();

    /** The number of exceptions after the first. */
    private final AtomicLong others = new AtomicLong();

    private record Uncaught(
            @NonNull String threadName, @NonNull Throwable exception) {}

    /**
     * Keeps the first exception and counts the others.
     *
     * @param thread the thread that threw
     * @param exception the exception
     */
    @Override
    public void uncaughtException(@NonNull final Thread thread, @NonNull final Throwable exception) {
        if (!first.compareAndSet(null, new Uncaught(thread.getName(), exception))) {
            others.incrementAndGet();
        }
    }

    /**
     * Throws if a pipeline thread has thrown since this collector was created.
     *
     * @throws IllegalStateException with the first exception as its cause
     */
    public void throwIfAny() {
        final Uncaught uncaught = first.get();
        if (uncaught != null) {
            final long more = others.get();
            throw new IllegalStateException(
                    "The pipeline thread " + uncaught.threadName() + " threw"
                            + (more > 0 ? ", and " + more + " more exceptions followed" : ""),
                    uncaught.exception());
        }
    }
}
