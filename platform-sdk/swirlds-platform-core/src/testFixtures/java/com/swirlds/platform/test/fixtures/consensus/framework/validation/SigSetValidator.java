// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import com.swirlds.platform.state.signed.ReservedSignedState;
import java.util.HashSet;
import java.util.Set;
import org.hiero.consensus.model.node.NodeId;

public class SigSetValidator {

    private final Set<SigSetValidation> sigSetValidations;

    public SigSetValidator() {
        sigSetValidations = new HashSet<>();
        sigSetValidations.add(new StateSigSetValidation());
    }

    public SigSetValidator(Set<SigSetValidation> sigSetValidations) {
        this.sigSetValidations = sigSetValidations;
    }

    public void validate(ReservedSignedState reservedSignedState, NodeId nodeId) {
        for (SigSetValidation validation : sigSetValidations) {
            validation.validate(reservedSignedState, nodeId);
        }
    }
}
