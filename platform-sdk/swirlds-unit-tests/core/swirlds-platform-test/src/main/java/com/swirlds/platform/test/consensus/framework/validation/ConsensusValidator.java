// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.consensus.framework.validation;

import static com.swirlds.platform.test.consensus.framework.validation.ConsensusValidator.ConsensusValidationType.CONSENSUS_EVENTS;
import static com.swirlds.platform.test.consensus.framework.validation.ConsensusValidator.ConsensusValidationType.CONSENSUS_TIMESTAMPS;
import static com.swirlds.platform.test.consensus.framework.validation.ConsensusValidator.ConsensusValidationType.DIFFERENT_ORDER;
import static com.swirlds.platform.test.consensus.framework.validation.ConsensusValidator.ConsensusValidationType.INPUTS_ARE_SAME;
import static com.swirlds.platform.test.consensus.framework.validation.ConsensusValidator.ConsensusValidationType.RATIOS;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class ConsensusValidator {

    private final Map<ConsensusValidationType, ConsensusOutputValidation> consensusOutputValidationsMap =
            new EnumMap<>(ConsensusValidationType.class);

    private final Map<ConsensusValidationType, ConsensusRoundValidation> consensusRoundValidationsMap =
            new EnumMap<>(ConsensusValidationType.class);

    public static @NonNull ConsensusValidator newInstance() {
        return new ConsensusValidator();
    }

    public @NonNull ConsensusValidator remove(final ConsensusValidationType type) {
        consensusOutputValidationsMap.remove(type);
        return this;
    }

    public @NonNull ConsensusValidator standard() {
        consensusOutputValidationsMap.putAll(Map.of(
                INPUTS_ARE_SAME, InputEventsValidation::validateInputsAreTheSame,
                DIFFERENT_ORDER, InputEventsValidation::validateEventsAreInDifferentOrder));

        consensusRoundValidationsMap.putAll(Map.of(
                CONSENSUS_EVENTS, RoundContentValidation::validateRounds,
                CONSENSUS_TIMESTAMPS, TimestampChecker::validateConsensusTimestamps));
        return this;
    }

    public @NonNull ConsensusValidator ratios(@NonNull final EventRatioValidation ratioValidation) {
        consensusOutputValidationsMap.put(RATIOS, ratioValidation);
        return this;
    }

    public @NonNull ConsensusValidator rounds() {
        consensusRoundValidationsMap.putAll(Map.of(
                CONSENSUS_EVENTS, RoundContentValidation::validateRounds,
                CONSENSUS_TIMESTAMPS, TimestampChecker::validateConsensusTimestamps));
        return this;
    }

    public @NonNull List<ConsensusOutputValidation> getConsensusOutputList() {
        return consensusOutputValidationsMap.values().stream().toList();
    }

    public @NonNull List<ConsensusRoundValidation> getConsensusRoundList() {
        return consensusRoundValidationsMap.values().stream().toList();
    }

    public enum ConsensusValidationType {
        INPUTS_ARE_SAME,
        DIFFERENT_ORDER,
        CONSENSUS_EVENTS,
        CONSENSUS_TIMESTAMPS,
        RATIOS,
        NO_EVENTS_LOST
    }
}
