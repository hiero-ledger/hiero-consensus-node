// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

/**
 * Interface for performing asynchronous mini-node actions with a specified timeout.
 */
public interface AsyncMiniNodeActions {

    /**
     * Start the node with the configured timeout.
     *
     * @see MiniNode#start()
     */
    void start();

    /**
     * Kill the node without prior cleanup with the configured timeout.
     *
     * @see MiniNode#killImmediately()
     */
    void killImmediately();
}
