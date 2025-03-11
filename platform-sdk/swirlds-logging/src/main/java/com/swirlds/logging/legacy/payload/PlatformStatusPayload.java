// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

/**
 * This payload is logged when the platform status changes.
 */
public class PlatformStatusPayload extends AbstractLogPayload {

    private String oldStatus;
    private String newStatus;

    public PlatformStatusPayload() {}

    /**
     * @param message
     * 		the message in this payload
     * @param oldStatus
     * 		the original status of the platform
     * @param newStatus
     * 		the new status of the platform
     */
    public PlatformStatusPayload(final String message, final String oldStatus, final String newStatus) {
        super(message);
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
    }

    public String getOldStatus() {
        return oldStatus;
    }

    public void setOldStatus(String oldStatus) {
        this.oldStatus = oldStatus;
    }

    public String getNewStatus() {
        return newStatus;
    }

    public void setNewStatus(String newStatus) {
        this.newStatus = newStatus;
    }
}
