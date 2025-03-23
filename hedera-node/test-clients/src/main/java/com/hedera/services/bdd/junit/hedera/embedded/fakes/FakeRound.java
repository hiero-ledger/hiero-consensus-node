// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.embedded.fakes;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.hashgraph.Round;

public class FakeRound implements Round {
    private final long roundNum;
    private final Roster roster;
    private final List<ConsensusEvent> consensusEvents;

    public FakeRound(
            final long roundNum, @NonNull final Roster roster, @NonNull final List<ConsensusEvent> consensusEvents) {
        this.roundNum = roundNum;
        this.roster = requireNonNull(roster);
        this.consensusEvents = requireNonNull(consensusEvents);
    }

    @NonNull
    @Override
    public Iterator<ConsensusEvent> iterator() {
        return consensusEvents.iterator();
    }

    @Override
    public long getRoundNum() {
        return roundNum;
    }

    @Override
    public boolean isEmpty() {
        return consensusEvents.isEmpty();
    }

    @Override
    public int getEventCount() {
        return consensusEvents.size();
    }

    @NonNull
    @Override
    public Roster getConsensusRoster() {
        return roster;
    }

    @NonNull
    @Override
    public Instant getConsensusTimestamp() {
        return consensusEvents.getLast().getConsensusTimestamp();
    }
}
