// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;

/**
 * Interface for performing asynchronous node actions with a specified timeout.
 */
@SuppressWarnings("unused")
public interface AsyncNodeActions {

    /**
     * Start the node with the configured timeout.
     *
     * @see Node#start()
     */
    void start();

    /**
     * Kill the node without prior cleanup with the configured timeout.
     *
     * @see Node#killImmediately()
     */
    void killImmediately();

    /**
     * Sets the quiescence command of the node.
     *
     * <p>The default command is {@link QuiescenceCommand#DONT_QUIESCE}.
     *
     * @param command the new quiescence command
     */
    void sendQuiescenceCommand(@NonNull QuiescenceCommand command);
}
