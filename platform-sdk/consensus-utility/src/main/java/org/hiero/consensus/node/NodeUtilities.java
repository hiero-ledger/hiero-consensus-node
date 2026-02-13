// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.node;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.node.NodeId;

/**
 * A utility class to help with node-related operations.
 */
public class NodeUtilities {

    private NodeUtilities() {}

    /**
     * Formats a "node name" for a given node id, e.g. "node1" for nodeId == 0.
     * This name can be used for logging purposes, or to support code that
     * uses strings to identify nodes.
     *
     * @param nodeId a node id
     * @return a "node name"
     */
    @NonNull
    public static String formatNodeName(final long nodeId) {
        return "node" + (nodeId + 1);
    }

    /**
     * Formats a "node name" for a given node id, e.g. "node1" for nodeId == 0.
     * This name can be used for logging purposes, or to support code that
     * uses strings to identify nodes.
     *
     * @param nodeId a node id
     * @return a "node name"
     */
    @NonNull
    public static String formatNodeName(final @NonNull NodeId nodeId) {
        return formatNodeName(nodeId.id());
    }
}
