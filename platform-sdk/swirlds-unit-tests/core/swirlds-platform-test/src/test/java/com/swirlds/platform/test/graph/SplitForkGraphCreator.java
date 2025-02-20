// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.graph;

import static com.swirlds.platform.test.graph.OtherParentMatrixFactory.createBalancedOtherParentMatrix;
import static com.swirlds.platform.test.graph.OtherParentMatrixFactory.createForcedOtherParentMatrix;
import static com.swirlds.platform.test.graph.OtherParentMatrixFactory.createShunnedNodeOtherParentAffinityMatrix;

import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.test.event.emitter.StandardEventEmitter;
import com.swirlds.platform.test.sync.SyncTestParams;
import java.util.List;
import java.util.Random;

/**
 * <p>This class manipulates the event generator to force the creation of a split fork, where each node has one branch
 * of a fork. Neither node knows that there is a fork until they sync.</p>
 *
 * <p>Graphs will have {@link SyncTestParams#getNumCommonEvents()} events that do not fork, then the
 * split fork occurs. Events generated after the {@link SyncTestParams#getNumCommonEvents()} will not select the creator
 * with the split fork as an other parent to prevent more split forks from occurring. The creator with the split fork may
 * select any other creator's event as an other parent.</p>
 */
public class SplitForkGraphCreator {

    public static void createSplitForkConditions(
            final SyncTestParams params,
            final StandardEventEmitter generator,
            final int creatorToFork,
            final int otherParent) {

        forceNextCreator(params, generator, creatorToFork);
        forceNextOtherParent(params, generator, creatorToFork, otherParent);
    }

    private static void forceNextCreator(
            final SyncTestParams params, final StandardEventEmitter emitter, final int creatorToFork) {
        final AddressBook addressBook = emitter.getGraphGenerator().getAddressBook();
        final int numberOfSources = addressBook.getSize();
        for (int i = 0; i < numberOfSources; i++) {
            final boolean sourceIsCreatorToFork = i == creatorToFork;
            emitter.getGraphGenerator().getSource(addressBook.getNodeId(i)).setNewEventWeight((r, index, prev) -> {
                if (index < params.getNumCommonEvents()) {
                    return 1.0;
                } else if (index == params.getNumCommonEvents() && sourceIsCreatorToFork) {
                    return 1.0;
                } else if (index > params.getNumCommonEvents()) {
                    return 1.0;
                } else {
                    return 0.0;
                }
            });
        }
    }

    private static void forceNextOtherParent(
            final SyncTestParams params,
            final StandardEventEmitter emitter,
            final int creatorToShun,
            final int nextOtherParent) {

        final int numSources = emitter.getGraphGenerator().getNumberOfSources();

        final List<List<Double>> balancedMatrix = createBalancedOtherParentMatrix(params.getNumNetworkNodes());

        final List<List<Double>> forcedOtherParentMatrix = createForcedOtherParentMatrix(numSources, nextOtherParent);

        final List<List<Double>> shunnedOtherParentMatrix =
                createShunnedNodeOtherParentAffinityMatrix(numSources, creatorToShun);

        emitter.getGraphGenerator()
                .setOtherParentAffinity((Random r, long eventIndex, List<List<Double>> previousValue) -> {
                    if (eventIndex < params.getNumCommonEvents()) {
                        // Before the split fork, use the normal matrix
                        return balancedMatrix;
                    } else if (eventIndex == params.getNumCommonEvents()) {
                        // At the event to create the fork, force the other parent
                        return forcedOtherParentMatrix;
                    } else {
                        // After the fork, shun the creator that forked so that other creators dont use it and
                        // therefore create
                        // more split forks on other creators.
                        return shunnedOtherParentMatrix;
                    }
                });
    }
}
