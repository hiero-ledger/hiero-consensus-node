// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static com.swirlds.common.test.fixtures.RandomUtils.getRandomPrintSeed;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.base.utility.Pair;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.test.fixtures.RandomUtils;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.common.test.fixtures.merkle.util.PairedStreams;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.merkledb.MerkleDb;
import com.swirlds.platform.metrics.ReconnectMetrics;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.SocketConnection;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.state.signed.SignedStateValidator;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.test.fixtures.state.FakeConsensusStateEventHandler;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade;
import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import org.hiero.consensus.model.node.NodeId;
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

    private final PlatformContext platformContext =
            TestPlatformContextBuilder.create().build();

    @BeforeAll
    static void setUp() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds.common");
        registry.registerConstructables("com.swirlds.platform.state");
        registry.registerConstructables("com.swirlds.platform.state.signed");
        registry.registerConstructables("com.swirlds.platform.system");
        registry.registerConstructables("com.swirlds.state.merkle");
        FakeConsensusStateEventHandler.registerMerkleStateRootClassIds();
    }

    @AfterAll
    static void tearDown() {
        RandomSignedStateGenerator.releaseAllBuiltSignedStates();
    }

    @Test
    @DisplayName("Successfully reconnects multiple times and stats are updated")
    void statsTrackSuccessfulReconnect() throws IOException, InterruptedException {
        final int numberOfReconnects = 11;

        final ReconnectMetrics reconnectMetrics = mock(ReconnectMetrics.class);

        for (int index = 1; index <= numberOfReconnects; index++) {
            MerkleDb.resetDefaultInstancePath();

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

        try (final PairedStreams pairedStreams = new PairedStreams()) {
            final Pair<SignedState, TestPlatformStateFacade> signedStateFacadePair = new RandomSignedStateGenerator()
                    .setRoster(roster)
                    .setSigningNodeIds(nodeIds)
                    .buildWithFacade();
            final SignedState signedState = signedStateFacadePair.left();
            final PlatformStateFacade platformStateFacade = signedStateFacadePair.right();

            final MerkleCryptography cryptography = TestMerkleCryptoFactory.getInstance();
            cryptography.digestSync(signedState.getState().getRoot());

            final ReconnectLearner receiver = buildReceiver(
                    signedState.getState(),
                    new DummyConnection(
                            platformContext, pairedStreams.getLearnerInput(), pairedStreams.getLearnerOutput()),
                    reconnectMetrics,
                    platformStateFacade);

            final Thread thread = new Thread(() -> {
                try {
                    signedState.reserve("test");
                    final ReconnectTeacher sender = buildSender(
                            new DummyConnection(
                                    platformContext, pairedStreams.getTeacherInput(), pairedStreams.getTeacherOutput()),
                            reconnectMetrics,
                            platformStateFacade);
                    sender.execute(signedState);
                } catch (final IOException ex) {
                    ex.printStackTrace();
                }
            });

            thread.start();
            receiver.execute(mock(SignedStateValidator.class));
            thread.join();
        }
    }

    private ReconnectTeacher buildSender(
            final SocketConnection connection,
            final ReconnectMetrics reconnectMetrics,
            final PlatformStateFacade platformStateFacade)
            throws IOException {

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        final NodeId selfId = NodeId.of(0);
        final NodeId otherId = NodeId.of(3);
        final long lastRoundReceived = 100;
        return new ReconnectTeacher(
                platformContext,
                Time.getCurrent(),
                getStaticThreadManager(),
                connection,
                RECONNECT_SOCKET_TIMEOUT,
                selfId,
                otherId,
                lastRoundReceived,
                reconnectMetrics,
                platformStateFacade);
    }

    private ReconnectLearner buildReceiver(
            final MerkleNodeState state,
            final Connection connection,
            final ReconnectMetrics reconnectMetrics,
            final PlatformStateFacade platformStateFacade) {
        final Roster roster =
                RandomRosterBuilder.create(getRandomPrintSeed()).withSize(5).build();

        return new ReconnectLearner(
                TestPlatformContextBuilder.create().build(),
                getStaticThreadManager(),
                connection,
                roster,
                state,
                RECONNECT_SOCKET_TIMEOUT,
                reconnectMetrics,
                platformStateFacade);
    }
}
