// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.otter.fixtures.Capability.DETERMINISTIC_EXECUTION;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;

/**
 * Tests that a node which is far behind its peers stops creating events until it has caught up, as the sync lag rule
 * does in production.
 */
public class SyncLagTest {

    /** The default of {@code event.creation.maxAllowedSyncLag}. */
    private static final long MAX_ALLOWED_SYNC_LAG = 15;

    /** Rounds of margin around the threshold, to allow for the delay between a peer's window and its delivery. */
    private static final long MARGIN = 3;

    /**
     * Isolates a node until it is well behind its peers (but not far enough to fall behind), rejoins it, and checks
     * that it creates no events while it lags by {@link #MAX_ALLOWED_SYNC_LAG} rounds or more, and creates events again
     * afterwards. Event creation is observed through the birth rounds of the node's events in its peers' consensus.
     *
     * @param env the test environment
     */
    @OtterTest(requires = DETERMINISTIC_EXECUTION)
    void nodeDoesNotCreateEventsWhileFarBehind(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        network.addNodes(4);
        network.start();

        timeManager.waitFor(Duration.ofSeconds(5));

        final Node lagging = network.nodes().getLast();
        final Node peer = network.nodes().getFirst();

        network.isolate(lagging);
        timeManager.waitForCondition(
                () -> lag(peer, lagging) >= MAX_ALLOWED_SYNC_LAG + 2 * MARGIN,
                Duration.ofSeconds(120),
                "Peers did not get far enough ahead of the isolated node");

        final long laggingRoundAtRejoin = lagging.newConsensusResult().lastRoundNum();
        final long peerRoundAtRejoin = peer.newConsensusResult().lastRoundNum();
        network.rejoin(lagging);

        // Wait until the node has caught up, then let its newest events reach consensus on the peer
        timeManager.waitForCondition(
                () -> lag(peer, lagging) <= 1, Duration.ofSeconds(60), "The node did not catch up after rejoining");
        timeManager.waitFor(Duration.ofSeconds(5));

        // The birth round of an event is the node's own round when it was created. Events born while the node was
        // still more than the allowed lag behind the peers must not exist.
        final List<Long> birthRounds = peer.newConsensusResult().consensusRounds().stream()
                .flatMap(round -> round.getConsensusEvents().stream())
                .filter(event -> event.getCreatorId().equals(lagging.selfId()))
                .map(PlatformEvent::getBirthRound)
                .toList();
        final long firstBlockedBirthRound = laggingRoundAtRejoin + 2;
        final long lastBlockedBirthRound = peerRoundAtRejoin - MAX_ALLOWED_SYNC_LAG - MARGIN;
        assertThat(lastBlockedBirthRound).isGreaterThan(firstBlockedBirthRound);
        assertThat(birthRounds)
                .as("birth rounds of events created by the lagging node")
                .noneMatch(round -> round >= firstBlockedBirthRound && round <= lastBlockedBirthRound);

        // Once caught up, the node creates events again
        assertThat(birthRounds).anyMatch(round -> round > peerRoundAtRejoin);
    }

    private static long lag(@NonNull final Node peer, @NonNull final Node node) {
        return peer.newConsensusResult().lastRoundNum()
                - node.newConsensusResult().lastRoundNum();
    }
}
