// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.sync;

import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.event.GossipEvent;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.Utilities;
import com.swirlds.platform.gossip.permits.SyncPermitProvider;
import com.swirlds.platform.gossip.rpc.GossipRpcReceiverHandler;
import com.swirlds.platform.gossip.rpc.SyncData;
import com.swirlds.platform.gossip.sync.config.SyncConfig;
import com.swirlds.platform.metrics.SyncMetrics;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.NetworkMetrics;
import com.swirlds.platform.network.protocol.rpc.RpcPeerProtocol;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.test.fixtures.sync.ConnectionFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hiero.consensus.concurrent.pool.CachedPoolParallelExecutor;
import org.hiero.consensus.concurrent.pool.ParallelExecutor;
import org.hiero.consensus.concurrent.utility.throttle.RateLimiter;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.junit.jupiter.api.Test;

public class RpcPeerProtocolTests {

    private long lastSentSync;
    private Throwable foundException;

    @Test
    public void testPeerProtocolFrequentDisconnections() throws Throwable {

        final Randotron randotron = Randotron.create();

        final AtomicBoolean running = new AtomicBoolean(true);

        final ParallelExecutor executor = new CachedPoolParallelExecutor(getStaticThreadManager(), "a name");
        executor.start();

        final Roster roster = RandomRosterBuilder.create(randotron).withSize(2).build();

        final var metrics = new NoOpMetrics();

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        var syncConfig = platformContext.getConfiguration().getConfigData(SyncConfig.class);

        final Time time = Time.getCurrent();
        final var permitProvider = new SyncPermitProvider(
                metrics, time, syncConfig, roster.rosterEntries().size());

        for (int i = 0; i < 2; i++) {

            final var selfEntry = roster.rosterEntries().get(i);
            final var selfId = NodeId.of(selfEntry.nodeId());

            final var peers = Utilities.createPeerInfoList(roster, selfId);
            // other peer will be the only one in list of other peers
            final var otherPeer = peers.get(0).nodeId();

            final RpcPeerProtocol peerProtocol = new RpcPeerProtocol(
                    selfId,
                    executor,
                    () -> false,
                    () -> PlatformStatus.ACTIVE,
                    permitProvider,
                    new NetworkMetrics(metrics, selfId, peers),
                    Time.getCurrent(),
                    new SyncMetrics(metrics, Time.getCurrent(), peers),
                    syncConfig,
                    this::handleException);

            peerProtocol.setRpcPeerHandler(new GossipRpcReceiverHandler() {

                private boolean receivedEvents;
                private boolean receivedTips;
                private boolean receivedSyncData;
                private boolean sentTips;
                private boolean sentSyncData = false;

                @Override
                public boolean checkForPeriodicActions(final boolean wantToExit, final boolean ignoreIncomingEvents) {
                    maybeSendSync();
                    return true;
                }

                private void maybeSendSync() {
                    if (!sentSyncData) {
                        peerProtocol.sendSyncData(new SyncData(EventWindow.getGenesisEventWindow(), List.of(), false));
                        lastSentSync = time.currentTimeMillis();
                        sentSyncData = true;
                    }
                }

                @Override
                public void cleanup() {
                    sentSyncData = false;
                    sentTips = false;
                    receivedSyncData = false;
                    receivedTips = false;
                    receivedEvents = false;
                }

                @Override
                public void receiveSyncData(@NonNull final SyncData syncMessage) {
                    maybeSendSync();
                    receivedSyncData = true;
                    peerProtocol.sendTips(List.of());
                    sentTips = true;
                }

                @Override
                public void receiveTips(@NonNull final List<Boolean> tips) {
                    if (!receivedSyncData) {
                        throw new IllegalStateException("ERROR: Received tips before sync data");
                    }
                    receivedTips = true;
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    peerProtocol.sendEvents(List.of());
                    peerProtocol.sendEndOfEvents();
                }

                @Override
                public void receiveEvents(@NonNull final List<GossipEvent> gossipEvents) {
                    if (!receivedTips) {
                        throw new IllegalStateException("ERROR: Received events before tips");
                    }
                    receivedEvents = true;
                    try {
                        Thread.sleep(6);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public void receiveEventsFinished() {
                    try {
                        Thread.sleep(7);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    cleanup();
                }
            });

            new Thread(() -> {
                        while (running.get()) {
                            try {
                                if (permitProvider.acquire()) {
                                    Connection connection = connect(selfId, otherPeer);
                                    peerProtocol.runProtocol(connection);
                                }
                            } catch (Exception exc) {
                                foundException = exc;
                            }
                        }
                    })
                    .start();
        }

        for (int i = 0; i < 100; i++) {
            Thread.sleep(100);
            if (lastConnection != null) {
                lastConnection.disconnect();
            }
            if (foundException != null) {
                throw foundException;
            }
        }

        running.set(false);
        lastConnection.disconnect();
        Thread.sleep(100);
        final long noSyncTime = time.currentTimeMillis() - lastSentSync;
        if (noSyncTime > 1000) {
            throw new IllegalStateException("Has not synced in " + noSyncTime + " ms");
        }
    }

    private void handleException(Exception e, Connection connection, RateLimiter rateLimiter) {
        if (e.getCause().toString().contains("ERROR")) {
            foundException = e.getCause();
        }
    }

    NodeId alreadyAsked = null;
    Connection otherConnection = null;
    Connection lastConnection = null;

    /**
     * Create the pair of local connections between the nodes and returns them depending on who is asking. Valid only
     * for two nodes.
     *
     * @param selfId    who is asking for connection
     * @param otherPeer to whom connection should be made
     * @return connection for given node
     * @throws IOException should never happen
     */
    private synchronized Connection connect(final NodeId selfId, final NodeId otherPeer) throws IOException {

        if (alreadyAsked == null) {
            final var connections = ConnectionFactory.createLocalConnections(selfId, otherPeer);
            alreadyAsked = selfId;
            otherConnection = connections.right();
            lastConnection = connections.left();
            return connections.left();
        }

        if (selfId.equals(alreadyAsked)) {
            throw new IllegalArgumentException(
                    "Node " + selfId + "asked about connection twice before other one tried to do so");
        }
        final var connection = otherConnection;
        alreadyAsked = null;
        otherConnection = null;
        return connection;
    }
}
