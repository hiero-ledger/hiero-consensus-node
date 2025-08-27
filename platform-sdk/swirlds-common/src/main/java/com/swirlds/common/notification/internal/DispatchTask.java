// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.notification.internal;

import com.swirlds.common.notification.Listener;
import com.swirlds.common.notification.NotificationResult;
import java.util.function.Consumer;
import org.hiero.consensus.model.notification.Notification;

public class DispatchTask<L extends Listener<N>, N extends Notification> implements Comparable<DispatchTask<L, N>> {

    private N notification;

    private Consumer<NotificationResult<N>> callback;

    public DispatchTask(final N notification, final Consumer<NotificationResult<N>> callback) {
        if (notification == null) {
            throw new IllegalArgumentException("notification");
        }

        this.notification = notification;
        this.callback = callback;
    }

    public N getNotification() {
        return notification;
    }

    public Consumer<NotificationResult<N>> getCallback() {
        return callback;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int compareTo(final DispatchTask<L, N> that) {
        final int EQUAL = 0;
        final int GREATER_THAN = 1;

        if (this == that) {
            return EQUAL;
        }

        if (that == null) {
            return GREATER_THAN;
        }

        return Long.compare(this.notification.getSequence(), that.notification.getSequence());
    }
}
