// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model.internal.standard;

import static java.util.concurrent.TimeUnit.SECONDS;

import com.swirlds.base.state.Startable;
import com.swirlds.base.state.Stoppable;

/**
 * Creates a non-daemon JVM thread that does nothing. Keeps the JVM alive if all other threads are daemon threads.
 */
public class JvmAnchor implements Startable, Stoppable {

    private final Thread thread;

    /**
     * Constructor.
     */
    public JvmAnchor() {
        thread = new Thread(this::run, "<jvm-anchor>");
        thread.setDaemon(false); // important
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        thread.start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() {
        thread.interrupt();
    }

    /**
     * Runs and does nothing until interrupted.
     */
    private void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                SECONDS.sleep(Integer.MAX_VALUE);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
