// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.consensus.framework.validation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.platform.NodeId;
import com.swirlds.platform.state.signed.ReservedSignedState;
import java.util.List;

public class SignatureSetValidation {

    public static void hasSignature(final List<ReservedSignedState> reservedSignedStates, final NodeId nodeId) {
        for (final ReservedSignedState reservedSignedState : reservedSignedStates) {
            assertTrue(reservedSignedState.get().getSigSet().hasSignature(nodeId));
        }
    }
}
