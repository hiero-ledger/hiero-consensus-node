// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.google.common.collect.ImmutableSet;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig_;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.Utilities;
import com.swirlds.platform.gossip.FallenBehindMonitor;
import com.swirlds.platform.network.PeerInfo;
import com.swirlds.platform.system.status.StatusActionSubmitter;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

class FallenBehindMonitorTest {
    private final int numNodes = 11;
    private final Roster roster =
            RandomRosterBuilder.create(Randotron.create()).withSize(numNodes).build();
    private final double fallenBehindThreshold = 0.5;
    private final NodeId selfId = NodeId.of(roster.rosterEntries().get(0).nodeId());
    private final ReconnectConfig config = new TestConfigBuilder()
            .withValue(ReconnectConfig_.FALLEN_BEHIND_THRESHOLD, fallenBehindThreshold)
            .getOrCreateConfig()
            .getConfigData(ReconnectConfig.class);
    final List<PeerInfo> peers = Utilities.createPeerInfoList(roster, selfId);
    private final FallenBehindMonitor manager =
            new FallenBehindMonitor(selfId, peers.size(), mock(StatusActionSubmitter.class), config);

    @Test
    void test() {
        assertFallenBehind(false, 0, "default should be none report fallen behind");

        // node 1 reports fallen behind
        manager.reportFallenBehind(NodeId.of(1));
        assertFallenBehind(false, 1, "one node only reported fallen behind");

        // if the same node reports again, nothing should change
        manager.reportFallenBehind(NodeId.of(1));
        assertFallenBehind(false, 1, "if the same node reports again, nothing should change");

        manager.reportFallenBehind(NodeId.of(2));
        manager.reportFallenBehind(NodeId.of(3));
        manager.reportFallenBehind(NodeId.of(4));
        manager.reportFallenBehind(NodeId.of(5));
        assertFallenBehind(false, 5, "we should still be missing one for fallen behind");

        manager.reportFallenBehind(NodeId.of(6));
        assertFallenBehind(true, 6, "we should be fallen behind");

        manager.reportFallenBehind(NodeId.of(1));
        manager.reportFallenBehind(NodeId.of(2));
        manager.reportFallenBehind(NodeId.of(3));
        manager.reportFallenBehind(NodeId.of(4));
        manager.reportFallenBehind(NodeId.of(5));
        manager.reportFallenBehind(NodeId.of(6));
        assertFallenBehind(true, 6, "if the same nodes report again, nothing should change");

        manager.reportFallenBehind(NodeId.of(7));
        manager.reportFallenBehind(NodeId.of(8));
        assertFallenBehind(true, 8, "more nodes reported, but the status should be the same");

        manager.resetFallenBehind();
        assertFallenBehind(false, 0, "resetting should return to default");
    }

    @Test
    void testRemoveFallenBehind() {
        assertFallenBehind(false, 0, "default should be none report fallen behind");

        // node 1 reports fallen behind
        manager.reportFallenBehind(NodeId.of(1));
        assertFallenBehind(false, 1, "one node only reported fallen behind");

        // if the same node reports again, nothing should change
        manager.reportFallenBehind(NodeId.of(1));
        assertFallenBehind(false, 1, "if the same node reports again, nothing should change");

        manager.reportFallenBehind(NodeId.of(2));
        manager.reportFallenBehind(NodeId.of(3));
        manager.reportFallenBehind(NodeId.of(4));
        manager.reportFallenBehind(NodeId.of(5));
        assertFallenBehind(false, 5, "we should still be missing one for fallen behind");

        manager.reportFallenBehind(NodeId.of(6));
        manager.clearFallenBehind(NodeId.of(4));
        assertFallenBehind(false, 5, "we should still be missing one for fallen behind");

        manager.reportFallenBehind(NodeId.of(7));
        assertFallenBehind(true, 6, "we should be fallen behind");
        assertTrue(manager.shouldReconnectFrom(NodeId.of(6)));
        assertFalse(manager.shouldReconnectFrom(NodeId.of(4)));

        manager.reportFallenBehind(NodeId.of(4));
        manager.reportFallenBehind(NodeId.of(8));
        assertFallenBehind(true, 8, "more nodes reported, but the status should be the same");

        manager.resetFallenBehind();
        assertFallenBehind(false, 0, "resetting should return to default");
    }

