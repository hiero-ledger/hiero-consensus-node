// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;

/**
 * Interface for performing asynchronous network actions such as starting, freezing, and shutting down the network with
 * a specified timeout.
 */
@SuppressWarnings("unused")
public interface AsyncNetworkActions {

    /**
     * Start the network with the currently configured setup and timeout
     *
     * @see Network#start()
     */
    void start();

    /**
     * Shuts down the network with the configured timeout.
     *
     * @see Network#shutdown()
     */
    void shutdown();

    /**
     * Sets the quiescence command of the network.
     *
     * <p>The default command is {@link QuiescenceCommand#DONT_QUIESCE}.
     *
     * @param command the new quiescence command
     */
    void sendQuiescenceCommand(@NonNull QuiescenceCommand command);
}
