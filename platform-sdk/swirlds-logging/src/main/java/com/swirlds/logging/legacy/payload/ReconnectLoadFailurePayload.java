// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

/**
 * This payload is logged when a state obtained from a reconnect operation can not be loaded.
 */
public class ReconnectLoadFailurePayload extends AbstractLogPayload {

    public ReconnectLoadFailurePayload() {}

    /**
     * @param message
     * 		a human readable message
     */
    public ReconnectLoadFailurePayload(final String message) {
        super(message);
    }
}
