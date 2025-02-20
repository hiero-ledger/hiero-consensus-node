// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

/**
 * This payload is used when a fatal message is logged.
 */
public class FatalErrorPayload extends AbstractLogPayload {
    public FatalErrorPayload(final String message) {
        super(message);
    }
}
