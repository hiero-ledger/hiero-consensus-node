// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

/**
 * Interface for performing asynchronous node actions with a specified timeout.
 */
public interface AsyncNodeActions {

    /**
     * Kill the node without prior cleanup with the configured timeout.
     *
     * @see Node#killImmediately()
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    void killImmediately() throws InterruptedException;

    /**
     * Start the node with the configured timeout.
     *
     * @see Node#start()
     */
    void start();
}
