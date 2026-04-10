// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.token.NodeRewardGroups.NodeActivityCriteria;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Unit tests for {@link NodeRewardGroups}. */
class NodeRewardGroupsTest {

    private NodeRewardGroups subject;

    private static final long ACTIVE_NODE_ID = 1L;
    private static final long INACTIVE_NODE_ID = 3L;

    private static final AccountID ACTIVE_ACCOUNT =
            AccountID.newBuilder().accountNum(1001L).build();
    private static final AccountID INACTIVE_ACCOUNT =
            AccountID.newBuilder().accountNum(1003L).build();

    /**
     * Set up a rewards group with one active and one inactive node.
     * 100 rounds, 80% min judge threshold → max 20 missed allowed.
     * Active node: 5 missed → 95% active. Inactive node: 30 missed → 70% active.
     */
    @BeforeEach
    void before() {
        final var activities = List.of(
                new NodeRewardActivity(ACTIVE_NODE_ID, ACTIVE_ACCOUNT, 5, 100, 80),
                new NodeRewardActivity(INACTIVE_NODE_ID, INACTIVE_ACCOUNT, 30, 100, 80));
        subject = NodeRewardGroups.from(activities);
    }

    @Test
    void testActiveNodeIds() {
        assertEquals(List.of(ACTIVE_NODE_ID), subject.activeNodeIds());
    }

    @Test
    void testInactiveNodeIds() {
        assertEquals(List.of(INACTIVE_NODE_ID), subject.inactiveNodeIds());
    }

    @Test
    void testActiveNodeAccountIds() {
        assertEquals(List.of(ACTIVE_ACCOUNT), subject.activeNodeAccountIds());
    }

    @Test
    void testInactiveNodeAccountIds() {
        assertEquals(List.of(INACTIVE_ACCOUNT), subject.inactiveNodeAccountIds());
    }

    @Test
    void testActiveNodeActivities() {
        assertEquals(1, subject.activeNodeActivities().size());
        final var activeActivity = subject.activeNodeActivities().getFirst();
        assertEquals(ACTIVE_NODE_ID, activeActivity.nodeId());
        assertEquals(ACTIVE_ACCOUNT, activeActivity.accountId());
        assertEquals(5L, activeActivity.numMissedRounds());
    }

    @Test
    void testInactiveNodeActivities() {
        assertEquals(1, subject.inactiveNodeActivities().size());
        final var inactiveActivity = subject.inactiveNodeActivities().getFirst();
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

    /**
     * Verifies the default {@link NodeActivityCriteria} across thresholds, boundary conditions,
     * and edge cases.
     */
    @ParameterizedTest(name = "{0} missed / {1} rounds at {2}% min → active={3}")
    @CsvSource({
        // rounds=0: maxMissed=0, numMissed=0 → 0 ≤ 0 → active
        "0,   0,   80,  true",
        // 80% min, 100 rounds → maxMissed=20; boundary cases
        "20,  100, 80,  true", // exactly at limit
        "21,  100, 80,  false", // one over limit
        // 0% min → maxMissed=rounds; every possible missed count is active
        "0,   100, 0,   true",
        "100, 100, 0,   true",
        // 100% min → maxMissed=0; only 0 missed qualifies
        "0,   100, 100, true",
        "1,   100, 100, false",
        // 50% min, 100 rounds → maxMissed=50
        "50,  100, 50,  true",
        "51,  100, 50,  false",
        // 90% min, 100 rounds → maxMissed=10
        "10,  100, 90,  true",
        "11,  100, 90,  false",
        // 75% min, 10 rounds → maxMissed = floor(10*25/100) = 2 (integer division, not 2.5)
        "2,   10,  75,  true",
        "3,   10,  75,  false",
    })
    void testDefaultActivityCriteria(long numMissed, long rounds, int minPct, final boolean expectedActive) {
        final var activity = new NodeRewardActivity(
                1L, AccountID.newBuilder().accountNum(1001L).build(), numMissed, rounds, minPct);
        assertEquals(
                expectedActive,
                NodeActivityCriteria.DEFAULT.isActive(activity),
                "Expected node to be " + (expectedActive ? "active" : "inactive"));
    }
}
