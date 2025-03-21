// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import com.swirlds.platform.internal.ConsensusRound;
import com.swirlds.platform.test.fixtures.consensus.framework.ConsensusOutput;
import java.util.ArrayList;
import java.util.List;

public class ConsensusOutputValidator {

    private final ConsensusRoundValidator consensusRoundValidator;
    private final List<ConsensusOutputValidation> outputValidations;

    public ConsensusOutputValidator() {
        outputValidations = new ArrayList<>();
        outputValidations.add(new OutputEqualityEventsValidation());
        outputValidations.add(new OutputEventsAddedInDifferentOrderValidation());
        consensusRoundValidator = new ConsensusRoundValidator();
    }

    public ConsensusOutputValidator(
            final List<ConsensusRoundValidation> roundValidations,
            final List<ConsensusOutputValidation> outputValidations) {
        this.outputValidations = outputValidations;
        this.consensusRoundValidator = new ConsensusRoundValidator(roundValidations);
    }

    public ConsensusOutputValidator(final List<ConsensusOutputValidation> outputValidations) {
        this.outputValidations = outputValidations;
        this.consensusRoundValidator = new ConsensusRoundValidator();
    }

    public void validateOutputs(final ConsensusOutput output1, final ConsensusOutput output2) {
        for (final ConsensusOutputValidation outputValidation : outputValidations) {
            outputValidation.validate(output1, output2);
        }
    }

    public void validateRounds(
            final List<ConsensusRound> firstRoundsList, final List<ConsensusRound> secondRoundsList) {
        consensusRoundValidator.validate(firstRoundsList, secondRoundsList);
    }
}
