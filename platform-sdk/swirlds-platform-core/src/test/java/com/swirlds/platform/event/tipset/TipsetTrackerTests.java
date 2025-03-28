// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.tipset;

import static com.swirlds.common.test.fixtures.crypto.CryptoRandomUtils.randomHash;
import static com.swirlds.platform.event.creation.tipset.Tipset.merge;
import static org.hiero.consensus.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.event.EventDescriptor;
import com.swirlds.base.time.Time;
import com.swirlds.platform.event.creation.tipset.Tipset;
import com.swirlds.platform.event.creation.tipset.TipsetTracker;
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
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("TipsetTracker Tests")
class TipsetTrackerTests {

    private static void assertTipsetEquality(
            @NonNull final Roster roster, @NonNull final Tipset expected, @NonNull final Tipset actual) {
        assertEquals(expected.size(), actual.size());

        for (final RosterEntry address : roster.rosterEntries()) {
            assertEquals(
                    expected.getTipGenerationForNode(NodeId.of(address.nodeId())),
                    actual.getTipGenerationForNode(NodeId.of(address.nodeId())));
        }
    }

    @ParameterizedTest
    @EnumSource(AncientMode.class)
    @DisplayName("Basic Behavior Test")
    void basicBehaviorTest(final AncientMode ancientMode) {
        final Random random = getRandomPrintSeed(0);

        final int nodeCount = random.nextInt(10, 20);
        final Roster roster =
                RandomRosterBuilder.create(random).withSize(nodeCount).build();

        final Map<NodeId, EventDescriptorWrapper> latestEvents = new HashMap<>();
        final Map<EventDescriptorWrapper, Tipset> expectedTipsets = new HashMap<>();

        final TipsetTracker tracker = new TipsetTracker(Time.getCurrent(), roster, ancientMode);

        long birthRound = ConsensusConstants.ROUND_FIRST;

        for (int eventIndex = 0; eventIndex < 1000; eventIndex++) {

            final NodeId creator = NodeId.of(
                    roster.rosterEntries().get(random.nextInt(nodeCount)).nodeId());
            final long generation;
            if (latestEvents.containsKey(creator)) {
                generation = latestEvents.get(creator).eventDescriptor().generation() + 1;
            } else {
                generation = 1;
            }

            birthRound += random.nextLong(0, 3) / 2;

            final EventDescriptorWrapper selfParent = latestEvents.get(creator);
            final EventDescriptorWrapper fingerprint = new EventDescriptorWrapper(
                    new EventDescriptor(randomHash(random).getBytes(), creator.id(), birthRound, generation));
            latestEvents.put(creator, fingerprint);

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
            final List<EventDescriptorWrapper> parentFingerprints = new ArrayList<>(desiredParents.size());
            if (selfParent != null) {
                parentFingerprints.add(selfParent);
            }
            for (final NodeId parent : desiredParents) {
                final EventDescriptorWrapper parentFingerprint = latestEvents.get(parent);
                if (parentFingerprint != null) {
                    parentFingerprints.add(parentFingerprint);
                }
            }

            final Tipset newTipset = tracker.addEvent(fingerprint, parentFingerprints);
            assertSame(newTipset, tracker.getTipset(fingerprint));

            // Now, reconstruct the tipset manually, and make sure it matches what we were expecting.
            final List<Tipset> parentTipsets = new ArrayList<>(parentFingerprints.size());
            for (final EventDescriptorWrapper parentFingerprint : parentFingerprints) {
                parentTipsets.add(expectedTipsets.get(parentFingerprint));
            }

            final Tipset expectedTipset;
            if (parentTipsets.isEmpty()) {
                expectedTipset = new Tipset(roster).advance(creator, generation);
            } else {
                expectedTipset = merge(parentTipsets).advance(creator, generation);
            }

            expectedTipsets.put(fingerprint, expectedTipset);
            assertTipsetEquality(roster, expectedTipset, newTipset);
        }

        // At the very end, we shouldn't see any modified tipsets
        for (final EventDescriptorWrapper fingerprint : expectedTipsets.keySet()) {
            assertTipsetEquality(roster, expectedTipsets.get(fingerprint), tracker.getTipset(fingerprint));
        }

        // Slowly advance the ancient threshold, we should see tipsets disappear as we go.
        long ancientThreshold = ancientMode == AncientMode.BIRTH_ROUND_THRESHOLD
                ? ConsensusConstants.ROUND_FIRST
                : EventConstants.FIRST_GENERATION;
        while (tracker.size() > 0) {
            ancientThreshold += random.nextInt(1, 5);
            final EventWindow eventWindow =
                    new EventWindow(1, ancientThreshold, 1 /* ignored in this context */, ancientMode);
            tracker.setEventWindow(eventWindow);
            assertEquals(eventWindow, tracker.getEventWindow());
            for (final EventDescriptorWrapper fingerprint : expectedTipsets.keySet()) {
                if (fingerprint.getAncientIndicator(ancientMode) < ancientThreshold) {
                    assertNull(tracker.getTipset(fingerprint));
                } else {
                    assertTipsetEquality(roster, expectedTipsets.get(fingerprint), tracker.getTipset(fingerprint));
                }
            }
        }
    }
}
