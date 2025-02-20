// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.communication;

/**
 * Thrown when an issue occurs during protocol negotiation
 */
public class NegotiationException extends Exception {
    public NegotiationException(final String message) {
        super(message);
    }
}
