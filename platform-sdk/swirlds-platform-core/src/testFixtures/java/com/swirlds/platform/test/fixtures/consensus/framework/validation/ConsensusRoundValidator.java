// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import com.swirlds.platform.internal.ConsensusRound;
import com.swirlds.platform.test.fixtures.consensus.framework.ConsensusOutput;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * This is a specific validator for consensus related tests. It allows defining custom validations related to
 * {@link ConsensusOutput} that are specific objects used in ConsensusTests or validations
 * related to {@link ConsensusRound} that are commonly used in ConsensusTests and TurtleTests.
 *
 * Each custom validation should be defined with an enum value and be added in the suitable map structure holding
 * entries of common validations.
 */
public class ConsensusRoundValidator {

    private final List<ConsensusRoundValidation> consensusRoundValidations;

    public ConsensusRoundValidator() {
        this.consensusRoundValidations = new ArrayList<>();
        consensusRoundValidations.add(new RoundTimestampCheckerValidation());
        consensusRoundValidations.add(new RoundInternalEqualityValidation());
        consensusRoundValidations.add(new RoundAncientThresholdIncreasesValidation());
    }

    public void validate(@NonNull final ConsensusRound firstRound, @NonNull final ConsensusRound secondRound) {
        for (final ConsensusRoundValidation validation : consensusRoundValidations) {
            validation.validate(firstRound, secondRound);
        }
    }
}
