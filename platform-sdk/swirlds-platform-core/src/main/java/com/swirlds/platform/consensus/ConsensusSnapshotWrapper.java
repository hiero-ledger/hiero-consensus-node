// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import static com.swirlds.platform.state.service.PbjConverter.toPbjTimestamp;
import static java.util.stream.Collectors.toList;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.common.crypto.Hash;
import com.hedera.hapi.platform.state.MinimumJudgeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import com.swirlds.platform.state.service.PbjConverter;

/**
 * A snapshot of consensus at a particular round. This is all the information (except events) consensus needs to
 * continue from a particular point. Apart from this record, consensus needs all non-ancient events to continue.
 */
public class ConsensusSnapshotWrapper {
    private List<Hash> judgeHashes;
    private final ConsensusSnapshot snapshot;

    /**
     * @param round                the latest round for which fame has been decided
     * @param judgeHashes          the hashes of all the judges for this round, ordered by their creator ID
     * @param minimumJudgeInfoList the minimum ancient threshold for all judges per round, for all non-ancient rounds
     * @param nextConsensusNumber  the consensus order of the next event that will reach consensus
     * @param consensusTimestamp   the consensus time of this snapshot
     */
    public ConsensusSnapshotWrapper(
            final long round,
            @NonNull final List<Hash> judgeHashes,
            @NonNull final List<MinimumJudgeInfo> minimumJudgeInfoList,
            final long nextConsensusNumber,
            @NonNull final Instant consensusTimestamp) {
        this.judgeHashes = Objects.requireNonNull(judgeHashes);
        this.snapshot = new ConsensusSnapshot(
                round,
                judgeHashes.stream().map(Hash::getBytes).collect(toList()),
                minimumJudgeInfoList,
                nextConsensusNumber,
                toPbjTimestamp(consensusTimestamp));
    }

    @NonNull
    public ConsensusSnapshot getSnapshot() {
        return snapshot;
    }

    /**
     * @return the round number of this snapshot
     */
    public long round() {
        return snapshot.round();
    }

    /**
     * @return the hashes of all the judges for this round, ordered by their creator ID
     */
    public @NonNull List<Hash> judgeHashes() {
        return judgeHashes;
    }

    public @NonNull List<MinimumJudgeInfo> getMinimumJudgeInfoList() {
        return snapshot.minimumJudgeInfoList();
    }

    /**
     * @return the consensus order of the next event that will reach consensus
     */
    public long nextConsensusNumber() {
        return snapshot.nextConsensusNumber();
    }

    /**
     * @return the consensus time of this snapshot
     */
    public @NonNull Instant consensusTimestamp() {
        return Objects.requireNonNull(PbjConverter.fromPbjTimestamp(snapshot.consensusTimestamp()));
    }

    /**
     * Returns the minimum generation below which all events are ancient
     *
     * @param roundsNonAncient the number of non-ancient rounds
     * @return minimum non-ancient generation
     */
    public long getAncientThreshold(final int roundsNonAncient) {
        final long oldestNonAncientRound = RoundCalculationUtils.getOldestNonAncientRound(roundsNonAncient, round);
        return getMinimumJudgeAncientThreshold(oldestNonAncientRound);
    }

    /**
     * The minimum ancient threshold of famous witnesses (i.e. judges) for the round specified. This method only looks
     * at non-ancient rounds contained within this state.
     *
     * @param round the round whose minimum judge ancient indicator will be returned
     * @return the minimum judge ancient indicator for the round specified
     * @throws NoSuchElementException if the minimum judge info information for this round is not contained withing this
     *                                state
     */
    public long getMinimumJudgeAncientThreshold(final long round) {
        for (final MinimumJudgeInfo info : getMinimumJudgeInfoList()) {
            if (info.round() == round) {
                return info.minimumJudgeAncientThreshold();
            }
        }
        throw new NoSuchElementException("No minimum judge info found for round: " + round);
    }

    @Override
    public String toString() {
        return snapshot.toString();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof final ConsensusSnapshotWrapper that)) {
            return false;
        }
        return snapshot.equals(that.getSnapshot());
    }

    @Override
    public int hashCode() {
        return snapshot.hashCode();
    }
}
