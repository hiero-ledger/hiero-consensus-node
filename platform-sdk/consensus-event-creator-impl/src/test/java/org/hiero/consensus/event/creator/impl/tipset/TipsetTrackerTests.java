// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.tipset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.hiero.consensus.event.creator.impl.tipset.Tipset.merge;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.base.time.Time;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.EventConstants;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.NonDeterministicGeneration;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.model.test.fixtures.hashgraph.EventWindowBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("TipsetTracker Tests")
class TipsetTrackerTests {

    private static void assertTipsetEquality(
            @NonNull final Roster roster, @NonNull final Tipset expected, @NonNull final Tipset actual) {
        assertThat(actual.size()).isEqualTo(expected.size());

        for (final RosterEntry address : roster.rosterEntries()) {
            assertThat(actual.getTipGenerationForNode(NodeId.of(address.nodeId())))
                    .withFailMessage(
                            "Expected tip generation for node %s to be %s but was %s",
                            address.nodeId(), expected, actual)
                    .isEqualTo(expected.getTipGenerationForNode(NodeId.of(address.nodeId())));
        }
    }

    /**
     * This test creates a bunch of events, adds them to the {@link TipsetTracker}, and verifies that the correct
     * tipsets for those added events are returned. Lastly, it advances the event window and verifies that tipsets for
     * ancient events are no longer returned by the tracker.
     *
     * @param ancientMode the ancient mode to use (i.e. generation or birth round)
     */
    @ParameterizedTest
    @EnumSource(AncientMode.class)
    @DisplayName("Basic Behavior Test")
    void basicBehaviorTest(final AncientMode ancientMode) {
        final Random random = getRandomPrintSeed();

        final int nodeCount = random.nextInt(10, 20);
        final Roster roster =
                RandomRosterBuilder.create(random).withSize(nodeCount).build();
        final NodeId selfId = NodeId.of(random.nextLong(nodeCount));

        final Map<NodeId, PlatformEvent> latestEvents = new HashMap<>();
        final Map<EventDescriptorWrapper, Tipset> expectedTipsets = new HashMap<>();

        final TipsetTracker tracker = new TipsetTracker(Time.getCurrent(), selfId, roster, ancientMode);

        long birthRound = ConsensusConstants.ROUND_FIRST;

        for (int eventIndex = 0; eventIndex < 1000; eventIndex++) {

            final NodeId creator = NodeId.of(
                    roster.rosterEntries().get(random.nextInt(nodeCount)).nodeId());

            // Assign an nGen value to every event, including self events. Tipsets should always
            // have -1 for the self tips even if the event has a different nGen value.
            final long nGen;
            if (latestEvents.containsKey(creator)) {
                nGen = latestEvents.get(creator).getNGen() + 1;
            } else {
                nGen = NonDeterministicGeneration.FIRST_GENERATION;
            }

            birthRound += random.nextLong(0, 3) / 2;

            // Select some nodes we'd like to be our parents.
            final Set<NodeId> desiredParents = new HashSet<>();
            final int maxParentCount = random.nextInt(nodeCount);
            for (int parentIndex = 0; parentIndex < maxParentCount; parentIndex++) {
                final NodeId parent = NodeId.of(
                        roster.rosterEntries().get(random.nextInt(nodeCount)).nodeId());

                // We are only trying to generate a random number of parents, the exact count is unimportant.
                // So it doesn't matter if the actual number of parents is less than the number we requested.
                if (parent.equals(creator)) {
                    continue;
                }
                desiredParents.add(parent);
            }

            // Select the actual parents.
            final List<PlatformEvent> otherParents = new ArrayList<>(desiredParents.size());
            for (final NodeId parent : desiredParents) {
                final PlatformEvent otherParent = latestEvents.get(parent);
                if (otherParent != null) {
                    otherParents.add(otherParent);
                }
            }

            final PlatformEvent event = new TestingEventBuilder(random)
                    .setCreatorId(creator)
                    .setSelfParent(latestEvents.get(creator))
                    .setOtherParents(otherParents)
                    .setBirthRound(birthRound)
                    .setNGen(nGen)
                    .build();
            latestEvents.put(creator, event);

            final Tipset newTipset;
            if (creator.equals(selfId)) {
                newTipset = tracker.addSelfEvent(event.getDescriptor(), event.getAllParents());
            } else {
                newTipset = tracker.addPeerEvent(event);
            }
            assertThat(newTipset.getTipGenerationForNode(selfId))
                    .withFailMessage(String.format(
                            "The generation should always be %s for the self node",
                            NonDeterministicGeneration.GENERATION_UNDEFINED))
                    .isEqualTo(NonDeterministicGeneration.GENERATION_UNDEFINED);
            assertSame(newTipset, tracker.getTipset(event.getDescriptor()));

            // Now, reconstruct the tipset manually, and make sure it matches what we were expecting.
            final List<Tipset> parentTipsets = new ArrayList<>();
            for (final PlatformEvent otherParent : otherParents) {
                parentTipsets.add(expectedTipsets.get(otherParent.getDescriptor()));
            }
            if (expectedTipsets.get(event.getSelfParent()) != null) {
                parentTipsets.add(expectedTipsets.get(event.getSelfParent()));
            }

            final Tipset expectedTipset;
            if (parentTipsets.isEmpty()) {
                expectedTipset = new Tipset(roster);
            } else {
                expectedTipset = merge(parentTipsets);
            }

            if (!creator.equals(selfId)) {
                expectedTipset.advance(creator, nGen);
            }

            expectedTipsets.put(event.getDescriptor(), expectedTipset);
            assertTipsetEquality(roster, expectedTipset, newTipset);
        }

        // At the very end, we shouldn't see any modified tipsets
        for (final EventDescriptorWrapper descriptor : expectedTipsets.keySet()) {
            final Tipset tipset = tracker.getTipset(descriptor);
            assertThat(tipset).isNotNull();
            assertTipsetEquality(roster, expectedTipsets.get(descriptor), tipset);
        }

        // Slowly advance the ancient threshold, we should see tipsets disappear as we go.
        long ancientThreshold = ancientMode == AncientMode.BIRTH_ROUND_THRESHOLD
                ? ConsensusConstants.ROUND_FIRST
                : EventConstants.FIRST_GENERATION;
        while (tracker.size() > 0) {
            ancientThreshold += random.nextInt(1, 5);
            final EventWindow eventWindow = EventWindowBuilder.builder()
                    .setAncientMode(ancientMode)
                    .setAncientThreshold(ancientThreshold)
                    .build();
            tracker.setEventWindow(eventWindow);
            assertEquals(eventWindow, tracker.getEventWindow());
            for (final EventDescriptorWrapper descriptor : expectedTipsets.keySet()) {
                if (descriptor.getAncientIndicator(ancientMode) < ancientThreshold) {
                    assertNull(tracker.getTipset(descriptor));
                } else {
                    assertTipsetEquality(roster, expectedTipsets.get(descriptor), tracker.getTipset(descriptor));
                }
            }
        }
    }
}
