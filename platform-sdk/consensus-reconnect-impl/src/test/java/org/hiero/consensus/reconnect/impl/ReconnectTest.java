// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.reconnect.impl;

import static com.swirlds.state.test.fixtures.merkle.VirtualMapStateTestUtils.createTestState;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.base.time.Time;
import com.swirlds.common.test.fixtures.merkle.util.PairedStreams;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.platform.metrics.ReconnectMetrics;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import com.swirlds.platform.test.fixtures.state.TestStateUtils;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.StateLifecycleManagerImpl;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.gossip.impl.network.Connection;
import org.hiero.consensus.gossip.impl.network.SocketConnection;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.test.fixtures.RandomRosterBuilder;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.SignedState;
import org.hiero.consensus.test.fixtures.WeightGenerators;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Originally this class used {@link java.io.PipedInputStream} and {@link java.io.PipedOutputStream}, but the reconnect
 * methods use two threads to write data, and {@link java.io.PipedOutputStream} keeps a reference to the original thread
 * that started writing data (which is in the reconnect-phase). Then, we send signatures through the current thread
 * (which is different from the first thread that started sending data). At this point,
 * {@link java.io.PipedOutputStream} checks if the first thread is alive, and if not, it will throw an
 * {@link IOException} with the message {@code write end dead}. This is a non-deterministic behavior, but usually
 * running the test 15 times would make the test fail.
 */
final class ReconnectTest {

    private static final Duration RECONNECT_SOCKET_TIMEOUT = Duration.of(1_000, ChronoUnit.MILLIS);

    // This test uses a threading pattern that is incompatible with gzip compression.
    private final Configuration configuration =
            new TestConfigBuilder().withValue("socket.gzipCompression", false).getOrCreateConfig();

    @BeforeAll
    static void setUp() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds.common");
        registry.registerConstructables("com.swirlds.platform.state");
        registry.registerConstructables("com.swirlds.platform.state.signed");
        registry.registerConstructables("com.swirlds.platform.system");
        registry.registerConstructables("com.swirlds.state.merkle");
    }

    @AfterAll
    static void tearDown() {
        RandomSignedStateGenerator.releaseAllBuiltSignedStates();
        MerkleDbTestUtils.assertAllDatabasesClosed();
    }

    @Test
    @DisplayName("Successfully reconnects multiple times and stats are updated")
    void statsTrackSuccessfulReconnect() throws IOException, InterruptedException {
        final int numberOfReconnects = 11;

        final ReconnectMetrics reconnectMetrics = mock(ReconnectMetrics.class);

        for (int index = 1; index <= numberOfReconnects; index++) {
            executeReconnect(reconnectMetrics);
            verify(reconnectMetrics, times(index)).incrementReceiverStartTimes();
            verify(reconnectMetrics, times(index)).incrementSenderStartTimes();
            verify(reconnectMetrics, times(index)).incrementReceiverEndTimes();
            verify(reconnectMetrics, times(index)).incrementSenderEndTimes();
        }
    }

    private void executeReconnect(final ReconnectMetrics reconnectMetrics) throws InterruptedException, IOException {

        final long weightPerNode = 100L;
        final int numNodes = 4;
        final List<NodeId> nodeIds =
                IntStream.range(0, numNodes).mapToObj(NodeId::of).toList();
        final Random random = RandomUtils.getRandomPrintSeed();

        final Roster roster = RandomRosterBuilder.create(random)
                .withSize(numNodes)
                .withWeightGenerator((l, i) -> WeightGenerators.balancedNodeWeights(numNodes, weightPerNode * numNodes))
                .build();

        final VirtualMapState stateCopy;
        final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager =
                new StateLifecycleManagerImpl(new NoOpMetrics(), new FakeTime(), configuration);
        try (final PairedStreams pairedStreams = new PairedStreams()) {
            stateLifecycleManager.initWithState(createTestState());
            final SignedState signedState = new RandomSignedStateGenerator()
                    .setRoster(roster)
                    .setSigningNodeIds(nodeIds)
                    .setState(stateLifecycleManager.getMutableState())
                    .build();
            stateCopy = stateLifecycleManager.copyMutableState();
            // hash the underlying VM
            signedState.getState().getRoot().getHash();

            final ReconnectStateLearner receiver = buildReceiver(
                    stateCopy,
                    new DummyConnection(
                            configuration, pairedStreams.getLearnerInput(), pairedStreams.getLearnerOutput()),
                    reconnectMetrics,
                    stateLifecycleManager);

            final Thread thread = new Thread(() -> {
                try {
                    signedState.reserve("test");
                    final ReconnectStateTeacher sender = buildSender(
                            new DummyConnection(
                                    configuration, pairedStreams.getTeacherInput(), pairedStreams.getTeacherOutput()),
                            signedState,
                            reconnectMetrics);
                    sender.execute();
                } catch (final IOException ex) {
                    ex.printStackTrace();
                }
            });

            thread.start();
            final ReservedSignedState receivedState = receiver.execute();
            receivedState.get().getState().release();
            thread.join();
        } finally {
            TestStateUtils.destroyStateLifecycleManager(stateLifecycleManager);
        }
    }

    private ReconnectStateTeacher buildSender(
            final SocketConnection connection, final SignedState signedState, final ReconnectMetrics reconnectMetrics)
            throws IOException {

        final NodeId selfId = NodeId.of(0);
        final NodeId otherId = NodeId.of(3);
        final long lastRoundReceived = 100;
        return new ReconnectStateTeacher(
                configuration,
                Time.getCurrent(),
                getStaticThreadManager(),
                connection,
                RECONNECT_SOCKET_TIMEOUT,
                selfId,
                otherId,
                lastRoundReceived,
                signedState,
                reconnectMetrics);
    }

    private ReconnectStateLearner buildReceiver(
            final VirtualMapState state,
            final Connection connection,
            final ReconnectMetrics reconnectMetrics,
            final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager) {

        return new ReconnectStateLearner(
                configuration,
                new NoOpMetrics(),
                getStaticThreadManager(),
                connection,
                state,
                RECONNECT_SOCKET_TIMEOUT,
                reconnectMetrics,
                stateLifecycleManager);
    }
}
