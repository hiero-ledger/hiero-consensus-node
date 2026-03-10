// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.NodeActivity;
import com.hedera.hapi.node.state.token.NodeRewards;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NodeRewardGroupsTest {

    private NodeRewardGroups subject;

    private static final long ACTIVE_ELIGIBLE_NODE_ID = 1L;
    private static final long INACTIVE_ELIGIBLE_NODE_ID = 3L;

    private static final long ACTIVE_ELIGIBLE_ACCOUNT_NUM = 1001L;
    private static final long INACTIVE_ELIGIBLE_ACCOUNT_NUM = 1003L;

    private static final AccountID ACTIVE_ELIGIBLE_ACCOUNT =
            AccountID.newBuilder().accountNum(ACTIVE_ELIGIBLE_ACCOUNT_NUM).build();
    private static final AccountID INACTIVE_ELIGIBLE_ACCOUNT =
            AccountID.newBuilder().accountNum(INACTIVE_ELIGIBLE_ACCOUNT_NUM).build();

    /**
     * Set up a rewards group containing all the combinations of node activities.
     * Nodes 2 and 4 (declining reward) are excluded by the caller before passing candidates.
     */
    @BeforeEach
    void setUp() {
        final var candidates = List.of(
                new NodeRewardGroups.NodeRewardCandidate(ACTIVE_ELIGIBLE_NODE_ID, ACTIVE_ELIGIBLE_ACCOUNT),
                new NodeRewardGroups.NodeRewardCandidate(INACTIVE_ELIGIBLE_NODE_ID, INACTIVE_ELIGIBLE_ACCOUNT));

        final var nodeRewards = NodeRewards.newBuilder()
                .numRoundsInStakingPeriod(100L)
                .nodeActivities(List.of(
                        NodeActivity.newBuilder()
                                .nodeId(ACTIVE_ELIGIBLE_NODE_ID)
                                .numMissedJudgeRounds(5L)
                                .build(),
                        NodeActivity.newBuilder()
                                .nodeId(INACTIVE_ELIGIBLE_NODE_ID)
                                .numMissedJudgeRounds(30L)
                                .build()))
                .build();

        // minJudgeRoundPercentage = 80%. maxMissedJudges = 20.
        subject = NodeRewardGroups.from(candidates, nodeRewards, 80);
    }

    @Test
    void testActiveNodeIds() {
        assertEquals(Set.of(ACTIVE_ELIGIBLE_NODE_ID), subject.activeNodeIds());
    }

    @Test
    void testInactiveNodeIds() {
        assertEquals(Set.of(INACTIVE_ELIGIBLE_NODE_ID), subject.inactiveNodeIds());
    }

    @Test
    void testActiveNodeAccountIds() {
        assertEquals(Set.of(ACTIVE_ELIGIBLE_ACCOUNT), subject.activeNodeAccountIds());
    }

    @Test
    void testInactiveNodeAccountIds() {
        assertEquals(Set.of(INACTIVE_ELIGIBLE_ACCOUNT), subject.inactiveNodeAccountIds());
    }

    @Test
    void testActiveNodeActivities() {
        assertEquals(1, subject.activeNodeActivities().size());
        final var activeActivity = subject.activeNodeActivities().iterator().next();
        assertEquals(ACTIVE_ELIGIBLE_NODE_ID, activeActivity.nodeId());
        assertEquals(ACTIVE_ELIGIBLE_ACCOUNT, activeActivity.accountId());
        assertEquals(95.0, activeActivity.activePercent());
        assertEquals(5L, activeActivity.numMissedRounds());
        assertTrue(activeActivity.isActive());
    }

    @Test
    void testInactiveNodeActivities() {
        assertEquals(1, subject.inactiveNodeActivities().size());
        final var inactiveActivity = subject.inactiveNodeActivities().iterator().next();
        assertEquals(INACTIVE_ELIGIBLE_NODE_ID, inactiveActivity.nodeId());
        assertEquals(INACTIVE_ELIGIBLE_ACCOUNT, inactiveActivity.accountId());
        assertEquals(70.0, inactiveActivity.activePercent());
        assertEquals(30L, inactiveActivity.numMissedRounds());
        assertFalse(inactiveActivity.isActive());
    }

    @Test
    void testAllNodeActivities() {
        assertEquals(2, subject.allNodeActivities().size());
        final var ids = subject.allNodeActivities().stream()
                .map(NodeRewardGroups.NodeRewardActivity::nodeId)
                .toList();
        assertTrue(ids.contains(ACTIVE_ELIGIBLE_NODE_ID));
        assertTrue(ids.contains(INACTIVE_ELIGIBLE_NODE_ID));
    }

    @Test
    void testAllNodeIds() {
        assertEquals(Set.of(ACTIVE_ELIGIBLE_NODE_ID, INACTIVE_ELIGIBLE_NODE_ID), subject.allNodeIds());
    }

    @Test
    void testNodeRewardActivityZeroRoundsInPeriod() {
        final var activity = new NodeRewardGroups.NodeRewardActivity(
                ACTIVE_ELIGIBLE_NODE_ID, ACTIVE_ELIGIBLE_ACCOUNT, 0, 0, 80);
        assertEquals(0.0, activity.activePercent());
        assertTrue(activity.isActive());
    }

    @Test
    void testNodeRewardActivityMaxMissedJudges() {
        // 100 rounds, 80% min judge -> max 20 missed allowed.
        final var exactlyOnLimit = new NodeRewardGroups.NodeRewardActivity(
                ACTIVE_ELIGIBLE_NODE_ID, ACTIVE_ELIGIBLE_ACCOUNT, 20, 100, 80);
        assertTrue(exactlyOnLimit.isActive());
        assertEquals(80.0, exactlyOnLimit.activePercent());

        final var justOverLimit = new NodeRewardGroups.NodeRewardActivity(
                INACTIVE_ELIGIBLE_NODE_ID, INACTIVE_ELIGIBLE_ACCOUNT, 21, 100, 80);
        assertFalse(justOverLimit.isActive());
        assertEquals(79.0, justOverLimit.activePercent());
    }

    @Test
    void testNodeRewardGroupsWithMissingActivity() {
        // A candidate with no entry in nodeActivities defaults to 0 missed judges (active).
        final var candidates =
                List.of(new NodeRewardGroups.NodeRewardCandidate(ACTIVE_ELIGIBLE_NODE_ID, ACTIVE_ELIGIBLE_ACCOUNT));

        final var nodeRewards = NodeRewards.newBuilder()
                .numRoundsInStakingPeriod(100L)
                .nodeActivities(List.of()) // No activity record for this node
                .build();

        final var localSubject = NodeRewardGroups.from(candidates, nodeRewards, 80);

        // 0 missed judges -> active
        assertEquals(Set.of(ACTIVE_ELIGIBLE_NODE_ID), localSubject.activeNodeIds());
        assertTrue(localSubject.inactiveNodeIds().isEmpty());
    }

    @Test
    void testNodeRewardGroupsWithEmptyCandidates() {
        final var nodeRewards = NodeRewards.newBuilder()
                .numRoundsInStakingPeriod(100L)
                .nodeActivities(List.of())
                .build();

        final var localSubject = NodeRewardGroups.from(List.of(), nodeRewards, 80);

        assertTrue(localSubject.activeNodeIds().isEmpty());
        assertTrue(localSubject.inactiveNodeIds().isEmpty());
        assertTrue(localSubject.activeNodeAccountIds().isEmpty());
        assertTrue(localSubject.inactiveNodeAccountIds().isEmpty());
    }
}
