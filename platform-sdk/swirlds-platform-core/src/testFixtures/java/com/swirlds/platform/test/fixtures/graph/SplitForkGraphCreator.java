// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.graph;

import static com.swirlds.platform.test.fixtures.graph.OtherParentMatrixFactory.createBalancedOtherParentMatrix;
import static com.swirlds.platform.test.fixtures.graph.OtherParentMatrixFactory.createForcedOtherParentMatrix;
import static com.swirlds.platform.test.fixtures.graph.OtherParentMatrixFactory.createShunnedNodeOtherParentAffinityMatrix;

import com.swirlds.platform.test.fixtures.event.emitter.StandardEventEmitter;
import java.util.List;
import java.util.Random;

/**
 * <p>This class manipulates the event generator to force the creation of a split fork, where each node has one branch
 * of a fork. Neither node knows that there is a fork until they sync.</p>
 *
 * <p>Graphs will have {@code numCommonEvents} events that do not fork, then the
 * split fork occurs. Events generated after the {@code numCommonEvents} will not select the creator
 * with the split fork as an other parent to prevent more split forks from occurring. The creator with the split fork may
 * select any other creator's event as an other parent.</p>
 */
public class SplitForkGraphCreator {

    public static void createSplitForkConditions(
            final StandardEventEmitter generator,
            final int creatorToFork,
            final int otherParent,
            final int numCommonEvents,
            final int numNetworkNodes) {

        forceNextCreator(generator, creatorToFork, numCommonEvents);
        forceNextOtherParent(generator, creatorToFork, otherParent, numCommonEvents, numNetworkNodes);
    }

    private static void forceNextCreator(
            final StandardEventEmitter emitter, final int creatorToFork, final int numCommonEvents) {
        final int numberOfSources = emitter.getGraphGenerator().getNumberOfSources();
        for (int i = 0; i < numberOfSources; i++) {
            final boolean sourceIsCreatorToFork = i == creatorToFork;
            emitter.getGraphGenerator().getSourceByIndex(i).setNewEventWeight((r, index, prev) -> {
                if (index < numCommonEvents) {
                    return 1.0;
                } else if (index == numCommonEvents && sourceIsCreatorToFork) {
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
                        // Before the split fork, use the normal matrix
                        return balancedMatrix;
                    } else if (eventIndex == numCommonEvents) {
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
