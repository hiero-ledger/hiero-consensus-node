// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

/**
 * Classes representing machine parsable log messages should implement this interface.
 *
 * A zero argument constructor is required.
 */
public interface LogPayload {

    /**
     * Get the human readable message contained within the payload.
     *
     * @return human readable message contained within the payload
     */
    String getMessage();

    /**
     * Set the human readable message contained within the payload.
     *
     * @param message
     * 		the human readable message in the payload
     */
    void setMessage(String message);
}
