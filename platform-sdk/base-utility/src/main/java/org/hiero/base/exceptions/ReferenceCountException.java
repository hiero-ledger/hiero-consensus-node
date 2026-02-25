// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.exceptions;

/**
 * This exception is thrown when there are problems with reference counts.
 */
public class ReferenceCountException extends RuntimeException {

    public ReferenceCountException() {}

    public ReferenceCountException(final String message) {
        super(message);
    }

    public ReferenceCountException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public ReferenceCountException(final Throwable cause) {
        super(cause);
    }

    public ReferenceCountException(
            final String message,
            final Throwable cause,
            final boolean enableSuppression,
            final boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
