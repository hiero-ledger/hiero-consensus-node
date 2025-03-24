// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.function.Consumer;
import org.hiero.consensus.model.crypto.Hash;

/**
 * This interface represents the platform state and provide methods for modifying the state.
 */
public interface PlatformStateModifier extends PlatformStateAccessor {

    /**
     * Set the software version of the application that created this state.
     *
     * @param creationVersion the creation version
     */
    void setCreationSoftwareVersion(@NonNull SemanticVersion creationVersion);

    /**
     * Set the round when this state was generated.
     *
     * @param round a round number
     */
    void setRound(long round);

    /**
     * Set the legacy running event hash. Used by the consensus event stream.
     *
     * @param legacyRunningEventHash a running hash of events
     */
    void setLegacyRunningEventHash(@Nullable Hash legacyRunningEventHash);

    /**
     * Set the consensus timestamp for this state, defined as the timestamp of the first transaction that was applied in
     * the round that created the state.
     *
     * @param consensusTimestamp a consensus timestamp
     */
    void setConsensusTimestamp(@NonNull Instant consensusTimestamp);

    /**
     * Sets the number of non-ancient rounds.
     *
     * @param roundsNonAncient the number of non-ancient rounds
     */
    void setRoundsNonAncient(int roundsNonAncient);

    /**
     * @param snapshot the consensus snapshot for this round
     */
    void setSnapshot(@NonNull ConsensusSnapshot snapshot);

    /**
     * Sets the instant after which the platform will enter FREEZING status. When consensus timestamp of a signed state
     * is after this instant, the platform will stop creating events and accepting transactions. This is used to safely
     * shut down the platform for maintenance.
     *
     * @param freezeTime an Instant in UTC
     */
    void setFreezeTime(@Nullable Instant freezeTime);

    /**
     * Sets the last freezeTime based on which the nodes were frozen.
     *
     * @param lastFrozenTime the last freezeTime based on which the nodes were frozen
     */
    void setLastFrozenTime(@Nullable Instant lastFrozenTime);

    /**
     * Set the first software version where the birth round migration happened.
     *
     * @param firstVersionInBirthRoundMode the first software version where the birth round migration happened
     */
    void setFirstVersionInBirthRoundMode(SemanticVersion firstVersionInBirthRoundMode);

    /**
     * Set the last round before the birth round mode was enabled.
     *
     * @param lastRoundBeforeBirthRoundMode the last round before the birth round mode was enabled
     */
    void setLastRoundBeforeBirthRoundMode(long lastRoundBeforeBirthRoundMode);

    /**
     * Set the lowest judge generation before the birth round mode was enabled.
     *
     * @param lowestJudgeGenerationBeforeBirthRoundMode the lowest judge generation before the birth round mode was
     *                                                  enabled
     */
    void setLowestJudgeGenerationBeforeBirthRoundMode(long lowestJudgeGenerationBeforeBirthRoundMode);

    /**
     * This is a convenience method to update multiple fields in the platform state in a single operation.
     * @param updater a consumer that updates the platform state
     */
    void bulkUpdate(@NonNull Consumer<PlatformStateModifier> updater);
}
