// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.tipset;

import static com.swirlds.common.utility.Threshold.SUPER_MAJORITY;
import static org.hiero.consensus.event.creator.impl.tipset.TipsetAdvancementWeight.ZERO_ADVANCEMENT_WEIGHT;
import static org.hiero.consensus.model.event.NonDeterministicGeneration.FIRST_GENERATION;
import static org.hiero.consensus.model.hashgraph.ConsensusConstants.ROUND_FIRST;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.base.time.Time;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.NonDeterministicGeneration;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.model.test.fixtures.hashgraph.EventWindowBuilder;
import org.hiero.junit.extensions.ParamName;
import org.hiero.junit.extensions.ParamSource;
import org.hiero.junit.extensions.ParameterCombinationExtension;
import org.hiero.junit.extensions.UseParameterSources;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

@DisplayName("TipsetWeightCalculator Tests")
class TipsetWeightCalculatorTests {

    /**
     * Create a new event descriptor with the given parameters
     * and {@link org.hiero.consensus.model.hashgraph.ConsensusConstants#ROUND_FIRST} for the birth round.
     * Given that the event has no parents, the generation assigned will be 0.
     *
     * @param random the random instance to use
     * @param creator the creator of the event
     * @param nGen    the non-deterministic generation of the event
     * @return the event
     */
    private static PlatformEvent newEvent(
            @NonNull final Random random, @NonNull final NodeId creator, final long nGen) {
        return new TestingEventBuilder(random)
                .setCreatorId(creator)
                .setNGen(nGen)
                .setBirthRound(ROUND_FIRST)
                .build();
    }

    /**
     * Create a new event descriptor with the given parameters
     * and {@link org.hiero.consensus.model.hashgraph.ConsensusConstants#ROUND_FIRST} for the birth round.
     * The generation given to the events will be max(selfparent#generation, otherParents#generation) + 1.
     *
     * @param random the random instance to use
     * @param nGen    the non-deterministic generation of the event
     * @param selfParent the self parent
     * @param otherParents all the other parents for the new event
     * @return the event
     */
    private static PlatformEvent newEvent(
            @NonNull final Random random,
            final long nGen,
            @NonNull final PlatformEvent selfParent,
            @NonNull final List<PlatformEvent> otherParents) {
        return newEvent(random, nGen, selfParent, otherParents, ROUND_FIRST);
    }

    /**
     * Create a new event descriptor with the given parameters and given birth round.
     * The generation given to the events will be max(selfparent#generation, otherParents#generation) + 1.
     *
     * @param random the random instance to use
     * @param nGen    the non-deterministic generation of the event
     * @param selfParent the self-parent
     * @param otherParents all the other parents for the new event
     * @param birthRound the birthRound to assign to the event
     * @return the event
     */
    private static PlatformEvent newEvent(
            @NonNull final Random random,
            final long nGen,
            @NonNull final PlatformEvent selfParent,
            @NonNull final List<PlatformEvent> otherParents,
            final long birthRound) {
        return new TestingEventBuilder(random)
                .setCreatorId(selfParent.getCreatorId())
                .setNGen(nGen)
                .setSelfParent(selfParent)
                .setOtherParents(otherParents)
                .setBirthRound(birthRound)
                .build();
    }

