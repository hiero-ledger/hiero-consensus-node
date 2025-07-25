// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.permits;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class LruFairSyncSelectorTests {

    private LruFairSyncSelector selector;
    private final int MAX_CONCURRENT_SYNCS = 2;
    private final int MIN_ROUND_ROBIN_SIZE = 3;
    private NodeId node1, node2, node3, node4, node5;

    @BeforeEach
    void setUp() {
        selector = new LruFairSyncSelector(MAX_CONCURRENT_SYNCS, MIN_ROUND_ROBIN_SIZE);
        node1 = NodeId.of(1);
        node2 = NodeId.of(2);
        node3 = NodeId.of(3);
        node4 = NodeId.of(4);
        node5 = NodeId.of(5);
    }

    @Test
    void testTryAcquireNewNode() {
        assertTrue(selector.tryAcquire(node1));
    }

    @Test
    void testTryAcquireMaxLimitReached() {
        assertTrue(selector.tryAcquire(node1));
        assertTrue(selector.tryAcquire(node2));
        assertFalse(selector.tryAcquire(node3));
    }

    @Test
    void testTryAcquireAlreadyAcquiredThrows() {
        selector.tryAcquire(node1);
        assertThrows(IllegalStateException.class, () -> selector.tryAcquire(node1));
    }

    @Test
    void testReleaseIfAcquiredRemovesAndAddsToRecent() {
        selector.tryAcquire(node1);
        selector.releaseIfAcquired(node1);
        assertFalse(selector.tryAcquire(node1)); // Should now check recentSyncs position
    }

    @Test
    void testTryAcquireBasedOnRecentSyncsOrder() {
        selector.tryAcquire(node1);
        selector.releaseIfAcquired(node1); // node1 now in recentSyncs

        selector.tryAcquire(node2);
        selector.releaseIfAcquired(node2); // node2 also in recentSyncs

        selector.tryAcquire(node3);
        selector.releaseIfAcquired(node3); // node2 also in recentSyncs

        assertTrue(selector.tryAcquire(node1)); // node1 is within maxConcurrentSyncs position in recentSyncs
        assertFalse(selector.tryAcquire(node3)); // node2 moved down the list, but still not fresh enough
    }

    @Test
    void testReleaseWithoutAcquisitionStillUpdatesRecent() {
        selector.releaseIfAcquired(node3); // node3 was never acquired
        assertFalse(selector.tryAcquire(node3)); // Now it depends on recentSyncs position
    }

    @Test
    void testForceAcquire() {
        selector.tryAcquire(node1);
        selector.releaseIfAcquired(node1); // node1 now in recentSyncs

        selector.tryAcquire(node2);
        selector.releaseIfAcquired(node2); // node2 also in recentSyncs

        selector.tryAcquire(node3);
        selector.releaseIfAcquired(node3); // node2 also in recentSyncs

        assertTrue(selector.tryAcquire(node1)); // node1 is within maxConcurrentSyncs position in recentSyncs
        assertTrue(selector.tryAcquire(node2));
        assertFalse(selector.tryAcquire(node3));
        selector.forceAcquire(node3);
        assertFalse(selector.tryAcquire(node4));
        assertFalse(selector.tryAcquire(node5));
    }
}
