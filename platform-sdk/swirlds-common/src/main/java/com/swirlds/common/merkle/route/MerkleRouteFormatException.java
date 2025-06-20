// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.route;

/**
 * Thrown when there is trouble parsing a merkle route.
 */
public class MerkleRouteFormatException extends RuntimeException {
    public MerkleRouteFormatException() {
        super();
    }

    public MerkleRouteFormatException(final String message) {
        super(message);
    }

    public MerkleRouteFormatException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public MerkleRouteFormatException(final Throwable cause) {
        super(cause);
    }

    protected MerkleRouteFormatException(
            final String message,
            final Throwable cause,
            final boolean enableSuppression,
            final boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
