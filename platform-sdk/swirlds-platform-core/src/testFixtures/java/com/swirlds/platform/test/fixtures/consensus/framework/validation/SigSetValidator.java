// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashSet;
import java.util.Set;
import org.hiero.consensus.model.node.NodeId;

/**
 * This is a specific validator for set of signed state related tests. It allows defining custom validations related to
 * {@link ReservedSignedState}
 *
 * Each custom validation should be initialized in the constructor and added to the set of validations.
 * A separate constructor is provided to allow custom set of validations to be added.
 */
public class SigSetValidator {

    private final Set<SigSetValidation> sigSetValidations;

    /**
     * Creates a new instance of the validator with validations for {@link ReservedSignedState}.
     */
    public SigSetValidator() {
        sigSetValidations = new HashSet<>();
        sigSetValidations.add(new StateSigSetValidation());
    }

    /**
     * Creates a new instance of the validator with a custom set of {@link ReservedSignedState}.
     */
    public SigSetValidator(@NonNull final Set<SigSetValidation> sigSetValidations) {
        this.sigSetValidations = sigSetValidations;
    }

    /**
     * Validates the given {@link ReservedSignedState} object.
     *
     * @param reservedSignedState the signed state to validate
     * @param nodeId              the node id to validate
     */
    public void validate(@NonNull final ReservedSignedState reservedSignedState, @NonNull final NodeId nodeId) {
        for (final SigSetValidation validation : sigSetValidations) {
            validation.validate(reservedSignedState, nodeId);
        }
    }
}
