// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.otter.fixtures.Capability.USES_REAL_NETWORK;
import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;

import com.hedera.hapi.node.base.SemanticVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.hiero.consensus.test.fixtures.WeightGenerators;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.specs.OtterSpecs;
import org.hiero.otter.fixtures.util.OtterSavedStateUtils;

/**
 * Verifies that on restart from a saved state with less than 2/3 of stake online, self
 * events reaching consensus via PCES replay or gossip alone do not promote the platform
 * from {@link org.hiero.consensus.model.status.PlatformStatus#CHECKING} to
 * {@link org.hiero.consensus.model.status.PlatformStatus#ACTIVE}. Only a self event with
 * {@link org.hiero.consensus.model.event.EventOrigin#RUNTIME} origin — one actually
 * created in the current runtime — represents event-creation liveness in this session and
 * may drive the transition.
 *
 * <p>The test starts the online subset ({@code < 2/3} stake) from a fixture whose PCES
 * lets one round past the loaded checkpoint reach consensus without any runtime-created
 * self event, asserts every online node stays in {@code CHECKING} for the observation
 * window, then brings a recovery node online to push stake above 2/3 and asserts the
 * network reaches {@code ACTIVE} via the normal path.
 *
 * <p>Requires the real-network environment
 * ({@link org.hiero.otter.fixtures.Capability#USES_REAL_NETWORK}): the Turtle deterministic
 * simulator cannot reproduce the timing and gossip conditions this fixture depends on.
 *
 * <p>Regression tag: incident-20260821-mainnet-upgrade-briefly-back-to-checking-844.
 */
public class PostFreezeStakeThresholdFlipTest {

    /**
     * Symmetric-filtered fixture: every node loads the same PCES which contains all-creator
     * events pre-checkpoint plus only the offline creators' events past the checkpoint
     * (online creators' post-R events are dropped so the online subset must recreate them
     * live). Committed under {@code saved-states/post-freeze-4-node-state-symmetric/}.
     *
     * <p>Regenerate with:
     * <pre>
     *   ./gradlew :consensus-otter-tests:generatePostFreezeSavedState
     *   ./gradlew :consensus-otter-tests:partitionPostFreezeFixtureSymmetric
     * </pre>
     */
    private static final Path SYMMETRIC_FILTERED_FIXTURE = Path.of("post-freeze-4-node-state-symmetric");

    /**
     * How long to wait after restart before asserting no spurious ACTIVE fired. Must be
     * comfortably longer than {@code observingStatusDelay} (default 10 s) plus the time it
     * takes replayed self events to reach consensus post-OBSERVING — pre-fix the flip
     * happened within a few seconds of entering CHECKING. 60 s is generous enough that a
     * flip would have fired if the fix regressed.
     */
    private static final Duration STAKE_BELOW_SUPERMAJORITY_OBSERVATION = Duration.ofSeconds(60);

    @OtterTest(requires = USES_REAL_NETWORK)
    @OtterSpecs(randomNodeIds = false)
    void testNoSpuriousFlipWithSymmetricFilteredFixture(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        network.weightGenerator(WeightGenerators.BALANCED_REAL_WEIGHT);
        network.addNodes(10);
        final SemanticVersion artifactVersion = OtterSavedStateUtils.fetchApplicationVersion();
        network.version(artifactVersion);

        network.savedStateDirectory(SYMMETRIC_FILTERED_FIXTURE);

        assertContinuouslyThat(network.newReconnectResults()).doNotAttemptToReconnect();

        final List<Node> onlineNodes = List.of(
                network.nodes().get(0),
                network.nodes().get(1),
                network.nodes().get(2),
                network.nodes().get(3),
                network.nodes().get(4),
                network.nodes().get(5));
        final Node recoveryNode = network.nodes().get(6);

        network.startSubset(onlineNodes);

        // Regression guard: with < 2/3 stake online, replayed self events reaching consensus
        // must NOT drive CHECKING → ACTIVE. Wait long enough for the pre-fix flip to have
        // fired, then assert every online node is still in CHECKING with no ACTIVE in its
        // history.
        timeManager.waitFor(STAKE_BELOW_SUPERMAJORITY_OBSERVATION);
        for (final Node node : onlineNodes) {
            assertThat(node.newPlatformStatusResult().statusProgression().contains(ACTIVE))
                    .as("node %s must not have reached ACTIVE while online stake < 2/3", node.selfId())
                    .isFalse();
            assertThat(node.isChecking())
                    .as("node %s must be in CHECKING while online stake < 2/3", node.selfId())
                    .isTrue();
        }

        // Recovery: the 7th node's runtime-origin self events push stake above 2/3 and
        // drive the transition to ACTIVE via the normal path.
        recoveryNode.start();
        timeManager.waitForCondition(
                () -> onlineNodes.stream().allMatch(Node::isActive) && recoveryNode.isActive(),
                Duration.ofMinutes(2L),
                "Nodes did not reach ACTIVE after online stake crossed 2/3");
    }
}
