// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.framework.validation;

import static org.assertj.core.api.Assertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * Validates that the timestamps of consensus events increase.
 */
public enum RoundTimestampCheckerValidation implements ConsensusRoundConsistencyValidation {
    INSTANCE;

    /**
     * Validate the timestamps of {@link PlatformEvent} in a consensus round are increasing.
     *
     * @param rounds the rounds to validate
     * @throws AssertionError if the validation fails
     */
    @Override
    public void validate(@NonNull final List<ConsensusRound> rounds) {
        for (final ConsensusRound round : rounds) {
            final List<PlatformEvent> events = round.getConsensusEvents();
            for (int i = 1, n = events.size(); i < n; i++) {

                final PlatformEvent previousEvent = events.get(i - 1);
                final PlatformEvent currentEvent = events.get(i);

                // Check the consensus timestamp
                assertThat(currentEvent.getConsensusTimestamp())
                        .withFailMessage(
                                () -> failMessage("Consensus time does not increase!", previousEvent, currentEvent))
                        .isAfter(previousEvent.getConsensusTimestamp());

                // Check the consensus order
                assertThat(currentEvent.getConsensusOrder())
                        .withFailMessage(() ->
                                failMessage("Consensus order does not increase by 1!", previousEvent, currentEvent))
                        .isEqualTo(previousEvent.getConsensusOrder() + 1);
            }
        }
    }

    /**
     * Renders the failure message for a pair of adjacent events.
     *
     * @param headline describes which of the two checks failed
     * @param previousEvent the earlier of the two events
     * @param currentEvent the latter of the two events
     * @return the failure message
     */
    @NonNull
    private static String failMessage(
            @NonNull final String headline,
            @NonNull final PlatformEvent previousEvent,
            @NonNull final PlatformEvent currentEvent) {
        return String.format(
                "%s%nEvent %s consOrder:%s consTime:%s%nEvent %s consOrder:%s consTime:%s%n",
                headline,
                previousEvent.getDescriptor(),
                previousEvent.getConsensusOrder(),
                previousEvent.getConsensusTimestamp(),
                currentEvent.getDescriptor(),
                currentEvent.getConsensusOrder(),
                currentEvent.getConsensusTimestamp());
    }
}
