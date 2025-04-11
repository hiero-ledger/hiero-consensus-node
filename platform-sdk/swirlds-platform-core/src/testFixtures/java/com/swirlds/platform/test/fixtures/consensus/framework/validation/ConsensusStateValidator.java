// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import com.swirlds.platform.state.signed.ReservedSignedState;
import java.util.HashSet;
import java.util.Set;

public class ConsensusStateValidator {

    private final Set<ConsensusStateValidation> consensusStateValidations;

    public ConsensusStateValidator() {
        consensusStateValidations = new HashSet<>();
        consensusStateValidations.add(new StateCompletionValidation());
        consensusStateValidations.add(new StateVerifiableValidation());
        consensusStateValidations.add(new StateCreationTimestampValidation());
    }

    public ConsensusStateValidator(Set<ConsensusStateValidation> consensusStateValidations) {
        this.consensusStateValidations = consensusStateValidations;
    }

    public void validate(ReservedSignedState reservedSignedState) {
        for (ConsensusStateValidation validation : consensusStateValidations) {
            validation.validate(reservedSignedState);
        }
    }
}
