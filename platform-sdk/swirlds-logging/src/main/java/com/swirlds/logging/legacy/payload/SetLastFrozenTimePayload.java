// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

import java.time.Instant;

/**
 * This payload is logged when the platform sets last frozen time.
 */
public class SetLastFrozenTimePayload extends AbstractLogPayload {

    /** the last freezeTime based on which the nodes were frozen */
    private Instant lastFrozenTime;

    public SetLastFrozenTimePayload() {}

    public SetLastFrozenTimePayload(final Instant lastFrozenTime) {
        super("Set last frozen time");
        this.lastFrozenTime = lastFrozenTime;
    }

    public Instant getLastFrozenTime() {
        return lastFrozenTime;
    }

    public void setLastFrozenTime(Instant lastFrozenTime) {
        this.lastFrozenTime = lastFrozenTime;
    }
}
