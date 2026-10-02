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
import java.nio.file.Path;
import java.time.Duration;
import org.assertj.core.api.Assertions;
import org.hiero.base.crypto.DigestType;
import org.hiero.consensus.event.creator.config.EventCreationConfig_;
import org.hiero.consensus.event.stream.config.EventConfig_;
import org.hiero.consensus.io.IOIterator;
import org.hiero.consensus.model.event.EventHashFactory;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.app.OtterApp;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodePcesResult;
import org.hiero.otter.fixtures.specs.OtterSpecs;
import org.hiero.otter.fixtures.util.OtterSavedStateUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class EventCutoverTest {

    @OtterTest
    @ParameterizedTest
    @CsvSource({"true, SHA-256", "false, SHA-384"})
    void eventCutoverBehaviorFromGenesis(
            final boolean cutoverEnabled, @NonNull final String expectedDigest, @NonNull final TestEnvironment env)
            throws IOException {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        network.addNodes(4);

        // Set the desired cutover configuration for this test.
        network.withConfigValue(EventConfig_.ENABLE_EVENT_CUTOVER, cutoverEnabled);
        final DigestType expectedDigestType = DigestType.algorithmNameToDigestType(expectedDigest);

        // Setup continuous assertions
        assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();
        assertContinuouslyThat(network.newConsensusResults())
                .haveEqualCommonRounds()
                .haveConsistentRounds();
        assertContinuouslyThat(network.newReconnectResults()).doNotAttemptToReconnect();

        network.start();

        // Wait for 30 seconds
        timeManager.waitFor(Duration.ofSeconds(30L));

        // Validations
        assertThat(network.newPlatformStatusResults())
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));

        for (final SingleNodePcesResult pcesResult : network.newPcesResults().pcesResults()) {
            try (final IOIterator<PlatformEvent> pcesEventIt = pcesResult.pcesEvents()) {
                while (pcesEventIt.hasNext()) {
                    final PlatformEvent event = pcesEventIt.next();

                    // Each event's parents hashes (the only hashes written to PCES)
                    // should match the expected digest type.
                    event.allParentsIterator().forEachRemaining(parent -> {
                        assertThat(parent.hash()).isNotNull();
                        assertThat(parent.hash().getDigestType()).isEqualTo(expectedDigestType);
                    });
                }
            }
        }
    }

    @OtterTest
    @OtterSpecs(randomNodeIds = false)
    void eventCutoverFromStateHappyPath(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();
        final SemanticVersion currentVersion = OtterSavedStateUtils.fetchApplicationVersion();

        // Setup simulation
        network.addNodes(4);
        // The increase in event creation rate is to fix a past issue.
        // This test encountered a coin round, it took many voting rounds to reach consensus. Because the checking
        // status gets activated if an event does not reach consensus within a certain amount of time, the test would
        // fail. By increasing the event creation rate, we can ensure that the network can create enough events to reach
        // consensus in a timely manner.
        network.withConfigValue(EventCreationConfig_.MAX_CREATION_RATE, 40);
        network.savedStateDirectory(Path.of("previous-version-state"));
        network.version(
                currentVersion.copyBuilder().minor(currentVersion.minor()).build());

        // Enable the event cutover feature for this test.
        network.withConfigValue(EventConfig_.ENABLE_EVENT_CUTOVER, true);

        // Initialize the EventHashFactory on the local JVM so that events sent back in consensus
        // rounds for assertions are hashed correctly. The state this test starts from is round 32.
        EventHashFactory.initialize(33);

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
        Assertions.assertThat(network.newConsensusResults().allNodesAdvancedToRound(highestRound + 15))
                .isTrue();
        assertThat(network.newPlatformStatusResults())
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));

        assertThat(network.newEventStreamResults()).haveEqualFiles();
    }
}
