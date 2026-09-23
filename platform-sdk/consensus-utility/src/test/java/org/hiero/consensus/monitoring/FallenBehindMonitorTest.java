// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.monitoring;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.test.fixtures.RosterFactory;
import org.hiero.consensus.test.fixtures.WeightGenerators;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

class FallenBehindMonitorTest {
    private FallenBehindMonitor monitor;
    private Roster roster;
    private NodeId[] nodeIds;

    @BeforeEach
    void setUp() {

        final int numNodes = 11;
        this.roster = RosterFactory.randomRoster(getRandomPrintSeed(), numNodes, WeightGenerators.BALANCED);
        this.nodeIds = roster.rosterEntries().stream()
                .map(entry -> NodeId.of(entry.nodeId()))
                .toArray(NodeId[]::new);
        monitor = new FallenBehindMonitor(roster, nodeIds[0], 0.5);
    }

    @Test
    void test() {
        assertFallenBehind(false, 0, "default should be none report fallen behind");

        // node 1 reports fallen behind
        monitor.report(nodeIds[1]);
        assertFallenBehind(false, 1, "one node only reported fallen behind");

        // if the same node reports again, nothing should change
        monitor.report(nodeIds[1]);
        assertFallenBehind(false, 1, "if the same node reports again, nothing should change");

        monitor.report(nodeIds[2]);
        monitor.report(nodeIds[3]);
        monitor.report(nodeIds[4]);
        monitor.report(nodeIds[5]);
        assertFallenBehind(false, 5, "we should still be missing one for fallen behind");

        monitor.report(nodeIds[6]);
        assertFallenBehind(true, 6, "we should be fallen behind");

        monitor.report(nodeIds[1]);
        monitor.report(nodeIds[2]);
        monitor.report(nodeIds[3]);
        monitor.report(nodeIds[4]);
        monitor.report(nodeIds[5]);
        monitor.report(nodeIds[6]);
        assertFallenBehind(true, 6, "if the same nodes report again, nothing should change");

        monitor.report(nodeIds[7]);
        monitor.report(nodeIds[8]);
        assertFallenBehind(true, 8, "more nodes reported, but the status should be the same");

        monitor.clear();
        assertFallenBehind(false, 0, "resetting should return to default");
    }

    @Test
    void multipleRemovalsShouldBeNoOp() {
        assertFallenBehind(false, 0, "default should be none report fallen behind");

        // node 1 reports fallen behind
        monitor.report(nodeIds[1]);
        assertFallenBehind(false, 1, "one node only reported fallen behind");

        // if the same node reports again, nothing should change
        monitor.report(nodeIds[1]);
        assertFallenBehind(false, 1, "if the same node reports again, nothing should change");

        monitor.report(nodeIds[2]);
        monitor.report(nodeIds[3]);
        monitor.report(nodeIds[4]);
        monitor.report(nodeIds[5]);
        assertFallenBehind(false, 5, "we should still be missing one for fallen behind");

        monitor.report(nodeIds[6]);
        assertFallenBehind(true, 6, "we should be fallen behind");

        monitor.report(nodeIds[7]);
        monitor.report(nodeIds[8]);
        assertFallenBehind(true, 8, "more nodes reported, but the status should be the same");

        monitor.clear(nodeIds[8]);
        monitor.clear(nodeIds[8]);
        monitor.clear(nodeIds[8]);
        monitor.clear(nodeIds[8]);
        monitor.clear(nodeIds[8]);
        monitor.clear(nodeIds[8]);
        assertFallenBehind(true, 7, "Multiple removals of single node shouldn't recover the situation");
    }

    @Test
    void testRemoveFallenBehind() {
        assertFallenBehind(false, 0, "default should be none report fallen behind");

        // node 1 reports fallen behind
        monitor.report(nodeIds[1]);
        assertFallenBehind(false, 1, "one node only reported fallen behind");

        // if the same node reports again, nothing should change
        monitor.report(nodeIds[1]);
        assertFallenBehind(false, 1, "if the same node reports again, nothing should change");

        monitor.report(nodeIds[2]);
        monitor.report(nodeIds[3]);
        monitor.report(nodeIds[4]);
        monitor.report(nodeIds[5]);
        assertFallenBehind(false, 5, "we should still be missing one for fallen behind");

        monitor.report(nodeIds[6]);
        monitor.clear(nodeIds[4]);
        assertFallenBehind(false, 5, "we should still be missing one for fallen behind");

        monitor.report(nodeIds[7]);
        assertFallenBehind(true, 6, "we should be fallen behind");
        assertTrue(monitor.isBehindPeer(nodeIds[6]));
        assertFalse(monitor.isBehindPeer(nodeIds[4]));

        monitor.report(nodeIds[4]);
        monitor.report(nodeIds[8]);
        assertFallenBehind(true, 8, "more nodes reported, but the status should be the same");

        monitor.clear();
        assertFallenBehind(false, 0, "resetting should return to default");
    }

