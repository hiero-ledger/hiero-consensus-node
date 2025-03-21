// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import com.swirlds.platform.test.fixtures.consensus.framework.ConsensusOutput;
import java.util.ArrayList;
import java.util.List;

public class ConsensusOutputValidator {

    private final List<ConsensusOutputValidation> outputValidations;

    public ConsensusOutputValidator() {
        outputValidations = new ArrayList<>();
        outputValidations.add(new OutputEqualityEventsValidation());
        outputValidations.add(new OutputEventsAddedInDifferentOrderValidation());
    }

    public ConsensusOutputValidator(final List<ConsensusOutputValidation> outputValidations) {
        this.outputValidations = outputValidations;
    }

    public void validate(final ConsensusOutput output1, final ConsensusOutput output2) {
        for (final ConsensusOutputValidation outputValidation : outputValidations) {
            outputValidation.validate(output1, output2);
        }
    }
}
