// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableSet;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.platform.Utilities;
import com.swirlds.platform.network.PeerInfo;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

class FallenBehindMonitorTest {
    private FallenBehindMonitor monitor;

    @BeforeEach
    void setUp() {

        final int numNodes = 11;
        monitor = new FallenBehindMonitor(numNodes - 1, 0.5);
    }

    @Test
    void test() {
        assertFallenBehind(false, 0, "default should be none report fallen behind");

        // node 1 reports fallen behind
        monitor.report(NodeId.of(1));
        assertFallenBehind(false, 1, "one node only reported fallen behind");

        // if the same node reports again, nothing should change
        monitor.report(NodeId.of(1));
        assertFallenBehind(false, 1, "if the same node reports again, nothing should change");

        monitor.report(NodeId.of(2));
        monitor.report(NodeId.of(3));
        monitor.report(NodeId.of(4));
        monitor.report(NodeId.of(5));
        assertFallenBehind(false, 5, "we should still be missing one for fallen behind");

        monitor.report(NodeId.of(6));
        assertFallenBehind(true, 6, "we should be fallen behind");

        monitor.report(NodeId.of(1));
        monitor.report(NodeId.of(2));
        monitor.report(NodeId.of(3));
        monitor.report(NodeId.of(4));
        monitor.report(NodeId.of(5));
        monitor.report(NodeId.of(6));
        assertFallenBehind(true, 6, "if the same nodes report again, nothing should change");

        monitor.report(NodeId.of(7));
        monitor.report(NodeId.of(8));
        assertFallenBehind(true, 8, "more nodes reported, but the status should be the same");

        monitor.reset();
        assertFallenBehind(false, 0, "resetting should return to default");
    }

    @Test
    void testRemoveFallenBehind() {
        assertFallenBehind(false, 0, "default should be none report fallen behind");

        // node 1 reports fallen behind
        monitor.report(NodeId.of(1));
        assertFallenBehind(false, 1, "one node only reported fallen behind");

        // if the same node reports again, nothing should change
        monitor.report(NodeId.of(1));
        assertFallenBehind(false, 1, "if the same node reports again, nothing should change");

        monitor.report(NodeId.of(2));
        monitor.report(NodeId.of(3));
        monitor.report(NodeId.of(4));
        monitor.report(NodeId.of(5));
        assertFallenBehind(false, 5, "we should still be missing one for fallen behind");

        monitor.report(NodeId.of(6));
        monitor.clear(NodeId.of(4));
        assertFallenBehind(false, 5, "we should still be missing one for fallen behind");

        monitor.report(NodeId.of(7));
        assertFallenBehind(true, 6, "we should be fallen behind");
        assertTrue(monitor.wasReportedByPeer(NodeId.of(6)));
        assertFalse(monitor.wasReportedByPeer(NodeId.of(4)));

        monitor.report(NodeId.of(4));
        monitor.report(NodeId.of(8));
        assertFallenBehind(true, 8, "more nodes reported, but the status should be the same");

        monitor.reset();
        assertFallenBehind(false, 0, "resetting should return to default");
    }

    @Test
    void testChangingPeerAmount() {
        assertFallenBehind(false, 0, "default should be none report fallen behind");

        // node 1 reports fallen behind
        monitor.report(NodeId.of(1));
        assertFallenBehind(false, 1, "one node only reported fallen behind");

        // if the same node reports again, nothing should change
        monitor.report(NodeId.of(1));
        assertFallenBehind(false, 1, "if the same node reports again, nothing should change");

        monitor.report(NodeId.of(2));
        monitor.report(NodeId.of(3));
        monitor.report(NodeId.of(4));
        monitor.report(NodeId.of(5));
        assertFallenBehind(false, 5, "we should still be missing one for fallen behind");

        monitor.update(ImmutableSet.of(NodeId.of(22), NodeId.of(23)), Collections.emptySet());

        monitor.report(NodeId.of(6));
        assertFallenBehind(false, 6, "we miss one due to changed size");

        monitor.update(Collections.emptySet(), Collections.singleton(NodeId.of(9)));

        assertFallenBehind(true, 6, "we should fall behind due to reduced number of peers");

        monitor.report(NodeId.of(1));
        monitor.report(NodeId.of(2));
        monitor.report(NodeId.of(3));
        monitor.report(NodeId.of(4));
        monitor.report(NodeId.of(6));
        assertFallenBehind(true, 6, "if the same nodes report again, nothing should change");

        monitor.report(NodeId.of(7));
        monitor.report(NodeId.of(8));
        assertFallenBehind(true, 8, "more nodes reported, but the status should be the same");

        monitor.reset();
        assertFallenBehind(false, 0, "resetting should return to default");
    }

    private void assertFallenBehind(
            final boolean expectedFallenBehind, final int expectedNumFallenBehind, final String message) {
        assertEquals(expectedFallenBehind, monitor.hasFallenBehind(), message);
        assertEquals(expectedNumFallenBehind, monitor.reportedSize(), message);
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

            this.roster = RandomRosterBuilder.create(random).withSize(41).build();
            this.selfId = NodeId.of(roster.rosterEntries().get(0).nodeId());

            this.fallenBehindMonitor = new FallenBehindMonitor(40, .25);
        }
    }

    /**
     * Verify that SyncManager's core functionality is working with basic input.
     */
    @Test
    @Order(0)
    void basicTest() {
        final FallenBehindMonitorTestData test = new FallenBehindMonitorTestData();

        final List<PeerInfo> peers = Utilities.createPeerInfoList(test.roster, test.selfId);

        // we should not think we have fallen behind initially
        assertFalse(test.fallenBehindMonitor.hasFallenBehind());

        // neighbors 0 and 1 report fallen behind
        test.fallenBehindMonitor.report(peers.get(0).nodeId());
        test.fallenBehindMonitor.report(peers.get(1).nodeId());

        // we still dont have enough reports that we have fallen behind, we need more than [fallenBehindThreshold] of
        // the neighbors
        assertFalse(test.fallenBehindMonitor.hasFallenBehind());

        // add more reports
        for (int i = 2; i < 10; i++) {
            test.fallenBehindMonitor.report(peers.get(i).nodeId());
        }

        // we are still missing 1 report
        assertFalse(test.fallenBehindMonitor.hasFallenBehind());

        // add the report that will go over the [fallenBehindThreshold]
        test.fallenBehindMonitor.report(peers.get(10).nodeId());

        // we should now say we have fallen behind
        assertTrue(test.fallenBehindMonitor.hasFallenBehind());

        // reset it
        test.fallenBehindMonitor.reset();

        // we should now be back where we started
        assertFalse(test.fallenBehindMonitor.hasFallenBehind());
    }
}
