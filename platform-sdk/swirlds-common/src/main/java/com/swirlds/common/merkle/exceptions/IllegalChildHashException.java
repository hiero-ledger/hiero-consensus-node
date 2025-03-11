// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.exceptions;

public class IllegalChildHashException extends RuntimeException {
    public IllegalChildHashException() {}

    public IllegalChildHashException(final String message) {
        super(message);
    }

    public IllegalChildHashException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public IllegalChildHashException(final Throwable cause) {
        super(cause);
    }
}
