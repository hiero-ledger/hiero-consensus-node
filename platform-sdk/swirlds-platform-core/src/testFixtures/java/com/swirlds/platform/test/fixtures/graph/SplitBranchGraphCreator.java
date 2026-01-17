// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.graph;

import static com.swirlds.platform.test.fixtures.graph.OtherParentMatrixFactory.createBalancedOtherParentMatrix;
import static com.swirlds.platform.test.fixtures.graph.OtherParentMatrixFactory.createForcedOtherParentMatrix;
import static com.swirlds.platform.test.fixtures.graph.OtherParentMatrixFactory.createShunnedNodeOtherParentAffinityMatrix;

import com.swirlds.platform.test.fixtures.event.emitter.StandardEventEmitter;
import java.util.List;
import java.util.Random;

/**
 * <p>This class manipulates the event generator to force the creation of a split branch, where each node has one branch
 * of a branch. Neither node knows that there is a branch until they sync.</p>
 *
 * <p>Graphs will have {@code numCommonEvents} events that do not branch, then the
 * split branch occurs. Events generated after the {@code numCommonEvents} will not select the creator
 * with the split branch as an other parent to prevent more split branches from occurring. The creator with the split branch may
 * select any other creator's event as an other parent.</p>
 */
public class SplitBranchGraphCreator {

    public static void createSplitBranchConditions(
            final StandardEventEmitter generator,
            final int creatorToBranch,
            final int otherParent,
            final int numCommonEvents,
            final int numNetworkNodes) {

        forceNextCreator(generator, creatorToBranch, numCommonEvents);
        forceNextOtherParent(generator, creatorToBranch, otherParent, numCommonEvents, numNetworkNodes);
    }

    private static void forceNextCreator(
            final StandardEventEmitter emitter, final int creatorToBranch, final int numCommonEvents) {
        final int numberOfSources = emitter.getGraphGenerator().getNumberOfSources();
        for (int i = 0; i < numberOfSources; i++) {
            final boolean sourceIsCreatorToBranch = i == creatorToBranch;
            emitter.getGraphGenerator().getSourceByIndex(i).setNewEventWeight((r, index, prev) -> {
                if (index < numCommonEvents) {
                    return 1.0;
                } else if (index == numCommonEvents && sourceIsCreatorToBranch) {
                    return 1.0;
                } else if (index > numCommonEvents) {
                    return 1.0;
                } else {
                    return 0.0;
                }
            });
        }
    }

    private static void forceNextOtherParent(
            final StandardEventEmitter emitter,
            final int creatorToShun,
            final int nextOtherParent,
            final int numCommonEvents,
            final int numNetworkNodes) {

        final int numSources = emitter.getGraphGenerator().getNumberOfSources();

        final List<List<Double>> balancedMatrix = createBalancedOtherParentMatrix(numNetworkNodes);

        final List<List<Double>> forcedOtherParentMatrix = createForcedOtherParentMatrix(numSources, nextOtherParent);

        final List<List<Double>> shunnedOtherParentMatrix =
                createShunnedNodeOtherParentAffinityMatrix(numSources, creatorToShun);

        emitter.getGraphGenerator()
                .setOtherParentAffinity((Random r, long eventIndex, List<List<Double>> previousValue) -> {
                    if (eventIndex < numCommonEvents) {
                        // Before the split branch, use the normal matrix
                        return balancedMatrix;
                    } else if (eventIndex == numCommonEvents) {
                        // At the event to create the branch, force the other parent
                        return forcedOtherParentMatrix;
                    } else {
                        // After the branch, shun the creator that branched so that other creators don't use it and
                        // therefore create
                        // more split branches on other creators.
                        return shunnedOtherParentMatrix;
                    }
                });
    }
}
