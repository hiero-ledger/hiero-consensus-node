// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validators;

public class ValidationException extends RuntimeException {

    private final String validatorTag;

    public ValidationException(String validatorTag, String message) {
        super(String.format("[%s] Validation failed: %s", validatorTag, message));
        this.validatorTag = validatorTag;
    }

    public ValidationException(String validatorTag, String message, Throwable cause) {
        super(String.format("[%s] Validation failed at: %s", validatorTag, message), cause);
        this.validatorTag = validatorTag;
    }

    public String getValidatorTag() {
        return validatorTag;
    }
}
