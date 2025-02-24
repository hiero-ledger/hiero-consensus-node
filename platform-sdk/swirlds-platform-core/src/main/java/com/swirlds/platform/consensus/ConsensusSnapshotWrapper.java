// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import static com.swirlds.platform.state.service.PbjConverter.toPbjTimestamp;
import static java.util.stream.Collectors.toList;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.pbj.runtime.io.buffer.Bytes;
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
        this.snapshot = new ConsensusSnapshot(
                round,
                judgeHashes.stream().map(Hash::getBytes).collect(toList()),
                minimumJudgeInfoList,
                nextConsensusNumber,
                toPbjTimestamp(consensusTimestamp));
    }

    public ConsensusSnapshotWrapper(final ConsensusSnapshot snapshot) {
        this.snapshot = snapshot;
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

    public @NonNull List<Bytes> judgeHashes() {
        return snapshot.judgeHashes();
    }

    public @NonNull List<MinimumJudgeInfo> minimumJudgeInfoList() {
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
