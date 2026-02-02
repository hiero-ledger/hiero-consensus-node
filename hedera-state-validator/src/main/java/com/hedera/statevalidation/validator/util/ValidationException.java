// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.util;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Exception thrown when a validation check fails.
 */
public class ValidationException extends RuntimeException {

    private final String validatorTag;

    public ValidationException(@NonNull final String validatorTag, @NonNull final String message) {
        super(String.format("[%s] Validation failed: %s", validatorTag, message));
        this.validatorTag = validatorTag;
    }

    public ValidationException(
            @NonNull final String validatorTag, @NonNull final String message, @NonNull final Throwable cause) {
        super(String.format("[%s] Validation failed at: %s", validatorTag, message), cause);
        this.validatorTag = validatorTag;
    }

    public String getValidatorTag() {
        return validatorTag;
    }
}
