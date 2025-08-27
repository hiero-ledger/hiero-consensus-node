// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.notification;

import org.hiero.consensus.model.notification.Notification;

/**
 * Defines how the dispatcher handles the delivery of {@link Notification} to each registered {@link Listener}
 * implementation.
 */
public enum DispatchOrder {
    /**
     * Provides no guarantees in terms of ordering when the dispatcher is called from multiple threads for the same
     * {@link Listener} class.
     *
     * If used with {@link DispatchMode#SYNC}, then all {@link Notification} dispatched from a single thread will be in
     * order.
     */
    UNORDERED,

    /**
     * Provides a best effort ordering guarantee that {@link Listener} implementations will be notified in the original
     * order the {@link Notification} were dispatched.
     */
    ORDERED
}
