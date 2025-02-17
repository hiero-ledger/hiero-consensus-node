/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.consensus;

import com.swirlds.platform.event.PlatformEvent;
import com.swirlds.platform.state.MinimumJudgeInfo;
import com.swirlds.platform.system.events.EventConstants;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import java.util.stream.LongStream;

/**
 * Utility class for generating "synthetic" snapshots
 */
public final class SyntheticSnapshot {
    /** genesis snapshot, when loaded by consensus, it will start from genesis */
    public static final ConsensusSnapshot GENESIS_SNAPSHOT = new ConsensusSnapshot(
            ConsensusConstants.ROUND_FIRST,
            List.of(),
            List.of(new MinimumJudgeInfo(ConsensusConstants.ROUND_FIRST, EventConstants.FIRST_GENERATION)),
            ConsensusConstants.FIRST_CONSENSUS_NUMBER,
            Instant.EPOCH);

    /** Utility class, should not be instantiated */
    private SyntheticSnapshot() {}

    /**
     * Generate a {@link ConsensusSnapshot} based on the supplied data. This snapshot is not the result of consensus
     * but is instead generated to be used as a starting point for consensus. The snapshot will contain a single
     * judge whose generation will be almost ancient. All events older than the judge will be considered ancient.
     * The judge is the only event needed to continue consensus operations. Once the judge is added to
     * {@link com.swirlds.platform.Consensus}, it will be marked as already having reached consensus beforehand, so it
     * will not reach consensus again.
     *
     * @param round the round of the snapshot
     * @param lastConsensusOrder the last consensus order of all events that have reached consensus
     * @param roundTimestamp the timestamp of the round
     * @param config the consensus configuration
     * @param judge the judge event
     * @return the synthetic snapshot
     */
    public static @NonNull ConsensusSnapshot generateSyntheticSnapshot(
            final long round,
            final long lastConsensusOrder,
            @NonNull final Instant roundTimestamp,
            @NonNull final ConsensusConfig config,
            @NonNull final PlatformEvent judge) {
        final List<MinimumJudgeInfo> minimumJudgeInfos = LongStream.range(
                        RoundCalculationUtils.getOldestNonAncientRound(config.roundsNonAncient(), round), round + 1)
                .mapToObj(r -> new MinimumJudgeInfo(r, judge.getGeneration()))
                .toList();
        return new ConsensusSnapshot(
                round,
                List.of(judge.getHash()),
                minimumJudgeInfos,
                lastConsensusOrder + 1,
                ConsensusUtils.calcMinTimestampForNextEvent(roundTimestamp));
    }

    /**
     * @return the genesis snapshot
     */
    public static @NonNull ConsensusSnapshot getGenesisSnapshot() {
        return GENESIS_SNAPSHOT;
    }
}
