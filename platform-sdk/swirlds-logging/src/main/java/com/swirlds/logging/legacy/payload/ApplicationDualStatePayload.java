// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

import java.time.Instant;

/**
 * This payload is logged when the swirld app receives a dual state instance
 */
public class ApplicationDualStatePayload extends AbstractLogPayload {

    /** the time when the freeze starts */
    private Instant freezeTime;

    /** the last freezeTime based on which the nodes were frozen */
    private Instant lastFrozenTime;

    public ApplicationDualStatePayload() {}

    public ApplicationDualStatePayload(final Instant freezeTime, final Instant lastFrozenTime) {
        super("App init with a dual state");
        this.freezeTime = freezeTime;
        this.lastFrozenTime = lastFrozenTime;
    }

    public Instant getFreezeTime() {
        return freezeTime;
    }

    public void setFreezeTime(Instant freezeTime) {
        this.freezeTime = freezeTime;
    }

    public Instant getLastFrozenTime() {
        return lastFrozenTime;
    }

    public void setLastFrozenTime(Instant lastFrozenTime) {
        this.lastFrozenTime = lastFrozenTime;
    }
}
