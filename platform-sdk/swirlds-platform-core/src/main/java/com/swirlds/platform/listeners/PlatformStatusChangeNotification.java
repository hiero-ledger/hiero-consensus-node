// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.listeners;

import org.hiero.consensus.model.notification.AbstractNotification;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * This notification is sent when the platform status changes.
 */
public class PlatformStatusChangeNotification extends AbstractNotification {

    private final PlatformStatus newStatus;

    /**
     * Create a new platform status change notification.
     *
     * @param newStatus
     * 		the new status of the platform
     */
    public PlatformStatusChangeNotification(final PlatformStatus newStatus) {
        this.newStatus = newStatus;
    }

    /**
     * Get the new platform status.
     *
     * @return the new platform status
     */
    public PlatformStatus getNewStatus() {
        return newStatus;
    }
}
