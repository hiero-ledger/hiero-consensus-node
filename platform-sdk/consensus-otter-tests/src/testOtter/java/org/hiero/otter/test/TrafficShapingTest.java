// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.BEHIND;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;
import static org.hiero.otter.fixtures.Capability.USES_REAL_NETWORK;
import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.fixtures.assertions.StatusProgressionStep.target;

import com.swirlds.logging.legacy.payload.AbstractLogPayload;
import com.swirlds.logging.legacy.payload.PeerTrafficShapingPayload;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.hiero.consensus.gossip.config.TrafficShapingConfig_;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.logging.StructuredLog;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;

/**
 * Tests the per-peer inbound traffic shaper in the rpc gossip protocol.
 *
 * <h2>Why this is container only</h2>
 *
 * The Turtle environment substitutes {@code TurtleGossipModule} / {@code SimulatedGossip} for the real gossip stack,
 * so {@code RpcPeerProtocol} — and therefore the shaper — never executes there. These tests require
 * {@link org.hiero.otter.fixtures.Capability#USES_REAL_NETWORK} so they only run against the Container environment,
 * where nodes gossip over real sockets.
 *
 * <h2>How heavy traffic is produced</h2>
 *
 * Rather than adding a malicious flooding node, these tests shrink the byte budget until ordinary gossip traffic
 * exceeds it. That exercises exactly the same code path with far less machinery, and it lets the budget be the one
 * variable under test.
 *
 * <h2>Why the network survives an absurdly small budget</h2>
 *
 * The shaper caps any single pause at {@code maxReadDelay}, which puts a floor on throughput regardless of how low
 * the configured rate is. Charges are taken per message from a byte counter sitting under an 8 KiB buffered stream,
 * so a charge is at most about one socket buffer. The resulting floor is roughly:
 *
 * <pre>
 *     floor ~= socketBufferBytes / maxReadDelay
 *            = 8192 B / 100 ms
 *            ~= 80 KiB/s per peer
 * </pre>
 *
 * With {@link #TIGHT_BYTES_PER_SECOND} set to 8 KiB/s, the shaper is therefore continuously engaged while the peer
 * still receives roughly ten times its configured rate. That gap is expected and is the reason these tests are
 * stable, but it is also a real limitation of the design: the configured sustained rate is not achievable for
 * message sizes whose cost exceeds {@code maxReadDelay}. If that behaviour is changed, the throughput floor
 * disappears and {@link #TIGHT_BYTES_PER_SECOND} will need raising to keep the network alive here.
 */
public class TrafficShapingTest {

    /** Nodes in each test network. Four is enough for consensus with headroom. */
    private static final int NETWORK_SIZE = 4;

    /**
     * A sustained budget far below real gossip traffic, so the shaper is guaranteed to engage. Not so low that the
     * network starves, for the reason described in the class javadoc.
     */
    private static final long TIGHT_BYTES_PER_SECOND = 8192L;

    /** Roughly 8 seconds of burst at {@link #TIGHT_BYTES_PER_SECOND}; enough to cover the startup handshake. */
    private static final long TIGHT_BURST_BYTES = 65536L;

    /**
     * Maximum pause applied to a read thread. Must stay well below {@code broadcast.disablePingThreshold} (900 ms
     * by default), because a paused read thread does not answer the peer's pings and a peer that stops receiving
     * ping replies will conclude we are unhealthy.
     */
    private static final Duration MAX_READ_DELAY = Duration.ofMillis(100);

    /** Short reporting interval so watermark breaches surface quickly instead of once a minute. */
    private static final Duration REPORT_INTERVAL = Duration.ofSeconds(1);

    private static final Duration REPORT_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration OBSERVATION_TIME = Duration.ofSeconds(30);

    private static final String PAYLOAD_TYPE = PeerTrafficShapingPayload.class.getName();

