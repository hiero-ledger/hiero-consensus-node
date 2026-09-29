// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.roster;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;

/**
 * A record that holds the inputs for a roster, including the history of round-roster pairs and the map of rosters.
 *
 * <p>The {@code candidateRosterHash} is always expected to be {@code Bytes.EMPTY} until DAB will be implemented.
 *
 * @param history the list of round-roster pairs representing the history of active rosters
 * @param rosters the map of roster hashes to their corresponding rosters
 */
public record RosterInputs(
        @NonNull List<RoundRosterPair> history,
        @NonNull Map<Bytes, Roster> rosters,
        @NonNull Bytes candidateRosterHash) {

    public RosterInputs {
        if (candidateRosterHash.length() > 0) {
            throw new IllegalArgumentException(
                    "candidateRosterHash is not supported yet and must be empty, but was: " + candidateRosterHash);
        }
    }
}
