// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.throttle;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CONSENSUS_GAS_EXHAUSTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.THROTTLED_AT_CONSENSUS;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A custom exception to be thrown when a request is throttled at consensus.
 */
public class ThrottleException extends Exception {
    private final ResponseCodeEnum status;

    /**
     * Creates a new ThrottleException with status CONSENSUS_GAS_EXHAUSTED.
     * @return the new ThrottleException
     */
    public static ThrottleException newGasThrottleException() {
        return new ThrottleException(CONSENSUS_GAS_EXHAUSTED);
    }

    /**
     * Creates a new ThrottleException with the status THROTTLED_AT_CONSENSUS.
     * @return the new ThrottleException
     */
    public static ThrottleException newNativeThrottleException() {
        return new ThrottleException(THROTTLED_AT_CONSENSUS);
    }

    private ThrottleException(@NonNull final ResponseCodeEnum status) {
        this.status = requireNonNull(status);
    }

    /**
     * Gets the status of the exception.
     *
     * @return the status
     */
    public ResponseCodeEnum getStatus() {
        return status;
    }
}
