// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.util;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Locale;

/**
 * Exception thrown when a validation check fails.
 */
public class ValidationException extends RuntimeException {

    private final String validatorName;

    public ValidationException(@NonNull final String validatorName, @NonNull final String message) {
        super(String.format(Locale.ROOT, "[%s] Validation failed: %s", validatorName, message));
        this.validatorName = validatorName;
    }

    public ValidationException(
            @NonNull final String validatorName, @NonNull final String message, @NonNull final Throwable cause) {
        super(String.format(Locale.ROOT, "[%s] Validation failed at: %s", validatorName, message), cause);
        this.validatorName = validatorName;
    }

    public String getValidatorName() {
        return validatorName;
    }
}
