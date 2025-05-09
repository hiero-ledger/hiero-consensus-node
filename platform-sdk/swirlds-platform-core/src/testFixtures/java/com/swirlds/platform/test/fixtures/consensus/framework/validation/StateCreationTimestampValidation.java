// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * Validate that the creation timestamp of the state is before the current time.
 */
public class StateCreationTimestampValidation implements ConsensusStateValidation {

    /**
     * Validate that the state is created before the current time.
     *
     * @param reservedSignedState the state to validate
     */
    @Override
    public void validate(@NonNull final ReservedSignedState reservedSignedState) {
        assertThat(reservedSignedState.get().getCreationTimestamp()).isBefore(Instant.now());
    }
}
