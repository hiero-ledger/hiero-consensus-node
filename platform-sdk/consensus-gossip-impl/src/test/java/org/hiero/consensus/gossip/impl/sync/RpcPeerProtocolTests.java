// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.sync;

import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.junit.jupiter.api.Assertions.fail;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.event.GossipEvent;
import com.swirlds.base.time.Time;
import com.swirlds.base.utility.Pair;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hiero.consensus.concurrent.pool.CachedPoolParallelExecutor;
import org.hiero.consensus.concurrent.pool.ParallelExecutor;
import org.hiero.consensus.concurrent.utility.throttle.RateLimiter;
import org.hiero.consensus.gossip.config.SyncConfig;
import org.hiero.consensus.gossip.impl.gossip.Utilities;
import org.hiero.consensus.gossip.impl.gossip.permits.SyncPermitProvider;
import org.hiero.consensus.gossip.impl.gossip.rpc.GossipRpcReceiverHandler;
import org.hiero.consensus.gossip.impl.gossip.rpc.SyncData;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncMetrics;
import org.hiero.consensus.gossip.impl.network.Connection;
import org.hiero.consensus.gossip.impl.network.NetworkMetrics;
import org.hiero.consensus.gossip.impl.network.PeerInfo;
import org.hiero.consensus.gossip.impl.network.protocol.rpc.RpcPeerProtocol;
import org.hiero.consensus.gossip.impl.test.fixtures.sync.ConnectionFactory;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.roster.test.fixtures.RandomRosterBuilder;
import org.hiero.consensus.test.fixtures.Randotron;
import org.junit.jupiter.api.Test;

public class RpcPeerProtocolTests {

    private long lastSentSync;
    private Throwable foundException;
    private NodeId alreadyAsked = null;
    private Connection otherConnection = null;
    private Connection lastConnection = null;

    @Test
    public void testPeerProtocolFrequentDisconnections() throws Throwable {

        final Randotron randotron = Randotron.create();

        final AtomicBoolean running = new AtomicBoolean(true);

        final ParallelExecutor executor = new CachedPoolParallelExecutor(getStaticThreadManager(), "a name");
        executor.start();

        final Roster roster = RandomRosterBuilder.create(randotron).withSize(2).build();

        final NoOpMetrics metrics = new NoOpMetrics();

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        final SyncConfig syncConfig = platformContext.getConfiguration().getConfigData(SyncConfig.class);

        final Time time = Time.getCurrent();
        final SyncPermitProvider permitProvider = new SyncPermitProvider(
                metrics, time, syncConfig, roster.rosterEntries().size());

        for (int i = 0; i < 2; i++) {

            final RosterEntry selfEntry = roster.rosterEntries().get(i);
            final NodeId selfId = NodeId.of(selfEntry.nodeId());

            final List<PeerInfo> peers = Utilities.createPeerInfoList(roster, selfId);
            // other peer will be the only one in list of other peers
            final NodeId otherPeer = peers.get(0).nodeId();

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

                // we need to emulate the state machine of actual rpc handler

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
                        // we don't care what contents we are sending, remote side is also a test implementation
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
                        // pretend we are doing some processing which takes time (filtering events, serializing them and
                        // sending them over network etc)
                        Thread.sleep(5);
                    } catch (final InterruptedException e) {
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
                        // pretend we are doing some processing which takes time (deserializing events, receiving them
                        // over network etc)
                        Thread.sleep(6);
                    } catch (final InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public void receiveEventsFinished() {
                    try {
                        // again, wait for emulating internal processing and network delays
                        Thread.sleep(7);
                    } catch (final InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    cleanup();
                }
            });

            new Thread(() -> {
                        while (running.get()) {
                            try {
                                if (permitProvider.acquire()) {
                                    final Connection connection = connect(selfId, otherPeer);
                                    peerProtocol.runProtocol(connection);
                                }
                            } catch (final Exception exc) {
                                foundException = exc;
                            }
                        }
                    })
                    .start();
        }

        for (int i = 0; i < 100; i++) {
            // force disconnections multiple times per second;
            // this time should be few times bigger than sum of all the delays in the methods above, to allow few syncs
            // to happen between disconnections
            Thread.sleep(100);
            if (lastConnection != null) {
                lastConnection.disconnect();
            }
            if (foundException != null) {
                throw foundException;
            }
        }

        running.set(false);
        if (lastConnection != null) {
            lastConnection.disconnect();
        }
        Thread.sleep(100);
        final long noSyncTime = time.currentTimeMillis() - lastSentSync;
        if (noSyncTime > 1000) {
            // one of the possible failure scenarios is that we are stuck in illegal state of
            // "I think sync was sent to other party, but cleanup has happened and I didn't notice"
            // we want to fail the test in such case
            fail("Has not synced in " + noSyncTime + " ms");
        }
    }

    private void handleException(
            @NonNull final Exception e, @NonNull final Connection connection, @NonNull final RateLimiter rateLimiter) {
        if (e.getCause().toString().contains("ERROR")) {
            foundException = e.getCause();
        }
    }

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
            final Pair<Connection, Connection> connections =
                    ConnectionFactory.createLocalConnections(selfId, otherPeer);
            alreadyAsked = selfId;
            otherConnection = connections.right();
            lastConnection = connections.left();
            return connections.left();
        }

        if (selfId.equals(alreadyAsked)) {
            throw new IllegalArgumentException(
                    "Node " + selfId + "asked about connection twice before other one tried to do so");
        }
        final Connection connection = otherConnection;
        alreadyAsked = null;
        otherConnection = null;
        return connection;
    }
}
