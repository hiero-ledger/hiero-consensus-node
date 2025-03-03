// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.consensus.framework.validation;

import com.swirlds.common.platform.NodeId;
import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

@FunctionalInterface
public interface SigSetValidation {

    void validate(@NonNull final List<ReservedSignedState> reservedSignedStates, @NonNull final NodeId nodeId);
}
