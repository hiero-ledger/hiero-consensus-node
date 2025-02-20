// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * This payload is used to signal that a state written to disk did not collect sufficient signatures to be considered
 * complete.
 */
public class InsufficientSignaturesPayload extends AbstractLogPayload {

    /**
     * Constructor
     * @param message a human-readable message
     */
    public InsufficientSignaturesPayload(@NonNull final String message) {
        super(message);
    }
}
