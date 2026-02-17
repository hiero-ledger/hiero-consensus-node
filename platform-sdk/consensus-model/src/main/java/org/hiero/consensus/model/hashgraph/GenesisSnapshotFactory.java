// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.hashgraph;

import static org.hiero.consensus.model.PbjConverters.toPbjTimestamp;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.MinimumJudgeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;

/**
 * Utility class for generating snapshots
 */
public final class GenesisSnapshotFactory {
    /**
     * Utility class, should not be instantiated
     */
    private GenesisSnapshotFactory() {}

    /**
     * Create a genesis snapshot. This snapshot is not the result of consensus but is instead generated to be used as a
     * starting point for consensus.
     *
     * @return the genesis snapshot, when loaded by consensus, it will start from genesis
     */
    public static @NonNull ConsensusSnapshot newGenesisSnapshot() {
        return ConsensusSnapshot.newBuilder()
                .round(ConsensusConstants.ROUND_FIRST)
                .judgeIds(List.of())
                .minimumJudgeInfoList(
                        List.of(new MinimumJudgeInfo(ConsensusConstants.ROUND_FIRST, ConsensusConstants.ROUND_FIRST)))
                .nextConsensusNumber(ConsensusConstants.FIRST_CONSENSUS_NUMBER)
                .consensusTimestamp(toPbjTimestamp(Instant.EPOCH))
                .build();
    }
}
