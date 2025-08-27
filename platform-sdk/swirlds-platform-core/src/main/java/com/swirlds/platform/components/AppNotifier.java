// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.components;

import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.platform.components.appcomm.CompleteStateNotificationWithCleanup;
import com.swirlds.platform.listeners.ReconnectCompleteNotification;
import com.swirlds.platform.listeners.StateWriteToDiskCompleteNotification;
import com.swirlds.platform.system.state.notifications.StateHashedNotification;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.notification.IssNotification;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * A component that wraps around a notification engine, for sending notifications to the app.
 */
public interface AppNotifier {
    /**
     * Send a notification to the app that a state has been written to disk.
     *
     * @param notification the notification
     */
    @InputWireLabel("state written notification")
    void sendStateWrittenToDiskNotification(@NonNull final StateWriteToDiskCompleteNotification notification);

    /**
     * Send a notification to the app that a state has been written to disk.
     *
     * @param notification the notification
     */
    @InputWireLabel("state hashed notification")
    void sendStateHashedNotification(@NonNull final StateHashedNotification notification);

    /**
     * Send a notification to the app that a reconnect has completed.
     *
     * @param notification the notification
     */
    @InputWireLabel("reconnect notification")
    void sendReconnectCompleteNotification(@NonNull final ReconnectCompleteNotification notification);

    /**
     * Send a notification to the app that the platform status has changed.
     *
     * @param newStatus the new status
     */
    @InputWireLabel("PlatformStatus")
    void sendPlatformStatusChangeNotification(@NonNull final PlatformStatus newStatus);

    /**
     * Send a notification to the app with the latest complete state.
     *
     * @param notificationWithCleanup the notification, with required cleanup
     */
    @InputWireLabel("complete state notification")
    void sendLatestCompleteStateNotification(
            @NonNull final CompleteStateNotificationWithCleanup notificationWithCleanup);

    /**
     * Notify the app of an ISS
     *
     * @param notification the notification
     */
    @InputWireLabel("IssNotification")
    void sendIssNotification(@NonNull final IssNotification notification);
}