    /**
     *
     * @param random {@link org.hiero.base.utility.test.fixtures.RandomUtils#getRandomPrintSeed()}
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(
                param = "random",
                fullyQualifiedClass = "org.hiero.base.utility.test.fixtures.RandomUtils",
                method = "getRandomPrintSeed")
    })
    @DisplayName("Basic Behavior Test")
    public void basicBehaviorTest(@ParamName("random") final Random random) {
        final int nodeCount = 5;

        final Map<NodeId, PlatformEvent> latestEvents = new HashMap<>();

        final Roster roster =
                RandomRosterBuilder.create(random).withSize(nodeCount).build();

        final Map<NodeId, Long> weightMap = new HashMap<>();
        long totalWeight = 0;
        for (final RosterEntry address : roster.rosterEntries()) {
            weightMap.put(NodeId.of(address.nodeId()), address.weight());
            totalWeight += address.weight();
        }

        final NodeId selfId =
                NodeId.of(roster.rosterEntries().get(random.nextInt(nodeCount)).nodeId());

        final Configuration configuration =
                ConfigurationBuilder.create().autoDiscoverExtensions().build();
        final Time time = Time.getCurrent();

        final TipsetTracker tipsetTracker = new TipsetTracker(Time.getCurrent(), selfId, roster);
        final ChildlessEventTracker childlessEventTracker = new ChildlessEventTracker();
        final TipsetWeightCalculator calculator =
                new TipsetWeightCalculator(configuration, time, roster, selfId, tipsetTracker, childlessEventTracker);

        List<PlatformEvent> previousParents = List.of();
        TipsetAdvancementWeight runningAdvancementScore = ZERO_ADVANCEMENT_WEIGHT;
        Tipset previousSnapshot = calculator.getSnapshot();

        for (int eventIndex = 0; eventIndex < 1000; eventIndex++) {
            final NodeId creator = NodeId.of(
                    roster.rosterEntries().get(random.nextInt(nodeCount)).nodeId());
            final long nGen;
            if (latestEvents.containsKey(creator)) {
                nGen = latestEvents.get(creator).getNGen() + 1;
            } else {
                nGen = FIRST_GENERATION;
            }

            // Select some nodes we'd like to be our parents.
            final Set<NodeId> desiredOtherParents = new HashSet<>();
            final int maxParentCount = random.nextInt(nodeCount);
            for (int parentIndex = 0; parentIndex < maxParentCount; parentIndex++) {
                final NodeId parent = NodeId.of(
                        roster.rosterEntries().get(random.nextInt(nodeCount)).nodeId());

                // We are only trying to generate a random number of parents, the exact count is unimportant.
                // So it doesn't matter if the actual number of parents is less than the number we requested.
                if (parent.equals(creator)) {
                    continue;
                }
                desiredOtherParents.add(parent);
            }

            // Select the actual parents.
            final PlatformEvent selfParent = latestEvents.get(creator);

            final List<PlatformEvent> otherParents = new ArrayList<>(desiredOtherParents.size());
            for (final NodeId desiredOtherParent : desiredOtherParents) {
                final PlatformEvent otherParent = latestEvents.get(desiredOtherParent);
                if (otherParent != null) {
                    otherParents.add(otherParent);
                }
            }
            final PlatformEvent event = new TestingEventBuilder(random)
                    .setCreatorId(creator)
                    .setNGen(nGen)
                    .setSelfParent(selfParent)
                    .setOtherParents(otherParents)
                    .build();
            latestEvents.put(creator, event);

            if (creator.equals(selfId)) {
                tipsetTracker.addSelfEvent(event.getDescriptor(), event.getAllParents());
            } else {
                tipsetTracker.addPeerEvent(event);
            }

            if (creator != selfId) {
                // The following validation only needs to happen for events created by self

                // Only do previous parent validation if we create two or more events in a row.
                previousParents = List.of();

                continue;
            }

            // Manually calculate the advancement score.
            final List<Tipset> parentTipsets =
                    new ArrayList<>(event.getAllParents().size());
            for (final EventDescriptorWrapper parent : event.getAllParents()) {
                parentTipsets.add(tipsetTracker.getTipset(parent));
            }

            final Tipset newTipset = new Tipset(roster).merge(parentTipsets);

            final TipsetAdvancementWeight expectedAdvancementScoreChange =
                    previousSnapshot.getTipAdvancementWeight(selfId, newTipset).minus(runningAdvancementScore);

            // For events created by "this" node, check that the calculator is updated correctly.
            final TipsetAdvancementWeight advancementScoreChange =
                    calculator.addEventAndGetAdvancementWeight(event.getDescriptor());

            assertEquals(expectedAdvancementScoreChange, advancementScoreChange);

            // Special case: if we create more than one event in a row and our current parents are a
            // subset of the previous parents, then we should expect an advancement score of zero.
            boolean subsetOfPreviousParents = true;
            for (final PlatformEvent otherParent : otherParents) {
                if (!previousParents.contains(otherParent)) {
                    subsetOfPreviousParents = false;
                    break;
                }
            }
            if (subsetOfPreviousParents) {
                assertEquals(ZERO_ADVANCEMENT_WEIGHT, advancementScoreChange);
            }
            previousParents = otherParents;

            // Validate that the snapshot advances correctly.
            runningAdvancementScore = runningAdvancementScore.plus(advancementScoreChange);
            if (SUPER_MAJORITY.isSatisfiedBy(
                    runningAdvancementScore.advancementWeight() + weightMap.get(selfId), totalWeight)) {
                // The snapshot should have been updated.
                assertNotSame(previousSnapshot, calculator.getSnapshot());
                previousSnapshot = calculator.getSnapshot();
                runningAdvancementScore = ZERO_ADVANCEMENT_WEIGHT;
            } else {
                // The snapshot should have not been updated.
                assertSame(previousSnapshot, calculator.getSnapshot());
            }
        }
    }

    /**
     *
     * @param random {@link org.hiero.base.utility.test.fixtures.RandomUtils#getRandomPrintSeed()}
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(
                param = "random",
                fullyQualifiedClass = "org.hiero.base.utility.test.fixtures.RandomUtils",
                method = "getRandomPrintSeed")
    })
    @DisplayName("Selfish Node Test")
    public void selfishNodeTest(@ParamName("random") final Random random) {
        final int nodeCount = 4;
        final Roster roster = RandomRosterBuilder.create(random)
                .withSize(nodeCount)
                .withWeightGenerator(WeightGenerators.BALANCED)
                .build();

        // In this test, we simulate from the perspective of node A. All nodes have 1 weight.
        final NodeId nodeA = NodeId.of(roster.rosterEntries().get(0).nodeId());
        final NodeId nodeB = NodeId.of(roster.rosterEntries().get(1).nodeId());
        final NodeId nodeC = NodeId.of(roster.rosterEntries().get(2).nodeId());
        final NodeId nodeD = NodeId.of(roster.rosterEntries().get(3).nodeId());

        final Configuration configuration =
                ConfigurationBuilder.create().autoDiscoverExtensions().build();
        final Time time = Time.getCurrent();

        final TipsetTracker tipsetTracker = new TipsetTracker(Time.getCurrent(), nodeA, roster);
        final ChildlessEventTracker childlessEventTracker = new ChildlessEventTracker();
        final TipsetWeightCalculator calculator =
                new TipsetWeightCalculator(configuration, time, roster, nodeA, tipsetTracker, childlessEventTracker);

        final Tipset snapshot1 = calculator.getSnapshot();

        // Each node creates an event.
        final PlatformEvent eventA1 = newEvent(random, nodeA, 1);
        tipsetTracker.addSelfEvent(eventA1.getDescriptor(), eventA1.getAllParents());
        final PlatformEvent eventB1 = newEvent(random, nodeB, 1);
        tipsetTracker.addPeerEvent(eventB1);
        childlessEventTracker.addEvent(eventB1);
        final PlatformEvent eventC1 = newEvent(random, nodeC, 1);
        tipsetTracker.addPeerEvent(eventC1);
        childlessEventTracker.addEvent(eventC1);
        final PlatformEvent eventD1 = newEvent(random, nodeD, 1);
        tipsetTracker.addPeerEvent(eventD1);
        childlessEventTracker.addEvent(eventD1);

        assertEquals(ZERO_ADVANCEMENT_WEIGHT, calculator.getTheoreticalAdvancementWeight(List.of()));
        assertEquals(ZERO_ADVANCEMENT_WEIGHT, calculator.addEventAndGetAdvancementWeight(eventA1.getDescriptor()));
        assertSame(snapshot1, calculator.getSnapshot());

        // Each node creates another event. All nodes use all available other parents except the event from D.
        final PlatformEvent eventA2 = newEvent(random, 2, eventA1, List.of(eventB1, eventC1));
        tipsetTracker.addSelfEvent(eventA2.getDescriptor(), eventA2.getAllParents());
        final PlatformEvent eventB2 = newEvent(random, 2, eventB1, List.of(eventA1, eventC1));
        tipsetTracker.addPeerEvent(eventB2);
        childlessEventTracker.addEvent(eventB2);
        final PlatformEvent eventC2 = newEvent(random, 2, eventC1, List.of(eventA1, eventB1));
        tipsetTracker.addPeerEvent(eventC2);
        childlessEventTracker.addEvent(eventC2);
        final PlatformEvent eventD2 = newEvent(random, 2, eventD1, List.of(eventA1, eventB1, eventC1));
        tipsetTracker.addPeerEvent(eventD2);
        childlessEventTracker.addEvent(eventD2);

        // Check the advancement weight for A2
        assertEquals(
                TipsetAdvancementWeight.of(2, 0), calculator.getTheoreticalAdvancementWeight(eventA2.getAllParents()));
        assertEquals(
                TipsetAdvancementWeight.of(2, 0), calculator.addEventAndGetAdvancementWeight(eventA2.getDescriptor()));

        // This should have been enough to advance the snapshot window by 1.
        final Tipset snapshot2 = calculator.getSnapshot();
        assertNotSame(snapshot1, snapshot2);

        // D should have a selfishness score of 1, all others a score of 0.
        assertEquals(0, calculator.getSelfishnessScoreForNode(nodeA));
        assertEquals(0, calculator.getSelfishnessScoreForNode(nodeB));
        assertEquals(0, calculator.getSelfishnessScoreForNode(nodeC));
        assertEquals(1, calculator.getSelfishnessScoreForNode(nodeD));
        assertEquals(1, calculator.getMaxSelfishnessScore());

        // Create another batch of events where D is bullied.
        final PlatformEvent eventA3 = newEvent(random, 3, eventA2, List.of(eventB2, eventC2));
        tipsetTracker.addSelfEvent(eventA3.getDescriptor(), eventA3.getAllParents());
        final PlatformEvent eventB3 = newEvent(random, 3, eventB2, List.of(eventA2, eventC2));
        tipsetTracker.addPeerEvent(eventB3);
        childlessEventTracker.addEvent(eventB3);
        final PlatformEvent eventC3 = newEvent(random, 3, eventC2, List.of(eventA2, eventB2));
        tipsetTracker.addPeerEvent(eventC3);
        childlessEventTracker.addEvent(eventC3);
        final PlatformEvent eventD3 = newEvent(random, 3, eventD2, List.of(eventA2, eventB2, eventC2));
        tipsetTracker.addPeerEvent(eventD3);
        childlessEventTracker.addEvent(eventD3);

        assertEquals(
                TipsetAdvancementWeight.of(2, 0), calculator.getTheoreticalAdvancementWeight(eventA3.getAllParents()));
        assertEquals(
                TipsetAdvancementWeight.of(2, 0), calculator.addEventAndGetAdvancementWeight(eventA3.getDescriptor()));

        final Tipset snapshot3 = calculator.getSnapshot();
        assertNotSame(snapshot2, snapshot3);

        // D should have a selfishness score of 2, all others a score of 0.
        assertEquals(0, calculator.getSelfishnessScoreForNode(nodeA));
        assertEquals(0, calculator.getSelfishnessScoreForNode(nodeB));
        assertEquals(0, calculator.getSelfishnessScoreForNode(nodeC));
        assertEquals(2, calculator.getSelfishnessScoreForNode(nodeD));
        assertEquals(2, calculator.getMaxSelfishnessScore());

        // Create a batch of events that don't ignore D. Let's all ignore C, because C is a jerk.
        final PlatformEvent eventA4 = newEvent(random, 4, eventA3, List.of(eventB3, eventD3));
        tipsetTracker.addSelfEvent(eventA4.getDescriptor(), eventA4.getAllParents());
        final PlatformEvent eventB4 = newEvent(random, 4, eventB3, List.of(eventA3, eventD3));
        tipsetTracker.addPeerEvent(eventB4);
        childlessEventTracker.addEvent(eventB4);
        final PlatformEvent eventC4 = newEvent(random, 4, eventC3, List.of(eventA3, eventB3, eventD3));
        tipsetTracker.addPeerEvent(eventC4);
        childlessEventTracker.addEvent(eventC4);
        final PlatformEvent eventD4 = newEvent(random, 4, eventD3, List.of(eventA3, eventB3));
        tipsetTracker.addPeerEvent(eventD4);
        childlessEventTracker.addEvent(eventD4);

        assertEquals(
                TipsetAdvancementWeight.of(2, 0), calculator.getTheoreticalAdvancementWeight(eventA4.getAllParents()));
        assertEquals(
                TipsetAdvancementWeight.of(2, 0), calculator.addEventAndGetAdvancementWeight(eventA4.getDescriptor()));

        final Tipset snapshot4 = calculator.getSnapshot();
        assertNotSame(snapshot3, snapshot4);

        // Now, all nodes should have a selfishness score of 0 except for C, which should have a score of 1.
        assertEquals(0, calculator.getSelfishnessScoreForNode(nodeA));
        assertEquals(0, calculator.getSelfishnessScoreForNode(nodeB));
        assertEquals(1, calculator.getSelfishnessScoreForNode(nodeC));
        assertEquals(0, calculator.getSelfishnessScoreForNode(nodeD));
        assertEquals(1, calculator.getMaxSelfishnessScore());

        // Stop ignoring C. D stops creating events.
        final PlatformEvent eventA5 = newEvent(random, 5, eventA4, List.of(eventB4, eventC4, eventD4));
        tipsetTracker.addSelfEvent(eventA5.getDescriptor(), eventA5.getAllParents());
        final PlatformEvent eventB5 = newEvent(random, 5, eventB4, List.of(eventA4, eventC4, eventD4));
        tipsetTracker.addPeerEvent(eventB5);
        childlessEventTracker.addEvent(eventB5);
        final PlatformEvent eventC5 = newEvent(random, 5, eventC4, List.of(eventA4, eventB4, eventD4));
        tipsetTracker.addPeerEvent(eventC5);
        childlessEventTracker.addEvent(eventC5);

        assertEquals(
                TipsetAdvancementWeight.of(3, 0), calculator.getTheoreticalAdvancementWeight(eventA5.getAllParents()));
        assertEquals(
                TipsetAdvancementWeight.of(3, 0), calculator.addEventAndGetAdvancementWeight(eventA5.getDescriptor()));

        final Tipset snapshot5 = calculator.getSnapshot();
        assertNotSame(snapshot4, snapshot5);

        assertEquals(0, calculator.getSelfishnessScoreForNode(nodeA));
        assertEquals(0, calculator.getSelfishnessScoreForNode(nodeB));
        assertEquals(0, calculator.getSelfishnessScoreForNode(nodeC));
        assertEquals(0, calculator.getSelfishnessScoreForNode(nodeD));
        assertEquals(0, calculator.getMaxSelfishnessScore());

        // D still is not creating events. Since there is no legal event from D to use as a parent, this doesn't
        // count as being selfish.
        final PlatformEvent eventA6 = newEvent(random, 6, eventA5, List.of(eventB5, eventC5));
        tipsetTracker.addSelfEvent(eventA6.getDescriptor(), eventA6.getAllParents());
        final PlatformEvent eventB6 = newEvent(random, 6, eventB5, List.of(eventA5, eventC5));
        tipsetTracker.addPeerEvent(eventB6);
        childlessEventTracker.addEvent(eventB6);
        final PlatformEvent eventC6 = newEvent(random, 6, eventC5, List.of(eventA5, eventB5));
        tipsetTracker.addPeerEvent(eventC6);
        childlessEventTracker.addEvent(eventC6);

        assertEquals(
                TipsetAdvancementWeight.of(2, 0), calculator.getTheoreticalAdvancementWeight(eventA6.getAllParents()));
        assertEquals(
                TipsetAdvancementWeight.of(2, 0), calculator.addEventAndGetAdvancementWeight(eventA6.getDescriptor()));

        final Tipset snapshot6 = calculator.getSnapshot();
        assertNotSame(snapshot5, snapshot6);

        assertEquals(0, calculator.getSelfishnessScoreForNode(nodeA));
        assertEquals(0, calculator.getSelfishnessScoreForNode(nodeB));
        assertEquals(0, calculator.getSelfishnessScoreForNode(nodeC));
        assertEquals(0, calculator.getSelfishnessScoreForNode(nodeD));
        assertEquals(0, calculator.getMaxSelfishnessScore());

        // Rinse and repeat.
        final PlatformEvent eventA7 = newEvent(random, 7, eventA6, List.of(eventB6, eventC6));
        tipsetTracker.addSelfEvent(eventA7.getDescriptor(), eventA7.getAllParents());
        final PlatformEvent eventB7 = newEvent(random, 7, eventB6, List.of(eventA6, eventC6));
        tipsetTracker.addPeerEvent(eventB7);
        childlessEventTracker.addEvent(eventB7);
        final PlatformEvent eventC7 = newEvent(random, 7, eventC6, List.of(eventA6, eventB6));
        tipsetTracker.addPeerEvent(eventC7);
        childlessEventTracker.addEvent(eventC7);

        assertEquals(
                TipsetAdvancementWeight.of(2, 0), calculator.getTheoreticalAdvancementWeight(eventA7.getAllParents()));
        assertEquals(
                TipsetAdvancementWeight.of(2, 0), calculator.addEventAndGetAdvancementWeight(eventA7.getDescriptor()));

        final Tipset snapshot7 = calculator.getSnapshot();
        assertNotSame(snapshot6, snapshot7);

        assertEquals(0, calculator.getSelfishnessScoreForNode(nodeA));
        assertEquals(0, calculator.getSelfishnessScoreForNode(nodeB));
        assertEquals(0, calculator.getSelfishnessScoreForNode(nodeC));
        assertEquals(0, calculator.getSelfishnessScoreForNode(nodeD));
        assertEquals(0, calculator.getMaxSelfishnessScore());
    }

    /**
     *
     * @param random {@link org.hiero.base.utility.test.fixtures.RandomUtils#getRandomPrintSeed()}
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(
                param = "random",
                fullyQualifiedClass = "org.hiero.base.utility.test.fixtures.RandomUtils",
                method = "getRandomPrintSeed")
    })
    @DisplayName("Zero Stake Node Test")
    public void zeroWeightNodeTest(@ParamName("random") final Random random) {
        final int nodeCount = 4;

        Roster roster = RandomRosterBuilder.create(random)
                .withSize(nodeCount)
                .withWeightGenerator(WeightGenerators.BALANCED)
                .build();
        // In this test, we simulate from the perspective of node A.
        // All nodes have 1 weight except for D, which has 0 weight.
        final NodeId nodeA = NodeId.of(roster.rosterEntries().get(0).nodeId());
        final NodeId nodeB = NodeId.of(roster.rosterEntries().get(1).nodeId());
        final NodeId nodeC = NodeId.of(roster.rosterEntries().get(2).nodeId());
        final NodeId nodeD = NodeId.of(roster.rosterEntries().get(3).nodeId());

        roster = Roster.newBuilder()
                .rosterEntries(roster.rosterEntries().stream()
                        .map(entry -> {
                            if (entry.nodeId() == nodeD.id()) {
                                return entry.copyBuilder().weight(0).build();
                            } else {
                                return entry;
                            }
                        })
                        .toList())
                .build();

        final Configuration configuration =
                ConfigurationBuilder.create().autoDiscoverExtensions().build();
        final Time time = Time.getCurrent();

        final TipsetTracker builder = new TipsetTracker(Time.getCurrent(), nodeA, roster);
        final ChildlessEventTracker childlessEventTracker = new ChildlessEventTracker();
        final TipsetWeightCalculator calculator =
                new TipsetWeightCalculator(configuration, time, roster, nodeA, builder, childlessEventTracker);

        final Tipset snapshot1 = calculator.getSnapshot();

        // Each node creates an event.
        final PlatformEvent eventA1 = newEvent(random, nodeA, 1);
        builder.addSelfEvent(eventA1.getDescriptor(), eventA1.getAllParents());
        final PlatformEvent eventB1 = newEvent(random, nodeB, 1);
        builder.addPeerEvent(eventB1);
        final PlatformEvent eventC1 = newEvent(random, nodeC, 1);
        builder.addPeerEvent(eventC1);
        final PlatformEvent eventD1 = newEvent(random, nodeD, 1);
        builder.addPeerEvent(eventD1);

        assertEquals(ZERO_ADVANCEMENT_WEIGHT, calculator.getTheoreticalAdvancementWeight(eventA1.getAllParents()));
        assertEquals(ZERO_ADVANCEMENT_WEIGHT, calculator.addEventAndGetAdvancementWeight(eventA1.getDescriptor()));
        assertSame(snapshot1, calculator.getSnapshot());

        // Create a node "on top of" B1.
        final PlatformEvent eventA2 = newEvent(random, 2, eventA1, List.of(eventB1));
        builder.addPeerEvent(eventA2);
        final TipsetAdvancementWeight advancement1 =
                calculator.addEventAndGetAdvancementWeight(eventA2.getDescriptor());
        assertEquals(TipsetAdvancementWeight.of(1, 0), advancement1);

        // Snapshot should not have advanced.
        assertSame(snapshot1, calculator.getSnapshot());

        // If we get 1 more advancement point then the snapshot will advance. But building
        // on top of a zero stake node will not contribute to this and the snapshot will not
        // advance. Build on top of node D.
        final PlatformEvent eventA3 = newEvent(random, 3, eventA2, List.of(eventD1));
        builder.addSelfEvent(eventA3.getDescriptor(), eventA3.getAllParents());
        final TipsetAdvancementWeight advancement2 =
                calculator.addEventAndGetAdvancementWeight(eventA3.getDescriptor());
        assertEquals(TipsetAdvancementWeight.of(0, 1), advancement2);

        // Snapshot should not have advanced.
        assertSame(snapshot1, calculator.getSnapshot());

        // Now, build on top of C. This should push us into the next snapshot.
        final PlatformEvent eventA4 = newEvent(random, 4, eventA3, List.of(eventC1));
        builder.addSelfEvent(eventA4.getDescriptor(), eventA4.getAllParents());
        final TipsetAdvancementWeight advancement3 =
                calculator.addEventAndGetAdvancementWeight(eventA4.getDescriptor());
        assertEquals(TipsetAdvancementWeight.of(1, 0), advancement3);

        final Tipset snapshot2 = calculator.getSnapshot();
        assertNotEquals(snapshot1, snapshot2);
        assertEquals(snapshot2, builder.getTipset(eventA4.getDescriptor()));
    }

    /**
     *
     * @param random {@link org.hiero.base.utility.test.fixtures.RandomUtils#getRandomPrintSeed()}
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(
                param = "random",
                fullyQualifiedClass = "org.hiero.base.utility.test.fixtures.RandomUtils",
                method = "getRandomPrintSeed")
    })
    @DisplayName("Ancient Parent Test")
    public void ancientParentTest(@ParamName("random") final Random random) {
        final int nodeCount = 4;

        final Roster roster = RandomRosterBuilder.create(random)
                .withSize(nodeCount)
                .withWeightGenerator(WeightGenerators.BALANCED)
                .build();

        final NodeId nodeA = NodeId.of(roster.rosterEntries().get(0).nodeId());
        final NodeId nodeB = NodeId.of(roster.rosterEntries().get(1).nodeId());
        final NodeId nodeC = NodeId.of(roster.rosterEntries().get(2).nodeId());
        final NodeId nodeD = NodeId.of(roster.rosterEntries().get(3).nodeId());

        final Configuration configuration =
                ConfigurationBuilder.create().autoDiscoverExtensions().build();
        final Time time = Time.getCurrent();

        final TipsetTracker tipsetTracker = new TipsetTracker(Time.getCurrent(), nodeA, roster);
        final ChildlessEventTracker childlessEventTracker = new ChildlessEventTracker();
        final TipsetWeightCalculator tipsetWeightCalculator =
                new TipsetWeightCalculator(configuration, time, roster, nodeA, tipsetTracker, childlessEventTracker);

        // Create generation 0 / birth round 1 events
        final PlatformEvent a0 = newEvent(random, nodeA, NonDeterministicGeneration.GENERATION_UNDEFINED);
        final PlatformEvent b0 = newEvent(random, nodeB, NonDeterministicGeneration.GENERATION_UNDEFINED);
        final PlatformEvent c0 = newEvent(random, nodeC, NonDeterministicGeneration.GENERATION_UNDEFINED);
        final PlatformEvent d0 = newEvent(random, nodeD, NonDeterministicGeneration.GENERATION_UNDEFINED);

        tipsetTracker.addSelfEvent(a0.getDescriptor(), a0.getAllParents());
        tipsetTracker.addPeerEvent(b0);
        tipsetTracker.addPeerEvent(c0);
        tipsetTracker.addPeerEvent(d0);

        final long newEventBirthRound = 2L;
        // Create some events (birth round 2). Node A does not create an event yet.
        final PlatformEvent b1 = newEvent(
                random, NonDeterministicGeneration.FIRST_GENERATION, b0, List.of(a0, c0, d0), newEventBirthRound);
        final PlatformEvent c1 = newEvent(
                random, NonDeterministicGeneration.FIRST_GENERATION, c0, List.of(a0, b0, d0), newEventBirthRound);
        final PlatformEvent d1 = newEvent(
                random, NonDeterministicGeneration.FIRST_GENERATION, d0, List.of(a0, b0, c0), newEventBirthRound);
        tipsetTracker.addPeerEvent(b1);
        tipsetTracker.addPeerEvent(c1);
        tipsetTracker.addPeerEvent(d1);

        // Mark birth round 1 as ancient:
        final EventWindow eventWindow = EventWindowBuilder.builder()
                .setAncientThreshold(newEventBirthRound)
                .build();
        tipsetTracker.setEventWindow(eventWindow);
        childlessEventTracker.pruneOldEvents(eventWindow);

        // We shouldn't be able to find tipsets for ancient events.
        assertNull(tipsetTracker.getTipset(a0.getDescriptor()));
        assertNull(tipsetTracker.getTipset(b0.getDescriptor()));
        assertNull(tipsetTracker.getTipset(c0.getDescriptor()));
        assertNull(tipsetTracker.getTipset(d0.getDescriptor()));

        // Including generation 0 / birth round 1 events (which are ancient now) as parents shouldn't cause us to throw.
        // (Angry log messages are ok).
        assertDoesNotThrow(() -> {
            final PlatformEvent a1 = newEvent(
                    random, NonDeterministicGeneration.FIRST_GENERATION, a0, List.of(b0, c0, d0), newEventBirthRound);

            tipsetWeightCalculator.getTheoreticalAdvancementWeight(a1.getAllParents());
            tipsetTracker.addSelfEvent(a1.getDescriptor(), a1.getAllParents());
            tipsetWeightCalculator.addEventAndGetAdvancementWeight(a1.getDescriptor());
        });
    }
}
