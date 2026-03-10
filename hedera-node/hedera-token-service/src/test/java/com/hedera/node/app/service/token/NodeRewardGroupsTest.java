// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.AccountID;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NodeRewardGroupsTest {

    private NodeRewardGroups subject;

    private static final long ACTIVE_NODE_ID = 1L;
    private static final long INACTIVE_NODE_ID = 3L;

    private static final AccountID ACTIVE_ACCOUNT = AccountID.newBuilder().accountNum(1001L).build();
    private static final AccountID INACTIVE_ACCOUNT = AccountID.newBuilder().accountNum(1003L).build();

    /**
     * Set up a rewards group with one active and one inactive node.
     * 100 rounds, 80% min judge threshold → max 20 missed allowed.
     * Active node: 5 missed → 95% active. Inactive node: 30 missed → 70% active.
     */
    @BeforeEach
    void setUp() {
        final var activities = List.of(
                new NodeRewardActivity(ACTIVE_NODE_ID, ACTIVE_ACCOUNT, 5, 100, 80),
                new NodeRewardActivity(INACTIVE_NODE_ID, INACTIVE_ACCOUNT, 30, 100, 80));
        subject = NodeRewardGroups.from(activities);
    }

    @Test
    void testActiveNodeIds() {
        assertEquals(Set.of(ACTIVE_NODE_ID), subject.activeNodeIds());
    }

    @Test
    void testInactiveNodeIds() {
        assertEquals(Set.of(INACTIVE_NODE_ID), subject.inactiveNodeIds());
    }

    @Test
    void testActiveNodeAccountIds() {
        assertEquals(Set.of(ACTIVE_ACCOUNT), subject.activeNodeAccountIds());
    }

    @Test
    void testInactiveNodeAccountIds() {
        assertEquals(Set.of(INACTIVE_ACCOUNT), subject.inactiveNodeAccountIds());
    }

    @Test
    void testActiveNodeActivities() {
        assertEquals(1, subject.activeNodeActivities().size());
        final var activeActivity = subject.activeNodeActivities().iterator().next();
        assertEquals(ACTIVE_NODE_ID, activeActivity.nodeId());
        assertEquals(ACTIVE_ACCOUNT, activeActivity.accountId());
        assertEquals(5L, activeActivity.numMissedRounds());
    }

    @Test
    void testInactiveNodeActivities() {
        assertEquals(1, subject.inactiveNodeActivities().size());
        final var inactiveActivity = subject.inactiveNodeActivities().iterator().next();
        assertEquals(INACTIVE_NODE_ID, inactiveActivity.nodeId());
        assertEquals(INACTIVE_ACCOUNT, inactiveActivity.accountId());
        assertEquals(30L, inactiveActivity.numMissedRounds());
    }

    @Test
    void testFromWithEmptyActivities() {
        final var localSubject = NodeRewardGroups.from(List.of());

        assertTrue(localSubject.activeNodeIds().isEmpty());
        assertTrue(localSubject.inactiveNodeIds().isEmpty());
        assertTrue(localSubject.activeNodeAccountIds().isEmpty());
        assertTrue(localSubject.inactiveNodeAccountIds().isEmpty());
    }

    @Test
    void testFromPartitionsCorrectly() {
        // from() must not contain any overlap between active and inactive
        final var activeIds = subject.activeNodeIds();
        final var inactiveIds = subject.inactiveNodeIds();

        assertTrue(activeIds.stream().noneMatch(inactiveIds::contains));
        assertTrue(inactiveIds.stream().noneMatch(activeIds::contains));
    }
}
