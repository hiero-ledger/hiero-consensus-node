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

class RosterWrapperTest {

    private static final NodeId NODE_3 = NodeId.of(3);
    private static final NodeId NODE_7 = NodeId.of(7);
    private static final NodeId NODE_11 = NodeId.of(11);
    private static final NodeId ABSENT_NODE = NodeId.of(5);

    private RosterWrapper roster;

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

    @Test
    void indexFollowsRosterOrder() {
        assertThat(roster.getIndex(NODE_3)).isEqualTo(0);
        assertThat(roster.getIndex(NODE_7)).isEqualTo(1);
        assertThat(roster.getIndex(NODE_11)).isEqualTo(2);

        assertThat(roster.getIndex(ABSENT_NODE)).isEqualTo(-1);
        assertThat(roster.contains(NODE_7)).isTrue();
        assertThat(roster.contains(ABSENT_NODE)).isFalse();
    }

    @Test
    void rosterEntryIsLookedUpByNodeId() {
        assertThat(roster.getRosterEntry(NODE_7).nodeId()).isEqualTo(NODE_7);
        assertThat(roster.getRosterEntry(NODE_7).weight()).isEqualTo(20L);

        assertThatThrownBy(() -> roster.getRosterEntry(ABSENT_NODE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void totalWeightSumsAllEntries() {
        assertThat(roster.totalWeight()).isEqualTo(60L);
    }
}
