// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * Utility class for testing the behavior of birth rounds in conjunction with a freeze.
 */
public class BirthRoundFreezeTestUtils {

    /**
     * Asserts that the birth rounds of events created before and after a freeze/restart are correctly set.
     *
     * @param consensusRounds        the list of consensus rounds that reached consensus including rounds from before
     *                               and after the freeze from at least one node
     * @param postFreezeShutdownTime the time after which the network was frozen and shutdown, and before it was started
     *                               again
     * @param freezeRound            the freeze round number
     */
    public static void assertBirthRoundsBeforeAndAfterFreeze(
            final List<ConsensusRound> consensusRounds, final Instant postFreezeShutdownTime, final long freezeRound) {
        final ConsensusRound firstRound = consensusRounds.getFirst();
        final ConsensusRound lastRound = consensusRounds.getLast();
        assertThat(firstRound.getRoundNum()).isLessThan(freezeRound);
        assertThat(lastRound.getRoundNum()).isGreaterThan(freezeRound);
        for (final ConsensusRound round : consensusRounds) {
            for (final PlatformEvent event : round.getConsensusEvents()) {
                if (event.getTimeCreated().isAfter(postFreezeShutdownTime)) {
                    assertThat(event.getBirthRound()).isGreaterThan(freezeRound);
                } else {
                    assertThat(event.getBirthRound()).isLessThanOrEqualTo(freezeRound);
                }
            }
        }
    }
}
