// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;

/**
 * Interface representing a network of nodes.
 *
 * <p>This interface provides methods to add and remove nodes, start the network, and add instrumented nodes.
 */
public interface Network {

    /**
     * Add regular nodes to the network.
     *
     * @param count the number of nodes to add
     * @return a list of the added nodes
     */
    @NonNull
    List<Node> addNodes(final int count);

    /**
     * Start the network with the currently configured setup.
     *
     * @param timeout the duration to wait before considering the start operation as failed
     */
    void start(@NonNull Duration timeout) throws InterruptedException;

    /**
     * Add an instrumented node to the network.
     *
     * <p>This method is used to add a node that has additional instrumentation for testing purposes.
     * For example, it can exhibit malicious or erroneous behavior.
     *
     * @return the added instrumented node
     */
    @NonNull
    InstrumentedNode addInstrumentedNode();
}
