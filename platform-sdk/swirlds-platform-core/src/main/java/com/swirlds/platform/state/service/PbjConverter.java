// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.service;

import static java.util.Objects.requireNonNull;
import static org.hiero.base.utility.CommonUtils.toPbjTimestamp;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.state.PlatformStateAccessor;
import com.swirlds.platform.state.PlatformStateModifier;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import org.hiero.consensus.model.crypto.Hash;

/**
 * This class handles conversion from PBJ objects related to the platform state to the corresponding Java objects, and vice versa.
 */
public final class PbjConverter {
    /**
     * Converts an instance of {@link PlatformStateModifier} to PBJ representation (an instance of {@link com.hedera.hapi.platform.state.PlatformState}.)
     * @param accessor the source of the data
     * @return the platform state as PBJ object
     */
    @NonNull
    public static com.hedera.hapi.platform.state.PlatformState toPbjPlatformState(
            @NonNull final PlatformStateAccessor accessor) {
        requireNonNull(accessor);
        return new PlatformState(
                accessor.getCreationSoftwareVersion(),
                accessor.getRoundsNonAncient(),
                accessor.getSnapshot(),
                toPbjTimestamp(accessor.getFreezeTime()),
                toPbjTimestamp(accessor.getLastFrozenTime()),
                Optional.ofNullable(accessor.getLegacyRunningEventHash())
                        .map(Hash::getBytes)
                        .orElse(null),
                accessor.getLowestJudgeGenerationBeforeBirthRoundMode(),
                accessor.getLastRoundBeforeBirthRoundMode(),
                accessor.getFirstVersionInBirthRoundMode());
    }

    /**
     * Converts an instance of {@link PlatformStateModifier} to PBJ representation (an instance of {@link com.hedera.hapi.platform.state.PlatformState}.)
     * @param accumulator the source of the data
     * @return the platform state as PBJ object
     */
    @NonNull
    public static com.hedera.hapi.platform.state.PlatformState toPbjPlatformState(
            @NonNull final com.hedera.hapi.platform.state.PlatformState previousState,
            @NonNull final PlatformStateValueAccumulator accumulator) {
        requireNonNull(accumulator);
        final var builder = previousState.copyBuilder();

        if (accumulator.isCreationSoftwareVersionUpdated()) {
            builder.creationSoftwareVersion(accumulator.getCreationSoftwareVersion());
        }

        if (accumulator.isRoundsNonAncientUpdated()) {
            builder.roundsNonAncient(accumulator.getRoundsNonAncient());
        }

        final com.hedera.hapi.platform.state.ConsensusSnapshot.Builder consensusSnapshotBuilder;
        if (accumulator.isSnapshotUpdated()) {
            consensusSnapshotBuilder = accumulator.getSnapshot().copyBuilder();
        } else {
            consensusSnapshotBuilder = previousState
                    .consensusSnapshotOrElse(com.hedera.hapi.platform.state.ConsensusSnapshot.DEFAULT)
                    .copyBuilder();
        }

        if (accumulator.isRoundUpdated()) {
            consensusSnapshotBuilder.round(accumulator.getRound());
        }

        if (accumulator.isConsensusTimestampUpdated()) {
            consensusSnapshotBuilder.consensusTimestamp(toPbjTimestamp(accumulator.getConsensusTimestamp()));
        }

        builder.consensusSnapshot(consensusSnapshotBuilder);

        if (accumulator.isFreezeTimeUpdated()) {
            builder.freezeTime(toPbjTimestamp(accumulator.getFreezeTime()));
        }

        if (accumulator.isLastFrozenTimeUpdated()) {
            builder.lastFrozenTime(toPbjTimestamp(accumulator.getLastFrozenTime()));
        }

        if (accumulator.isLegacyRunningEventHashUpdated()) {
            if (accumulator.getLegacyRunningEventHash() == null) {
                builder.legacyRunningEventHash(Bytes.EMPTY);
            } else {
                builder.legacyRunningEventHash(
                        accumulator.getLegacyRunningEventHash().getBytes());
            }
        }

        if (accumulator.isLowestJudgeGenerationBeforeBirthRoundModeUpdated()) {
            builder.lowestJudgeGenerationBeforeBirthRoundMode(
                    accumulator.getLowestJudgeGenerationBeforeBirthRoundMode());
        }

        if (accumulator.isLastRoundBeforeBirthRoundModeUpdated()) {
            builder.lastRoundBeforeBirthRoundMode(accumulator.getLastRoundBeforeBirthRoundMode());
        }

        if (accumulator.isFirstVersionInBirthRoundModeUpdated()) {
            if (accumulator.getFirstVersionInBirthRoundMode() == null) {
                builder.firstVersionInBirthRoundMode((SemanticVersion) null);
            } else {
                builder.firstVersionInBirthRoundMode(accumulator.getFirstVersionInBirthRoundMode());
            }
        }

        return builder.build();
    }

    private PbjConverter() {
        // empty
    }
}
