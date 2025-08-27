// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.exceptions;

/**
 * Thrown when an issue occurs while constructing required platform instances
 */
public class PlatformConstructionException extends RuntimeException {
    public PlatformConstructionException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public PlatformConstructionException(final Throwable cause) {
        super(cause);
    }
}
