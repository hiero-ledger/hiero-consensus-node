// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.test.fixtures;

import static org.hiero.base.concurrent.test.fixtures.ThrowingRunnableWrapper.runWrappingChecked;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.Semaphore;

/**
 * Allows a thread to "mark" the execution of a task and for another thread to "wait" for a specified number of these
 * marks. Includes a Gate allowing the initial execution of tasks to be blocked until the gate is released.
 */
public class ExecutionControl {
    protected final Semaphore semaphore;
    protected final Gate gate;

    protected ExecutionControl(@NonNull final Gate gate) {
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
     */
    public void await(int numberOfExecutions) {
        runWrappingChecked(() -> semaphore.acquire(numberOfExecutions));
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

    @Override
    public String toString() {
        return "ExecutionControl{" + "Semaphore=" + semaphore + ", gate=" + gate + '}';
    }
}
