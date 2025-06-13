// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import java.io.IOException;

/**
 * Interface for performing asynchronous node actions with a specified timeout.
 */
@SuppressWarnings("unused")
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
     * Shutdown the node gracefully with the configured timeout.
     *
     * @see Node#shutdownGracefully()
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    void shutdownGracefully() throws InterruptedException;

    /**
     * Start the node with the configured timeout.
     *
     * @see Node#start()
     *
     * @throws IOException if an I/O error occurs while starting the node
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    void start() throws IOException, InterruptedException;
}
