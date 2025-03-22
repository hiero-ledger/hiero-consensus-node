// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.service;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.MinimumJudgeInfo;
import com.swirlds.platform.state.PlatformStateModifier;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.event.AncientMode;

/**
 * As the name suggest, the sole purpose of this class is to accumulate changes, so that they could be applied to the
 * platform state atomically in {@link com.swirlds.platform.state.service.WritablePlatformStateStore#bulkUpdate(java.util.function.Consumer)} method.
 * It's not meant to be used for other purposes. This class tracks the changes to the fields to prevent resetting original
 * platform state fields to null if they are not updated.
 */
public class PlatformStateValueAccumulator implements PlatformStateModifier {

    /**
     * The round of this state. This state represents the handling of all transactions that have reached consensus in
     * all previous rounds. All transactions from this round will eventually be applied to this state.
     */
    private long round = GENESIS_ROUND;

    private boolean roundUpdated;

    /**
     * The running event hash computed by the consensus event stream. This should be deleted once the consensus event
     * stream is retired.
     */
    private Hash legacyRunningEventHash;

    private boolean legacyRunningEventHashUpdated;

    /**
     * the consensus timestamp for this signed state
     */
    private Instant consensusTimestamp;

    private boolean consensusTimestampUpdated;

    /**
     * The version of the application software that was responsible for creating this state.
     */
    private SemanticVersion creationSoftwareVersion;

    private boolean creationSoftwareVersionUpdated;

    /**
     * The number of non-ancient rounds.
     */
    private int roundsNonAncient;

    private boolean roundsNonAncientUpdated;

    /**
     * A snapshot of the consensus state at the end of the round, used for restart/reconnect
     */
    private ConsensusSnapshot snapshot;

    private boolean snapshotUpdated;

    /**
     * the time when the freeze starts
     */
    private Instant freezeTime;

    private boolean freezeTimeUpdated;

    /**
     * the last time when a freeze was performed
     */
    private Instant lastFrozenTime;

    private boolean lastFrozenTimeUpdated;

    /**
     * Null if birth round migration has not yet happened, otherwise the software version that was first used when the
     * birth round migration was performed.
     */
    private SemanticVersion firstVersionInBirthRoundMode;

    private boolean firstVersionInBirthRoundModeUpdated;

    /**
     * The last round before the birth round mode was enabled, or -1 if birth round mode has not yet been enabled.
     */
    private long lastRoundBeforeBirthRoundMode = -1;

    private boolean lastRoundBeforeBirthRoundModeUpdated;

    /**
     * The lowest judge generation before the birth round mode was enabled, or -1 if birth round mode has not yet been
     * enabled.
     */
    private long lowestJudgeGenerationBeforeBirthRoundMode = -1;

    private boolean lowestJudgeGenerationBeforeBirthRoundModeUpdated;

    @NonNull
    @Override
    public SemanticVersion getCreationSoftwareVersion() {
        return creationSoftwareVersion;
    }

    @Override
    public void setCreationSoftwareVersion(@NonNull final SemanticVersion creationVersion) {
        this.creationSoftwareVersion = Objects.requireNonNull(creationVersion);
        creationSoftwareVersionUpdated = true;
    }

    /**
     * Get the round when this state was generated.
     *
     * @return a round number
     */
    @Override
    public long getRound() {
        return round;
    }

    /**
     * Set the round when this state was generated.
     *
     * @param round a round number
     */
    @Override
    public void setRound(final long round) {
        this.round = round;
        roundUpdated = true;
    }

    /**
     * Get the legacy running event hash. Used by the consensus event stream.
     *
     * @return a running hash of events
     */
    @Override
    @Nullable
    public Hash getLegacyRunningEventHash() {
        return legacyRunningEventHash;
    }

    /**
     * Set the legacy running event hash. Used by the consensus event stream.
     *
     * @param legacyRunningEventHash a running hash of events
     */
    @Override
    public void setLegacyRunningEventHash(@Nullable final Hash legacyRunningEventHash) {
        this.legacyRunningEventHash = legacyRunningEventHash;
        legacyRunningEventHashUpdated = true;
    }

