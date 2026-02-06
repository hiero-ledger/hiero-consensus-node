// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.platformstate;

import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.model.PbjConverters.toPbjTimestamp;
import static org.hiero.consensus.platformstate.PbjConverter.toPbjPlatformState;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.function.Consumer;
import org.hiero.base.crypto.Hash;

/**
 * Extends the read-only platform state store to provide write access to the platform state.
 */
public class WritablePlatformStateStore extends ReadablePlatformStateStore implements PlatformStateModifier {

    private final WritableStates writableStates;
    private final WritableSingletonState<PlatformState> state;

    /**
     * Constructor that can be used to change and access any part of state.
     * @param writableStates the writable states
     */
    public WritablePlatformStateStore(@NonNull final WritableStates writableStates) {
        super(writableStates);
        this.writableStates = writableStates;
        this.state = writableStates.getSingleton(V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setCreationSoftwareVersion(@NonNull final SemanticVersion creationVersion) {
        requireNonNull(creationVersion);
        final var previousState = stateOrThrow();
        update(previousState.copyBuilder().creationSoftwareVersion(creationVersion));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setRound(final long round) {
        final var previousState = stateOrThrow();
        update(previousState
                .copyBuilder()
                .consensusSnapshot(previousState
                        .consensusSnapshotOrElse(ConsensusSnapshot.DEFAULT)
                        .copyBuilder()
                        .round(round)));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setLegacyRunningEventHash(@Nullable final Hash legacyRunningEventHash) {
        final var previousState = stateOrThrow();
        update(previousState
                .copyBuilder()
                .legacyRunningEventHash(
                        legacyRunningEventHash == null ? Bytes.EMPTY : legacyRunningEventHash.getBytes()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setConsensusTimestamp(@NonNull final Instant consensusTimestamp) {
        requireNonNull(consensusTimestamp);
        final var previousState = stateOrThrow();
        update(previousState
                .copyBuilder()
                .consensusSnapshot(previousState
                        .consensusSnapshotOrElse(ConsensusSnapshot.DEFAULT)
                        .copyBuilder()
                        .consensusTimestamp(toPbjTimestamp(consensusTimestamp))));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setRoundsNonAncient(final int roundsNonAncient) {
        final var previousState = stateOrThrow();
        update(previousState.copyBuilder().roundsNonAncient(roundsNonAncient));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setSnapshot(@NonNull final ConsensusSnapshot snapshot) {
        requireNonNull(snapshot);
        final var previousState = stateOrThrow();
        update(previousState.copyBuilder().consensusSnapshot(snapshot));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setFreezeTime(@Nullable final Instant freezeTime) {
        final var previousState = stateOrThrow();
        update(previousState.copyBuilder().freezeTime(toPbjTimestamp(freezeTime)));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setLastFrozenTime(@Nullable final Instant lastFrozenTime) {
        final var previousState = stateOrThrow();
        update(previousState.copyBuilder().lastFrozenTime(toPbjTimestamp(lastFrozenTime)));
    }

    @Override
    public void setLatestFreezeRound(final long latestFreezeRound) {
        final var previousState = stateOrThrow();
        update(previousState.copyBuilder().latestFreezeRound(latestFreezeRound));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void bulkUpdate(@NonNull final Consumer<PlatformStateModifier> updater) {
        if (state.get() == null) {
            // A very special case of going ACTIVE at genesis; this is the first change each new network makes to state,
            // and the Hedera app has a matching special case to ensure it is the first state change in the block stream
            state.put(PlatformState.DEFAULT);
        }
        final var accumulator = new PlatformStateValueAccumulator();
        updater.accept(accumulator);
        update(toPbjPlatformState(stateOrThrow(), accumulator));
    }

    private @NonNull PlatformState stateOrThrow() {
        return requireNonNull(state.get());
    }

    private void update(@NonNull final PlatformState.Builder stateBuilder) {
        update(stateBuilder.build());
    }

    private void update(@NonNull final PlatformState stateBuilder) {
        this.state.put(stateBuilder);
        if (writableStates instanceof CommittableWritableStates committableWritableStates) {
            committableWritableStates.commit();
        }
    }
}
