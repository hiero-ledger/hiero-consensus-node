// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import static org.junit.jupiter.api.Assertions.*;

import com.hedera.hapi.node.state.roster.Roster;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.test.fixtures.RandomRosterBuilder;
import org.hiero.consensus.test.fixtures.Randotron;
import org.hiero.consensus.test.fixtures.WeightGenerators;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RosterLookupTest {
    public static final int NEGATIVE_INDEX = -1;
    public static final int OUT_OF_RANGE_INDEX = 123456;
    public static final NodeId NON_EXISTENT_NODE_ID = NodeId.of(654321);

    private static Roster simpleRoster;
    private static RosterLookup simpleLookup;
    private static Roster singleNodeRoster;
    private static RosterLookup singleNodeLookup;
    private static Roster supermajorityNodeRoster;
    private static RosterLookup supermajorityNodeLookup;

    @BeforeAll
    static void setup() {
        final Randotron r = Randotron.create();
        simpleRoster = RandomRosterBuilder.create(r)
                .withSize(4)
                .withRealKeysEnabled(false)
                .withWeightGenerator(WeightGenerators.INCREMENTING)
                .build();
        simpleLookup = new RosterLookup(simpleRoster);
        singleNodeRoster = RandomRosterBuilder.create(r)
                .withSize(1)
                .withRealKeysEnabled(false)
                .withWeightGenerator(WeightGenerators.BALANCED_1000_PER_NODE)
                .build();
        singleNodeLookup = new RosterLookup(singleNodeRoster);
        supermajorityNodeRoster = RandomRosterBuilder.create(r)
                .withSize(3)
                .withRealKeysEnabled(false)
                .withWeightGenerator(WeightGenerators.SINGLE_NODE_SUPERMAJORITY)
                .build();
        supermajorityNodeLookup = new RosterLookup(supermajorityNodeRoster);
    }

    @Test
    void testGetRoster() {
        assertNotNull(simpleLookup.getRoster());
        assertSame(simpleRoster, simpleLookup.getRoster());

        assertNotNull(singleNodeLookup.getRoster());
        assertSame(singleNodeRoster, singleNodeLookup.getRoster());

        assertNotNull(supermajorityNodeLookup.getRoster());
        assertSame(supermajorityNodeRoster, supermajorityNodeLookup.getRoster());
    }

    @Test
    void testRosterTotalWeight() {
        assertEquals(10, simpleLookup.rosterTotalWeight());
        assertEquals(1000, singleNodeLookup.rosterTotalWeight());
        assertEquals(3002, supermajorityNodeLookup.rosterTotalWeight());
    }

    @Test
    void testNodeHasSupermajorityWeight() {
        assertFalse(simpleLookup.nodeHasSupermajorityWeight());
        assertTrue(singleNodeLookup.nodeHasSupermajorityWeight());
        assertTrue(supermajorityNodeLookup.nodeHasSupermajorityWeight());
    }

    @Test
    void testNumMembers() {
        assertEquals(4, simpleLookup.numMembers());
        assertEquals(1, singleNodeLookup.numMembers());
        assertEquals(3, supermajorityNodeLookup.numMembers());
    }

    @Test
    void testIsIdAtIndex() {
        for (int i = 0; i < simpleLookup.getRoster().rosterEntries().size(); i++) {
            final NodeId nodeId =
                    NodeId.of(simpleLookup.getRoster().rosterEntries().get(i).nodeId());
            assertTrue(simpleLookup.isIdAtIndex(nodeId, i));
            assertFalse(simpleLookup.isIdAtIndex(nodeId, i + 1));
            assertFalse(simpleLookup.isIdAtIndex(nodeId, NEGATIVE_INDEX));
            assertFalse(simpleLookup.isIdAtIndex(nodeId, OUT_OF_RANGE_INDEX));
        }

        final NodeId singleId = NodeId.of(
                singleNodeLookup.getRoster().rosterEntries().getFirst().nodeId());
        assertTrue(singleNodeLookup.isIdAtIndex(singleId, 0));
        assertFalse(singleNodeLookup.isIdAtIndex(singleId, 1));
        assertFalse(singleNodeLookup.isIdAtIndex(singleId, NEGATIVE_INDEX));
        assertFalse(singleNodeLookup.isIdAtIndex(singleId, OUT_OF_RANGE_INDEX));

        for (int i = 0; i < supermajorityNodeLookup.getRoster().rosterEntries().size(); i++) {
            final NodeId nodeId = NodeId.of(
                    supermajorityNodeLookup.getRoster().rosterEntries().get(i).nodeId());
            assertTrue(supermajorityNodeLookup.isIdAtIndex(nodeId, i));
            assertFalse(supermajorityNodeLookup.isIdAtIndex(nodeId, i + 1));
            assertFalse(supermajorityNodeLookup.isIdAtIndex(nodeId, NEGATIVE_INDEX));
            assertFalse(supermajorityNodeLookup.isIdAtIndex(nodeId, OUT_OF_RANGE_INDEX));
        }
    }

    @Test
    void testGetWeight() {
        for (int i = 0; i < simpleLookup.getRoster().rosterEntries().size(); i++) {
            final NodeId nodeId =
                    NodeId.of(simpleLookup.getRoster().rosterEntries().get(i).nodeId());
            assertEquals(i + 1, simpleLookup.getWeight(nodeId));
            assertEquals(i + 1, simpleLookup.getWeight(i));
        }

        final NodeId singleId = NodeId.of(
                singleNodeLookup.getRoster().rosterEntries().getFirst().nodeId());
        assertEquals(1000, singleNodeLookup.getWeight(singleId));
        assertEquals(1000, singleNodeLookup.getWeight(0));

        for (int i = 0; i < supermajorityNodeLookup.getRoster().rosterEntries().size(); i++) {
            final NodeId nodeId = NodeId.of(
                    supermajorityNodeLookup.getRoster().rosterEntries().get(i).nodeId());
            final long expectedWeight = (i == 0) ? 3000 : 1;
            assertEquals(expectedWeight, supermajorityNodeLookup.getWeight(nodeId));
            assertEquals(expectedWeight, supermajorityNodeLookup.getWeight(i));
        }

        assertEquals(0, simpleLookup.getWeight(NON_EXISTENT_NODE_ID));
        assertEquals(0, singleNodeLookup.getWeight(NON_EXISTENT_NODE_ID));
        assertEquals(0, supermajorityNodeLookup.getWeight(NON_EXISTENT_NODE_ID));
    }

    @Test
    void testGetRosterIndex() {
        for (int i = 0; i < simpleLookup.getRoster().rosterEntries().size(); i++) {
            final NodeId nodeId =
                    NodeId.of(simpleLookup.getRoster().rosterEntries().get(i).nodeId());
            assertEquals(i, simpleLookup.getRosterIndex(nodeId));
        }

        final NodeId singleId = NodeId.of(
                singleNodeLookup.getRoster().rosterEntries().getFirst().nodeId());
        assertEquals(0, singleNodeLookup.getRosterIndex(singleId));

        for (int i = 0; i < supermajorityNodeLookup.getRoster().rosterEntries().size(); i++) {
            final NodeId nodeId = NodeId.of(
                    supermajorityNodeLookup.getRoster().rosterEntries().get(i).nodeId());
            assertEquals(i, supermajorityNodeLookup.getRosterIndex(nodeId));
        }

        assertThrows(IllegalArgumentException.class, () -> simpleLookup.getRosterIndex(NON_EXISTENT_NODE_ID));
        assertThrows(IllegalArgumentException.class, () -> singleNodeLookup.getRosterIndex(NON_EXISTENT_NODE_ID));
        assertThrows(
                IllegalArgumentException.class, () -> supermajorityNodeLookup.getRosterIndex(NON_EXISTENT_NODE_ID));
    }
}
