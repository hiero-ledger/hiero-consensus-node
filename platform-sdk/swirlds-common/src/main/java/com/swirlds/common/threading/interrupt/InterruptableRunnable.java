// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.interrupt;

/**
 * Similar to {@link Runnable} but can be interrupted.
 */
@FunctionalInterface
public interface InterruptableRunnable {

    /**
     * Perform an action.
     *
     * @throws InterruptedException
     * 		if the thread is interrupted
     */
    void run() throws InterruptedException;
}