    private void assertFallenBehind(
            final boolean expectedFallenBehind, final int expectedNumFallenBehind, final String message) {
        assertEquals(expectedFallenBehind, monitor.hasFallenBehind(), message);
        assertEquals(expectedNumFallenBehind, monitor.reportedSize(), message);
        assertEquals(expectedNumFallenBehind / (double) (nodeIds.length - 1), monitor.reportedWeight(), 0.001, message);
    }

    /**
     * A helper class that contains dummy data to feed into SyncManager lambdas.
     */
    private static class FallenBehindMonitorTestData {
        public Roster roster;
        public NodeId selfId;
        public FallenBehindMonitor fallenBehindMonitor;

        public FallenBehindMonitorTestData() {
            final Random random = getRandomPrintSeed();

            this.roster = RosterFactory.randomRoster(random, 41, WeightGenerators.BALANCED);
            this.selfId = NodeId.of(roster.rosterEntries().getFirst().nodeId());
            this.fallenBehindMonitor = new FallenBehindMonitor(roster, selfId, 0.25);
        }
    }

    /**
     * Verify that SyncManager's core functionality is working with basic input.
     */
    @Test
    @Order(0)
    void basicTest() {
        final FallenBehindMonitorTestData test = new FallenBehindMonitorTestData();

        final List<NodeId> peers = test.roster.rosterEntries().stream()
                .map(RosterEntry::nodeId)
                .map(NodeId::of)
                .toList();

        // we should not think we have fallen behind initially
        assertFalse(test.fallenBehindMonitor.hasFallenBehind());

        // neighbors 0 and 1 report fallen behind
        test.fallenBehindMonitor.report(peers.get(1));
        test.fallenBehindMonitor.report(peers.get(2));

        // we still dont have enough reports that we have fallen behind, we need more than [fallenBehindThreshold] of
        // the neighbors
        assertFalse(test.fallenBehindMonitor.hasFallenBehind());

        // add more reports
        for (int i = 3; i < 11; i++) {
            test.fallenBehindMonitor.report(peers.get(i));
        }

        // we are still missing 1 report
        assertFalse(test.fallenBehindMonitor.hasFallenBehind());

        // add the report that will go over the [fallenBehindThreshold]
        test.fallenBehindMonitor.report(peers.get(12));

        // we should now say we have fallen behind
        assertTrue(test.fallenBehindMonitor.hasFallenBehind());

        // reset it
        test.fallenBehindMonitor.clear();

        // we should now be back where we started
        assertFalse(test.fallenBehindMonitor.hasFallenBehind());
    }

