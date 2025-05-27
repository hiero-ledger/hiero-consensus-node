// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.schedulers.helpers;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A Runnable task that automatically marks its completion and allows to wait for that completion to be marked.
 */
public final class RunnableWithCompletion extends AbstractWithCompleteMarks implements Runnable {
    private final Runnable handler;

    RunnableWithCompletion(@NonNull Runnable handler) {
        super(Gate.openGate());
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
            mark();
        }
    }

    /**
     * Waits until the runnable task is marked as executed.
     */
    public void waitIsFinished() {
        waitExecutions(1);
    }

    /**
     * Creates a new runnable task that will automatically mark its completion.
     *
     * @param runnable the runnable to wrap
     * @return the new {@link RunnableWithCompletion}
     */
    public static RunnableWithCompletion unblocked(@NonNull final Runnable runnable) {
        return new RunnableWithCompletion(runnable);
    }
}
