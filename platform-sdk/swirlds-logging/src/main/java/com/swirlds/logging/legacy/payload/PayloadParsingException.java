// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

/**
 * This exception is thrown when payload parsing fails.
 */
public class PayloadParsingException extends RuntimeException {
    public PayloadParsingException() {}

    public PayloadParsingException(String message) {
        super(message);
    }

    public PayloadParsingException(String message, Throwable cause) {
        super(message, cause);
    }

    public PayloadParsingException(Throwable cause) {
        super(cause);
    }

    public PayloadParsingException(
            String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
