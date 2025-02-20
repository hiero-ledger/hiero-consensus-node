// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.stream.internal;

/**
 * Thrown when parsing an invalid stream file.
 */
public class InvalidStreamFileException extends Exception {
    /**
     * Constructs a new exception with the specified detail message and
     * cause.
     *
     * @param message
     * 		the detail message
     * @param cause
     * 		the cause
     */
    public InvalidStreamFileException(final String message, final Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new exception with the specified detail message.  The
     * cause is not initialized.
     *
     * @param message
     * 		the detail message.
     */
    public InvalidStreamFileException(final String message) {
        super(message);
    }
}
