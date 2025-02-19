/*
 * Copyright (C) 2021-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.test.graph;

import static com.swirlds.platform.test.graph.OtherParentMatrixFactory.createBalancedOtherParentMatrix;
import static com.swirlds.platform.test.graph.OtherParentMatrixFactory.createPartitionedOtherParentAffinityMatrix;

import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.test.event.emitter.EventEmitter;
import com.swirlds.platform.test.fixtures.event.generator.GraphGenerator;
import com.swirlds.platform.test.sync.SyncNode;
import com.swirlds.platform.test.sync.SyncTestParams;
import java.util.List;
import java.util.Random;

/**
 * <p>This class manipulates the event generator to force a partition in the graphs. Nodes in partition A only create
 * events with other parents that are also in partition A. Nodes in partition B only create events with other parents
 * that are also in partition B.</p>
 *
 * <p>Graphs will have {@link SyncTestParams#getNumCommonEvents()} events that are in a fully connected graph, then the
 * partition occurs. Events generated after the {@link SyncTestParams#getNumCommonEvents()} will conform to the defined
 * partition parameters.</p>
 */
public class PartitionedGraphCreator {

    public static void setupPartitionForNode(
            final SyncTestParams params, final SyncNode node, final List<Integer> nodesInPartition) {
        final EventEmitter emitter = node.getEmitter();
        final GraphGenerator graphGenerator = emitter.getGraphGenerator();
        final AddressBook addressBook = graphGenerator.getAddressBook();

        final List<List<Double>> fullyConnectedMatrix = createBalancedOtherParentMatrix(params.getNumNetworkNodes());

        final List<List<Double>> partitionedOtherMatrix =
                createPartitionedOtherParentAffinityMatrix(params.getNumNetworkNodes(), nodesInPartition);

        // Setup node sources to select creators and other parents in it's own partition after the common
        // events are generated. Nodes not in this partition must not create any events so that the caller and listener
        // know nothing of the other's partition.
        for (int i = 0; i < graphGenerator.getNumberOfSources(); i++) {
            final boolean isSourceInPartition = nodesInPartition.contains(i);

            graphGenerator.getSource(addressBook.getNodeId(i)).setNewEventWeight((r, index, prev) -> {
                if (index < params.getNumCommonEvents() || isSourceInPartition) {
                    return 1.0;
                } else {
                    return 0.0;
                }
            });
        }

        graphGenerator.setOtherParentAffinity((Random r, long eventIndex, List<List<Double>> previousValue) -> {
            if (eventIndex < params.getNumCommonEvents()) {
                return fullyConnectedMatrix;
            } else {
                return partitionedOtherMatrix;
            }
        });
    }
}
