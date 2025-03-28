// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import com.swirlds.platform.test.fixtures.consensus.framework.ConsensusOutput;
import java.util.HashSet;
import java.util.Set;

/**
 * This is a specific validator for consensus output related tests. It allows defining custom validations related to
 * {@link ConsensusOutput}
 *
 * Each custom validation should be initialized in the constructor and added to the set of validations.
 * A separate constructor is provided to allow custom set of validations to be added.
 */
public class ConsensusOutputValidator {

    private final Set<ConsensusOutputValidation> outputValidations;

    /**
     * Creates a new instance of the validator with equality and different order validations for {@link ConsensusOutput}.
     */
    public ConsensusOutputValidator() {
        outputValidations = new HashSet<>();
        outputValidations.add(new OutputEventsEqualityValidation());
        outputValidations.add(new OutputEventsAddedInDifferentOrderValidation());
    }

    /**
     * Creates a new instance of the validator with a custom set of {@link ConsensusOutput}.
     */
    public ConsensusOutputValidator(final Set<ConsensusOutputValidation> outputValidations) {
        this.outputValidations = outputValidations;
    }

    /**
     * Validates the given {@link ConsensusOutput} objects coming from separate nodes
     *
     * @param output1 output from one node
     * @param output2 output from another node
     */
    public void validate(final ConsensusOutput output1, final ConsensusOutput output2) {
        for (final ConsensusOutputValidation outputValidation : outputValidations) {
            outputValidation.validate(output1, output2);
        }
    }
}
