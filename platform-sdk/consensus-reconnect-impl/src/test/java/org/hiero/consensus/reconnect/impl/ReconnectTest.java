// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.reconnect.impl;

import static org.hiero.base.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.merkle.VirtualMapStateLifecycleManager;
import com.swirlds.state.test.fixtures.merkle.TestStateUtils;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.test.fixtures.sync.PairedStreams;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.file.FileSystemManager;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.constructable.ConstructableRegistration;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.gossip.impl.network.Connection;
import org.hiero.consensus.gossip.impl.network.SocketConnection;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.test.fixtures.RosterFactory;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.SignedState;
import org.hiero.consensus.state.test.fixtures.RandomSignedStateGenerator;
import org.hiero.consensus.test.fixtures.WeightGenerators;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    /** How long the teacher waits for the learner to start copying its data source. */
    private static final Duration COPY_TIMEOUT = Duration.ofSeconds(30);

    /** Part of the name of every MerkleDB data source directory, see {@code MerkleDbDataSourceBuilder}. */
    private static final String MERKLEDB_DIRECTORY_TAG = "-merkledb-";

    // This test uses a threading pattern that is incompatible with gzip compression.
    private final Configuration configuration =
            new TestConfigBuilder().withValue("socket.gzipCompression", false).getOrCreateConfig();

    /** Same as {@link #configuration}, with the learner copying its state while it waits for the signatures. */
    private final Configuration earlyCopyConfiguration = new TestConfigBuilder()
            .withValue("socket.gzipCompression", false)
            .withValue("reconnect.copyLearnerStateEarly", true)
            .getOrCreateConfig();

    @BeforeAll
    static void setUp() throws ConstructableRegistryException {
        ConstructableRegistration.registerSyncConstructables();
    }

    @AfterEach
    void tearDown() {
        RandomSignedStateGenerator.releaseAllBuiltSignedStates();
    }

    @Test
    @DisplayName("Successfully reconnects multiple times and stats are updated")
    void statsTrackSuccessfulReconnect(final @TempDir Path tempDir) throws IOException, InterruptedException {
        final int numberOfReconnects = 11;

        final ReconnectMetrics reconnectMetrics = mock(ReconnectMetrics.class);

        for (int index = 1; index <= numberOfReconnects; index++) {
            // Use a different data dir for every reconnect attempt
            final FileSystemManager fileSystemManager = new FileSystemManager(tempDir.resolve("" + index));
            executeReconnect(fileSystemManager, reconnectMetrics);
            verify(reconnectMetrics, times(index)).incrementReceiverStartTimes();
            verify(reconnectMetrics, times(index)).incrementSenderStartTimes();
            verify(reconnectMetrics, times(index)).incrementReceiverEndTimes();
            verify(reconnectMetrics, times(index)).incrementSenderEndTimes();
        }
    }

    @Test
    @DisplayName("Learner copies its data source while it waits for the teacher's signatures")
    void learnerCopiesDataSourceWhileWaitingForSignatures(final @TempDir Path tempDir)
            throws IOException, InterruptedException {
        final FileSystemManager fileSystemManager = new FileSystemManager(tempDir);
        // The teacher sends nothing until the learner's copy exists. If the learner copied its data source
        // only after receiving the signatures, the teacher would give up and the reconnect would fail
        executeReconnect(fileSystemManager, mock(ReconnectMetrics.class), earlyCopyConfiguration, dataSourcesBefore -> {
            final long deadline = System.currentTimeMillis() + COPY_TIMEOUT.toMillis();
            while (countDataSources(fileSystemManager) <= dataSourcesBefore) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IOException("The learner didn't copy its data source before receiving signatures");
                }
                Thread.sleep(10);
            }
        });
    }

    @Test
    @DisplayName("Learner closes its data source copy if receiving the signatures fails")
    void learnerClosesDataSourceCopyIfSignaturesFail(final @TempDir Path tempDir)
            throws IOException, InterruptedException {
        final FileSystemManager fileSystemManager = new FileSystemManager(tempDir);
        final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager =
                new VirtualMapStateLifecycleManager(
                        new NoOpMetrics(), new FakeTime(), configuration, fileSystemManager);
        final VirtualMap originalMap = stateLifecycleManager.getMutableState().getRoot();
        try (final PairedStreams pairedStreams = new PairedStreams()) {
            buildSignedState(stateLifecycleManager);
            final VirtualMapState stateCopy = stateLifecycleManager.copyMutableState();
            final ReconnectStateLearner receiver = buildReceiver(
                    earlyCopyConfiguration,
                    stateCopy,
                    new DummyConnection(
                            configuration, pairedStreams.getLearnerInput(), pairedStreams.getLearnerOutput()),
                    mock(ReconnectMetrics.class),
                    stateLifecycleManager);
            final long dataSourcesBefore = countDataSources(fileSystemManager);

            // The teacher disconnects instead of sending the signatures
            pairedStreams.disconnectTeacher();
            assertThrows(RuntimeException.class, receiver::execute);

            assertEquals(
                    dataSourcesBefore,
                    countDataSources(fileSystemManager),
                    "the learner's data source copy should be closed");
        } finally {
            destroyStates(stateLifecycleManager, originalMap);
        }
    }

    private void executeReconnect(final FileSystemManager fileSystemManager, final ReconnectMetrics reconnectMetrics)
            throws InterruptedException, IOException {
        executeReconnect(fileSystemManager, reconnectMetrics, configuration, dataSourcesBefore -> {});
    }

    /**
     * Reconnects a learner to a teacher with the same state.
     *
     * @param fileSystemManager the file system manager for the states
     * @param reconnectMetrics the reconnect metrics
     * @param receiverConfiguration the learner's configuration
     * @param beforeTeacherSends called on the teacher's thread before the teacher sends anything, with the number of
     *     data sources before the learner started
     */
    private void executeReconnect(
            final FileSystemManager fileSystemManager,
            final ReconnectMetrics reconnectMetrics,
            final Configuration receiverConfiguration,
            final BeforeTeacherSends beforeTeacherSends)
            throws InterruptedException, IOException {
        final VirtualMapState stateCopy;
        final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager =
                new VirtualMapStateLifecycleManager(
                        new NoOpMetrics(), new FakeTime(), configuration, fileSystemManager);
        final VirtualMap originalMap = stateLifecycleManager.getMutableState().getRoot();
        try (final PairedStreams pairedStreams = new PairedStreams()) {
            final SignedState signedState = buildSignedState(stateLifecycleManager);
            stateCopy = stateLifecycleManager.copyMutableState();
            // hash the underlying VM
            signedState.getState().getRoot().getHash();

            final ReconnectStateLearner receiver = buildReceiver(
                    receiverConfiguration,
                    stateCopy,
                    new DummyConnection(
                            configuration, pairedStreams.getLearnerInput(), pairedStreams.getLearnerOutput()),
                    reconnectMetrics,
                    stateLifecycleManager);
            final long dataSourcesBefore = countDataSources(fileSystemManager);

            final Thread thread = new Thread(() -> {
                try {
                    signedState.reserve("test");
                    beforeTeacherSends.run(dataSourcesBefore);
                    final ReconnectStateTeacher sender = buildSender(
                            new DummyConnection(
                                    configuration, pairedStreams.getTeacherInput(), pairedStreams.getTeacherOutput()),
                            signedState,
                            reconnectMetrics);
                    sender.execute();
                } catch (final IOException | InterruptedException ex) {
                    ex.printStackTrace();
                    // Unblock the learner, so that it fails instead of waiting for the teacher
                    pairedStreams.disconnectTeacher();
                }
            });

            thread.start();
            final ReservedSignedState receivedState = receiver.execute();
            receivedState.get().getState().release();
            thread.join();
            assertTrue(
                    receivedState.get().getState().getRoot().waitUntilFamilyDestroyed(Duration.ofSeconds(3)),
                    "reconnect state is not destroyed within the timeout");
        } finally {
            destroyStates(stateLifecycleManager, originalMap);
        }
    }

    /**
     * Releases all states and waits until their data sources are closed. On Windows, files that are still open
     * can't be deleted, and the test's temporary directory is deleted right after the test.
     *
     * @param stateLifecycleManager the state lifecycle manager to destroy
     * @param originalMap any map of the state family
     */
    private static void destroyStates(
            final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager,
            final VirtualMap originalMap)
            throws InterruptedException {
        TestStateUtils.destroyStateLifecycleManager(stateLifecycleManager);
        RandomSignedStateGenerator.releaseAllBuiltSignedStates();
        originalMap.waitUntilFamilyDestroyed(Duration.ofSeconds(10));
    }

    /** Called on the teacher's thread before the teacher sends anything. */
    @FunctionalInterface
    private interface BeforeTeacherSends {
        void run(long dataSourcesBefore) throws IOException, InterruptedException;
    }

    private SignedState buildSignedState(
            final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager) {
        final long weightPerNode = 100L;
        final int numNodes = 4;
        final List<NodeId> nodeIds =
                IntStream.range(0, numNodes).mapToObj(NodeId::of).toList();
        final Random random = RandomUtils.getRandomPrintSeed();

        final Roster roster = RosterFactory.randomRoster(
                random, numNodes, (l, i) -> WeightGenerators.balancedNodeWeights(numNodes, weightPerNode * numNodes));

        return new RandomSignedStateGenerator()
                .setRoster(roster)
                .setSigningNodeIds(nodeIds)
                .setState(stateLifecycleManager.getMutableState())
                .build();
    }

    /**
     * Counts MerkleDB data source directories, including data source copies and their snapshots, in the temporary
     * directory of the file system manager.
     */
    private static long countDataSources(final FileSystemManager fileSystemManager) throws IOException {
        try (final Stream<Path> paths = Files.list(fileSystemManager.getTempPath())) {
            return paths.filter(path -> path.getFileName().toString().contains(MERKLEDB_DIRECTORY_TAG))
                    .count();
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
            final Configuration receiverConfiguration,
            final VirtualMapState state,
            final Connection connection,
            final ReconnectMetrics reconnectMetrics,
            final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager) {

        return new ReconnectStateLearner(
                receiverConfiguration,
                new NoOpMetrics(),
                getStaticThreadManager(),
                connection,
                state,
                RECONNECT_SOCKET_TIMEOUT,
                reconnectMetrics,
                stateLifecycleManager);
    }
}
