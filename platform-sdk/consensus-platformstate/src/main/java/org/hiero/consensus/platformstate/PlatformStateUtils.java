// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.platformstate;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.platformstate.PlatformStateAccessor.GENESIS_ROUND;
import static org.hiero.consensus.platformstate.PlatformStateService.NAME;
import static org.hiero.consensus.platformstate.V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID;
import static org.hiero.consensus.platformstate.V0540PlatformStateSchema.UNINITIALIZED_PLATFORM_STATE;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.MinimumJudgeInfo;
import com.hedera.hapi.platform.state.PlatformState;
import com.swirlds.base.formatting.TextTable;
import com.swirlds.common.utility.Mnemonics;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.hashgraph.Round;

/**
 * This class is an entry point for the platform state. Though the class itself is stateless, given an instance of
 * {@link State}, it can find an instance of {@link PlatformStateAccessor} or {@link PlatformStateModifier} and provide
 * access to particular properties of the platform state.
 */
public final class PlatformStateUtils {
    public static final String HASH_INFO_TEMPLATE = "(root) VirtualMap    state    /    %s";

    /**
     * @param state the state to extract value from
     * @param round the round to check
     * @return true if the round is a freeze round
     */
    public static boolean isFreezeRound(@NonNull final State state, @NonNull final Round round) {
        final var platformState = platformStateOf(state);
        return isInFreezePeriod(
                round.getConsensusTimestamp(),
                platformState.freezeTime() == null ? null : asInstant(platformState.freezeTime()),
                platformState.lastFrozenTime() == null ? null : asInstant(platformState.lastFrozenTime()));
    }

    /**
     * Given a {@link State}, returns the creation version of the platform state if it exists.
     *
     * @param state the state to extract the creation version from
     * @return the creation version of the platform state, or null if the state is a genesis state
     */
    public static SemanticVersion creationSemanticVersionOf(@NonNull final State state) {
        requireNonNull(state);
        final PlatformState platformState = platformStateOf(state);
        return platformState == null ? null : platformState.creationSoftwareVersion();
    }

    /**
     * Determines if the provided {@code state} is a genesis state.
     *
     * @param state the state to check
     * @return true if the state is a genesis state
     */
    public static boolean isGenesisStateOf(@NonNull final State state) {
        return readablePlatformStateStore(state).getRound() == GENESIS_ROUND;
    }

