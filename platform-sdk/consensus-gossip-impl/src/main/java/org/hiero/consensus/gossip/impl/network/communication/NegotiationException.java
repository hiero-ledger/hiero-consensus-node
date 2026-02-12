// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.network.communication;

/**
 * Thrown when an issue occurs during protocol negotiation
 */
public class NegotiationException extends Exception {
    public NegotiationException(final String message) {
        super(message);
    }
}
