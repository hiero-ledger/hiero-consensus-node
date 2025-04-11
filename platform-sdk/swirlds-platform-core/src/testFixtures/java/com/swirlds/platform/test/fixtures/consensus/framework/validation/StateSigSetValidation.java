// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.node.NodeId;

public class StateSigSetValidation implements SigSetValidation {
    @Override
    public void validate(@NonNull ReservedSignedState reservedSignedState, @NonNull NodeId nodeId) {
        assertTrue(reservedSignedState.get().getSigSet().hasSignature(nodeId));
    }
}
