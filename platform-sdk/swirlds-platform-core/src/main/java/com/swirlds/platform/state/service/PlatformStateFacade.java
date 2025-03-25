// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.service;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.swirlds.platform.state.MerkleStateUtils.createInfoString;
import static com.swirlds.platform.state.PlatformStateAccessor.GENESIS_ROUND;
import static com.swirlds.platform.state.service.PlatformStateService.NAME;
import static com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema.PLATFORM_STATE_KEY;
import static com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema.UNINITIALIZED_PLATFORM_STATE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.PlatformState;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.platform.state.PlatformStateAccessor;
import com.swirlds.platform.state.PlatformStateModifier;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.function.Consumer;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.hashgraph.Round;

/**
 * This class is an entry point for the platform state. Though the class itself is stateless, given an instance of {@link State},
 * it can find an instance of {@link PlatformStateAccessor} or {@link PlatformStateModifier} and provide access to particular properties
 * of the platform state.
 */
public class PlatformStateFacade {

    public static final PlatformStateFacade DEFAULT_PLATFORM_STATE_FACADE = new PlatformStateFacade();

    /**
     * Given a {@link State}, returns the creation version of the platform state if it exists.
     * @param state the state to extract the creation version from
     * @return the creation version of the platform state, or null if the state is a genesis state
     */
    public SemanticVersion creationSemanticVersionOf(@NonNull final State state) {
        requireNonNull(state);
        final PlatformState platformState = platformStateOf(state);
        return platformState == null ? null : platformState.creationSoftwareVersion();
    }

    /**
     * @param state the state to extract value from
     * @param round the round to check
     * @return true if the round is a freeze round
     */
    public boolean isFreezeRound(@NonNull final State state, @NonNull final Round round) {
        final var platformState = platformStateOf(state);
        return isInFreezePeriod(
                round.getConsensusTimestamp(),
                platformState.freezeTime() == null ? null : asInstant(platformState.freezeTime()),
                platformState.lastFrozenTime() == null ? null : asInstant(platformState.lastFrozenTime()));
    }

    /**
     * Determines if the provided {@code state} is a genesis state.
     * @param state the state to check
     * @return true if the state is a genesis state
     */
    public boolean isGenesisStateOf(@NonNull final State state) {
        return readablePlatformStateStore(state).getRound() == GENESIS_ROUND;
    }

    /**
     * Determines if a {@code timestamp} is in a freeze period according to the provided timestamps.
     *
     * @param consensusTime  the consensus time to check
     * @param freezeTime     the freeze time
     * @param lastFrozenTime the last frozen time
     * @return true is the {@code timestamp} is in a freeze period
     */
    public static boolean isInFreezePeriod(
            @NonNull final Instant consensusTime,
            @Nullable final Instant freezeTime,
            @Nullable final Instant lastFrozenTime) {

        // if freezeTime is not set, or consensusTime is before freezeTime, we are not in a freeze period
        // if lastFrozenTime is equal to or after freezeTime, which means the nodes have been frozen once at/after the
        // freezeTime, we are not in a freeze period
        if (freezeTime == null || consensusTime.isBefore(freezeTime)) {
            return false;
        }
        // Now we should check whether the nodes have been frozen at the freezeTime.
        // when consensusTime is equal to or after freezeTime,
        // and lastFrozenTime is before freezeTime, we are in a freeze period.
        return lastFrozenTime == null || lastFrozenTime.isBefore(freezeTime);
    }

    /**
     * Given a {@link State}, returns the creation version of the state if it was deserialized, or null otherwise.
     * @param state the state
     * @return the version of the state if it was deserialized, otherwise null
     */
    @Nullable
    public SemanticVersion creationSoftwareVersionOf(@NonNull final State state) {
        requireNonNull(state);
        if (isPlatformStateEmpty(state)) {
            return null;
        }
        return readablePlatformStateStore(state).getCreationSoftwareVersion();
    }

    private static boolean isPlatformStateEmpty(State state) {
        return state.getReadableStates(NAME).isEmpty();
    }

    /**
     * Given a {@link State}, returns the round number of the platform state if it exists.
     * @param root the root to extract the round number from
     * @return the round number of the platform state, or zero if the state is a genesis state
     */
    public long roundOf(@NonNull final State root) {
        requireNonNull(root);
        return readablePlatformStateStore(root).getRound();
    }

    /**
     * Given a {@link State}, returns an instance of {@link PlatformState} if it exists.
     * @param state the state to extract the platform state from
     * @return the platform state, or null if the state is a genesis state
     */
    @SuppressWarnings("unchecked")
    public @Nullable PlatformState platformStateOf(@NonNull final State state) {
        final ReadableStates readableStates = state.getReadableStates(NAME);
        if (readableStates.isEmpty()) {
            return UNINITIALIZED_PLATFORM_STATE;
        } else {
            return (PlatformState)
                    readableStates.getSingleton(PLATFORM_STATE_KEY).get();
        }
    }

    /**
     * Given a {@link State}, returns the legacy running event hash if it exists.
     * @param state the state to extract the legacy running event hash from
     * @return the legacy running event hash, or null if the state is a genesis state
     */
    @Nullable
    public Hash legacyRunningEventHashOf(@NonNull final State state) {
        return readablePlatformStateStore(state).getLegacyRunningEventHash();
    }

    /**
     * Given a {@link State}, for the oldest non-ancient round, get the lowest ancient indicator out of all of those round's judges.
     * See {@link PlatformStateAccessor#getAncientThreshold()} for more information.
     * @param state the state to extract the ancient threshold from
     * @return the ancient threshold, or zero if the state is a genesis state
     */
    public long ancientThresholdOf(@NonNull final State state) {
        return readablePlatformStateStore(state).getAncientThreshold();
    }

