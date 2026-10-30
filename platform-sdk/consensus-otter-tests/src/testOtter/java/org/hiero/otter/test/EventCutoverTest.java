// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.BEHIND;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.RECONNECT_COMPLETE;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;
import static org.hiero.otter.fixtures.Capability.RECONNECT;
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
import org.hiero.consensus.hashgraph.config.ConsensusConfig_;
import org.hiero.consensus.io.IOIterator;
import org.hiero.consensus.model.event.EventHashFactory;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.pces.config.PcesConfig_;
import org.hiero.otter.fixtures.Capability;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.app.OtterApp;
import org.hiero.otter.fixtures.result.MultipleNodePlatformStatusResults;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodePcesResult;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResult;
import org.hiero.otter.fixtures.specs.OtterSpecs;
import org.hiero.otter.fixtures.util.OtterSavedStateUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 2. Do the cutover, then do another upgrade via config and clear the flag
 */
public class EventCutoverTest {

    /** Reducing the number of rounds non-expired will allow nodes to require a reconnect faster. */
    private static final long ROUNDS_EXPIRED = 100L;

    /** The round of the initial state used by several tests in this class. */
    private static final long STARTING_STATE_ROUND = 32L;

    /**
     * Tests that the event cutover feature works correctly when starting from a genesis state.
     */
    @OtterTest
    @ParameterizedTest
    @CsvSource({"true, SHA-256", "false, SHA-384"})
    void eventCutoverBehaviorFromGenesis(
            final boolean cutoverEnabled, @NonNull final String expectedDigest, @NonNull final TestEnvironment env)
            throws IOException {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Set the desired cutover configuration for this test.
        network.withConfigValue(EventConfig_.ENABLE_EVENT_CUTOVER, cutoverEnabled);
        final DigestType expectedDigestType = DigestType.algorithmNameToDigestType(expectedDigest);

        // Setup simulation
        network.addNodes(4);

        // Setup continuous assertions
        assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();
        assertContinuouslyThat(network.newConsensusResults()).haveEqualCommonRounds();
        assertContinuouslyThat(network.newReconnectResults()).doNotAttemptToReconnect();

        network.start();

        // Wait for 30 seconds
        timeManager.waitFor(Duration.ofSeconds(30L));

        // Validations
        final MultipleNodePlatformStatusResults networkStatusResults = network.newPlatformStatusResults();
        assertThat(networkStatusResults)
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));

        network.shutdown();

        networkStatusResults.clear();

        network.start();

        // Validations
        assertThat(networkStatusResults)
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));

        // Wait for 30 seconds
        timeManager.waitFor(Duration.ofSeconds(30L));

        network.shutdown();

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

    /**
     * Tests that a cutover from a previous version of the software works correctly.
     */
    @OtterTest
    @OtterSpecs(randomNodeIds = false)
    void eventCutoverFromStateHappyPath(@NonNull final TestEnvironment env) throws IOException {
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
        if (env.capabilities().contains(Capability.SINGLE_NODE_JVM_SHUTDOWN)) {
            // Only required for the container environment
            EventHashFactory.initialize(33);
        }

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

        network.shutdown();

        // Validations
        assertThat(network.newLogResults()).allNodesHaveMessageContaining(OtterApp.UPGRADE_DETECTED_LOG_PAYLOAD);
        // Verify that all nodes progress at least 15 rounds
        Assertions.assertThat(network.newConsensusResults().allNodesAdvancedToRound(highestRound + 15))
                .isTrue();
        assertThat(network.newPlatformStatusResults())
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));

        assertThat(network.newEventStreamResults()).haveEqualFiles();

        assertPcesEventHashesMatchExpectedDigestType(network);
    }

    /**
     * Tests that a crash and restart of a single node post event cutover works correctly.
     */
    @OtterTest
    @OtterSpecs(randomNodeIds = false)
    void eventCutoverAndRestart(@NonNull final TestEnvironment env) throws IOException {
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
        if (env.capabilities().contains(Capability.SINGLE_NODE_JVM_SHUTDOWN)) {
            // Only required for the container environment
            EventHashFactory.initialize(33);
        }

        network.start();

        // Wait for 30 seconds
        timeManager.waitFor(Duration.ofSeconds(30L));

        final Node nodeToRestart = network.nodes().getFirst();
        nodeToRestart.killImmediately();

        // Verify that the node was healthy prior to being killed
        final SingleNodePlatformStatusResult nodeToRestartStatusResults = nodeToRestart.newPlatformStatusResult();
        assertThat(nodeToRestartStatusResults)
                .hasSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));
        nodeToRestartStatusResults.clear();

        timeManager.waitFor(Duration.ofSeconds(1L));

        nodeToRestart.start();

        timeManager.waitForCondition(network::allNodesAreActive, Duration.ofMinutes(2L));

        final long highestRound = network.newConsensusResults().results().stream()
                .map(SingleNodeConsensusResult::lastRoundNum)
                .max(Long::compareTo)
                .orElseThrow();

        // Wait for 30 seconds
        timeManager.waitFor(Duration.ofSeconds(30L));

        network.shutdown();

        // Validations
        assertThat(network.newLogResults()).allNodesHaveMessageContaining(OtterApp.UPGRADE_DETECTED_LOG_PAYLOAD);
        // Verify that all nodes progress at least 15 rounds
        Assertions.assertThat(network.newConsensusResults().allNodesAdvancedToRound(highestRound + 15))
                .isTrue();
        assertThat(network.newPlatformStatusResults().suppressingNode(nodeToRestart))
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));
        // The restarted node should have gone through the normal status progression since restarting
        assertThat(nodeToRestartStatusResults)
                .hasSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));

        assertThat(network.newEventStreamResults().suppressingNode(nodeToRestart))
                .haveEqualFiles();

        assertPcesEventHashesMatchExpectedDigestType(network);
    }

    /**
     * Tests that forcing a node to fall behind and then reconnect post event cutover works correctly.
     */
    @OtterTest(requires = RECONNECT)
    @OtterSpecs(randomNodeIds = false)
    void eventCutoverAndReconnect(@NonNull final TestEnvironment env) throws IOException {
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

        // Set the rounds non-ancient and expired to smaller values to allow nodes to fall behind quickly
        network.withConfigValue(ConsensusConfig_.ROUNDS_EXPIRED, ROUNDS_EXPIRED);

        // Enable the event cutover feature for this test.
        network.withConfigValue(EventConfig_.ENABLE_EVENT_CUTOVER, true);

        // Initialize the EventHashFactory on the local JVM so that events sent back in consensus
        // rounds for assertions are hashed correctly. The state this test starts from is round 32.
        if (env.capabilities().contains(Capability.SINGLE_NODE_JVM_SHUTDOWN)) {
            // Only required for the container environment
            EventHashFactory.initialize(33);
        }

        network.start();

        // Allow the nodes to run for a short time
        timeManager.waitFor(Duration.ofSeconds(5L));

        // Shutdown the node for a period of time so that it falls behind.
        final Node nodeToReconnect = network.nodes().getFirst();
        nodeToReconnect.killImmediately();

        // Verify that the node was healthy prior to being killed
        final SingleNodePlatformStatusResult nodeToReconnectStatusResults = nodeToReconnect.newPlatformStatusResult();
        assertThat(nodeToReconnectStatusResults)
                .hasSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));
        nodeToReconnectStatusResults.clear();

        // Wait for the node we just killed to become behind enough to require a reconnect.
        timeManager.waitForCondition(
                () -> network.nodeIsBehindByNodeCount(nodeToReconnect),
                Duration.ofSeconds(120L),
                "Node did not fall behind in the time allotted.");

        final int numEventStreamFilesBeforeReconnect =
                nodeToReconnect.newEventStreamResult().eventStreamFiles().size();

        // Restart the node that was killed
        nodeToReconnect.start();

        // First, we must wait for the node to come back up and report that it is behind.
        // If we wait for it to be active, this check will pass immediately. That was the last status it had,
        // and we will check the value before the node has a change to tell us that it is behind.
        timeManager.waitForCondition(nodeToReconnect::isBehind, Duration.ofSeconds(120L));

        // Now we wait for the node to reconnect and become active again.
        timeManager.waitForCondition(nodeToReconnect::isActive, Duration.ofSeconds(120L));

        // Allow some additional time to ensure we have at least one event stream file after reconnect
        timeManager.waitForCondition(
                () -> nodeToReconnect.newEventStreamResult().eventStreamFiles().size()
                        > numEventStreamFilesBeforeReconnect,
                Duration.ofSeconds(120L));

        // Validations
        assertThat(nodeToReconnect.newReconnectResult()).hasExactSuccessfulReconnects(1);

        final long highestRound = network.newConsensusResults().results().stream()
                .map(SingleNodeConsensusResult::lastRoundNum)
                .max(Long::compareTo)
                .orElseThrow();

        // Wait for 30 seconds
        timeManager.waitFor(Duration.ofSeconds(30L));

        network.shutdown();

        // Validations
        assertThat(network.newLogResults()).allNodesHaveMessageContaining(OtterApp.UPGRADE_DETECTED_LOG_PAYLOAD);
        // Verify that all nodes progress at least 15 rounds
        Assertions.assertThat(network.newConsensusResults().allNodesAdvancedToRound(highestRound + 15))
                .isTrue();
        assertThat(network.newPlatformStatusResults().suppressingNode(nodeToReconnect))
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));
        assertThat(nodeToReconnectStatusResults)
                .hasSteps(target(ACTIVE)
                        .requiringInterim(REPLAYING_EVENTS, OBSERVING, BEHIND, RECONNECT_COMPLETE, CHECKING));

        assertThat(network.newEventStreamResults().suppressingNode(nodeToReconnect))
                .haveEqualFiles();

        assertPcesEventHashesMatchExpectedDigestType(network);
    }

    /**
     * Tests that a cutover followed by a config upgrade that disables the cutover works correctly.
     */
    @OtterTest
    @OtterSpecs(randomNodeIds = false)
    void eventCutoverAndClear(@NonNull final TestEnvironment env) throws IOException {
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

        // Make the PCES files smaller, so that after the next freeze/upgrade, no PCES files that contain
        // any pre-cutover events need to be read.
        network.withConfigValue(PcesConfig_.SPAN_OVERLAP_FACTOR, 1.0);
        network.withConfigValue(PcesConfig_.BOOTSTRAP_SPAN_OVERLAP_FACTOR, 1.0);
        network.withConfigValue(PcesConfig_.BOOTSTRAP_SPAN, 1);
        network.withConfigValue(ConsensusConfig_.ROUNDS_EXPIRED, ROUNDS_EXPIRED);

        // Initialize the EventHashFactory on the local JVM so that events sent back in consensus
        // rounds for assertions are hashed correctly. The state this test starts from is round 32.
        if (env.capabilities().contains(Capability.SINGLE_NODE_JVM_SHUTDOWN)) {
            // Only required for the container environment
            EventHashFactory.initialize(STARTING_STATE_ROUND + 1);
        }

        network.start();

        timeManager.waitFor(Duration.ofSeconds(60L));

        network.freeze();

        network.shutdown();

        network.bumpConfigVersion();
        network.withConfigValue(EventConfig_.ENABLE_EVENT_CUTOVER, false);
        if (env.capabilities().contains(Capability.SINGLE_NODE_JVM_SHUTDOWN)) {
            // Only required for the container environment
            EventHashFactory.initialize(0L);
        }

        network.start();

        final long highestRound = network.newConsensusResults().results().stream()
                .map(SingleNodeConsensusResult::lastRoundNum)
                .max(Long::compareTo)
                .orElseThrow();

        timeManager.waitFor(Duration.ofSeconds(30L));

        network.shutdown();

        // Verify that all nodes progress at least 15 rounds
        Assertions.assertThat(network.newConsensusResults().allNodesAdvancedToRound(highestRound + 15))
                .isTrue();
        assertPcesEventHashesMatchExpectedDigestType(network);
    }

    private static void assertPcesEventHashesMatchExpectedDigestType(@NonNull final Network network)
            throws IOException {
        // The PCES files still on disk can contain events from before the cutover. Parse them with the original
        // cutover, regardless of the value the nodes use after the cutover was cleared.
        EventHashFactory.initialize(STARTING_STATE_ROUND + 1);
        for (final SingleNodePcesResult pcesResult : network.newPcesResults().pcesResults()) {
            try (final IOIterator<PlatformEvent> pcesEventIt = pcesResult.pcesEvents()) {
                while (pcesEventIt.hasNext()) {
                    final PlatformEvent event = pcesEventIt.next();

                    // Each event's parents hashes (the only hashes written to PCES)
                    // should match the expected digest type.
                    event.allParentsIterator().forEachRemaining(parent -> {
                        final DigestType expectedDigestType =
                                parent.birthRound() > STARTING_STATE_ROUND ? DigestType.SHA_256 : DigestType.SHA_384;
                        assertThat(parent.hash()).isNotNull();
                        assertThat(parent.hash().getDigestType()).isEqualTo(expectedDigestType);
                    });
                }
            }
        }
    }
}
