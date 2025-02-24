// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.MinimumJudgeInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.state.service.PbjConverter;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A snapshot of consensus at a particular round. This is all the information (except events) consensus needs to
 * continue from a particular point. Apart from this record, consensus needs all non-ancient events to continue.
 */
public class ConsensusSnapshotWrapper {
    private final ConsensusSnapshot snapshot;

    public ConsensusSnapshotWrapper(
            final long round,
            @NonNull final List<Bytes> judgeHashes,
            @NonNull final List<MinimumJudgeInfo> minimumJudgeInfoList,
            final long nextConsensusNumber,
            @NonNull final Timestamp consensusTimestamp) {
        this.snapshot = new ConsensusSnapshot(
                round,
                judgeHashes,
                minimumJudgeInfoList,
                nextConsensusNumber,
                consensusTimestamp);
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
    //TODO remove
    public @NonNull Instant consensusTimestampOld() {
        return Objects.requireNonNull(PbjConverter.fromPbjTimestamp(snapshot.consensusTimestamp()));
    }

    public @NonNull Timestamp consensusTimestamp() {
        return Objects.requireNonNull(snapshot.consensusTimestamp());
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
