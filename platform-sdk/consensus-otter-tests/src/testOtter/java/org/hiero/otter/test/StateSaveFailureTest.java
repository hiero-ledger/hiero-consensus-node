// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;

import com.swirlds.logging.legacy.payload.AbstractLogPayload;
import com.swirlds.logging.legacy.payload.FatalErrorPayload;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hiero.consensus.state.config.StateConfig_;
import org.hiero.otter.fixtures.Capability;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;

/**
 * Tests for how a node responds when it can't save a state to disk.
 */
public class StateSaveFailureTest {

    /**
     * Makes one node's periodic state saves time out waiting for the asynchronous snapshot, and verifies that the
     * node shuts itself down instead of carrying on without the state, while the rest of the network keeps reaching
     * consensus.
     * <p>
     * The node waits zero seconds ({@code state.asyncSnapshotTimeout=0}) for its asynchronous snapshot, so its first
     * periodic save times out. This covers only the timeout failure, not a snapshot that fails to write.
     * <p>
     * The node gets this configuration only after the network is ACTIVE, through a restart. Configured from the
     * start, it could shut down before it ever becomes ACTIVE, and {@link Network#start()} waits for all nodes to
     * become ACTIVE.
     *
     * @param env the environment to test in
     */
    @OtterTest(requires = Capability.SINGLE_NODE_JVM_SHUTDOWN)
    void testNodeShutsDownWhenAsyncSnapshotTimesOut(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        final List<Node> nodes = network.addNodes(4);
        final Node failingNode = nodes.getLast();

        assertContinuouslyThat(network.newLogResults().suppressingNode(failingNode))
                .haveNoErrorLevelMessages();
        assertContinuouslyThat(network.newConsensusResults()).haveEqualCommonRounds();
        assertContinuouslyThat(network.newConsensusResults().suppressingNode(failingNode))
                .haveConsistentRounds();

        network.start();

        failingNode.killImmediately();
        failingNode
                .configuration()
                .withConfigValue(StateConfig_.SAVE_STATE_ASYNC, true)
                .withConfigValue(StateConfig_.SAVE_STATE_PERIOD, 1)
                .withConfigValue(StateConfig_.ASYNC_SNAPSHOT_TIMEOUT, 0L);

        final SingleNodeLogResult failingNodeLogResult = failingNode.newLogResult();
        final AtomicBoolean fatalErrorLogged = failingNodeLogResult.onNextMatch(
                log -> FatalErrorPayload.class.getName().equals(AbstractLogPayload.extractPayloadType(log.message())));

        failingNode.start();

        // Wait for the fatal error log before checking isAlive(). Once isAlive() returns false, it closes the log
        // stream, and the node's last log lines may not be collected yet
        timeManager.waitForCondition(
                fatalErrorLogged::get,
                Duration.ofSeconds(120),
                "Node did not report a fatal error after its async snapshot timed out");
        timeManager.waitForCondition(
                () -> !failingNode.isAlive(),
                Duration.ofSeconds(120),
                "Node did not shut down after its async snapshot timed out");

        assertThat(failingNodeLogResult).hasMessageContaining("Timed out waiting for async snapshot");

        // The remaining nodes hold more than 2/3 of the weight, so they keep reaching consensus
        timeManager.waitFor(Duration.ofSeconds(30));
        for (final Node node : nodes) {
            if (node != failingNode) {
                assertThat(node.isActive())
                        .as("node %s should still be active", node.selfId())
                        .isTrue();
            }
        }
    }
}
