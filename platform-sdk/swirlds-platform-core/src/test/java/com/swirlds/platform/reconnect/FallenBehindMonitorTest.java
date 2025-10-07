// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.google.common.collect.ImmutableSet;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig_;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.Utilities;
import com.swirlds.platform.gossip.FallenBehindMonitor;
import com.swirlds.platform.network.PeerInfo;
import com.swirlds.platform.system.status.StatusActionSubmitter;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

class FallenBehindMonitorTest {
    private final int numNodes = 11;
    private final Configuration config = new TestConfigBuilder()
            .withValue(ReconnectConfig_.FALLEN_BEHIND_THRESHOLD, 0.5)
            .getOrCreateConfig();
    private FallenBehindMonitor manager;

    @BeforeEach
    void setUp() {

        manager = new FallenBehindMonitor(
                RandomRosterBuilder.create(Randotron.create())
                        .withSize(numNodes)
                        .build(),
                config,
                new NoOpMetrics());
        manager.bind(mock(StatusActionSubmitter.class));
    }

    @Test
    void test() {
        assertFallenBehind(false, 0, "default should be none report fallen behind");

        // node 1 reports fallen behind
        manager.report(NodeId.of(1));
        assertFallenBehind(false, 1, "one node only reported fallen behind");

        // if the same node reports again, nothing should change
        manager.report(NodeId.of(1));
        assertFallenBehind(false, 1, "if the same node reports again, nothing should change");

        manager.report(NodeId.of(2));
        manager.report(NodeId.of(3));
        manager.report(NodeId.of(4));
        manager.report(NodeId.of(5));
        assertFallenBehind(false, 5, "we should still be missing one for fallen behind");

        manager.report(NodeId.of(6));
        assertFallenBehind(true, 6, "we should be fallen behind");

        manager.report(NodeId.of(1));
        manager.report(NodeId.of(2));
        manager.report(NodeId.of(3));
        manager.report(NodeId.of(4));
        manager.report(NodeId.of(5));
        manager.report(NodeId.of(6));
        assertFallenBehind(true, 6, "if the same nodes report again, nothing should change");

        manager.report(NodeId.of(7));
        manager.report(NodeId.of(8));
        assertFallenBehind(true, 8, "more nodes reported, but the status should be the same");

        manager.reset();
        assertFallenBehind(false, 0, "resetting should return to default");
    }

    @Test
    void testRemoveFallenBehind() {
        assertFallenBehind(false, 0, "default should be none report fallen behind");

        // node 1 reports fallen behind
        manager.report(NodeId.of(1));
        assertFallenBehind(false, 1, "one node only reported fallen behind");

        // if the same node reports again, nothing should change
        manager.report(NodeId.of(1));
        assertFallenBehind(false, 1, "if the same node reports again, nothing should change");

        manager.report(NodeId.of(2));
        manager.report(NodeId.of(3));
        manager.report(NodeId.of(4));
        manager.report(NodeId.of(5));
        assertFallenBehind(false, 5, "we should still be missing one for fallen behind");

        manager.report(NodeId.of(6));
        manager.clear(NodeId.of(4));
        assertFallenBehind(false, 5, "we should still be missing one for fallen behind");

        manager.report(NodeId.of(7));
        assertFallenBehind(true, 6, "we should be fallen behind");
        assertTrue(manager.wasReportedByPeer(NodeId.of(6)));
        assertFalse(manager.wasReportedByPeer(NodeId.of(4)));

        manager.report(NodeId.of(4));
        manager.report(NodeId.of(8));
        assertFallenBehind(true, 8, "more nodes reported, but the status should be the same");

        manager.reset();
        assertFallenBehind(false, 0, "resetting should return to default");
    }

    @Test
    void testChangingPeerAmount() {
        assertFallenBehind(false, 0, "default should be none report fallen behind");

        // node 1 reports fallen behind
        manager.report(NodeId.of(1));
        assertFallenBehind(false, 1, "one node only reported fallen behind");

        // if the same node reports again, nothing should change
        manager.report(NodeId.of(1));
        assertFallenBehind(false, 1, "if the same node reports again, nothing should change");

        manager.report(NodeId.of(2));
        manager.report(NodeId.of(3));
        manager.report(NodeId.of(4));
        manager.report(NodeId.of(5));
        assertFallenBehind(false, 5, "we should still be missing one for fallen behind");

        manager.update(ImmutableSet.of(NodeId.of(22), NodeId.of(23)), Collections.emptySet());

        manager.report(NodeId.of(6));
        assertFallenBehind(false, 6, "we miss one due to changed size");

        manager.update(Collections.emptySet(), Collections.singleton(NodeId.of(9)));

        assertFallenBehind(true, 6, "we should fall behind due to reduced number of peers");

        manager.report(NodeId.of(1));
        manager.report(NodeId.of(2));
        manager.report(NodeId.of(3));
        manager.report(NodeId.of(4));
        manager.report(NodeId.of(6));
        assertFallenBehind(true, 6, "if the same nodes report again, nothing should change");

        manager.report(NodeId.of(7));
        manager.report(NodeId.of(8));
        assertFallenBehind(true, 8, "more nodes reported, but the status should be the same");

        manager.reset();
        assertFallenBehind(false, 0, "resetting should return to default");
    }

    private void assertFallenBehind(
            final boolean expectedFallenBehind, final int expectedNumFallenBehind, final String message) {
        assertEquals(expectedFallenBehind, manager.hasFallenBehind(), message);
        assertEquals(expectedNumFallenBehind, manager.reportedSize(), message);
    }

    /**
     * A helper class that contains dummy data to feed into SyncManager lambdas.
     */
    private static class FallenBehindMonitorTestData {
        public Roster roster;
        public NodeId selfId;
        public FallenBehindMonitor fallenBehindMonitor;
        public Configuration configuration;

        public FallenBehindMonitorTestData() {
            final Random random = getRandomPrintSeed();
            configuration = new TestConfigBuilder()
                    .withValue(ReconnectConfig_.FALLEN_BEHIND_THRESHOLD, "0.25")
                    .getOrCreateConfig();

            this.roster = RandomRosterBuilder.create(random).withSize(41).build();
            this.selfId = NodeId.of(roster.rosterEntries().get(0).nodeId());

            this.fallenBehindMonitor = new FallenBehindMonitor(roster, configuration, new NoOpMetrics());
            fallenBehindMonitor.bind(mock(StatusActionSubmitter.class));
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
