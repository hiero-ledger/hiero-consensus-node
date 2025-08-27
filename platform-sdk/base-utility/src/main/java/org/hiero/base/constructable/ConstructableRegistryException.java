// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.constructable;

/**
 * Exception caused when constructor cannot be registered for any reason
 */
public final class ConstructableRegistryException extends Exception {
    public ConstructableRegistryException(final String msg) {
        super(msg);
    }

    public ConstructableRegistryException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
