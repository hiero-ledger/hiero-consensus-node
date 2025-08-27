// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.sync.protocol;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import java.util.Set;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * A utility class for checking if a platform status permits syncing
 */
public class SyncStatusChecker {
    /**
     * The platform statuses that permit syncing. If the platform isn't in one of these statuses, no syncs will be
     * initiated or accepted
     */
    public static final Collection<PlatformStatus> STATUSES_THAT_PERMIT_SYNC = Set.of(
            PlatformStatus.ACTIVE,
            PlatformStatus.FREEZING,
            PlatformStatus.FREEZE_COMPLETE,
            PlatformStatus.OBSERVING,
            PlatformStatus.CHECKING,
            PlatformStatus.RECONNECT_COMPLETE);

    /**
     * Hidden constructor
     */
    private SyncStatusChecker() {}

    /**
     * Determines if the given status permits syncing
     *
     * @param status the status to check
     * @return true if the status permits syncing, false otherwise
     */
    public static boolean doesStatusPermitSync(@NonNull final PlatformStatus status) {
        return STATUSES_THAT_PERMIT_SYNC.contains(status);
    }
}
