// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.test.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;

/**
 * A Runnable task that automatically marks its completion and allows to wait for that completion to be marked.
 */
public final class RunnableCompletionControl implements Runnable {
    private final Runnable handler;
    private final ExecutionControl executionControl;

    RunnableCompletionControl(@NonNull Runnable handler) {
        this.executionControl = new ExecutionControl(Gate.closedGate());
        this.handler = handler;
    }

    /**
     * Starts the runnable task in a thread.
     *
     * @return the started thread
     */
    public Thread start() {
        final Thread thread = new Thread(this);
        thread.start();
        return thread;
    }

    /**
     * Runs the runnable task in the caller thread.
     */
    @Override
    public void run() {
        try {
            handler.run();
        } finally {
            executionControl.mark();
        }
    }

    /**
     * Waits until the runnable task is marked as executed.
     * @param duration the max duration to await for
     */
    public void waitIsFinished(final @NonNull Duration duration) {
        executionControl.await(1, duration);
    }

    /**
     * Creates a new runnable task that will automatically mark its completion.
     *
     * @param runnable the runnable to wrap
     * @return the new {@link RunnableCompletionControl}
     */
    public static RunnableCompletionControl unblocked(@NonNull final Runnable runnable) {
        return new RunnableCompletionControl(runnable);
    }
}