    /**
     * Get the consensus timestamp for this state, defined as the timestamp of the first transaction that was applied in
     * the round that created the state.
     *
     * @return a consensus timestamp
     */
    @Override
    @Nullable
    public Instant getConsensusTimestamp() {
        return consensusTimestamp;
    }

    /**
     * Set the consensus timestamp for this state, defined as the timestamp of the first transaction that was applied in
     * the round that created the state.
     *
     * @param consensusTimestamp a consensus timestamp
     */
    @Override
    public void setConsensusTimestamp(@NonNull final Instant consensusTimestamp) {
        this.consensusTimestamp = Objects.requireNonNull(consensusTimestamp);
        consensusTimestampUpdated = true;
    }

    /**
     * For the oldest non-ancient round, get the lowest ancient indicator out of all of those round's judges. This is
     * the ancient threshold at the moment after this state's round reached consensus. All events with an ancient
     * indicator that is greater than or equal to this value are non-ancient. All events with an ancient indicator less
     * than this value are ancient.
     *
     * <p>
     * When running in {@link AncientMode#GENERATION_THRESHOLD}, this value is the minimum generation non-ancient. When
     * running in {@link AncientMode#BIRTH_ROUND_THRESHOLD}, this value is the minimum birth round non-ancient.
     * </p>
     *
     * @return the ancient threshold after this round has reached consensus
     */
    @Override
    public long getAncientThreshold() {
        if (snapshot == null) {
            throw new IllegalStateException(
                    "No minimum judge info found in state for round " + round + ", snapshot is null");
        }

        final List<MinimumJudgeInfo> minimumJudgeInfo = snapshot.minimumJudgeInfoList();
        if (minimumJudgeInfo.isEmpty()) {
            throw new IllegalStateException(
                    "No minimum judge info found in state for round " + round + ", list is empty");
        }

        return minimumJudgeInfo.getFirst().minimumJudgeAncientThreshold();
    }

    /**
     * Sets the number of non-ancient rounds.
     *
     * @param roundsNonAncient the number of non-ancient rounds
     */
    @Override
    public void setRoundsNonAncient(final int roundsNonAncient) {
        this.roundsNonAncient = roundsNonAncient;
        roundsNonAncientUpdated = true;
    }

    /**
     * Gets the number of non-ancient rounds.
     *
     * @return the number of non-ancient rounds
     */
    @Override
    public int getRoundsNonAncient() {
        return roundsNonAncient;
    }

    /**
     * @return the consensus snapshot for this round
     */
    @Override
    @Nullable
    public ConsensusSnapshot getSnapshot() {
        return snapshot;
    }

