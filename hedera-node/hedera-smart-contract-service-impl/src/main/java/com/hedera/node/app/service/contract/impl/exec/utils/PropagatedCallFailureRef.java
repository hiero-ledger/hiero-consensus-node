// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

import static com.hedera.node.app.service.contract.impl.hevm.HevmPropagatedCallFailure.NONE;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.hevm.HevmPropagatedCallFailure;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Wrapper that holds a reference to a {@link HevmPropagatedCallFailure} value. Added to the
 * {@link org.hyperledger.besu.evm.frame.MessageFrame} context so that such failures can be
 * propagated up the call stack.
 */
public class PropagatedCallFailureRef {
    private HevmPropagatedCallFailure failure = NONE;

    /**
     * Sets the failure value to the given value.
     *
     * @param failure the failure value to set
     */
    public void set(@NonNull final HevmPropagatedCallFailure failure) {
        this.failure = requireNonNull(failure);
    }

    /**
     * Returns the current failure value, if any, and ensures the reference is reset to {@link HevmPropagatedCallFailure#NONE}.
     *
     * @return the current failure value
     */
    public @NonNull HevmPropagatedCallFailure getAndClear() {
        final var maybeFailure = failure;
        failure = NONE;
        return maybeFailure;
    }
}
