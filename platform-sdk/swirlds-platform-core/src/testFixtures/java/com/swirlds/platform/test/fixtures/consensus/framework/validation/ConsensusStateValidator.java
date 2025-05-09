// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashSet;
import java.util.Set;

/**
 * This is a specific validator for signed state related tests. It allows defining custom validations related to
 * {@link ReservedSignedState}
 *
 * Each custom validation should be initialized in the constructor and added to the set of validations.
 * A separate constructor is provided to allow custom set of validations to be added.
 */
public class ConsensusStateValidator {

    private final Set<ConsensusStateValidation> consensusStateValidations;

    /**
     * Creates a new instance of the validator with validations for {@link ReservedSignedState}.
     */
    public ConsensusStateValidator() {
        consensusStateValidations = new HashSet<>();
        consensusStateValidations.add(new StateCompletionValidation());
        consensusStateValidations.add(new StateVerifiableValidation());
        consensusStateValidations.add(new StateCreationTimestampValidation());
    }

    /**
     * Creates a new instance of the validator with a custom set of {@link ReservedSignedState}.
     */
    public ConsensusStateValidator(@NonNull final Set<ConsensusStateValidation> consensusStateValidations) {
        this.consensusStateValidations = consensusStateValidations;
    }

    /**
     * Validates the given {@link ReservedSignedState} object.
     *
     * @param reservedSignedState the signed state to validate
     */
    public void validate(@NonNull final ReservedSignedState reservedSignedState) {
        for (final ConsensusStateValidation validation : consensusStateValidations) {
            validation.validate(reservedSignedState);
        }
    }
}
