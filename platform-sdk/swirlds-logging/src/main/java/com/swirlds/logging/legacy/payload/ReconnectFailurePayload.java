// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

/**
 * This payload is logged when a reconnect attempt fails.
 */
public class ReconnectFailurePayload extends AbstractLogPayload {

    public enum CauseOfFailure {
        /**
         * Reconnect failed due to a socket exception.
         */
        SOCKET,
        /**
         * Reconnect failed due to the requested teacher being unwilling.
         */
        REJECTION,
        /**
         * Reconnect failed due to an error.
         */
        ERROR
    }

    private CauseOfFailure causeOfFailure;

    public ReconnectFailurePayload() {}

    /**
     * @param message
     * 		a human readable message
     * @param causeOfFailure
     * 		the reason why the reconnect failed
     */
    public ReconnectFailurePayload(final String message, final CauseOfFailure causeOfFailure) {
        super(message);
        this.causeOfFailure = causeOfFailure;
    }

    public CauseOfFailure getCauseOfFailure() {
        return causeOfFailure;
    }

    public void setCauseOfFailure(final CauseOfFailure causeOfFailure) {
        this.causeOfFailure = causeOfFailure;
    }
}
