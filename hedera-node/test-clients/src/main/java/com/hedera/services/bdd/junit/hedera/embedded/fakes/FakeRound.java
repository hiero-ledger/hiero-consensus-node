// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.embedded.fakes;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import org.hiero.base.crypto.RunningHash;
import org.hiero.consensus.main.model.ConsensusEvent;
import org.hiero.consensus.main.model.Round;

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

    @Override
    public long getRoundNum() {
        return roundNum;
    }

    @Override
    public boolean isEmpty() {
        return consensusEvents.isEmpty();
    }

    @Override
    public List<ConsensusEvent> getConsensusEvents() {
        return consensusEvents;
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

    @NonNull
    @Override
    public ConsensusSnapshot getConsensusSnapshot() {
        return null;
    }

    @Override
    public boolean isPcesRound() {
        return false;
    }

    @Override
    public Instant getReachedConsTimestamp() {
        return null;
    }

    @NonNull
    @Override
    public RunningHash getLastEventRunningHash() {
        return null;
    }
}