    /**
     * @param snapshot the consensus snapshot for this round
     */
    @Override
    public void setSnapshot(@NonNull final ConsensusSnapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot);
        snapshotUpdated = true;
    }

    /**
     * Gets the time when the next freeze is scheduled to start. If null then there is no freeze scheduled.
     *
     * @return the time when the freeze starts
     */
    @Override
    @Nullable
    public Instant getFreezeTime() {
        return freezeTime;
    }

    /**
     * Sets the instant after which the platform will enter FREEZING status. When consensus timestamp of a signed state
     * is after this instant, the platform will stop creating events and accepting transactions. This is used to safely
     * shut down the platform for maintenance.
     *
     * @param freezeTime an Instant in UTC
     */
    @Override
    public void setFreezeTime(@Nullable final Instant freezeTime) {
        this.freezeTime = freezeTime;
        freezeTimeUpdated = true;
    }

    /**
     * Gets the last freezeTime based on which the nodes were frozen. If null then there has never been a freeze.
     *
     * @return the last freezeTime based on which the nodes were frozen
     */
    @Override
    @Nullable
    public Instant getLastFrozenTime() {
        return lastFrozenTime;
    }

    /**
     * Sets the last freezeTime based on which the nodes were frozen.
     *
     * @param lastFrozenTime the last freezeTime based on which the nodes were frozen
     */
    @Override
    public void setLastFrozenTime(@Nullable final Instant lastFrozenTime) {
        this.lastFrozenTime = lastFrozenTime;
        lastFrozenTimeUpdated = true;
    }

    /**
     * Get the first software version where the birth round migration happened, or null if birth round migration has not
     * yet happened.
     *
     * @return the first software version where the birth round migration happened
     */
    @Override
    @Nullable
    public SemanticVersion getFirstVersionInBirthRoundMode() {
        return firstVersionInBirthRoundMode;
    }

    /**
     * Set the first software version where the birth round migration happened.
     *
     * @param firstVersionInBirthRoundMode the first software version where the birth round migration happened
     */
    @Override
    public void setFirstVersionInBirthRoundMode(final SemanticVersion firstVersionInBirthRoundMode) {
        this.firstVersionInBirthRoundMode = firstVersionInBirthRoundMode;
        firstVersionInBirthRoundModeUpdated = true;
    }

    /**
     * Get the last round before the birth round mode was enabled, or -1 if birth round mode has not yet been enabled.
     *
     * @return the last round before the birth round mode was enabled
     */
    @Override
    public long getLastRoundBeforeBirthRoundMode() {
        return lastRoundBeforeBirthRoundMode;
    }

    /**
     * Set the last round before the birth round mode was enabled.
     *
     * @param lastRoundBeforeBirthRoundMode the last round before the birth round mode was enabled
     */
    @Override
    public void setLastRoundBeforeBirthRoundMode(final long lastRoundBeforeBirthRoundMode) {
        this.lastRoundBeforeBirthRoundMode = lastRoundBeforeBirthRoundMode;
        lastRoundBeforeBirthRoundModeUpdated = true;
    }

    /**
     * Get the lowest judge generation before the birth round mode was enabled, or -1 if birth round mode has not yet
     * been enabled.
     *
     * @return the lowest judge generation before the birth round mode was enabled
     */
    @Override
    public long getLowestJudgeGenerationBeforeBirthRoundMode() {
        return lowestJudgeGenerationBeforeBirthRoundMode;
    }

    /**
     * Set the lowest judge generation before the birth round mode was enabled.
     *
     * @param lowestJudgeGenerationBeforeBirthRoundMode the lowest judge generation before the birth round mode was
     *                                                  enabled
     */
    @Override
    public void setLowestJudgeGenerationBeforeBirthRoundMode(final long lowestJudgeGenerationBeforeBirthRoundMode) {
        this.lowestJudgeGenerationBeforeBirthRoundMode = lowestJudgeGenerationBeforeBirthRoundMode;
        lowestJudgeGenerationBeforeBirthRoundModeUpdated = true;
    }

    public boolean isRoundUpdated() {
        return roundUpdated;
    }

    public boolean isLegacyRunningEventHashUpdated() {
        return legacyRunningEventHashUpdated;
    }

    public boolean isConsensusTimestampUpdated() {
        return consensusTimestampUpdated;
    }

    public boolean isCreationSoftwareVersionUpdated() {
        return creationSoftwareVersionUpdated;
    }

    public boolean isRoundsNonAncientUpdated() {
        return roundsNonAncientUpdated;
    }

    public boolean isSnapshotUpdated() {
        return snapshotUpdated;
    }

    public boolean isFreezeTimeUpdated() {
        return freezeTimeUpdated;
    }

    public boolean isLastFrozenTimeUpdated() {
        return lastFrozenTimeUpdated;
    }

    public boolean isFirstVersionInBirthRoundModeUpdated() {
        return firstVersionInBirthRoundModeUpdated;
    }

    public boolean isLastRoundBeforeBirthRoundModeUpdated() {
        return lastRoundBeforeBirthRoundModeUpdated;
    }

    public boolean isLowestJudgeGenerationBeforeBirthRoundModeUpdated() {
        return lowestJudgeGenerationBeforeBirthRoundModeUpdated;
    }

    @Override
    public void bulkUpdate(@NonNull Consumer<PlatformStateModifier> updater) {
        updater.accept(this);
    }
}
