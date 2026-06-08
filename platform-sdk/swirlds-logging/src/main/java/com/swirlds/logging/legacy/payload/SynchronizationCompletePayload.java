// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

/**
 * This message is logged by the receiving synchronizer when synchronization has completed.
 */
public class SynchronizationCompletePayload extends AbstractLogPayload {

    private double timeInSeconds;

    public SynchronizationCompletePayload() {}

    /**
     * @param message
     * 		the human readable message
     */
    public SynchronizationCompletePayload(final String message) {
        super(message);
    }

    /**
     * Get the time required to transmit the state over the network, measured in seconds.
     */
    public double getTimeInSeconds() {
        return timeInSeconds;
    }

    /**
     * Set the time required to transmit the state over the network, measured in seconds.
     *
     * @param timeInSeconds
     * 		the time required to transmit the state
     * @return this object
     */
    public SynchronizationCompletePayload setTimeInSeconds(double timeInSeconds) {
        this.timeInSeconds = timeInSeconds;
        return this;
    }
}
