// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.service;

import static com.swirlds.platform.state.service.PbjConverter.fromPbjAddressBook;
import static com.swirlds.platform.state.service.PbjConverter.fromPbjConsensusSnapshot;
import static com.swirlds.platform.state.service.PbjConverter.fromPbjTimestamp;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.PlatformState;
import com.swirlds.common.crypto.Hash;
import com.swirlds.platform.consensus.ConsensusSnapshot;
import com.swirlds.platform.state.PlatformStateAccessor;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.platform.system.address.AddressBook;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.function.Function;

/**
 * Provides access to a snapshot of the platform state.
 */
public class SnapshotPlatformStateAccessor implements PlatformStateAccessor {
    private final PlatformState state;
    private final Function<SemanticVersion, SoftwareVersion> versionFactory;

    /**
     * Constructs a new accessor for the given state.
     *
     * @param state the state to access
     * @param versionFactory a factory for creating software versions
     */
    public SnapshotPlatformStateAccessor(
            @NonNull final PlatformState state,
            @NonNull final Function<SemanticVersion, SoftwareVersion> versionFactory) {
        this.state = requireNonNull(state);
        this.versionFactory = requireNonNull(versionFactory);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SoftwareVersion getCreationSoftwareVersion() {
        return versionFactory.apply(stateOrThrow().creationSoftwareVersionOrThrow());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public AddressBook getAddressBook() {
        return fromPbjAddressBook(stateOrThrow().addressBook());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public AddressBook getPreviousAddressBook() {
        return fromPbjAddressBook(stateOrThrow().previousAddressBook());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getRound() {
        final var consensusSnapshot = stateOrThrow().consensusSnapshot();
        if (consensusSnapshot == null) {
            return GENESIS_ROUND;
        } else {
            return consensusSnapshot.round();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public Hash getLegacyRunningEventHash() {
        final var hash = stateOrThrow().legacyRunningEventHash();
        return hash.length() == 0 ? null : new Hash(hash);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public Instant getConsensusTimestamp() {
        final var consensusSnapshot = stateOrThrow().consensusSnapshot();
        if (consensusSnapshot == null) {
            return null;
        }
        return fromPbjTimestamp(consensusSnapshot.consensusTimestamp());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getAncientThreshold() {
        final var consensusSnapshot = stateOrThrow().consensusSnapshot();
        requireNonNull(consensusSnapshot, "No minimum judge info found in state for round, snapshot is null");
        final var minimumJudgeInfos = consensusSnapshot.minimumJudgeInfoList();
        if (minimumJudgeInfos.isEmpty()) {
            throw new IllegalStateException(
                    "No minimum judge info found in state for round " + consensusSnapshot.round() + ", list is empty");
        }
        return minimumJudgeInfos.getFirst().minimumJudgeAncientThreshold();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getRoundsNonAncient() {
        return stateOrThrow().roundsNonAncient();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public ConsensusSnapshot getSnapshot() {
        return fromPbjConsensusSnapshot(stateOrThrow().consensusSnapshot());
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public Instant getFreezeTime() {
        return fromPbjTimestamp(stateOrThrow().freezeTime());
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public Instant getLastFrozenTime() {
        return fromPbjTimestamp(stateOrThrow().lastFrozenTime());
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public SoftwareVersion getFirstVersionInBirthRoundMode() {
        final var version = stateOrThrow().firstVersionInBirthRoundMode();
        return version == null ? null : versionFactory.apply(version);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getLastRoundBeforeBirthRoundMode() {
        return stateOrThrow().lastRoundBeforeBirthRoundMode();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getLowestJudgeGenerationBeforeBirthRoundMode() {
        return stateOrThrow().lowestJudgeGenerationBeforeBirthRoundMode();
    }

    private @NonNull PlatformState stateOrThrow() {
        return requireNonNull(state);
    }
}
