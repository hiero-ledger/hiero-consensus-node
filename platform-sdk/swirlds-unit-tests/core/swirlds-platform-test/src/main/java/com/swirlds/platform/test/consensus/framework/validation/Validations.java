// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.consensus.framework.validation;

import static com.swirlds.platform.test.consensus.framework.validation.Validations.ValidationType.CONSENSUS_EVENTS;
import static com.swirlds.platform.test.consensus.framework.validation.Validations.ValidationType.CONSENSUS_TIMESTAMPS;
import static com.swirlds.platform.test.consensus.framework.validation.Validations.ValidationType.DIFFERENT_ORDER;
import static com.swirlds.platform.test.consensus.framework.validation.Validations.ValidationType.INPUTS_ARE_SAME;
import static com.swirlds.platform.test.consensus.framework.validation.Validations.ValidationType.RATIOS;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Validations {
    private final Map<ValidationType, ConsensusOutputValidation> consensusOutputValidationsMap = new HashMap<>(Map.of(
            INPUTS_ARE_SAME, InputEventsValidation::validateInputsAreTheSame,
            DIFFERENT_ORDER, InputEventsValidation::validateEventsAreInDifferentOrder));

    private final Map<ValidationType, ConsensusRoundValidation> consensusRoundValidationsMap = new HashMap<>(Map.of(
            CONSENSUS_EVENTS, ConsensusRoundEqualityValidation::validateConsensusRounds,
            CONSENSUS_TIMESTAMPS, TimestampChecker::validateConsensusTimestamps));

    public static @NonNull Validations standard() {
        return new Validations();
    }

    public @NonNull Validations remove(final ValidationType type) {
        consensusOutputValidationsMap.remove(type);
        return this;
    }

    public @NonNull Validations ratios(@NonNull final EventRatioValidation ratioValidation) {
        consensusOutputValidationsMap.put(RATIOS, ratioValidation);
        return this;
    }

    public @NonNull List<ConsensusOutputValidation> getConsensusOuputValidationsList() {
        return consensusOutputValidationsMap.values().stream().toList();
    }

    public @NonNull List<ConsensusRoundValidation> getConsensusRoundValidationsList() {
        return consensusRoundValidationsMap.values().stream().toList();
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
