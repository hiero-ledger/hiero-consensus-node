// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;

/**
 * Interface representing a node in the network.
 *
 * <p>This interface provides methods to control the state of the node, such as killing and reviving it.
 */
public interface Node {

    /**
     * Kill the node.
     *
     * @param timeout the duration to wait before considering the kill operation as failed
     */
    void kill(@NonNull Duration timeout) throws InterruptedException;

    /**
     * Revive the node.
     *
     * @param timeout the duration to wait before considering the revive operation as failed
     */
    void revive(@NonNull Duration timeout) throws InterruptedException;

    /**
     * Submit a transaction to the node.
     *
     * @param transaction the transaction to submit
     */
    void submitTransaction(@NonNull byte[] transaction);

    /**
     * Gets the configuration of the node. The returned object can be used to evaluate the current
     * configuration, but also for modifications.
     *
     * @return the configuration of the node
     */
    @NonNull
    NodeConfiguration getConfiguration();
}
