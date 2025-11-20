// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.addressbook;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.roster.RosterUtils;

/**
 * A builder for {@link RosterHistory} for use in tests.
 */
public class RosterHistoryBuilder {

    private final List<RoundRosterPair> rosterPairs = new ArrayList<>();
    private final Map<Bytes, Roster> rosterMap = new HashMap<>();

    /**
     * Add a roster for the first possible consensus round.
     *
     * @param roster the roster to add
     * @return this builder
     */
    @NonNull
    public RosterHistoryBuilder withRoster(@NonNull final Roster roster) {
        return withRoster(ConsensusConstants.ROUND_FIRST, roster);
    }

    /**
     * Add a roster for a specific round.
     *
     * @param round the round number this roster becomes active in
     * @param roster the roster to add
     * @return this builder
     */
    @NonNull
    public RosterHistoryBuilder withRoster(final long round, @NonNull final Roster roster) {
        final Bytes rosterHash = RosterUtils.hash(roster).getBytes();
        rosterPairs.add(RoundRosterPair.newBuilder()
                .roundNumber(round)
                .activeRosterHash(rosterHash)
                .build());
        rosterMap.put(rosterHash, roster);
        return this;
    }

    /**
     * Build the {@link RosterHistory}.
     *
     * @return the built {@link RosterHistory}
     */
    @NonNull
    public RosterHistory build() {
        return new RosterHistory(rosterPairs, rosterMap);
    }
}
