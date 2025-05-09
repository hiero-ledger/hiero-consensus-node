// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.node.NodeId;

/**
 * Validate set of signed states. The type of validation depends on the implementation.
 */
@FunctionalInterface
public interface SigSetValidation {

    /**
     * Perform validation on a set of signed states.
     *
     * @param reservedSignedStates the signed state to validate
     * @param nodeId              the node id to validate
     */
    void validate(@NonNull final ReservedSignedState reservedSignedStates, @NonNull final NodeId nodeId);
}
