// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.exceptions;

/**
 * An exception that is thrown when an error in a copy operation is encountered.
 */
public class MerkleCopyException extends RuntimeException {

    public MerkleCopyException() {}

    public MerkleCopyException(final String message) {
        super(message);
    }

    public MerkleCopyException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public MerkleCopyException(final Throwable cause) {
        super(cause);
    }

    public MerkleCopyException(
            final String message,
            final Throwable cause,
            final boolean enableSuppression,
            final boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