    @Test
    @DisplayName("Test awaitFallenBehind blocks until threshold is met")
    void testAwaitFallenBehindBlocking() throws InterruptedException {
        final CountDownLatch threadStarted = new CountDownLatch(1);
        final AtomicBoolean awaitReturned = new AtomicBoolean(false);

        // Start a thread that waits for fallen behind
        Thread waitingThread = new Thread(() -> {
            try {
                threadStarted.countDown();
                monitor.awaitFallenBehind();
                awaitReturned.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        waitingThread.start();
        threadStarted.await();

        // Give the thread a moment to start waiting
        Thread.sleep(100);

        // Thread should still be waiting
        assertFalse(awaitReturned.get(), "awaitFallenBehind should still be blocking");

        // Report enough nodes to cross threshold
        for (int i = 1; i <= 6; i++) {
            monitor.report(nodeIds[i]);
        }

        // Wait for the waiting thread to be notified
        waitingThread.join(1000);

        // Now the await should have returned
        assertTrue(awaitReturned.get(), "awaitFallenBehind should have returned after threshold met");
        assertFalse(waitingThread.isAlive(), "waiting thread should have completed");
    }

    @Test
    @DisplayName("Test awaitFallenBehind returns immediately if already fallen behind")
    void testAwaitFallenBehindWhenAlreadyBehind() {
        // Set up fallen behind state
        for (int i = 1; i <= 6; i++) {
            monitor.report(nodeIds[i]);
        }
        assertTrue(monitor.hasFallenBehind());

        // This should return immediately without blocking
        assertTimeout(
                Duration.ofMillis(100),
                () -> {
                    monitor.awaitFallenBehind();
                },
                "awaitFallenBehind should return immediately when already fallen behind");
    }

    @Test
    @DisplayName("Test awaitFallenBehind can be interrupted")
    void testAwaitFallenBehindInterruption() throws InterruptedException {
        final AtomicBoolean wasInterrupted = new AtomicBoolean(false);

        Thread waitingThread = new Thread(() -> {
            try {
                monitor.awaitFallenBehind();
            } catch (InterruptedException e) {
                wasInterrupted.set(true);
                Thread.currentThread().interrupt();
            }
        });

        waitingThread.start();
        Thread.sleep(100); // Let thread start waiting

        waitingThread.interrupt();
        waitingThread.join(1000);

        assertTrue(wasInterrupted.get(), "Thread should have been interrupted");
    }

    @Test
    @DisplayName("Test check() method with EventWindow - self fallen behind")
    void testCheckMethodSelfFallenBehind() {
        final EventWindow selfWindow = new EventWindow(100, 105, 90, 85);
        final EventWindow peerWindow = new EventWindow(110, 115, 100, 95);
        final NodeId peer = nodeIds[1];

        // Self is behind because self.ancientThreshold (90) < peer.expiredThreshold (95)
        final FallenBehindStatus status = monitor.check(selfWindow, peerWindow, peer);

        assertEquals(FallenBehindStatus.SELF_FALLEN_BEHIND, status);
        assertTrue(monitor.isBehindPeer(peer), "Peer should be in the reported set");
        assertEquals(1, monitor.reportedSize());
    }

    @Test
    @DisplayName("Test check() method with EventWindow - other fallen behind")
    void testCheckMethodOtherFallenBehind() {
        final EventWindow selfWindow = new EventWindow(110, 115, 100, 95);
        final EventWindow peerWindow = new EventWindow(100, 105, 90, 85);
        final NodeId peer = nodeIds[1];

        // Other is behind because other.ancientThreshold (90) < self.expiredThreshold (95)
        final FallenBehindStatus status = monitor.check(selfWindow, peerWindow, peer);

        assertEquals(FallenBehindStatus.OTHER_FALLEN_BEHIND, status);
        assertFalse(monitor.isBehindPeer(peer), "Peer should not be in the reported set");
        assertEquals(0, monitor.reportedSize());
    }

    @Test
    @DisplayName("Test check() method with EventWindow - none fallen behind")
    void testCheckMethodNoneFallenBehind() {
        final EventWindow selfWindow = new EventWindow(100, 105, 90, 85);
        final EventWindow peerWindow = new EventWindow(102, 107, 92, 87);
        final NodeId peer = nodeIds[1];

        // Neither is behind (windows are compatible)
        final FallenBehindStatus status = monitor.check(selfWindow, peerWindow, peer);

        assertEquals(FallenBehindStatus.NONE_FALLEN_BEHIND, status);
        assertFalse(monitor.isBehindPeer(peer), "Peer should not be in the reported set");
        assertEquals(0, monitor.reportedSize());
    }

    @Test
    @DisplayName("Test check() method clears previous reports when no longer behind")
    void testCheckMethodClearsPreviousReports() {
        final NodeId peer = nodeIds[1];

        // First, report as behind
        final EventWindow selfBehind = new EventWindow(100, 105, 90, 85);
        final EventWindow peerAhead = new EventWindow(110, 115, 100, 95);
        monitor.check(selfBehind, peerAhead, peer);

        assertTrue(monitor.isBehindPeer(peer), "Peer should be reported initially");
        assertEquals(1, monitor.reportedSize());

        // Now check with compatible windows
        final EventWindow selfCaughtUp = new EventWindow(110, 115, 100, 95);
        final EventWindow peerSame = new EventWindow(110, 115, 100, 95);
        monitor.check(selfCaughtUp, peerSame, peer);

        assertFalse(monitor.isBehindPeer(peer), "Peer report should be cleared");
        assertEquals(0, monitor.reportedSize());
    }

    @Test
    @DisplayName("Test check() method with multiple peers reaching threshold")
    void testCheckMethodMultiplePeersReachingThreshold() {
        final EventWindow selfWindow = new EventWindow(100, 105, 90, 85);
        final EventWindow peerAheadWindow = new EventWindow(110, 115, 100, 95);

        // Check with multiple peers
        for (int i = 1; i <= 6; i++) {
            monitor.check(selfWindow, peerAheadWindow, nodeIds[i]);
        }

        assertTrue(monitor.hasFallenBehind(), "Should be fallen behind after threshold");
        assertEquals(6, monitor.reportedSize());
    }

    @Test
    @DisplayName("Test wasReportedByPeer for various scenarios")
    void testIsBehindPeer() {
        final NodeId peer1 = nodeIds[1];
        final NodeId peer2 = nodeIds[2];
        final NodeId peer3 = nodeIds[3];

        assertFalse(monitor.isBehindPeer(peer1), "Initially no peer reported");

        monitor.report(peer1);
        assertTrue(monitor.isBehindPeer(peer1), "Peer1 should be reported");
        assertFalse(monitor.isBehindPeer(peer2), "Peer2 should not be reported");

        monitor.report(peer2);
        assertTrue(monitor.isBehindPeer(peer1), "Peer1 should still be reported");
        assertTrue(monitor.isBehindPeer(peer2), "Peer2 should be reported");

        monitor.clear(peer1);
        assertFalse(monitor.isBehindPeer(peer1), "Peer1 should be cleared");
        assertTrue(monitor.isBehindPeer(peer2), "Peer2 should still be reported");

        monitor.clear();
        assertFalse(monitor.isBehindPeer(peer1), "All should be cleared after reset");
        assertFalse(monitor.isBehindPeer(peer2), "All should be cleared after reset");
        assertFalse(monitor.isBehindPeer(peer3), "All should be cleared after reset");
    }

    @Test
    @DisplayName("Test threshold boundary conditions")
    void testThresholdBoundaries() {
        // With 10 peers and 0.5 threshold, need > 5 reports to fall behind

        // Exactly at threshold (5 reports) - should NOT be fallen behind
        for (int i = 1; i <= 5; i++) {
            monitor.report(nodeIds[i]);
        }
        assertFalse(monitor.hasFallenBehind(), "Should not be fallen behind at exactly threshold");
        assertEquals(5, monitor.reportedSize());

        // Just over threshold (6 reports) - should be fallen behind
        monitor.report(nodeIds[6]);
        assertTrue(monitor.hasFallenBehind(), "Should be fallen behind just over threshold");
        assertEquals(6, monitor.reportedSize());
    }

    @Test
    @DisplayName("Test edge case with threshold of 0.0")
    void testThresholdOfZero() {
        final FallenBehindMonitor permissiveMonitor = new FallenBehindMonitor(roster, nodeIds[0], 0.0);

        // With threshold of 0.0, need > 0 reports to fall behind
        assertFalse(permissiveMonitor.hasFallenBehind());

        permissiveMonitor.report(nodeIds[5]);
        assertTrue(permissiveMonitor.hasFallenBehind(), "Should fall behind with just 1 report");
    }

    @Test
    @DisplayName("Test concurrent access to monitor")
    void testConcurrentAccess() throws InterruptedException {
        final int numThreads = 10;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(numThreads);
        final AtomicBoolean failed = new AtomicBoolean(false);

        // Spawn multiple threads reporting different nodes
        for (int i = 0; i < numThreads; i++) {
            final int nodeId = i + 1;
            new Thread(() -> {
                        try {
                            startLatch.await(); // Wait for all threads to be ready
                            monitor.report(nodeIds[nodeId]);
                            monitor.hasFallenBehind();
                            monitor.reportedSize();
                            monitor.isBehindPeer(nodeIds[nodeId]);
                        } catch (Exception e) {
                            failed.set(true);
                        } finally {
                            doneLatch.countDown();
                        }
                    })
                    .start();
        }

        startLatch.countDown(); // Start all threads
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "All threads should complete");
        assertFalse(failed.get(), "No thread should have failed");

        // After all threads complete, verify state is consistent
        assertEquals(numThreads, monitor.reportedSize(), "All reports should be recorded");
        assertTrue(monitor.hasFallenBehind(), "Should be fallen behind after all reports");
    }

    @Test
    @DisplayName("Test multiple waiting threads are all notified")
    void testMultipleWaitingThreadsNotified() throws InterruptedException {
        final int numWaiters = 5;
        final CountDownLatch allStarted = new CountDownLatch(numWaiters);
        final CountDownLatch allCompleted = new CountDownLatch(numWaiters);

        // Start multiple waiting threads
        for (int i = 0; i < numWaiters; i++) {
            new Thread(() -> {
                        try {
                            allStarted.countDown();
                            monitor.awaitFallenBehind();
                            allCompleted.countDown();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    })
                    .start();
        }

        allStarted.await(); // Wait for all threads to start
        Thread.sleep(100); // Give them time to enter wait state

        // Cross the threshold
        for (int i = 1; i <= 6; i++) {
            monitor.report(nodeIds[i]);
        }

        // All waiting threads should be notified
        assertTrue(allCompleted.await(2, TimeUnit.SECONDS), "All waiting threads should be notified and complete");
    }

    @Test
    @DisplayName("Test clear on non-existent peer does nothing")
    void testClearNonExistentPeer() {
        monitor.report(nodeIds[1]);
        assertEquals(1, monitor.reportedSize());

        // Clear a peer that hasn't reported
        monitor.clear(NodeId.of(131238712));

        // State should be unchanged
        assertEquals(1, monitor.reportedSize());
        assertTrue(monitor.isBehindPeer(nodeIds[1]));
    }

    @Test
    @DisplayName("Test reportedSize reflects actual number of unique reporters")
    void testReportedSizeAccuracy() {
        assertEquals(0, monitor.reportedSize(), "Initial size should be 0");

        monitor.report(nodeIds[1]);
        assertEquals(1, monitor.reportedSize());

        monitor.report(nodeIds[1]); // Duplicate
        assertEquals(1, monitor.reportedSize(), "Duplicate reports shouldn't increase size");

        monitor.report(nodeIds[2]);
        assertEquals(2, monitor.reportedSize());

        monitor.clear(nodeIds[1]);
        assertEquals(1, monitor.reportedSize());

        monitor.clear();
        assertEquals(0, monitor.reportedSize(), "Reset should clear all reports");
    }

    @Test
    void testGossipPausedNotificationNormalOrder() throws InterruptedException {
        final CountDownLatch allStarted = new CountDownLatch(1);
        final CountDownLatch allCompleted = new CountDownLatch(1);

        new Thread(() -> {
                    try {
                        allStarted.countDown();
                        monitor.awaitGossipPaused();
                        allCompleted.countDown();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                })
                .start();
        allStarted.await();
        Thread.sleep(100);
        monitor.notifySyncProtocolPaused();
        assertTrue(allCompleted.await(2, TimeUnit.SECONDS), "The waiting thread should be notified and complete");
    }

    @Test
    void testGossipPausedNotificationReverseOrder() throws InterruptedException {
        final CountDownLatch allCompleted = new CountDownLatch(1);

        monitor.notifySyncProtocolPaused();

        new Thread(() -> {
                    try {
                        monitor.awaitGossipPaused();
                        allCompleted.countDown();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                })
                .start();

        assertTrue(allCompleted.await(2, TimeUnit.SECONDS), "The waiting thread should be notified and complete");
    }

    @Test
    void testUnevenWeights() {
        final int numNodes = 11;
        final Roster roster =
                RosterFactory.randomRoster(getRandomPrintSeed(), numNodes, WeightGenerators.SINGLE_NODE_SUPERMAJORITY);
        final NodeId[] nodeIds = roster.rosterEntries().stream()
                .map(entry -> NodeId.of(entry.nodeId()))
                .toArray(NodeId[]::new);
        final FallenBehindMonitor monitor = new FallenBehindMonitor(roster, nodeIds[5], 0.5);

        assertFalse(monitor.hasFallenBehind());
        monitor.report(nodeIds[0]); // this node alone has supermajority
        assertTrue(monitor.hasFallenBehind());
        assertEquals(1.0, monitor.reportedWeight(), 0.01);
        monitor.clear(nodeIds[0]); // this node alone has supermajority
        assertFalse(monitor.hasFallenBehind());
        assertEquals(0.0, monitor.reportedWeight());
        for (int i = 1; i < numNodes; i++) {
            monitor.report(nodeIds[i]);
        }
        assertEquals(0.0011, monitor.reportedWeight(), 0.001);

        assertFalse(
                monitor.hasFallenBehind()); // even if we fall behind everybody else, one with supermajority keeps us
        // alive
    }

    @Test
    void testIgnoreZeroWeights() {
        final int numNodes = 30;
        final Roster roster =
                RosterFactory.randomRoster(getRandomPrintSeed(), numNodes, WeightGenerators.ONE_THIRD_ZERO_WEIGHT);
        final NodeId[] nodeIds = roster.rosterEntries().stream()
                .map(entry -> NodeId.of(entry.nodeId()))
                .toArray(NodeId[]::new);
        final FallenBehindMonitor monitor = new FallenBehindMonitor(roster, nodeIds[0], 0.0);

        assertFalse(monitor.hasFallenBehind());
        for (int i = 1; i < 8; i++) {
            // all these have zero weights
            monitor.report(nodeIds[i]);
        }
        assertFalse(monitor.hasFallenBehind());

        monitor.report(nodeIds[15]);
        assertTrue(monitor.hasFallenBehind());
    }
}