    /**
     * Given a {@link State}, returns the consensus snapshot if it exists.
     * @param root the root to extract the consensus snapshot from
     * @return the consensus snapshot, or null if the state is a genesis state
     */
    @Nullable
    public ConsensusSnapshot consensusSnapshotOf(@NonNull final State root) {
        return readablePlatformStateStore(root).getSnapshot();
    }

    /**
     * Given a {@link State}, returns the first software version where the birth round migration happened,
     * or null if birth round migration has not yet happened.
     * @param state the state to extract the first version in birth round mode from
     * @return the number of non-ancient rounds, or zero if the state is a genesis state
     */
    @Nullable
    public SemanticVersion firstVersionInBirthRoundModeOf(@NonNull final State state) {
        return readablePlatformStateStore(state).getFirstVersionInBirthRoundMode();
    }

    /**
     * Given a {@link State}, returns the last round before the birth round mode was enabled.
     * @param root the root to extract the round number from
     * @return the last round before the birth round mode was enabled, or zero if the state is a genesis state
     */
    public long lastRoundBeforeBirthRoundModeOf(@NonNull final State root) {
        return readablePlatformStateStore(root).getLastRoundBeforeBirthRoundMode();
    }

    /**
     * Given a {@link State}, lowest judge generation before birth round mode.
     * @param state the state to extract the judge generation from
     * @return the number of non-ancient rounds, or zero if the state is a genesis state
     */
    public long lowestJudgeGenerationBeforeBirthRoundModeOf(@NonNull final State state) {
        return readablePlatformStateStore(state).getLowestJudgeGenerationBeforeBirthRoundMode();
    }

    /**
     * Given a {@link State}, returns consensus timestamp if it exists.
     * @param state the state to extract the consensus timestamp from
     * @return the consensus timestamp, or null if the state is a genesis state
     */
    @Nullable
    public Instant consensusTimestampOf(@NonNull final State state) {
        return readablePlatformStateStore(state).getConsensusTimestamp();
    }

    /**
     * Given a {@link State}, returns the freeze time of the state if it exists.
     * @param state the state to extract the freeze time from
     * @return the freeze time, or null if the state is a genesis state
     */
    public Instant freezeTimeOf(@NonNull final State state) {
        return readablePlatformStateStore(state).getFreezeTime();
    }

    /**
     * Update the last frozen time of the state.
     * @param state the state to update
     */
    public void updateLastFrozenTime(@NonNull final State state) {
        getWritablePlatformStateOf(state).setLastFrozenTime(freezeTimeOf(state));
    }

    /**
     * Given a {@link State}, returns the last frozen time of the state if it exists.
     * @param state the state to extract the last frozen time from
     * @return the last frozen time, or null if the state is a genesis state
     */
    @Nullable
    public Instant lastFrozenTimeOf(State state) {
        return readablePlatformStateStore(state).getLastFrozenTime();
    }

    /**
     * Get writable platform state. Works only on mutable {@link State}.
     * Call this method only if you need to modify the platform state.
     *
     * @return mutable platform state
     */
    @NonNull
    protected PlatformStateModifier getWritablePlatformStateOf(@NonNull final State state) {
        if (state.isImmutable()) {
            throw new IllegalStateException("Cannot get writable platform state when state is immutable");
        }
        return writablePlatformStateStore(state);
    }

    /**
     * This is a convenience method to update multiple fields in the platform state in a single operation.
     * @param updater a consumer that updates the platform state
     */
    public void bulkUpdateOf(@NonNull final State state, @NonNull Consumer<PlatformStateModifier> updater) {
        getWritablePlatformStateOf(state).bulkUpdate(updater);
    }

    /**
     * @param snapshot the consensus snapshot for this round
     */
    public void setSnapshotTo(@NonNull final State state, @NonNull ConsensusSnapshot snapshot) {
        getWritablePlatformStateOf(state).setSnapshot(snapshot);
    }

    /**
     * Set the legacy running event hash. Used by the consensus event stream.
     *
     * @param legacyRunningEventHash a running hash of events
     */
    public void setLegacyRunningEventHashTo(@NonNull final State state, @Nullable Hash legacyRunningEventHash) {
        getWritablePlatformStateOf(state).setLegacyRunningEventHash(legacyRunningEventHash);
    }

    /**
     * Set the software version of the application that created this state.
     *
     * @param creationVersion the creation version
     */
    public void setCreationSoftwareVersionTo(@NonNull final State state, @NonNull SemanticVersion creationVersion) {
        getWritablePlatformStateOf(state).setCreationSoftwareVersion(creationVersion);
    }

    /**
     * Generate a string that describes this state.
     *
     * @param hashDepth the depth of the tree to visit and print
     */
    @NonNull
    public String getInfoString(@NonNull final State state, final int hashDepth) {
        return createInfoString(hashDepth, readablePlatformStateStore(state), state.getHash(), (MerkleNode) state);
    }

    private PlatformStateAccessor readablePlatformStateStore(@NonNull final State state) {
        final ReadableStates readableStates = state.getReadableStates(NAME);
        if (readableStates.isEmpty()) {
            return new SnapshotPlatformStateAccessor(UNINITIALIZED_PLATFORM_STATE);
        }
        return new ReadablePlatformStateStore(readableStates);
    }

    private WritablePlatformStateStore writablePlatformStateStore(@NonNull final State state) {
        return new WritablePlatformStateStore(state.getWritableStates(NAME));
    }
}
