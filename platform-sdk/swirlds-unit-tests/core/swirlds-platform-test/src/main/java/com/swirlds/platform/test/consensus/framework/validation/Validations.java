// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.consensus.framework.validation;

import static com.swirlds.platform.test.consensus.framework.validation.Validations.ValidationType.CONSENSUS_EVENTS;
import static com.swirlds.platform.test.consensus.framework.validation.Validations.ValidationType.CONSENSUS_TIMESTAMPS;
import static com.swirlds.platform.test.consensus.framework.validation.Validations.ValidationType.DIFFERENT_ORDER;
import static com.swirlds.platform.test.consensus.framework.validation.Validations.ValidationType.INPUTS_ARE_SAME;
import static com.swirlds.platform.test.consensus.framework.validation.Validations.ValidationType.RATIOS;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class Validations {

    private final Map<ValidationType, ConsensusOutputValidation> consensusValidationsMap =
            new EnumMap<>(ValidationType.class);

    public static @NonNull Validations create() {
        return new Validations();
    }

    public @NonNull Validations remove(final ValidationType type) {
        consensusValidationsMap.remove(type);
        return this;
    }

    public @NonNull Validations standard() {
        consensusValidationsMap.putAll(Map.of(
                INPUTS_ARE_SAME, InputEventsValidation::validateInputsAreTheSame,
                DIFFERENT_ORDER, InputEventsValidation::validateEventsAreInDifferentOrder,
                CONSENSUS_EVENTS, ConsensusRoundValidation::validateConsensusRounds,
                CONSENSUS_TIMESTAMPS, TimestampChecker::validateConsensusTimestamps));
        return this;
    }

    public @NonNull Validations ratios(@NonNull final EventRatioValidation ratioValidation) {
        consensusValidationsMap.put(RATIOS, ratioValidation);
        return this;
    }

    public @NonNull Validations consensusEvents() {
        consensusValidationsMap.put(CONSENSUS_EVENTS, ConsensusRoundValidation::validateConsensusRounds);
        return this;
    }

    public @NonNull Validations consensusTimestamps() {
        consensusValidationsMap.put(CONSENSUS_TIMESTAMPS, TimestampChecker::validateConsensusTimestamps);
        return this;
    }

    public @NonNull List<ConsensusOutputValidation> getValidations() {
        return consensusValidationsMap.values().stream().toList();
    }

    public enum ValidationType {
        INPUTS_ARE_SAME,
        DIFFERENT_ORDER,
        CONSENSUS_EVENTS,
        CONSENSUS_TIMESTAMPS,
        RATIOS,
        NO_EVENTS_LOST
    }
}
