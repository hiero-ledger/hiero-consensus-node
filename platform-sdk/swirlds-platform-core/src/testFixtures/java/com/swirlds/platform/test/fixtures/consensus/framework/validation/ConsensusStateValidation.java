// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;

@FunctionalInterface
public interface ConsensusStateValidation {
    void validate(@NonNull final ReservedSignedState reservedSignedStates);
}
