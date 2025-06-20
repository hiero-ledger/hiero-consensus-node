// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Factory for creating other parent affinity matrices for specific use cases.
 */
public class OtherParentMatrixFactory {

    /**
     * Creates a balanced, fully connected other parent affinity matrix so that each node is equally weighted against
     * the others to be selected as the other parent for an event unless it is its own event.
     *
     * @param numNodes
     * 		the number of nodes in the network
     * @return balanced and fully connected affinity matrix
     */
    public static List<List<Double>> createBalancedOtherParentMatrix(final int numNodes) {
        return createOtherParentMatrix(numNodes, (creator, otherParent) -> {
            if (creator == otherParent) {
                return 0.0;
            } else {
                return 1.0;
            }
        });
    }

    /**
     * Creates an other parent affinity matrix that forces the other parent of an event to be {@code nextOtherParent}
     * unless the creator is the {@code nextOtherParent}.
     *
     * @param numNodes
     * @param nextOtherParent
     * @return
     */
    public static List<List<Double>> createForcedOtherParentMatrix(final int numNodes, final int nextOtherParent) {
        return createOtherParentMatrix(numNodes, (creator, otherParent) -> {
            if (creator == otherParent) {
                return 0.0;
            } else if (otherParent == nextOtherParent) {
                return 1.0;
            } else {
                return 0.0;
            }
        });
    }

    /**
     * Creates an other parent affinity matrix that creates cliques of nodes. Each clique syncs within itself frequently
     * but with outsiders rarely.
     *
     * @param numNodes
     * 		the number of nodes in the entire network
     * @param cliques
     * 		a map from node id to clique id
     * @return
     */
    public static List<List<Double>> createCliqueOtherParentMatrix(
            final int numNodes, final Map<Integer, Integer> cliques) {
        return createOtherParentMatrix(numNodes, (creator, otherParent) -> {
            if (creator == otherParent) {
                return 0.0;
            } else if (cliques.get(creator).equals(cliques.get(otherParent))) {
                return 1.0;
            } else {
                return 0.02;
            }
        });
    }

    /**
     * Creates an other parent affinity matrix that forces a partition. One partition will contain the {@code
     * nodeIDsInThisPartition}, and the other partition will contain all the other nodes. Each creator may only
     * select an other parent that is in it's own partition.
     *
     * @param numNodes
     * @param nodeIDsInThisPartition
     * @return
     */
    public static List<List<Double>> createPartitionedOtherParentAffinityMatrix(
            final int numNodes, final Collection<Integer> nodeIDsInThisPartition) {
        return createOtherParentMatrix(numNodes, (creator, otherParent) -> {
            if (creator == otherParent) {
                return 0.0;
            } else {
                if ((nodeIDsInThisPartition.contains(creator) && nodeIDsInThisPartition.contains(otherParent))
                        || (!nodeIDsInThisPartition.contains(creator)
                                && !nodeIDsInThisPartition.contains(otherParent))) {
                    return 1.0;
                } else {
                    return 0.0;
                }
            }
        });
    }

    /**
     * Creates an other parent affinity matrix that shuns a particular node as an other parent. Events will never select
     * the {@code shunnedNode} as an other parent.
     *
     * @param numNodes
     * @param shunnedNode
     * @return
     */
    public static List<List<Double>> createShunnedNodeOtherParentAffinityMatrix(
            final int numNodes, final int shunnedNode) {
        return createOtherParentMatrix(numNodes, (creator, otherParent) -> {
            if (creator == otherParent) {
                return 0.0;
            } else {
                // No node may select the shunnedNode as an other parent
                if (otherParent == shunnedNode) {
                    return 0.0;
                } else {
                    return 1.0;
                }
            }
        });
    }

    /**
     * Creates an other parent affinity matrix.
     *
     * @param numSources
     * 		the number of sources in the graph
     * @param function
     * 		the function that determines the weight of a particular other parent being selected for a creator's new
     * 		event
     * @return
     */
    private static List<List<Double>> createOtherParentMatrix(
            final int numSources, final BiFunction<Integer, Integer, Double> function) {
        final List<List<Double>> matrix = new ArrayList<>(numSources);
        for (int creator = 0; creator < numSources; creator++) {
            final List<Double> row = new ArrayList<>(numSources);
            for (int otherParent = 0; otherParent < numSources; otherParent++) {
                row.add(function.apply(creator, otherParent));
            }
            matrix.add(row);
        }
        return matrix;
    }
}
