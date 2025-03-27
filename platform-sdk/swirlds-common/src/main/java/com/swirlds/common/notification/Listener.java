// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.notification;

import org.hiero.consensus.model.notification.Notification;

/**
 * The base functional interface that must be implemented by all notification listeners. Uses the default {@link
 * DispatchModel} configuration.
 *
 * @param <N>
 * 		the type of the supported {@link Notification} which is passed to the {@link #notify(Notification)} method.
 */
@FunctionalInterface
@DispatchModel
public interface Listener<N extends Notification> {

    /**
     * Called for each {@link Notification} that this listener should handle.
     *
     * @param data
     * 		the notification to be handled
     */
    void notify(final N data);
}
