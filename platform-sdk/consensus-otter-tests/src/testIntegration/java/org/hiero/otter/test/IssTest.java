// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.CATASTROPHIC_FAILURE;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;
import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.fixtures.assertions.StatusProgressionStep.target;
import static org.hiero.otter.fixtures.assertions.StatusProgressionStep.targets;
import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

import com.swirlds.platform.config.StateConfig_;
import com.swirlds.platform.state.iss.DefaultIssDetector;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.hiero.consensus.model.notification.IssNotification.IssType;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.result.MarkerFilesStatus;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResult;

/**
 * Tests for the detection and response to ISSes (Inconsistent State Signatures).
 */
public class IssTest {

    /**
     * Triggers a recoverable ISS on a single node and verifies that it recovers by restarting.
     *
     * @param env the environment to test in
     */
    @OtterTest
    void testRecoverableSelfIss(@NonNull final TestEnvironment env) {
        final Network network = env.network();

        network.addNodes(4);
        final Node issNode = network.nodes().getFirst();

        // Setup continuous assertions
        assertContinuouslyThat(network.newLogResults().suppressingNode(issNode)).haveNoErrorLevelMessages();
        assertContinuouslyThat(network.newReconnectResults()).doNotAttemptToReconnect();
        assertContinuouslyThat(network.newConsensusResults()).haveEqualRounds();
        assertContinuouslyThat(network.newConsensusResults().suppressingNode(issNode))
                .haveConsistentRounds();
        assertContinuouslyThat(network.newMarkerFileResults().suppressingNode(issNode))
                .haveNoCoinRoundMarkerFiles()
                .haveNoNoSuperMajorityMarkerFiles()
                .haveNoNoJudgesMarkerFiles()
                .haveNoConsensusExceptionMarkerFiles()
                .haveNoIssMarkerFilesOfType(IssType.SELF_ISS)
                .haveNoIssMarkerFilesOfType(IssType.CATASTROPHIC_ISS);
        assertContinuouslyThat(issNode.newMarkerFileResult())
                .hasNoCoinRoundMarkerFile()
                .hasNoNoSuperMajorityMarkerFile()
                .hasNoNoJudgesMarkerFile()
                .hasNoWriteConsensusExceptionMarkerFile()
                // Check can be enabled once https://github.com/hiero-ledger/hiero-consensus-node/issues/21666 is fixed
                //                .hasNoISSMarkerFileOfType(IssType.OTHER_ISS)
                .hasNoISSMarkerFileOfType(IssType.CATASTROPHIC_ISS);

        network.start();

        issNode.triggerSelfIss();

        final SingleNodePlatformStatusResult issNodeStatusResult = issNode.newPlatformStatusResult();
        assertThat(issNodeStatusResult)
                .hasSteps(target(CATASTROPHIC_FAILURE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING, ACTIVE));
        issNodeStatusResult.clear();

        final SingleNodeLogResult issLogResult = issNode.newLogResult();
        assertThat(issLogResult.suppressingLoggerName(DefaultIssDetector.class)).hasNoErrorLevelMessages();
        issLogResult.clear();

        assertThat(network.newPlatformStatusResults().suppressingNode(issNode))
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));

        issNode.killImmediately();
        issNode.start();

        env.timeManager()
                .waitForCondition(
                        issNode::isActive, Duration.ofSeconds(120), "Node did not become ACTIVE in the time allowed.");

        assertThat(issNodeStatusResult)
                .hasSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));
        assertThat(issLogResult).hasNoErrorLevelMessages();

        // Wait for SELF_ISS marker file on the ISS node (max 2 minutes)
        final MarkerFilesStatus issMarkerFilesStatus =
                issNode.newMarkerFileResult().status();
        await().atMost(Duration.ofMinutes(2L))
                .until(() -> issMarkerFilesStatus.hasISSMarkerFileOfType(IssType.SELF_ISS));

        // This functionality is currently unstable. Enable the check once
        // https://github.com/hiero-ledger/hiero-consensus-node/issues/21684 has been fixed

        //        // Wait for OTHER_ISS marker files on all other nodes (max 10 seconds each)
        //        for (final SingleNodeMarkerFileResult result :
        // network.newMarkerFileResults().suppressingNode(issNode).results()) {
        //            final MarkerFilesStatus markerFilesStatus = result.status();
        //            await().atMost(Duration.ofSeconds(10L))
        //                    .until(() -> markerFilesStatus.hasISSMarkerFileOfType(IssType.OTHER_ISS));
        //        }
    }

    /**
     * Triggers a catastrophic ISS and verifies that all nodes in the network enter the CATASTROPHIC_FAILURE or CHECKING
     * state. One node will be the first to detect the catastrophic ISS and halt gossip. Once enough nodes have done
     * this, the other nodes will not be able to proceed in consensus and may not detect the ISS. Therefore, they enter
     * CHECKING instead. In networks with very low latency, it is likely that all nodes will enter
     * CATASTROPHIC_FAILURE.
     *
     * @param env the environment to test in
     */
    @OtterTest
    void testCatastrophicIss(@NonNull final TestEnvironment env) {
        final Network network = env.network();

        network.addNodes(4);
        network.withConfigValue(StateConfig_.HALT_ON_CATASTROPHIC_ISS, true);

        // Setup continuous assertions
        assertContinuouslyThat(network.newLogResults().suppressingLoggerName(DefaultIssDetector.class))
                .haveNoErrorLevelMessages();
        assertContinuouslyThat(network.newReconnectResults()).doNotAttemptToReconnect();
        assertContinuouslyThat(network.newConsensusResults()).haveEqualRounds().haveConsistentRounds();
        assertContinuouslyThat(network.newMarkerFileResults())
                .haveNoCoinRoundMarkerFiles()
                .haveNoNoSuperMajorityMarkerFiles()
                .haveNoNoJudgesMarkerFiles()
                .haveNoConsensusExceptionMarkerFiles()
                .haveNoIssMarkerFilesOfType(IssType.OTHER_ISS)
                .haveNoIssMarkerFilesOfType(IssType.SELF_ISS);

        network.start();

        network.triggerCatastrophicIss();

        assertThat(network.newPlatformStatusResults())
                .haveSteps(
                        target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING),
                        targets(CHECKING, CATASTROPHIC_FAILURE));

        assertThat(network.newEventStreamResults()).haveEqualFiles();

        // This functionality is currently unstable. Enable the check once
        // https://github.com/hiero-ledger/hiero-consensus-node/issues/21703 has been fixed

        //        // It takes a while (real time) until a marker file is created, therefore we check it in a loop
        //        for (final SingleNodeMarkerFileResult result : network.newMarkerFileResults().results()) {
        //            await().atMost(Duration.ofMinutes(2))
        //                    .until(() -> result.status().hasISSMarkerFileOfType(IssType.CATASTROPHIC_ISS));
        //        }
    }
}
