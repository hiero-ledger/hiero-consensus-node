// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.otter.fixtures.Node;

/**
 * Interface representing a single direction of a connection between two nodes in a network.
 */
public interface UnidirectionalConnection extends Connection {

    /**
     * Gets the start node of the connection.
     *
     * @return the start node of the connection
     */
    @NonNull
    Node sender();

    /**
     * Gets the end node of the connection.
     *
     * @return the end node of the connection
     */
    @NonNull
    Node receiver();
}
