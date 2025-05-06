// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.nexus;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * A thread-safe container that also manages reservations for the emergency state.
 */
public class EmergencyStateNexus extends LockFreeStateNexus {
    /**
     * Clears the current state when the platform becomes active.
     *
     * @param status the new platform status
     */
    public void platformStatusChanged(@NonNull final PlatformStatus status) {
        if (status == PlatformStatus.ACTIVE) {
            clear();
        }
    }
}