    /**
     * A budget generous enough that healthy traffic never approaches it must produce no reports at all. This is the
     * false positive guard: if the shaper complains here, its defaults are miscalibrated and enabling enforcement
     * in production would throttle honest peers.
     *
     * @param env the test environment
     */
    @OtterTest(requires = USES_REAL_NETWORK)
    void healthyTrafficIsNeverReported(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        network.addNodes(NETWORK_SIZE);

        // defaults for the budget, but report aggressively so that any breach would be visible
        network.withConfigValue(TrafficShapingConfig_.ENABLED, true)
                .withConfigValue(TrafficShapingConfig_.ENFORCE, true)
                .withConfigValue(TrafficShapingConfig_.REPORT_INTERVAL, REPORT_INTERVAL);

        assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();
        assertContinuouslyThat(network.newReconnectResults()).doNotAttemptToReconnect();
        assertContinuouslyThat(network.newConsensusResults())
                .haveEqualCommonRounds()
                .haveConsistentRounds();
        assertContinuouslyThat(network.newPlatformStatusResults()).doNotEnterAnyStatusesOf(BEHIND);

        network.start();

        timeManager.waitFor(OBSERVATION_TIME);

        // the whole point of the test: nothing was reported
        for (final Node node : network.nodes()) {
            assertThat(node.newLogResult()).hasNoMessageWithPayload(PeerTrafficShapingPayload.class);
        }

        assertThat(network.newPlatformStatusResults())
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));
    }

    /**
     * In shadow mode the shaper measures and reports but never pauses a read thread, so a budget far below real
     * traffic must produce reports on every node while leaving the network completely unaffected.
     *
     * <p>This is the configuration the design proposes shipping as the default, and the property under test is that
     * turning it on is free.
     *
     * @param env the test environment
     */
    @OtterTest(requires = USES_REAL_NETWORK)
    void shadowModeReportsHeavyPeersWithoutAffectingTraffic(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        network.addNodes(NETWORK_SIZE);
        applyTightBudget(network, false);

        assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();
        assertContinuouslyThat(network.newReconnectResults()).doNotAttemptToReconnect();
        assertContinuouslyThat(network.newConsensusResults())
                .haveEqualCommonRounds()
                .haveConsistentRounds();
        // shadow mode must not perturb the network at all, so BEHIND is a failure
        assertContinuouslyThat(network.newPlatformStatusResults()).doNotEnterAnyStatusesOf(BEHIND);

        network.start();

        timeManager.waitForCondition(
                () -> allNodesReportedShaping(network),
                REPORT_TIMEOUT,
                "Shaper did not report a watermark breach on every node despite a budget of " + TIGHT_BYTES_PER_SECOND
                        + " bytes/s");

        timeManager.waitFor(OBSERVATION_TIME);

        assertThat(network.newLogResults()).allNodesHaveMessageWithPayload(PeerTrafficShapingPayload.class);
        assertThat(network.newPlatformStatusResults())
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));
        assertThat(network.newConsensusResults()).haveAdvancedSinceRound(2);

        // nothing should have been paused, since enforcement is off
        for (final Node node : network.nodes()) {
            assertThat(enforcedPauseCount(node.newLogResult()))
                    .as("shadow mode must not pause any read thread")
                    .isZero();
        }
    }

    /**
     * With enforcement on and the same tight budget, read threads are actually paused. The network must still come
     * up, reach {@link org.hiero.consensus.model.status.PlatformStatus#ACTIVE}, and keep making consensus progress.
     *
     * <p>This is the liveness regression test for the shaper's most dangerous failure mode: throttling a peer so
     * hard that it treats us as dead. Note that {@code network.start()} itself waits for every node to become
     * {@code ACTIVE}, so a shaper that wedges gossip fails the test before any assertion runs.
     *
     * @param env the test environment
     */
    @OtterTest(requires = USES_REAL_NETWORK)
    void enforcedShapingThrottlesReadsWithoutBreakingConsensus(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        network.addNodes(NETWORK_SIZE);
        applyTightBudget(network, true);

        assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();
        assertContinuouslyThat(network.newConsensusResults())
                .haveEqualCommonRounds()
                .haveConsistentRounds();

        // If throttling starves a node it will fall behind and reconnect. That is the failure this test exists to
        // catch, so it is asserted continuously rather than only at the end.
        assertContinuouslyThat(network.newReconnectResults()).doNotAttemptToReconnect();
        assertContinuouslyThat(network.newPlatformStatusResults()).doNotEnterAnyStatusesOf(BEHIND);

        network.start();

        timeManager.waitForCondition(
                () -> allNodesReportedShaping(network),
                REPORT_TIMEOUT,
                "Shaper did not report a watermark breach on every node despite a budget of " + TIGHT_BYTES_PER_SECOND
                        + " bytes/s");

        // At least one node must have actually paused a read thread, otherwise enforcement is not being applied and
        // this test would be silently identical to the shadow mode one.
        timeManager.waitForCondition(
                () -> network.nodes().stream().anyMatch(node -> enforcedPauseCount(node.newLogResult()) > 0),
                REPORT_TIMEOUT,
                "No node paused a read thread, so enforcement was not applied");

        timeManager.waitFor(OBSERVATION_TIME);

        assertThat(network.newLogResults()).allNodesHaveMessageWithPayload(PeerTrafficShapingPayload.class);
        assertThat(network.newPlatformStatusResults())
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));

        // the network was slowed, but it kept working
        assertThat(network.newConsensusResults()).haveAdvancedSinceRound(2).haveEqualCommonRounds();
        assertThat(network.newLogResults()).haveNoErrorLevelMessages();
    }

    // -----------------------------------------------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------------------------------------------

    /**
     * Applies a byte budget far below real gossip traffic to every node in the network.
     *
     * @param network the network to configure
     * @param enforce {@code true} to actually pause read threads, {@code false} for shadow mode
     */
    private static void applyTightBudget(@NonNull final Network network, final boolean enforce) {
        network.withConfigValue(TrafficShapingConfig_.ENABLED, true)
                .withConfigValue(TrafficShapingConfig_.ENFORCE, enforce)
                .withConfigValue(TrafficShapingConfig_.PEER_BYTES_PER_SECOND, TIGHT_BYTES_PER_SECOND)
                .withConfigValue(TrafficShapingConfig_.PEER_BURST_BYTES, TIGHT_BURST_BYTES)
                .withConfigValue(TrafficShapingConfig_.MAX_READ_DELAY, MAX_READ_DELAY)
                .withConfigValue(TrafficShapingConfig_.REPORT_INTERVAL, REPORT_INTERVAL);
    }

    /**
     * @param network the network to inspect
     * @return {@code true} if every node has logged at least one traffic shaping report
     */
    private static boolean allNodesReportedShaping(@NonNull final Network network) {
        return network.nodes().stream().allMatch(node -> reportCount(node.newLogResult()) > 0);
    }

    /**
     * @param result the log result to inspect
     * @return the number of traffic shaping reports in the result
     */
    private static long reportCount(@NonNull final SingleNodeLogResult result) {
        return result.logs().stream()
                .filter(TrafficShapingTest::isShapingReport)
                .count();
    }

    /**
     * Counts reports where a pause was actually applied. Distinguishes enforcement from shadow mode without needing
     * access to node metrics, which the Otter framework does not currently expose.
     *
     * @param result the log result to inspect
     * @return the number of reports carrying a non-zero pause
     */
    private static long enforcedPauseCount(@NonNull final SingleNodeLogResult result) {
        return result.logs().stream()
                .filter(TrafficShapingTest::isShapingReport)
                .map(log -> AbstractLogPayload.parsePayload(PeerTrafficShapingPayload.class, log.message()))
                .filter(payload -> payload.isEnforced() && payload.getDelayNanos() > 0)
                .count();
    }

    /**
     * Matches on the payload type rather than the message text, so the log wording can change without breaking
     * these tests.
     *
     * @param log the log entry to test
     * @return {@code true} if the entry carries a {@link PeerTrafficShapingPayload}
     */
    private static boolean isShapingReport(@NonNull final StructuredLog log) {
        return PAYLOAD_TYPE.equals(AbstractLogPayload.extractPayloadType(log.message()));
    }
}
