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

public class RosterHistoryBuilder {

    private final List<RoundRosterPair> rosterPairs = new ArrayList<>();
    private final Map<Bytes, Roster> rosterMap = new HashMap<>();

    @NonNull
    public RosterHistoryBuilder withRoster(@NonNull final Roster roster) {
        return withRoster(ConsensusConstants.ROUND_FIRST, roster);
    }

    @NonNull
    public RosterHistoryBuilder withRoster(final long round, @NonNull final Roster roster) {
        final Bytes rosterHash = RosterUtils.hash(roster).getBytes();
        rosterPairs.add(
                RoundRosterPair.newBuilder()
                        .roundNumber(round)
                        .activeRosterHash(rosterHash)
                        .build());
        rosterMap.put(rosterHash, roster);
        return this;
    }

    @NonNull
    public RosterHistory build() {
        return new RosterHistory(rosterPairs, rosterMap);
    }

}
