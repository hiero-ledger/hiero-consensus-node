// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.base.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.event.GossipEvent;
import com.swirlds.base.time.Time;
import com.swirlds.base.utility.Pair;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.hiero.base.concurrent.pool.CachedPoolParallelExecutor;
import org.hiero.base.concurrent.throttle.RateLimiter;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.gossip.config.BroadcastConfig;
import org.hiero.consensus.gossip.config.SyncConfig;
import org.hiero.consensus.gossip.config.SyncConfig_;
import org.hiero.consensus.gossip.config.TrafficShapingConfig;
import org.hiero.consensus.gossip.config.TrafficShapingConfig_;
import org.hiero.consensus.gossip.impl.gossip.Utilities;
import org.hiero.consensus.gossip.impl.gossip.permits.SyncPermitProvider;
import org.hiero.consensus.gossip.impl.gossip.rpc.GossipRpcReceiverHandler;
import org.hiero.consensus.gossip.impl.gossip.rpc.SyncData;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncMetrics;
import org.hiero.consensus.gossip.impl.network.Connection;
import org.hiero.consensus.gossip.impl.network.NetworkMetrics;
import org.hiero.consensus.gossip.impl.network.NetworkProtocolException;
import org.hiero.consensus.gossip.impl.network.PeerInfo;
import org.hiero.consensus.gossip.impl.network.protocol.rpc.RpcPeerProtocol;
import org.hiero.consensus.gossip.impl.test.fixtures.sync.ConnectionFactory;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.roster.test.fixtures.RosterFactory;
import org.hiero.consensus.test.fixtures.Randotron;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link RpcPeerProtocol}, driving two instances against each other over a local piped connection.
 *
 * <p>Covers the original frequent-disconnection scenario plus the per-peer traffic shaping behaviour: a bounded
 * dispatch queue, rejection of oversized message batches, and read throttling when a peer exceeds its byte budget.
 */
public class RpcPeerProtocolTests {

    /** How many forced disconnect cycles to run. */
    private static final int DISCONNECT_CYCLES = 50;

    /**
     * Time between forced disconnections. Must be several times the sum of the artificial delays in
     * {@link StateMachineHandler}, so that a few complete syncs fit between disconnections.
     */
    private static final Duration CYCLE_PERIOD = Duration.ofMillis(100);

    /**
     * If no sync has been initiated for this long at the end of the run, the protocol is wedged. One failure mode is
     * the state "I believe a sync was sent, but cleanup happened and I did not notice".
     */
    private static final Duration NO_SYNC_TOLERANCE = Duration.ofSeconds(1);

    private static final int ROSTER_SIZE = 2;

    /** Batch size larger than {@code RpcPeerProtocol.EVENT_BATCH_SIZE} (512), which is package private. */
    private static final short OVERSIZED_BATCH = Short.MAX_VALUE;

    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final List<Thread> startedThreads = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(true);

    private CachedPoolParallelExecutor executor;

    // pairing state for handing out the two ends of a local connection
    private NodeId alreadyAsked = null;
    private volatile Connection otherConnection = null;
    private volatile Connection lastConnection = null;

    @AfterEach
    void tearDown() throws InterruptedException {
        running.set(false);
        disconnectBoth();
        for (final Thread thread : startedThreads) {
            thread.join(TimeUnit.SECONDS.toMillis(5));
        }
        if (executor != null) {
            executor.stop();
        }
    }

    // -----------------------------------------------------------------------------------------------------------
    // tests
    // -----------------------------------------------------------------------------------------------------------

    @Test
    @Timeout(120)
    void frequentDisconnectionsKeepSyncing() throws Throwable {
        final Harness harness = start(defaultConfig(), Duration.ofMillis(5));

        for (int i = 0; i < DISCONNECT_CYCLES; i++) {
            Thread.sleep(CYCLE_PERIOD.toMillis());
            disconnectBoth();
            rethrowIfFailed();
        }

        stopAndAssertStillSyncing(harness);
    }

    /**
     * With a dispatch queue of one and a deliberately slow handler, the read thread has to block on the queue rather
     * than throw. This is the regression test for switching the read loop from {@code add} to a blocking offer.
     */
    @Test
    @Timeout(120)
    void tinyInputQueueWithSlowDispatchStillSyncs() throws Throwable {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(SyncConfig_.RPC_INPUT_QUEUE_CAPACITY, "1")
                .getOrCreateConfig();

        final Harness harness = start(configuration, Duration.ofMillis(20));

        for (int i = 0; i < DISCONNECT_CYCLES / 2; i++) {
            Thread.sleep(CYCLE_PERIOD.toMillis());
            disconnectBoth();
            rethrowIfFailed();
        }

        stopAndAssertStillSyncing(harness);
    }

