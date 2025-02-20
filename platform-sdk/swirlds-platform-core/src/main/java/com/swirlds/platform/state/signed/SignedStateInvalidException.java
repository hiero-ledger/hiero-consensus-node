// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.signed;

/**
 * This exception is thrown if a signed state is invalid, usually due to lack of sufficient signatures.
 */
public class SignedStateInvalidException extends RuntimeException {

    public SignedStateInvalidException(final String message) {
        super(message);
    }

    public SignedStateInvalidException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public SignedStateInvalidException(final Throwable cause) {
        super(cause);
    }
}