    @Test
    void testChangingPeerAmount() {
        assertFallenBehind(false, 0, "default should be none report fallen behind");

        // node 1 reports fallen behind
        manager.reportFallenBehind(NodeId.of(1));
        assertFallenBehind(false, 1, "one node only reported fallen behind");

        // if the same node reports again, nothing should change
        manager.reportFallenBehind(NodeId.of(1));
        assertFallenBehind(false, 1, "if the same node reports again, nothing should change");

        manager.reportFallenBehind(NodeId.of(2));
        manager.reportFallenBehind(NodeId.of(3));
        manager.reportFallenBehind(NodeId.of(4));
        manager.reportFallenBehind(NodeId.of(5));
        assertFallenBehind(false, 5, "we should still be missing one for fallen behind");

        manager.addRemovePeers(ImmutableSet.of(NodeId.of(22), NodeId.of(23)), Collections.emptySet());

        manager.reportFallenBehind(NodeId.of(6));
        assertFallenBehind(false, 6, "we miss one due to changed size");

        manager.addRemovePeers(Collections.emptySet(), Collections.singleton(NodeId.of(9)));

        assertFallenBehind(true, 6, "we should fall behind due to reduced number of peers");

        manager.reportFallenBehind(NodeId.of(1));
        manager.reportFallenBehind(NodeId.of(2));
        manager.reportFallenBehind(NodeId.of(3));
        manager.reportFallenBehind(NodeId.of(4));
        manager.reportFallenBehind(NodeId.of(6));
        assertFallenBehind(true, 6, "if the same nodes report again, nothing should change");

        manager.reportFallenBehind(NodeId.of(7));
        manager.reportFallenBehind(NodeId.of(8));
        assertFallenBehind(true, 8, "more nodes reported, but the status should be the same");

        manager.resetFallenBehind();
        assertFallenBehind(false, 0, "resetting should return to default");
    }

    private void assertFallenBehind(
            final boolean expectedFallenBehind, final int expectedNumFallenBehind, final String message) {
        assertEquals(expectedFallenBehind, manager.hasFallenBehind(), message);
        assertEquals(expectedNumFallenBehind, manager.numReportedFallenBehind(), message);
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
            final Metrics metrics = new NoOpMetrics();

            this.roster = RandomRosterBuilder.create(random).withSize(41).build();
            this.selfId = NodeId.of(roster.rosterEntries().get(0).nodeId());

            final ReconnectConfig reconnectConfig = configuration.getConfigData(ReconnectConfig.class);

            final List<PeerInfo> peers = Utilities.createPeerInfoList(roster, selfId);

            fallenBehindMonitor =
                    new FallenBehindMonitor(selfId, peers.size(), mock(StatusActionSubmitter.class), reconnectConfig);
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
        test.fallenBehindMonitor.reportFallenBehind(peers.get(0).nodeId());
        test.fallenBehindMonitor.reportFallenBehind(peers.get(1).nodeId());

        // we still dont have enough reports that we have fallen behind, we need more than [fallenBehindThreshold] of
        // the neighbors
        assertFalse(test.fallenBehindMonitor.hasFallenBehind());

        // add more reports
        for (int i = 2; i < 10; i++) {
            test.fallenBehindMonitor.reportFallenBehind(peers.get(i).nodeId());
        }

        // we are still missing 1 report
        assertFalse(test.fallenBehindMonitor.hasFallenBehind());

        // add the report that will go over the [fallenBehindThreshold]
        test.fallenBehindMonitor.reportFallenBehind(peers.get(10).nodeId());

        // we should now say we have fallen behind
        assertTrue(test.fallenBehindMonitor.hasFallenBehind());

        // reset it
        test.fallenBehindMonitor.resetFallenBehind();

        // we should now be back where we started
        assertFalse(test.fallenBehindMonitor.hasFallenBehind());
    }
}
