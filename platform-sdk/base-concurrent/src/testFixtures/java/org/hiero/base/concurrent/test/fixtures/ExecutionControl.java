// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.test.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Allows a thread to "mark" the execution of a task and for another thread to "wait" for a specified number of these
 * marks. Includes a Gate allowing the initial execution of tasks to be blocked until the gate is released.
 */
public class ExecutionControl {
    private final Semaphore semaphore;
    private final Gate gate;
    private static final long DEFAULT_TIMEOUT = Duration.ofSeconds(10).toMillis();

    ExecutionControl(@NonNull final Gate gate) {
        this.semaphore = new Semaphore(0);
        this.gate = gate;
    }

    /**
     * Counts one executions
     */
    public void mark() {
        semaphore.release();
    }

    /**
     * Awaits for the number of executions to be collected
     * @param numberOfExecutions expected number of executions
     * @param duration the max time to wait for the mark to complete
     */
    public void await(int numberOfExecutions, final Duration duration) {
        try {
            if (!semaphore.tryAcquire(numberOfExecutions, duration.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Timed out of %s waiting for an execution to finish:".formatted(duration));
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while waiting for an execution to finish", e);
        }
    }

    /**
     * Unblocks the gate that is guarding the handler and enables its execution.
     */
    public void unblock() {
        gate.open();
    }

    /**
     * blocks the gate that is guarding the handler and prevents its execution.
     */
    public void block() {
        gate.close();
    }

    /**
     * If the gate that is guarding the handler is open it will return immediately.
     * If the gate is closed, it will block the calling thread.
     */
    public void knock() {
        gate.knock(DEFAULT_TIMEOUT);
    }

    @Override
    public String toString() {
        return "ExecutionControl{" + "Semaphore=" + semaphore + ", gate=" + gate + '}';
    }
}
