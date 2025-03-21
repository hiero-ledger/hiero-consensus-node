// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.graph;

import static com.swirlds.platform.test.fixtures.graph.OtherParentMatrixFactory.createBalancedOtherParentMatrix;
import static com.swirlds.platform.test.fixtures.graph.OtherParentMatrixFactory.createPartitionedOtherParentAffinityMatrix;

import com.swirlds.platform.test.fixtures.event.emitter.EventEmitter;
import com.swirlds.platform.test.fixtures.event.generator.GraphGenerator;
import com.swirlds.platform.test.fixtures.sync.SyncNode;
import java.util.List;
import java.util.Random;

/**
 * <p>This class manipulates the event generator to force a partition in the graphs. Nodes in partition A only create
 * events with other parents that are also in partition A. Nodes in partition B only create events with other parents
 * that are also in partition B.</p>
 *
 * <p>Graphs will have {@code numCommonEvents} events that are in a fully connected graph, then the
 * partition occurs. Events generated after the {@code numCommonEvents} will conform to the defined
 * partition parameters.</p>
 */
public class PartitionedGraphCreator {

    public static void setupPartitionForNode(
            final SyncNode node,
            final List<Integer> nodesInPartition,
            final int numCommonEvents,
            final int numNetworkNodes) {
        final EventEmitter emitter = node.getEmitter();
        final GraphGenerator graphGenerator = emitter.getGraphGenerator();

        final List<List<Double>> fullyConnectedMatrix = createBalancedOtherParentMatrix(numNetworkNodes);

        final List<List<Double>> partitionedOtherMatrix =
                createPartitionedOtherParentAffinityMatrix(numNetworkNodes, nodesInPartition);

        // Setup node sources to select creators and other parents in it's own partition after the common
        // events are generated. Nodes not in this partition must not create any events so that the caller and listener
        // know nothing of the other's partition.
        for (int i = 0; i < graphGenerator.getNumberOfSources(); i++) {
            final boolean isSourceInPartition = nodesInPartition.contains(i);

            graphGenerator.getSourceByIndex(i).setNewEventWeight((r, index, prev) -> {
                if (index < numCommonEvents || isSourceInPartition) {
                    return 1.0;
                } else {
                    return 0.0;
                }
            });
        }

        graphGenerator.setOtherParentAffinity((Random r, long eventIndex, List<List<Double>> previousValue) -> {
            if (eventIndex < numCommonEvents) {
                return fullyConnectedMatrix;
            } else {
                return partitionedOtherMatrix;
            }
        });
    }
}
