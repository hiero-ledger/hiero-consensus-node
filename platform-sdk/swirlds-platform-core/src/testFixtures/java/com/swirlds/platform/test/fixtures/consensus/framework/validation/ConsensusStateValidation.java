// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Validate signed states. The type of validation depends on the implementation.
 */
@FunctionalInterface
public interface ConsensusStateValidation {
    /**
     * Perform validation on a signed state.
     *
     * @param reservedSignedStates the signed state to validate
     */
    void validate(@NonNull final ReservedSignedState reservedSignedStates);
}
