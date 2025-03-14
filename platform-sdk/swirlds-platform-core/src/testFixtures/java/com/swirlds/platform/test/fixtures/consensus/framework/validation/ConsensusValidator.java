// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import static com.swirlds.platform.test.fixtures.consensus.framework.validation.ConsensusValidator.ConsensusValidationType.CONSENSUS_EVENTS;
import static com.swirlds.platform.test.fixtures.consensus.framework.validation.ConsensusValidator.ConsensusValidationType.CONSENSUS_TIMESTAMPS;
import static com.swirlds.platform.test.fixtures.consensus.framework.validation.ConsensusValidator.ConsensusValidationType.DIFFERENT_ORDER;
import static com.swirlds.platform.test.fixtures.consensus.framework.validation.ConsensusValidator.ConsensusValidationType.INPUTS_ARE_SAME;
import static com.swirlds.platform.test.fixtures.consensus.framework.validation.ConsensusValidator.ConsensusValidationType.RATIOS;

import com.swirlds.platform.internal.ConsensusRound;
import com.swirlds.platform.test.fixtures.consensus.framework.ConsensusOutput;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * This is a specific validator for consensus related tests. It allows defining custom validations related to
 * {@link ConsensusOutput} that are specific objects used in ConsensusTests or validations
 * related to {@link ConsensusRound} that are commonly used in ConsensusTests and TurtleTests.
 *
 * Each custom validation should be defined with an enum value and be added in the suitable map structure holding
 * entries of common validations.
 */
public class ConsensusValidator {

    /**
     * Enum defining different validation checks for consensus related data - output, rounds, events.
     * The validation can be configured to use specific types of validations.
     */
    public enum ConsensusValidationType {
        INPUTS_ARE_SAME,
        DIFFERENT_ORDER,
        CONSENSUS_EVENTS,
        CONSENSUS_TIMESTAMPS,
        RATIOS,
        NO_EVENTS_LOST
    }

    private final Map<ConsensusValidationType, BiConsumer<ConsensusOutput, ConsensusOutput>>
            consensusOutputValidationsMap = new EnumMap<>(ConsensusValidationType.class);

    private final Map<ConsensusValidationType, BiConsumer<List<ConsensusRound>, List<ConsensusRound>>>
            consensusRoundValidationsMap = new EnumMap<>(ConsensusValidationType.class);

    public static @NonNull ConsensusValidator newInstance() {
        return new ConsensusValidator();
    }

    /**
     * Removes a specific validation type noted by its {@link ConsensusValidationType}
     */
    public @NonNull ConsensusValidator remove(final ConsensusValidationType type) {
        consensusOutputValidationsMap.remove(type);
        return this;
    }

    /**
     * Initializes a standard {@link ConsensusValidator} instance with default consensus output and
     * consensus round validations.
     */
    public @NonNull ConsensusValidator standard() {
        consensusOutputValidationsMap.putAll(Map.of(
                INPUTS_ARE_SAME, InputEventsValidation::validateInputsAreTheSame,
                DIFFERENT_ORDER, InputEventsValidation::validateEventsAreInDifferentOrder));

        consensusRoundValidationsMap.putAll(Map.of(
                CONSENSUS_EVENTS, RoundContentValidation::validateRounds,
                CONSENSUS_TIMESTAMPS, TimestampChecker::validateConsensusTimestamps));
        return this;
    }

    /**
     * Adds a ratio related validation for consensus output.
     */
    public @NonNull ConsensusValidator ratios(@NonNull final EventRatioValidation ratioValidation) {
        consensusOutputValidationsMap.put(RATIOS, ratioValidation::validate);
        return this;
    }

    /**
     * Initializes {@link ConsensusValidator} with only consensus round validations.
     */
    public @NonNull ConsensusValidator rounds() {
        consensusRoundValidationsMap.putAll(Map.of(
                CONSENSUS_EVENTS, RoundContentValidation::validateRounds,
                CONSENSUS_TIMESTAMPS, TimestampChecker::validateConsensusTimestamps));
        return this;
    }

    /**
     * Initializes {@link ConsensusValidator} with only consensus output validations.
     */
    public @NonNull List<BiConsumer<ConsensusOutput, ConsensusOutput>> getOutputValidations() {
        return consensusOutputValidationsMap.values().stream().toList();
    }

    /**
     * Returns validations related to consensus round.
     */
    public @NonNull List<BiConsumer<List<ConsensusRound>, List<ConsensusRound>>> getRoundValidations() {
        return consensusRoundValidationsMap.values().stream().toList();
    }
}
