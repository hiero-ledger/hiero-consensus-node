// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig_;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.gossip.FallenBehindManagerImpl;
import com.swirlds.platform.gossip.sync.SyncManagerImpl;
import com.swirlds.platform.network.PeerInfo;
import com.swirlds.platform.network.topology.NetworkTopology;
import com.swirlds.platform.network.topology.StaticTopology;
import com.swirlds.platform.pool.TransactionPoolNexus;
import com.swirlds.platform.system.status.StatusActionSubmitter;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

// Tests utilize static Settings configuration and must not be run in parallel
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SyncManagerTest {

    /**
     * A helper class that contains dummy data to feed into SyncManager lambdas.
     */
    private static class SyncManagerTestData {
        public Roster roster;
        public NodeId selfId;
        public TransactionPoolNexus transactionPoolNexus;
        public SyncManagerImpl syncManager;
        public Configuration configuration;

        public SyncManagerTestData() {
            final Random random = getRandomPrintSeed();
            final PlatformContext platformContext =
                    TestPlatformContextBuilder.create().build();

            transactionPoolNexus = spy(new TransactionPoolNexus(platformContext));

            this.roster = RandomRosterBuilder.create(random).withSize(41).build();
            this.selfId = NodeId.of(roster.rosterEntries().get(0).nodeId());

            configuration = new TestConfigBuilder()
                    .withValue(ReconnectConfig_.FALLEN_BEHIND_THRESHOLD, "0.25")
                    .getOrCreateConfig();
            final ReconnectConfig reconnectConfig = configuration.getConfigData(ReconnectConfig.class);

            final List<PeerInfo> peers = Utilities.createPeerInfoList(roster, selfId);
            final NetworkTopology topology = new StaticTopology(peers, selfId);

            syncManager = new SyncManagerImpl(
                    platformContext,
                    new FallenBehindManagerImpl(
                            selfId, peers.size(), mock(StatusActionSubmitter.class), reconnectConfig));
        }
    }

    /**
     * Verify that SyncManager's core functionality is working with basic input.
     */
    @Test
    @Order(0)
    void basicTest() {
        final SyncManagerTestData test = new SyncManagerTestData();

        final List<PeerInfo> peers = Utilities.createPeerInfoList(test.roster, test.selfId);

        // we should not think we have fallen behind initially
        assertFalse(test.syncManager.hasFallenBehind());

        // neighbors 0 and 1 report fallen behind
        test.syncManager.reportFallenBehind(peers.get(0).nodeId());
        test.syncManager.reportFallenBehind(peers.get(1).nodeId());

        // we still dont have enough reports that we have fallen behind, we need more than [fallenBehindThreshold] of
        // the neighbors
        assertFalse(test.syncManager.hasFallenBehind());

        // add more reports
        for (int i = 2; i < 10; i++) {
            test.syncManager.reportFallenBehind(peers.get(i).nodeId());
        }

        // we are still missing 1 report
        assertFalse(test.syncManager.hasFallenBehind());

        // add the report that will go over the [fallenBehindThreshold]
        test.syncManager.reportFallenBehind(peers.get(10).nodeId());

        // we should now say we have fallen behind
        assertTrue(test.syncManager.hasFallenBehind());

        // reset it
        test.syncManager.resetFallenBehind();

        // we should now be back where we started
        assertFalse(test.syncManager.hasFallenBehind());
    }
}
