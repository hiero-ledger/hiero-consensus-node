// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.info;

import com.swirlds.platform.listeners.PlatformStatusChangeListener;
import com.swirlds.platform.system.Platform;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Singleton;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * An implementation of {@link CurrentPlatformStatus} that uses the {@link Platform} to get the current status.
 */
@Singleton
public class CurrentPlatformStatusImpl implements CurrentPlatformStatus {
    private PlatformStatus status = PlatformStatus.STARTING_UP;

    public CurrentPlatformStatusImpl(@NonNull final Platform platform) {
        platform.getNotificationEngine()
                .register(PlatformStatusChangeListener.class, notification -> status = notification.getNewStatus());
    }

    @NonNull
    @Override
    public PlatformStatus get() {
        return status;
    }
}