    /**
     * A peer declaring a batch larger than we would ever send is a protocol violation: the connection is dropped and
     * an evidence record is produced.
     */
    @Test
    @Timeout(60)
    void oversizedBatchIsRejectedAsMalformed() throws Exception {
        startExecutor();

        final Randotron randotron = Randotron.create();
        final Roster roster = RosterFactory.randomRoster(randotron, ROSTER_SIZE);
        final Configuration configuration = defaultConfig();
        final NoOpMetrics metrics = new NoOpMetrics();

        final RosterEntry selfEntry = roster.rosterEntries().get(0);
        final NodeId selfId = NodeId.of(selfEntry.nodeId());
        final List<PeerInfo> peers = Utilities.createPeerInfoList(roster, selfId);
        final NodeId otherPeer = peers.get(0).nodeId();

        final SyncPermitProvider permits =
                new SyncPermitProvider(configuration, metrics, Time.getCurrent(), ROSTER_SIZE);
        final RpcPeerProtocol protocol =
                newProtocol(configuration, metrics, Time.getCurrent(), peers, selfId, otherPeer, permits, null);
        protocol.setRpcPeerHandler(new NoOpHandler());

        final Pair<Connection, Connection> connections = ConnectionFactory.createLocalConnections(selfId, otherPeer);
        final Connection victim = connections.left();
        final Connection attacker = connections.right();

        // the attacker has to keep draining, otherwise the victim's write thread fills the pipe buffer and blocks
        final Thread drainer = startDaemon("attacker-drain", () -> {
            try {
                while (running.get() && attacker.getDis().read() >= 0) {
                    // discard
                }
            } catch (final IOException ignored) {
                // expected once the connection is closed
            }
        });

        assertThat(permits.acquire()).isTrue();
        final Thread victimThread = startDaemon("victim", () -> {
            try {
                protocol.runProtocol(victim);
            } catch (final Exception e) {
                failure.set(e);
            }
        });

        attacker.getDos().writeShort(OVERSIZED_BATCH);
        attacker.getDos().flush();

        // the violation must take the connection down rather than being logged and ignored
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> !victimThread.isAlive());
        assertThat(handledExceptions).anyMatch(RpcPeerProtocolTests::causedByProtocolViolation);

