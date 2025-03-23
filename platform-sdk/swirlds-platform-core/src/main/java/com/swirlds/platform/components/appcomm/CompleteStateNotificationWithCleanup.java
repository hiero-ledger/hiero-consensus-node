// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.components.appcomm;

import com.swirlds.common.notification.NotificationResult;
import com.swirlds.platform.system.state.notifications.NewSignedStateNotification;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.utility.StandardFuture;

/**
 * A record that contains a new complete state notification and a cleanup callback to be called when the notification
 * has been dispatched
 *
 * @param notification the new complete state notification
 * @param cleanup      the callback to be called when the notification has been dispatched
 */
public record CompleteStateNotificationWithCleanup(
        @NonNull NewSignedStateNotification notification,
        @NonNull StandardFuture.CompletionCallback<NotificationResult<NewSignedStateNotification>> cleanup) {}
