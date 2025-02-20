// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

/**
 * This payload is logged when a recovered state is saved.
 */
public class RecoveredStateSavedPayload extends AbstractLogPayload {

    /**
     * The round of the state that was saved.
     */
    private long round;

    public RecoveredStateSavedPayload(String message, long round) {
        super(message);
        this.round = round;
    }

    public long getRound() {
        return round;
    }

    public void setRound(long round) {
        this.round = round;
    }
}