        running.set(false);
        victim.disconnect();
        attacker.disconnect();
        drainer.join(TimeUnit.SECONDS.toMillis(5));
    }

    /**
     * With enforcement on and a byte budget far below what the exchange needs, reads are paused, but the two nodes
     * still make progress. This is the guard against the shaper wedging an otherwise healthy exchange.
     */
    @Test
    @Timeout(120)
    void enforcingShaperThrottlesReadsButKeepsSyncing() throws Throwable {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(TrafficShapingConfig_.ENFORCE, "true")
                .withValue(TrafficShapingConfig_.PEER_BYTES_PER_SECOND, "100")
                .withValue(TrafficShapingConfig_.PEER_BURST_BYTES, "200")
                .withValue(TrafficShapingConfig_.MAX_READ_DELAY, "20ms")
                .getOrCreateConfig();

        final Harness harness = start(configuration, Duration.ZERO);

        // let a few syncs happen under throttling without any forced disconnections
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .until(() -> harness.syncsInitiated() >= 3 || failure.get() != null);
        rethrowIfFailed();

        running.set(false);
        disconnectBoth();

        for (final SyncMetrics metrics : harness.syncMetrics()) {
            // at least one node must have paused its read thread
            try {
                verify(metrics, atLeastOnce()).rpcReadThrottled(anyLong());
                return;
            } catch (final AssertionError ignored) {
                // try the other node
            }
        }
        throw new AssertionError("expected at least one node to throttle its read thread");
    }

    // -----------------------------------------------------------------------------------------------------------
    // harness
    // -----------------------------------------------------------------------------------------------------------

    /**
     * Two {@link RpcPeerProtocol} instances, each looping over connections to the other, driven by
     * {@link StateMachineHandler}.
     */
    private record Harness(List<SyncMetrics> syncMetrics, AtomicLong lastSyncMillis, AtomicLong syncCount) {
        long syncsInitiated() {
            return syncCount.get();
        }
    }

    private Harness start(@NonNull final Configuration configuration, @NonNull final Duration handlerDelay)
            throws IOException {

        startExecutor();

        final Randotron randotron = Randotron.create();
        final Roster roster = RosterFactory.randomRoster(randotron, ROSTER_SIZE);
        final NoOpMetrics metrics = new NoOpMetrics();
        final Time time = Time.getCurrent();

        final List<SyncMetrics> allSyncMetrics = new ArrayList<>();
        final AtomicLong lastSyncMillis = new AtomicLong(time.currentTimeMillis());
        final AtomicLong syncCount = new AtomicLong();

        for (int i = 0; i < ROSTER_SIZE; i++) {
            final NodeId selfId = NodeId.of(roster.rosterEntries().get(i).nodeId());
            final List<PeerInfo> peers = Utilities.createPeerInfoList(roster, selfId);
            final NodeId otherPeer = peers.get(0).nodeId();

            final SyncPermitProvider permits = new SyncPermitProvider(configuration, metrics, time, ROSTER_SIZE);
            final SyncMetrics syncMetrics = spy(new SyncMetrics(metrics, time, peers));
            allSyncMetrics.add(syncMetrics);

            final RpcPeerProtocol protocol =
                    newProtocol(configuration, metrics, time, peers, selfId, otherPeer, permits, syncMetrics);
            protocol.setRpcPeerHandler(
                    new StateMachineHandler(protocol, time, handlerDelay, lastSyncMillis, syncCount, failure));

            startDaemon("rpc-" + selfId.id(), () -> {
                while (running.get()) {
                    try {
                        if (permits.acquire()) {
                            protocol.runProtocol(connect(selfId, otherPeer));
                        }
                    } catch (final Exception e) {
                        failure.compareAndSet(null, e);
                    }
                }
            });
        }

        return new Harness(allSyncMetrics, lastSyncMillis, syncCount);
    }

    private RpcPeerProtocol newProtocol(
            @NonNull final Configuration configuration,
            @NonNull final NoOpMetrics metrics,
            @NonNull final Time time,
            @NonNull final List<PeerInfo> peers,
            @NonNull final NodeId selfId,
            @NonNull final NodeId otherPeer,
            @NonNull final SyncPermitProvider permits,
            final SyncMetrics providedSyncMetrics) {

        final SyncMetrics syncMetrics =
                providedSyncMetrics != null ? providedSyncMetrics : new SyncMetrics(metrics, time, peers);

        return new RpcPeerProtocol(
                otherPeer,
                executor,
                () -> false,
                () -> PlatformStatus.ACTIVE,
                permits,
                new NetworkMetrics(metrics, selfId, peers),
                time,
                syncMetrics,
                configuration.getConfigData(SyncConfig.class),
                configuration.getConfigData(TrafficShapingConfig.class),
                configuration.getConfigData(BroadcastConfig.class),
                this::handleException);
    }

    private void startExecutor() {
        executor = new CachedPoolParallelExecutor(getStaticThreadManager(), "rpc-peer-protocol-tests");
        executor.start();
    }

    private static Configuration defaultConfig() {
        return new TestConfigBuilder().getOrCreateConfig();
    }

    private void stopAndAssertStillSyncing(@NonNull final Harness harness) throws Throwable {
        running.set(false);
        disconnectBoth();
        Thread.sleep(CYCLE_PERIOD.toMillis());
        rethrowIfFailed();

        final long sinceLastSync =
                Time.getCurrent().currentTimeMillis() - harness.lastSyncMillis().get();
        assertThat(sinceLastSync)
                .as("protocol appears wedged: no sync initiated for %d ms", sinceLastSync)
                .isLessThan(NO_SYNC_TOLERANCE.toMillis());
        assertThat(harness.syncsInitiated()).isPositive();
    }

    private void rethrowIfFailed() throws Throwable {
        final Throwable throwable = failure.get();
        if (throwable != null) {
            throw throwable;
        }
    }

    private synchronized void disconnectBoth() {
        if (lastConnection != null) {
            lastConnection.disconnect();
        }
        if (otherConnection != null) {
            otherConnection.disconnect();
        }
    }

    private Thread startDaemon(@NonNull final String name, @NonNull final Runnable body) {
        final Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        startedThreads.add(thread);
        thread.start();
        return thread;
    }

    /**
     * Hands out the two ends of a local connection pair. Valid only for two nodes: the first caller creates the pair
     * and takes one end, the second caller takes the other.
     */
    private synchronized Connection connect(@NonNull final NodeId selfId, @NonNull final NodeId otherPeer)
            throws IOException {

        if (alreadyAsked == null) {
            final Pair<Connection, Connection> connections =
                    ConnectionFactory.createLocalConnections(selfId, otherPeer);
            alreadyAsked = selfId;
            otherConnection = connections.right();
            lastConnection = connections.left();
            return connections.left();
        }

        if (selfId.equals(alreadyAsked)) {
            throw new IllegalStateException(
                    "Node " + selfId + " asked for a connection twice before the other node did");
        }
        final Connection connection = otherConnection;
        alreadyAsked = null;
        return connection;
    }

    // -----------------------------------------------------------------------------------------------------------
    // exception capture
    // -----------------------------------------------------------------------------------------------------------

    private final List<Exception> handledExceptions = new CopyOnWriteArrayList<>();

    private void handleException(
            @NonNull final Exception e, @NonNull final Connection connection, @NonNull final RateLimiter rateLimiter) {
        handledExceptions.add(e);
        // the handler in production swallows expected network churn; only surface state machine violations
        if (containsStateMachineViolation(e)) {
            failure.compareAndSet(null, e);
        }
    }

    private static boolean containsStateMachineViolation(final Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof IllegalStateException
                    && t.getMessage() != null
                    && t.getMessage().contains("ERROR")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static boolean causedByProtocolViolation(final Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof NetworkProtocolException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------------------------------------------
    // fakes
    // -----------------------------------------------------------------------------------------------------------

    /**
     * Emulates the state machine of the real rpc handler closely enough to drive a full sync round trip, and fails
     * the test if messages arrive out of order. Artificial delays stand in for filtering, serialisation and network
     * time, and are what makes the dispatch thread slower than the read thread.
     */
    private static final class StateMachineHandler implements GossipRpcReceiverHandler {

        private final RpcPeerProtocol protocol;
        private final Time time;
        private final Duration delay;
        private final AtomicLong lastSyncMillis;
        private final AtomicLong syncCount;
        private final AtomicReference<Throwable> failure;

        private boolean receivedTips;
        private boolean receivedSyncData;
        private boolean sentSyncData;

        private StateMachineHandler(
                @NonNull final RpcPeerProtocol protocol,
                @NonNull final Time time,
                @NonNull final Duration delay,
                @NonNull final AtomicLong lastSyncMillis,
                @NonNull final AtomicLong syncCount,
                @NonNull final AtomicReference<Throwable> failure) {
            this.protocol = protocol;
            this.time = time;
            this.delay = delay;
            this.lastSyncMillis = lastSyncMillis;
            this.syncCount = syncCount;
            this.failure = failure;
        }

        @Override
        public boolean checkForPeriodicActions(final boolean wantToExit, final boolean ignoreIncomingEvents) {
            maybeSendSync();
            return true;
        }

        private void maybeSendSync() {
            if (!sentSyncData) {
                // contents do not matter, the remote side is also a test implementation
                protocol.sendSyncData(new SyncData(EventWindow.getGenesisEventWindow(), List.of(), false));
                lastSyncMillis.set(time.currentTimeMillis());
                syncCount.incrementAndGet();
                sentSyncData = true;
            }
        }

        @Override
        public void cleanup() {
            sentSyncData = false;
            receivedSyncData = false;
            receivedTips = false;
        }

        @Override
        public void setCommunicationOverloaded(final boolean overloaded) {
            // no-op
        }

        @Override
        public void receiveSyncData(@NonNull final SyncData syncMessage) {
            maybeSendSync();
            receivedSyncData = true;
            protocol.sendTips(List.of());
        }

        @Override
        public void receiveTips(@NonNull final List<Boolean> tips) {
            if (!receivedSyncData) {
                fail("ERROR: received tips before sync data");
                return;
            }
            receivedTips = true;
            pause();
            protocol.sendEvents(List.of());
            protocol.sendEndOfEvents();
        }

        @Override
        public void receiveEvents(@NonNull final List<GossipEvent> gossipEvents) {
            if (!receivedTips) {
                fail("ERROR: received events before tips");
                return;
            }
            pause();
        }

        @Override
        public void receiveEventsFinished() {
            pause();
            cleanup();
        }

        @Override
        public void receiveBroadcastEvent(@NonNull final GossipEvent gossipEvent) {
            // no-op
        }

        private void fail(@NonNull final String message) {
            final IllegalStateException e = new IllegalStateException(message);
            failure.compareAndSet(null, e);
            throw e;
        }

        private void pause() {
            if (delay.isZero()) {
                return;
            }
            try {
                Thread.sleep(delay.toMillis());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }

    /** Handler that does nothing; used where no exchange is expected. */
    private static final class NoOpHandler implements GossipRpcReceiverHandler {

        @Override
        public boolean checkForPeriodicActions(final boolean wantToExit, final boolean ignoreIncomingEvents) {
            return true;
        }

        @Override
        public void cleanup() {
            // no-op
        }

        @Override
        public void setCommunicationOverloaded(final boolean overloaded) {
            // no-op
        }

        @Override
        public void receiveSyncData(@NonNull final SyncData syncMessage) {
            // no-op
        }

        @Override
        public void receiveTips(@NonNull final List<Boolean> tips) {
            // no-op
        }

        @Override
        public void receiveEvents(@NonNull final List<GossipEvent> gossipEvents) {
            // no-op
        }

        @Override
        public void receiveEventsFinished() {
            // no-op
        }

        @Override
        public void receiveBroadcastEvent(@NonNull final GossipEvent gossipEvent) {
            // no-op
        }
    }
}
