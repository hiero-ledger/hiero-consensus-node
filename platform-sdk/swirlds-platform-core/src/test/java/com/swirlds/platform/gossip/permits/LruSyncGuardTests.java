// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.permits;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class LruSyncGuardTests {

    private LruSyncGuard selector;
    private final int MAX_CONCURRENT_SYNCS = 2;
    private final int MIN_ROUND_ROBIN_SIZE = 3;
    private NodeId node1, node2, node3, node4, node5;

    @BeforeEach
    void setUp() {
        selector = new LruSyncGuard(MAX_CONCURRENT_SYNCS, MIN_ROUND_ROBIN_SIZE);
        node1 = NodeId.of(1);
        node2 = NodeId.of(2);
        node3 = NodeId.of(3);
        node4 = NodeId.of(4);
        node5 = NodeId.of(5);
    }

    @Test
    void testIsSyncAllowedNewNode() {
        assertTrue(selector.isSyncAllowed(node1));
    }

    @Test
    void testIsSyncAllowedMaxLimitReached() {
        assertTrue(selector.isSyncAllowed(node1));
        assertTrue(selector.isSyncAllowed(node2));
        assertFalse(selector.isSyncAllowed(node3));
    }

    @Test
    void testIsSyncAllowedAlreadyAcquiredThrows() {
        selector.isSyncAllowed(node1);
        assertThrows(IllegalStateException.class, () -> selector.isSyncAllowed(node1));
    }

    @Test
    void testOnSyncCompletedRemovesAndAddsToRecent() {
        selector.isSyncAllowed(node1);
        selector.onSyncCompleted(node1);
        assertFalse(selector.isSyncAllowed(node1)); // Should now check recentSyncs position
    }

    @Test
    void testIsSyncAllowedBasedOnRecentSyncsOrder() {
        selector.isSyncAllowed(node1);
        selector.onSyncCompleted(node1); // node1 now in recentSyncs

        selector.isSyncAllowed(node2);
        selector.onSyncCompleted(node2); // node2 also in recentSyncs

        selector.isSyncAllowed(node3);
        selector.onSyncCompleted(node3); // node2 also in recentSyncs

        assertTrue(selector.isSyncAllowed(node1)); // node1 is within maxConcurrentSyncs position in recentSyncs
        assertFalse(selector.isSyncAllowed(node3)); // node2 moved down the list, but still not fresh enough
    }

    @Test
    void testReleaseWithoutAcquisitionStillUpdatesRecent() {
        selector.onSyncCompleted(node3); // node3 was never acquired
        assertFalse(selector.isSyncAllowed(node3)); // Now it depends on recentSyncs position
    }

    @Test
    void testOnForcedSync() {
        selector.isSyncAllowed(node1);
        selector.onSyncCompleted(node1); // node1 now in recentSyncs

        selector.isSyncAllowed(node2);
        selector.onSyncCompleted(node2); // node2 also in recentSyncs

        selector.isSyncAllowed(node3);
        selector.onSyncCompleted(node3); // node2 also in recentSyncs

        assertTrue(selector.isSyncAllowed(node1)); // node1 is within maxConcurrentSyncs position in recentSyncs
        assertTrue(selector.isSyncAllowed(node2));
        assertFalse(selector.isSyncAllowed(node3));
        selector.onForcedSync(node3);
        assertFalse(selector.isSyncAllowed(node4));
        assertFalse(selector.isSyncAllowed(node5));
    }
}
