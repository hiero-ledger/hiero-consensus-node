// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.schedulers.helpers;

import static com.swirlds.component.framework.schedulers.helpers.ThrowingRunnableWrapper.runWrappingChecked;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.Semaphore;

/**
 * Allows a thread to "mark" the completion of a task and for another thread to "wait" for a specified number of these
 * marks. Includes a Gate allowing the initial execution of tasks to be blocked until the gate is released.
 */
public abstract class AbstractWithCompleteMarks {
    protected final Semaphore semaphore;
    protected final Gate gate;

    protected AbstractWithCompleteMarks(@NonNull final Gate gate) {
        this.semaphore = new Semaphore(0);
        this.gate = gate;
    }

    protected void mark() {
        semaphore.release();
    }

    public void waitExecutions(int numberOfExecutions) {
        runWrappingChecked(() -> semaphore.acquire(numberOfExecutions));
        runWrappingChecked(() -> Thread.sleep(10));
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
}
