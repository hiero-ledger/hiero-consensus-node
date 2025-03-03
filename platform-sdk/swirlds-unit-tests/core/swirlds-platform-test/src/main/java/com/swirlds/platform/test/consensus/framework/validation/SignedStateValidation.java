// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.consensus.framework.validation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.platform.state.signed.ReservedSignedState;
import java.time.Instant;
import java.util.List;

public class SignedStateValidation {

    public static void isComplete(final List<ReservedSignedState> reservedSignedStates) {
        for (final ReservedSignedState reservedSignedState : reservedSignedStates) {
            assertTrue(reservedSignedState.get().isComplete());
        }
    }

    public static void isVerifiable(final List<ReservedSignedState> reservedSignedStates) {
        for (final ReservedSignedState reservedSignedState : reservedSignedStates) {
            assertTrue(reservedSignedState.get().isVerifiable());
        }
    }

    public static void isWithCorrectCreationTimestamp(final List<ReservedSignedState> reservedSignedStates) {
        for (final ReservedSignedState reservedSignedState : reservedSignedStates) {
            assertTrue(reservedSignedState.get().getCreationTimestamp().isBefore(Instant.now()));
        }
    }
}
