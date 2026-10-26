// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;
import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.fixtures.assertions.StatusProgressionStep.target;

import com.hedera.hapi.node.base.SemanticVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.stream.Stream;
import org.hiero.base.crypto.KeyGeneratingException;
import org.hiero.base.crypto.SigningSchema;
import org.hiero.consensus.event.creator.config.EventCreationConfig_;
import org.hiero.consensus.fakes.crypto.DetRandomProvider;
import org.hiero.consensus.fakes.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.model.event.EventHashFactory;
import org.hiero.consensus.state.saved.SavedStateMetadata;
import org.hiero.otter.fixtures.Capability;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.app.OtterApp;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.specs.OtterSpecs;
import org.hiero.otter.fixtures.util.OtterSavedStateUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class StartFromStateTest {

    private static final Path SAVED_STATE_PATH = Path.of("previous-version-state");

    /**
     * Starts and validates the network from a previously saved state directory. This test simulates the environment by
     * loading the network from a "previous-version-state" directory. The test is parametrized with different sizes of
     * networks.
     *
     * @param env the test environment providing components such as the network and time manager
     */
    @OtterTest
    @OtterSpecs(randomNodeIds = false)
    @ParameterizedTest
    @ValueSource(
            ints = {
                4, // same as saved state
                3, // one less
                2, // half the original roster
                5, // one more
                8, // double the original roster
            })
    void migrationTest(final int numberOfNodes, @NonNull final TestEnvironment env) throws IOException {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();
        final SemanticVersion currentVersion = OtterSavedStateUtils.fetchApplicationVersion();

        // Setup simulation
        network.addNodes(numberOfNodes);
        // The increase in event creation rate is to fix a past issue.
        // This test encountered a coin round, it took many voting rounds to reach consensus. Because the checking
        // status gets activated if an event does not reach consensus within a certain amount of time, the test would
        // fail. By increasing the event creation rate, we can ensure that the network can create enough events to reach
        // consensus in a timely manner.
        network.withConfigValue(EventCreationConfig_.MAX_CREATION_RATE, 40);
        network.savedStateDirectory(SAVED_STATE_PATH);
        network.version(
                currentVersion.copyBuilder().minor(currentVersion.minor()).build());
        initializeEventHashFactory(env, readSavedStateRound());

        // Setup continuous assertions
        assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();
        assertContinuouslyThat(network.newConsensusResults())
                .haveEqualCommonRounds()
                .haveConsistentRounds();
        assertContinuouslyThat(network.newReconnectResults()).doNotAttemptToReconnect();

        network.start();

        final long highestRound = network.newConsensusResults().results().stream()
                .map(SingleNodeConsensusResult::lastRoundNum)
                .max(Long::compareTo)
                .orElseThrow();

        // Wait for 30 seconds
        timeManager.waitFor(Duration.ofSeconds(30L));

        // Validations
        assertThat(network.newLogResults()).allNodesHaveMessageContaining(OtterApp.UPGRADE_DETECTED_LOG_PAYLOAD);
        // Verify that all nodes progress at least 15 rounds
        assertThat(network.newConsensusResults().allNodesAdvancedToRound(highestRound + 15))
                .isTrue();
        assertThat(network.newPlatformStatusResults())
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));

        assertThat(network.newEventStreamResults()).haveEqualFiles();
    }

    /**
     * Tests that the network can start from a saved state when all node keys and certificates have been changed. This
     * simulates a scenario where the nodes have been rekeyed, and ensures that the network can still reach consensus.
     */
    @OtterTest
    @OtterSpecs(randomNodeIds = false)
    void keysChangeTest(@NonNull final TestEnvironment env)
            throws NoSuchAlgorithmException, KeyGeneratingException, NoSuchProviderException, IOException {
        final Network network = env.network();
        network.addNodes(4); // same as saved state
        network.savedStateDirectory(SAVED_STATE_PATH);

        final long savedStateRound = readSavedStateRound();
        initializeEventHashFactory(env, savedStateRound);

        // Override the keys and certificates for all nodes
        // Otter will automatically update the roster history with the new certs
        final SecureRandom secureRandom = DetRandomProvider.getDetRandom();
        secureRandom.setSeed(new byte[] {1, 2, 3});
        for (final Node node : network.nodes()) {
            node.keysAndCerts(
                    KeysAndCertsGenerator.generate(node.selfId(), SigningSchema.RSA, secureRandom, secureRandom));
        }

        // Setup continuous assertions
        assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();
        assertContinuouslyThat(network.newConsensusResults())
                .haveEqualCommonRounds()
                .haveConsistentRounds();
        assertContinuouslyThat(network.newReconnectResults()).doNotAttemptToReconnect();

        // Start the network
        network.start();

        // Wait for the nodes to advance 20 rounds, indicating that the network is working correctly with the new keys
        env.timeManager()
                .waitForCondition(
                        () -> network.newConsensusResults().allNodesAdvancedToRound(savedStateRound + 20),
                        Duration.ofSeconds(120L));
    }

    /**
     * Reads the round of the saved state the network starts from.
     *
     * @return the round of the saved state
     * @throws IOException if the saved state cannot be read
     */
    private static long readSavedStateRound() throws IOException {
        try (final Stream<Path> stream = Files.walk(OtterSavedStateUtils.findSaveState(SAVED_STATE_PATH))) {
            final Path metadataFile = stream.filter(
                            p -> p.getFileName().toString().equals(SavedStateMetadata.FILE_NAME))
                    .findAny()
                    .orElseThrow();
            return SavedStateMetadata.parse(metadataFile).round();
        }
    }

    /**
     * Initializes the EventHashFactory on the local JVM so that events sent back in consensus rounds for assertions are
     * hashed correctly. Starting from the saved state is an upgrade, so the nodes place the event cutover right after
     * the saved state round.
     *
     * @param env             the test environment
     * @param savedStateRound the round of the saved state
     */
    private static void initializeEventHashFactory(@NonNull final TestEnvironment env, final long savedStateRound) {
        if (env.capabilities().contains(Capability.SINGLE_NODE_JVM_SHUTDOWN)) {
            // Only required for the container environment
            EventHashFactory.initialize(savedStateRound + 1);
        }
    }
}
