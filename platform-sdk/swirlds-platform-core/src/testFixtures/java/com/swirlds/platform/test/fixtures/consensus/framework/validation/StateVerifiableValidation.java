// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Validate state has enough signatures so that it can be trusted to be valid
 *
 */
public class StateVerifiableValidation implements ConsensusStateValidation {

    /**
     * Validate that the state has enough signatures.
     *
     * @param reservedSignedState the state to validate
     */
    @Override
    public void validate(@NonNull final ReservedSignedState reservedSignedState) {
        assertThat(reservedSignedState.get().isVerifiable()).isTrue();
    }
}
