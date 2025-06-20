// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

import java.time.Instant;

/**
 * This payload is logged when the platform sets freeze time
 */
public class SetFreezeTimePayload extends AbstractLogPayload {

    /** the time when the freeze starts */
    private Instant freezeTime;

    public SetFreezeTimePayload() {}

    public SetFreezeTimePayload(final Instant freezeTime) {
        super("Set freeze time");
        this.freezeTime = freezeTime;
    }

    public Instant getFreezeTime() {
        return freezeTime;
    }

    public void setFreezeTime(Instant freezeTime) {
        this.freezeTime = freezeTime;
    }
}
