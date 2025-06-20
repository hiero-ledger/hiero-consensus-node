// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.notification;

import org.hiero.consensus.model.notification.Notification;

/**
 * Defines how the dispatcher for a given {@link Listener} operates with respect to the caller.
 */
public enum DispatchMode {
    /**
     * Blocking mode which guarantees that the {@link Notification} will have been successfully dispatched to all
     * registered {@link Listener} implementations before returning.
     *
     * The only guarantees provided are that the caller will be blocked until all registered listeners have been
     * notified and that any exceptions thrown by a listener implementation will be propagated to the caller.
     */
    SYNC,

    /**
     * Queues the notification for delivery and returns control to the caller as quickly as possible.
     * Any exceptions thrown will be available via the {@link NotificationResult#getExceptions()} method.
     */
    ASYNC
}
