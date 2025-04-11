// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

public class StateCreationTimestampValidation implements ConsensusStateValidation {
    @Override
    public void validate(@NonNull ReservedSignedState reservedSignedState) {
        assertTrue(reservedSignedState.get().getCreationTimestamp().isBefore(Instant.now()));
    }
}
