// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.crypto;

/**
 * Thrown when an issue occurs while generating keys deterministically
 */
public class KeyGeneratingException extends Exception {
    public KeyGeneratingException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
