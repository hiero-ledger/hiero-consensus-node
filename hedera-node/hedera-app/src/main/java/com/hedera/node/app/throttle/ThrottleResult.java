// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Represents the result of a throttle check operation, encapsulating whether the transaction
 * should be throttled, any validation errors, and gas throttling status.
 */
public class ThrottleResult {
    private final boolean shouldThrottle;
    private final ResponseCodeEnum validationError;
    private final boolean wasGasThrottled;

    private ThrottleResult(
            boolean shouldThrottle, @Nullable ResponseCodeEnum validationError, boolean wasGasThrottled) {
        this.shouldThrottle = shouldThrottle;
        this.validationError = validationError;
        this.wasGasThrottled = wasGasThrottled;
    }

    /**
     * Creates a result indicating the transaction is allowed (not throttled).
     */
    public static ThrottleResult allowed() {
        return new ThrottleResult(false, null, false);
    }

    /**
     * Creates a result indicating the transaction should be throttled.
     */
    public static ThrottleResult throttled() {
        return new ThrottleResult(true, null, false);
    }

    /**
     * Creates a result indicating the transaction was throttled due to gas limits.
     */
    public static ThrottleResult gasThrottled() {
        return new ThrottleResult(true, null, true);
    }

    /**
     * Creates a result indicating the transaction is invalid with the specified error code.
     */
    public static ThrottleResult invalid(ResponseCodeEnum error) {
        return new ThrottleResult(true, error, false);
    }

    /**
     * @return true if the transaction should be throttled
     */
    public boolean shouldThrottle() {
        return shouldThrottle;
    }

    /**
     * @return true if there was a validation error
     */
    public boolean hasValidationError() {
        return validationError != null;
    }

    /**
     * @return the validation error code, or null if no validation error
     */
    @Nullable
    public ResponseCodeEnum validationError() {
        return validationError;
    }

    /**
     * @return true if the transaction was throttled due to gas limits
     */
    public boolean wasGasThrottled() {
        return wasGasThrottled;
    }
}
