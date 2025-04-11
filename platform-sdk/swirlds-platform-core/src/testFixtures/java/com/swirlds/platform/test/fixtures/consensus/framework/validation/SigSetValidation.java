// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.node.NodeId;

public interface SigSetValidation {
    void validate(@NonNull final ReservedSignedState reservedSignedStates, @NonNull NodeId nodeId);
}
