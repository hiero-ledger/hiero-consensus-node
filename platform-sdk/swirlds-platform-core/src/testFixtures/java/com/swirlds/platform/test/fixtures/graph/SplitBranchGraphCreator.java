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
            final int creatorToBranch,
            final int forcedOtherParent,
            final int numCommonEvents,
            final int numNetworkNodes) {
        emitter.setOtherParentAffinity((random, creatorIndex, eventIndex, previousEventTimestamp) -> {
            if (eventIndex == numCommonEvents) {
                if (creatorIndex == creatorToBranch) {
                    if (random.nextBoolean()) {
                        // Before the split branch, use the normal matrix
                        return createBalancedOtherParentMatrix(numNetworkNodes);
                    } else {
                        // At the event to create the branch, force the other parent
                        return createForcedOtherParentMatrix(numNetworkNodes, forcedOtherParent);
                    }
                } else {
                    // Other creators have no influence on the branch
                    return createBalancedOtherParentMatrix(numNetworkNodes);
                }
            } else if (eventIndex > numCommonEvents) {
                // After the branch, shun the creator that branched so that other creators dont use it and
                // more split branches on other creators.
                return createShunnedNodeOtherParentAffinityMatrix(numNetworkNodes, creatorToBranch);
            } else {
                return createBalancedOtherParentMatrix(numNetworkNodes);
            }
        });
    }

    /**
     * Creates a graph with split branches and returns the two events that constitute the branches
     *
     * @param random            a random number generator
     * @param generator         the event generator
     * @param creatorToBranch   the creator that will create the branch
     * @param otherParent       the other parent for the second branch
     * @param numCommonEvents   the number of common events before the branch
     * @param numAfterBranchEvents the number of events to generate after the branch
     * @param numNetworkNodes   the total number of network nodes
     * @return the two events that constitute the branches
     */
    public static List<Integer> createSplitBranchGraph(
            final Random random,
            final StandardEventEmitter generator,
            final int creatorToBranch,
            final int otherParent,
            final int numCommonEvents,
            final int numAfterBranchEvents,
            final int numNetworkNodes) {
        createSplitBranchConditions(generator, creatorToBranch, otherParent, numCommonEvents, numNetworkNodes);

        generator.emitEvents(numCommonEvents);

        final int firstBranchEventIndex = generator.emitEvents(1).getFirst();
        final int secondBranchEventIndex = generator.emitEvents(1).getFirst();

        generator.emitEvents(numAfterBranchEvents);

        return List.of(firstBranchEventIndex, secondBranchEventIndex);
    }
}