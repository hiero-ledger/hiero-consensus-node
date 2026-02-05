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
import com.swirlds.platform.state.snapshot.SavedStateMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.stream.Stream;
import org.hiero.base.crypto.DetRandomProvider;
import org.hiero.consensus.crypto.KeyGeneratingException;
import org.hiero.consensus.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.crypto.SigningSchema;
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
    void migrationTest(final int numberOfNodes, @NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();
        final SemanticVersion currentVersion = OtterSavedStateUtils.fetchApplicationVersion();

        // Setup simulation
        network.addNodes(numberOfNodes);
        network.savedStateDirectory(Path.of("previous-version-state"));
        network.version(
                currentVersion.copyBuilder().minor(currentVersion.minor()).build());

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
        final Path savedStatePath = Path.of("previous-version-state");
        network.savedStateDirectory(savedStatePath);

        // Determine the round of the saved state
        final long savedStateRound;
        try (final Stream<Path> stream = Files.walk(OtterSavedStateUtils.findSaveState(savedStatePath))) {
            final Path metadataFile = stream.filter(
                            p -> p.getFileName().toString().equals(SavedStateMetadata.FILE_NAME))
                    .findAny()
                    .orElseThrow();
            savedStateRound = SavedStateMetadata.parse(metadataFile).round();
        }

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
}
