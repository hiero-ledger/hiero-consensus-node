// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.schedulers.helpers;

import java.util.concurrent.CountDownLatch;

public class Gate {
    private volatile CountDownLatch latch;

    /**
     * Creates a gate that will allways be open and never blocked in nock
     * @return a new Gate
     */
    public static Gate openGate() {
        return new Gate(0);
    }

    /**
     * Creates a closed Gate, it will block in nock by default.
     * @return a new Gate
     */
    public static Gate closedGate() {
        return new Gate(1);
    }

    Gate(int count) {
        latch = new CountDownLatch(count);
    }

    /**
     * Causes the calling thread to block if the gate is closed.
     * The first call to open will cause all blocked threads to continue.
     */
    public void nock() {
        ThrowingRunnableWrapper.runWrappingChecked(latch::await);
    }

    /**
     * Causes the Gate to open; after this method is called, later invocations to nock will not block.
     */
    public void open() {
        latch.countDown();
    }

    /**
     * Releases the previous waiting threads.
     * The gate is now a closed gate.
     */
    public void close() {
        latch.countDown();

        if (latch.getCount() > 0) {
            return;
        }
        latch = new CountDownLatch(1);
    }
}
