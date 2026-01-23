// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import static org.junit.jupiter.api.Assertions.*;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RosterLookupTest {

    private static RosterLookup simpleRoster;
    private static RosterLookup singleNodeRoster;

    @BeforeAll
    static void setup() {
        final Randotron r = Randotron.create();
        simpleRoster = new RosterLookup(RandomRosterBuilder.create(r)
                .withSize(4)
                .withRealKeysEnabled(false)
                .withWeightGenerator(WeightGenerators.BALANCED)
                .build());
        singleNodeRoster = new RosterLookup(RandomRosterBuilder.create(r)
                .withSize(1)
                .withRealKeysEnabled(false)
                .withWeightGenerator(WeightGenerators.BALANCED)
                .build());
    }

    @Test
    void testGetRoster() {
        final Roster simple = simpleRoster.getRoster();
        assertNotNull(simple);
        assertEquals(4, simple.rosterEntries().size());

        final Roster single = singleNodeRoster.getRoster();
        assertNotNull(single);
        assertEquals(1, single.rosterEntries().size());
    }

    @Test
    void testRosterTotalWeight() {
        assertEquals(4, simpleRoster.rosterTotalWeight());
        assertEquals(1, singleNodeRoster.rosterTotalWeight());
    }

    @Test
    void testNodeHasSupermajorityWeight() {
        assertFalse(simpleRoster.nodeHasSupermajorityWeight());
        assertTrue(singleNodeRoster.nodeHasSupermajorityWeight());
    }

    @Test
    void testNumMembers() {
        assertEquals(4, simpleRoster.getRoster().rosterEntries().size());
        assertEquals(1, singleNodeRoster.getRoster().rosterEntries().size());
    }

    @Test
    void testIdEqualsIndex() {
        for (int i = 0; i < simpleRoster.getRoster().rosterEntries().size(); i++) {
            final NodeId nodeId =
                    NodeId.of(simpleRoster.getRoster().rosterEntries().get(i).nodeId());
            assertTrue(simpleRoster.idEqualsIndex(nodeId, i));
            assertFalse(simpleRoster.idEqualsIndex(nodeId, i + 1));
            assertFalse(simpleRoster.idEqualsIndex(nodeId, -1));
            assertFalse(simpleRoster.idEqualsIndex(nodeId, 1000));
        }
        final NodeId singleId = NodeId.of(
                singleNodeRoster.getRoster().rosterEntries().getFirst().nodeId());
        assertTrue(singleNodeRoster.idEqualsIndex(singleId, 0));
        assertFalse(singleNodeRoster.idEqualsIndex(singleId, 1));
        assertFalse(singleNodeRoster.idEqualsIndex(singleId, -1));
        assertFalse(singleNodeRoster.idEqualsIndex(singleId, 1000));
    }

    @Test
    void testGetWeight() {
        for (int i = 0; i < simpleRoster.getRoster().rosterEntries().size(); i++) {
            final NodeId nodeId =
                    NodeId.of(simpleRoster.getRoster().rosterEntries().get(i).nodeId());
            assertEquals(1, simpleRoster.getWeight(nodeId));
            assertEquals(1, simpleRoster.getWeight(i));
        }

        final NodeId singleId = NodeId.of(
                singleNodeRoster.getRoster().rosterEntries().getFirst().nodeId());
        assertEquals(1, singleNodeRoster.getWeight(singleId));
        assertEquals(1, singleNodeRoster.getWeight(0));

        final NodeId nonExistentNodeId = NodeId.of(1000);
        assertEquals(0, simpleRoster.getWeight(nonExistentNodeId));
        assertEquals(0, singleNodeRoster.getWeight(nonExistentNodeId));
    }

    @Test
    void testGetRosterIndex() {
        for (int i = 0; i < simpleRoster.getRoster().rosterEntries().size(); i++) {
            final NodeId nodeId =
                    NodeId.of(simpleRoster.getRoster().rosterEntries().get(i).nodeId());
            assertEquals(i, simpleRoster.getRosterIndex(nodeId));
        }
        final NodeId singleId = NodeId.of(
                singleNodeRoster.getRoster().rosterEntries().getFirst().nodeId());

        final NodeId nonExistentNodeId = NodeId.of(1000);
        assertEquals(0, singleNodeRoster.getRosterIndex(singleId));

        assertThrows(IllegalArgumentException.class, () -> simpleRoster.getRosterIndex(nonExistentNodeId));
        assertThrows(IllegalArgumentException.class, () -> singleNodeRoster.getRosterIndex(nonExistentNodeId));
    }
}
