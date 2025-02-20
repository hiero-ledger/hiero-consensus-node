// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.signed;

import static com.swirlds.common.formatting.TextEffect.BRIGHT_CYAN;
import static com.swirlds.common.formatting.TextEffect.BRIGHT_RED;
import static com.swirlds.common.formatting.TextEffect.BRIGHT_YELLOW;
import static com.swirlds.common.formatting.TextEffect.GRAY;

import com.swirlds.common.formatting.TextTable;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.route.MerkleRoute;
import com.swirlds.common.merkle.route.MerkleRouteUtils;

/**
 * A pair of mismatched nodes. Nodes may be null.
 *
 * @param nodeA
 * 		the node from tree A
 * @param nodeB
 * 		the node from tree B
 */
public record MismatchedNodes(MerkleNode nodeA, MerkleNode nodeB) {

    /**
     * Append information describing the differences in the nodes to a string builder.
     *
     * @param table
     * 		the table where we are adding data
     */
    public void appendNodeDescriptions(final TextTable table) {
        final MerkleRoute route = nodeA == null ? nodeB.getRoute() : nodeA.getRoute();

        final String stepString;
        if (route.isEmpty()) {
            stepString = "(root)";
        } else {
            stepString = Integer.toString(route.getStep(-1));
        }
        final String formattedStepString = BRIGHT_CYAN.apply(stepString);

        final String nodeATypeString = nodeA == null ? "null" : nodeA.getClass().getSimpleName();
        final String formattedNodeATypeString = BRIGHT_YELLOW.apply(nodeATypeString);

        final StringBuilder firstColumnBuilder = new StringBuilder();
        final int routeSize = route.size();
        firstColumnBuilder
                .append("  ".repeat(routeSize))
                .append(formattedStepString)
                .append(" ")
                .append(formattedNodeATypeString);
        final String firstColumn = firstColumnBuilder.toString();

        final String routeString = MerkleRouteUtils.merkleRouteToPathFormat(route);
        final String formattedRouteString = BRIGHT_RED.apply(routeString);

        final String nodeAString;
        final String nodeBString;

        if (nodeA == null) {
            nodeAString = "null";
            nodeBString = nodeB.getClass().getSimpleName();
        } else if (nodeB == null) {
            nodeAString = nodeA.getClass().getSimpleName();
            nodeBString = "null";
        } else if (nodeA.getClassId() != nodeB.getClassId()) {
            nodeAString = nodeA.getClass().getSimpleName();
            nodeBString = nodeB.getClass().getSimpleName();
        } else {
            nodeAString = nodeA.getHash().toHex(12);
            nodeBString = nodeB.getHash().toHex(12);
        }

        final String formattedNodeAString = GRAY.apply(nodeAString);
        final String formattedNodeBString = GRAY.apply(nodeBString);

        table.addRow(firstColumn, formattedRouteString, formattedNodeAString, formattedNodeBString);
    }
}
