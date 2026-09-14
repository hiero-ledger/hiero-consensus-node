// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import java.util.List;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link RosterWrapper} class.
 */
class RosterWrapperTest {

    private static final NodeId NODE_3 = NodeId.of(3);
    private static final NodeId NODE_7 = NodeId.of(7);
    private static final NodeId NODE_11 = NodeId.of(11);
    private static final NodeId ABSENT_NODE = NodeId.of(5);

    private RosterWrapper roster;

    /**
     * Sets up a test roster with three entries before each test.
     */
    @BeforeEach
    void setUp() {
        final RosterEntry entry3 = entry(NODE_3, 10);
        final RosterEntry entry7 = entry(NODE_7, 20);
        final RosterEntry entry11 = entry(NODE_11, 30);
        roster = RosterWrapper.of(new Roster(List.of(entry3, entry7, entry11)));
    }

    private static RosterEntry entry(final NodeId nodeId, final long weight) {
        return RosterEntry.newBuilder().nodeId(nodeId.id()).weight(weight).build();
    }

    /**
     * Test that the index of each node in the roster follows the order of the entries.
     */
    @Test
    void indexFollowsRosterOrder() {
        assertThat(roster.getIndex(NODE_3)).isEqualTo(0);
        assertThat(roster.getIndex(NODE_7)).isEqualTo(1);
        assertThat(roster.getIndex(NODE_11)).isEqualTo(2);

        assertThat(roster.getIndex(ABSENT_NODE)).isEqualTo(-1);
        assertThat(roster.contains(NODE_7)).isTrue();
        assertThat(roster.contains(ABSENT_NODE)).isFalse();
    }

    /**
     * Test that the roster entry for a given node ID can be looked up correctly,
     * and that looking up an absent node throws an exception.
     */
    @Test
    void rosterEntryIsLookedUpByNodeId() {
        assertThat(roster.getRosterEntry(NODE_7).nodeId()).isEqualTo(NODE_7);
        assertThat(roster.getRosterEntry(NODE_7).weight()).isEqualTo(20L);

        assertThatThrownBy(() -> roster.getRosterEntry(ABSENT_NODE)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Test that the total weight of the roster sums all entries correctly.
     */
    @Test
    void totalWeightSumsAllEntries() {
        assertThat(roster.totalWeight()).isEqualTo(60L);
    }
}