    /**
     * Determines if a {@code timestamp} is in a freeze period according to the provided timestamps.
     *
     * @param consensusTime the consensus time to check
     * @param state         the state object to extract the data from
     * @return true is the {@code timestamp} is in a freeze period
     */
    public static boolean isInFreezePeriod(@NonNull final Instant consensusTime, @NonNull final State state) {
        return isInFreezePeriod(consensusTime, freezeTimeOf(state), lastFrozenTimeOf(state));
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
     *
     * @param state the state
     * @return the version of the state if it was deserialized, otherwise null
     */
    @Nullable
    public static SemanticVersion creationSoftwareVersionOf(@NonNull final State state) {
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
     *
     * @param root the root to extract the round number from
     * @return the round number of the platform state, or zero if the state is a genesis state
     */
    public static long roundOf(@NonNull final State root) {
        requireNonNull(root);
        return readablePlatformStateStore(root).getRound();
    }

    /**
     * Given a {@link State}, returns an instance of {@link PlatformState} if it exists.
     *
     * @param state the state to extract the platform state from
     * @return the platform state, or null if the state is a genesis state
     */
    @Nullable
    public static PlatformState platformStateOf(@NonNull final State state) {
        final ReadableStates readableStates = state.getReadableStates(NAME);
        if (readableStates.isEmpty()) {
            return UNINITIALIZED_PLATFORM_STATE;
        } else {
            return (PlatformState)
                    readableStates.getSingleton(PLATFORM_STATE_STATE_ID).get();
        }
    }

    /**
     * Given a {@link State}, returns the legacy running event hash if it exists.
     *
     * @param state the state to extract the legacy running event hash from
     * @return the legacy running event hash, or null if the state is a genesis state
     */
    @Nullable
    public static Hash legacyRunningEventHashOf(@NonNull final State state) {
        return readablePlatformStateStore(state).getLegacyRunningEventHash();
    }

    /**
     * Given a {@link State}, for the oldest non-ancient round, get the lowest ancient indicator out of all of those
     * round's judges. See {@link PlatformStateAccessor#getAncientThreshold()} for more information.
     *
     * @param state the state to extract the ancient threshold from
     * @return the ancient threshold, or zero if the state is a genesis state
     */
    public static long ancientThresholdOf(@NonNull final State state) {
        return readablePlatformStateStore(state).getAncientThreshold();
    }

    /**
     * Given a {@link State}, returns the consensus snapshot if it exists.
     *
     * @param root the root to extract the consensus snapshot from
     * @return the consensus snapshot, or null if the state is a genesis state
     */
    @Nullable
    public static ConsensusSnapshot consensusSnapshotOf(@NonNull final State root) {
        return readablePlatformStateStore(root).getSnapshot();
    }

    /**
     * Given a {@link State}, returns consensus timestamp if it exists.
     *
     * @param state the state to extract the consensus timestamp from
     * @return the consensus timestamp, or null if the state is a genesis state
     */
    @Nullable
    public static Instant consensusTimestampOf(@NonNull final State state) {
        return readablePlatformStateStore(state).getConsensusTimestamp();
    }

    /**
     * Given a {@link State}, returns the freeze time of the state if it exists.
     *
     * @param state the state to extract the freeze time from
     * @return the freeze time, or null if the state is a genesis state
     */
    public static Instant freezeTimeOf(@NonNull final State state) {
        return readablePlatformStateStore(state).getFreezeTime();
    }

    /**
     * Update the last frozen time of the state.
     *
     * @param state the state to update
     */
    public static void updateLastFrozenTime(@NonNull final State state) {
        getWritablePlatformStateOf(state).setLastFrozenTime(freezeTimeOf(state));
    }

    /**
     * Given a {@link State}, returns the last frozen time of the state if it exists.
     *
     * @param state the state to extract the last frozen time from
     * @return the last frozen time, or null if the state is a genesis state
     */
    @Nullable
    public static Instant lastFrozenTimeOf(@NonNull final State state) {
        return readablePlatformStateStore(state).getLastFrozenTime();
    }

    /**
     * Returns the last freeze round of the state.
     * @param state the state to extract the last freeze round from
     * @return the last freeze round
     */
    public static long latestFreezeRoundOf(@NonNull final State state) {
        return readablePlatformStateStore(state).getLatestFreezeRound();
    }

    /**
     * Get writable platform state. Works only on mutable {@link State}. Call this method only if you need to modify the
     * platform state.
     *
     * @return mutable platform state
     */
    @NonNull
    public static PlatformStateModifier getWritablePlatformStateOf(@NonNull final State state) {
        if (state.isImmutable()) {
            throw new IllegalStateException("Cannot get writable platform state when state is immutable");
        }
        return writablePlatformStateStore(state);
    }

    /**
     * This is a convenience method to update multiple fields in the platform state in a single operation.
     *
     * @param updater a consumer that updates the platform state
     */
    public static void bulkUpdateOf(@NonNull final State state, @NonNull Consumer<PlatformStateModifier> updater) {
        getWritablePlatformStateOf(state).bulkUpdate(updater);
    }

    /**
     * @param snapshot the consensus snapshot for this round
     */
    public static void setSnapshotTo(@NonNull final State state, @NonNull ConsensusSnapshot snapshot) {
        getWritablePlatformStateOf(state).setSnapshot(snapshot);
    }

    /**
     * Set the legacy running event hash. Used by the consensus event stream.
     *
     * @param legacyRunningEventHash a running hash of events
     */
    public static void setLegacyRunningEventHashTo(@NonNull final State state, @Nullable Hash legacyRunningEventHash) {
        getWritablePlatformStateOf(state).setLegacyRunningEventHash(legacyRunningEventHash);
    }

    /**
     * Set the software version of the application that created this state.
     *
     * @param creationVersion the creation version
     */
    public static void setCreationSoftwareVersionTo(
            @NonNull final State state, @NonNull SemanticVersion creationVersion) {
        getWritablePlatformStateOf(state).setCreationSoftwareVersion(creationVersion);
    }

    /**
     * Generate a string that describes this state.
     *
     */
    @NonNull
    public static String getInfoString(@NonNull final State state) {
        return createInfoString(readablePlatformStateStore(state), state.getHash())
                .concat(state.getInfoJson());
    }

    private static PlatformStateAccessor readablePlatformStateStore(@NonNull final State state) {
        final ReadableStates readableStates = state.getReadableStates(NAME);
        if (readableStates.isEmpty()
                || readableStates.getSingleton(PLATFORM_STATE_STATE_ID).get() == null) {
            return new SnapshotPlatformStateAccessor(UNINITIALIZED_PLATFORM_STATE);
        }
        return new ReadablePlatformStateStore(readableStates);
    }

    private static WritablePlatformStateStore writablePlatformStateStore(@NonNull final State state) {
        return new WritablePlatformStateStore(state.getWritableStates(NAME));
    }

    /**
     * Generate a string that describes this state.
     *
     * @param platformState current platform state
     *
     */
    @NonNull
    public static String createInfoString(
            @NonNull final PlatformStateAccessor platformState, @NonNull final Hash rootHash) {
        final Hash hashEventsCons = platformState.getLegacyRunningEventHash();

        final ConsensusSnapshot snapshot = platformState.getSnapshot();
        final List<MinimumJudgeInfo> minimumJudgeInfo = snapshot == null ? null : snapshot.minimumJudgeInfoList();

        final StringBuilder sb = new StringBuilder();
        final long round = platformState.getRound();
        final SemanticVersion creationSoftwareVersion =
                round == GENESIS_ROUND ? SemanticVersion.DEFAULT : platformState.getCreationSoftwareVersion();
        new TextTable()
                .setBordersEnabled(false)
                .addRow("Round:", round)
                .addRow("Timestamp:", platformState.getConsensusTimestamp())
                .addRow("Next consensus number:", snapshot == null ? "null" : snapshot.nextConsensusNumber())
                .addRow("Legacy running event hash:", hashEventsCons)
                .addRow(
                        "Legacy running event mnemonic:",
                        hashEventsCons == null ? "null" : Mnemonics.generateMnemonic(hashEventsCons))
                .addRow("Rounds non-ancient:", platformState.getRoundsNonAncient())
                .addRow("Creation version:", creationSoftwareVersion)
                .addRow("Minimum judge hash code:", minimumJudgeInfo == null ? "null" : minimumJudgeInfo.hashCode())
                .addRow("Root hash:", rootHash)
                .render(sb);

        sb.append("\n");
        sb.append(String.format(HASH_INFO_TEMPLATE, Mnemonics.generateMnemonic(rootHash)));
        sb.append("\n");
        return sb.toString();
    }

    /**
     * Default constructor
     */
    private PlatformStateUtils() {}
}
