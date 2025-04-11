// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A unit of work that is processed by a task scheduler.
 */
public abstract class AbstractTask extends ForkJoinTask<Void> {

    /**
     * Counts outstanding dependencies. When it reaches 0, the task is ready to run.
     */
    private final AtomicInteger dependencyCount;

    /**
     * The fork join pool that will execute this task.
     */
    private final ForkJoinPool pool;

    /**
     * Constructor.
     *
     * @param pool            the fork join pool that will execute this task
     * @param dependencyCount the number of dependencies that must be satisfied before this task is eligible for
     *                        execution
     */
    protected AbstractTask(@NonNull final ForkJoinPool pool, final int dependencyCount) {
        this.pool = pool;
        this.dependencyCount = dependencyCount > 0 ? new AtomicInteger(dependencyCount) : null;
    }

    /**
     * Unused.
     */
    @Override
    public final Void getRawResult() {
        return null;
    }

    /**
     * Unused.
     */
    @Override
    protected final void setRawResult(Void value) {}

    /**
     * If the task has no dependencies then execute it. If the task has dependencies, decrement the dependency count and
     * execute it if the resulting number of dependencies is zero.
     */
    public void send() {
        if (dependencyCount == null || dependencyCount.decrementAndGet() == 0) {
            if ((Thread.currentThread() instanceof ForkJoinWorkerThread t) && (t.getPool() == pool)) {
                fork();
            } else {
                pool.execute(this);
            }
        }
    }

    @Override
    protected final boolean exec() {
        try {
            return onExecute();
        } catch (final Throwable t) {
            completeExceptionally(t);
            return false;
        }
    }

    /**
     * Implement this method in subclasses to run this task. If an exception occurs while
     * running the task, use {@link #completeExceptionally(Throwable)} to report it.
     *
     * @return true if this task is known to have been completed normally
     */
    protected abstract boolean onExecute() throws Exception;

    @Override
    public final void completeExceptionally(final Throwable t) {
        super.completeExceptionally(t);
        onException(t);
    }

    /**
     * Implement this method in subclasses to handle exceptions occurred in {@link #onExecute()}
     * or in dependency tasks.
     */
    protected void onException(final Throwable t) {}
}
