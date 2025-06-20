// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import java.io.IOException;

/**
 * Interface for performing asynchronous network actions such as starting, freezing, and shutting down the network
 * with a specified timeout.
 */
@SuppressWarnings("unused")
public interface AsyncNetworkActions {

    /**
     * Start the network with the currently configured setup and timeout
     *
     * @see Network#start()
     *
     * @throws IOException if an I/O error occurs while starting the network
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    void start() throws IOException, InterruptedException;

    /**
     * Freezes the network with the configured timeout.
     *
     * @see Network#freeze()
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    void freeze() throws InterruptedException;

    /**
     * Shuts down the network with the configured timeout.
     *
     * @see Network#shutdown()
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    void shutdown() throws InterruptedException;
}
