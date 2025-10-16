// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.fixtures.assertions.StatusProgressionStep.target;
import static org.hiero.otter.fixtures.tools.GenerateStateTool.SEED;

import com.hedera.hapi.node.base.SemanticVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.OtterSpecs;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.turtle.TurtleSpecs;
import org.hiero.otter.fixtures.util.OtterSavedStateUtils;

public class StartFromStateTest {

    /**
     * Starts and validates the network from a previously saved state directory.
     * This test simulates the environment by loading the network from a "previous-version-state"
     * directory, adds nodes, starts the network, performs validations, and ensures that the network has
     * progressed and behaves as expected without errors.
     *
     * @param env the test environment providing components such as the network and time manager
     */
    @OtterTest
    @OtterSpecs(randomNodeIds = false)
    @TurtleSpecs(randomSeed = SEED)
    void startFromPreviousVersionState(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();
        final SemanticVersion currentVersion = OtterSavedStateUtils.fetchApplicationVersion();

        // Setup simulation
        network.addNodes(4);
        network.savedStateDirectory(Path.of("previous-version-state"));
        // Bump version, because the saved state version is currently the same. This will get removed once we have a new
        // release
        network.version(
                currentVersion.copyBuilder().minor(currentVersion.minor() + 1).build());
        network.start();

        final Map<NodeId, Long> lastRoundByNodeAtStart = network.newConsensusResults().results().stream()
                .collect(Collectors.toMap(SingleNodeConsensusResult::nodeId, SingleNodeConsensusResult::lastRoundNum));
        final long highesRound = lastRoundByNodeAtStart.values().stream()
                .mapToLong(Long::longValue)
                .max()
                .getAsLong();

        // Wait for two minutes
        timeManager.waitFor(Duration.ofMinutes(2L));

        // Validations
        // Verify that all nodes made progress
        network.newConsensusResults().results().stream().forEach(result -> assertThat(result.lastRoundNum())
                .isGreaterThan(lastRoundByNodeAtStart.get(result.nodeId())));
        assertThat(network.newConsensusResults())
                .haveAdvancedSinceRound(highesRound)
                .haveEqualCommonRounds();
        assertThat(network.newLogResults()).haveNoErrorLevelMessages();
        assertThat(network.newPlatformStatusResults())
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));
        assertThat(network.newMarkerFileResults()).haveNoMarkerFiles();
        assertThat(network.newReconnectResults()).haveNoReconnects();
    }
}
