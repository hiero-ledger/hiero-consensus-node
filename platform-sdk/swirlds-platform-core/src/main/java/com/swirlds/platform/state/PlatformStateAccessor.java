// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.event.AncientMode;

/**
 * This interface represents the platform state and provide access to the state's properties.
 */
public interface PlatformStateAccessor {
    /**
     * The round of the genesis state.
     */
    long GENESIS_ROUND = 0;

    /**
     * Get the software version of the application that created this state.
     *
     * @return the creation version
     */
    @NonNull
    SemanticVersion getCreationSoftwareVersion();

    /**
     * Get the round when this state was generated.
     *
     * @return a round number
     */
    long getRound();

    /**
     * Get the legacy running event hash. Used by the consensus event stream.
     *
     * @return a running hash of events
     */
    @Nullable
    Hash getLegacyRunningEventHash();

    /**
     * Get the consensus timestamp for this state, defined as the timestamp of the first transaction that was applied in
     * the round that created the state.
     *
     * @return a consensus timestamp
     */
    @Nullable
    Instant getConsensusTimestamp();

    /**
     * For the oldest non-ancient round, get the lowest ancient indicator out of all of those round's judges. This is
     * the ancient threshold at the moment after this state's round reached consensus. All events with an ancient
     * indicator that is greater than or equal to this value are non-ancient. All events with an ancient indicator less
     * than this value are ancient.
     * <p>
     * When running in {@link AncientMode#GENERATION_THRESHOLD}, this value is the minimum generation non-ancient. When
     * running in {@link AncientMode#BIRTH_ROUND_THRESHOLD}, this value is the minimum birth round non-ancient.
     * </p>
     * @return the ancient threshold after this round has reached consensus
     * @throws IllegalStateException if no minimum judge info is found in the state
     */
    long getAncientThreshold();

    /**
     * Gets the number of non-ancient rounds.
     *
     * @return the number of non-ancient rounds
     */
    int getRoundsNonAncient();

    /**
     * @return the consensus snapshot for this round
     */
    @Nullable
    ConsensusSnapshot getSnapshot();

    /**
     * Gets the time when the next freeze is scheduled to start. If null then there is no freeze scheduled.
     *
     * @return the time when the freeze starts
     */
    @Nullable
    Instant getFreezeTime();

    /**
     * Gets the last freezeTime based on which the nodes were frozen. If null then there has never been a freeze.
     *
     * @return the last freezeTime based on which the nodes were frozen
     */
    @Nullable
    Instant getLastFrozenTime();

    /**
     * Get the first software version where the birth round migration happened, or null if birth round migration has not
     * yet happened.
     *
     * @return the first software version where the birth round migration happened
     */
    @Nullable
    SemanticVersion getFirstVersionInBirthRoundMode();

    /**
     * Get the last round before the birth round mode was enabled, or -1 if birth round mode has not yet been enabled.
     *
     * @return the last round before the birth round mode was enabled
     */
    long getLastRoundBeforeBirthRoundMode();

    /**
     * Get the lowest judge generation before the birth round mode was enabled, or -1 if birth round mode has not yet
     * been enabled.
     *
     * @return the lowest judge generation before the birth round mode was enabled
     */
    long getLowestJudgeGenerationBeforeBirthRoundMode();
}
