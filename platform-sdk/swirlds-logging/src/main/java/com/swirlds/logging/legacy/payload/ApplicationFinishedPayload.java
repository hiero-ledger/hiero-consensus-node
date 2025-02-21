// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

/**
 * An application may write this payload to the log to indicate to a validator that it finished.
 */
public class ApplicationFinishedPayload extends AbstractLogPayload {

    public ApplicationFinishedPayload(final String message) {
        super(message);
    }
}
