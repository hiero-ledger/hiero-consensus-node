// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.components;

import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.platform.components.appcomm.CompleteStateNotificationWithCleanup;
import com.swirlds.platform.listeners.PlatformStatusChangeListener;
import com.swirlds.platform.listeners.PlatformStatusChangeNotification;
import com.swirlds.platform.listeners.ReconnectCompleteListener;
import com.swirlds.platform.listeners.ReconnectCompleteNotification;
import com.swirlds.platform.listeners.StateWriteToDiskCompleteListener;
import com.swirlds.platform.listeners.StateWriteToDiskCompleteNotification;
import com.swirlds.platform.system.state.notifications.AsyncFatalIssListener;
import com.swirlds.platform.system.state.notifications.IssListener;
import com.swirlds.platform.system.state.notifications.NewSignedStateListener;
import com.swirlds.platform.system.state.notifications.StateHashedListener;
import com.swirlds.platform.system.state.notifications.StateHashedNotification;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.notification.IssNotification;
import org.hiero.consensus.model.notification.IssNotification.IssType;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * The default implementation of the AppNotifier interface.
 */
public record DefaultAppNotifier(@NonNull NotificationEngine notificationEngine) implements AppNotifier {
    /**
     * {@inheritDoc}
     */
    @Override
    public void sendStateWrittenToDiskNotification(@NonNull final StateWriteToDiskCompleteNotification notification) {
        notificationEngine.dispatch(StateWriteToDiskCompleteListener.class, notification);
    }

    @Override
    public void sendStateHashedNotification(@NonNull final StateHashedNotification notification) {
        notificationEngine.dispatch(StateHashedListener.class, notification);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendReconnectCompleteNotification(@NonNull final ReconnectCompleteNotification notification) {
        notificationEngine.dispatch(ReconnectCompleteListener.class, notification);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendPlatformStatusChangeNotification(@NonNull final PlatformStatus newStatus) {
        notificationEngine.dispatch(
                PlatformStatusChangeListener.class, new PlatformStatusChangeNotification(newStatus));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendLatestCompleteStateNotification(
            @NonNull final CompleteStateNotificationWithCleanup notificationWithCleanup) {
        notificationEngine.dispatch(
                NewSignedStateListener.class,
                notificationWithCleanup.notification(),
                notificationWithCleanup.cleanup());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendIssNotification(@NonNull final IssNotification notification) {
        notificationEngine.dispatch(IssListener.class, notification);

        if (IssType.CATASTROPHIC_ISS == notification.getIssType() || IssType.SELF_ISS == notification.getIssType()) {
            // Forward notification to application
            notificationEngine.dispatch(AsyncFatalIssListener.class, notification);
        }
    }
}
