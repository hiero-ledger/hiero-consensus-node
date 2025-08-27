// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import com.swirlds.logging.legacy.LogMarker;

/**
 * Exception caused when Invalid algorithm name was provided
 */
public class InvalidDigestTypeException extends CryptographyException {

    private static final String MESSAGE_TEMPLATE = "Invalid algorithm name was provided (%s)";

    public InvalidDigestTypeException(final String algorithmName) {
        super(String.format(MESSAGE_TEMPLATE, algorithmName), LogMarker.TESTING_EXCEPTIONS);
    }

    public InvalidDigestTypeException(final String algorithmName, final Throwable cause, final LogMarker logMarker) {
        super(String.format(MESSAGE_TEMPLATE, algorithmName), cause, LogMarker.TESTING_EXCEPTIONS);
    }
}
