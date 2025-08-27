// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.notification.internal;

import com.swirlds.common.notification.DispatchMode;
import com.swirlds.common.notification.DispatchModel;
import com.swirlds.common.notification.DispatchOrder;
import com.swirlds.common.notification.Listener;
import com.swirlds.common.notification.NotificationEngine;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.hiero.consensus.model.notification.Notification;

public abstract class AbstractNotificationEngine implements NotificationEngine {

    private Map<Class<? extends Listener>, DispatchMode> listenerModeCache;
    private Map<Class<? extends Listener>, DispatchOrder> listenerOrderCache;
    private AtomicLong sequence;

    public AbstractNotificationEngine() {
        this.listenerModeCache = new HashMap<>();
        this.listenerOrderCache = new HashMap<>();
        this.sequence = new AtomicLong(0);
    }

    protected synchronized <L extends Listener> DispatchMode dispatchMode(final Class<L> listenerClass) {
        if (listenerModeCache.containsKey(listenerClass)) {
            return listenerModeCache.get(listenerClass);
        }

        final DispatchModel model = listenerClass.getAnnotation(DispatchModel.class);
        DispatchMode mode = DispatchMode.SYNC;

        if (model != null) {
            mode = model.mode();
        }

        listenerModeCache.putIfAbsent(listenerClass, mode);
        return mode;
    }

    protected synchronized <L extends Listener> DispatchOrder dispatchOrder(final Class<L> listenerClass) {
        if (listenerOrderCache.containsKey(listenerClass)) {
            return listenerOrderCache.get(listenerClass);
        }

        final DispatchModel model = listenerClass.getAnnotation(DispatchModel.class);
        DispatchOrder order = DispatchOrder.UNORDERED;

        if (model != null) {
            order = model.order();
        }

        listenerOrderCache.putIfAbsent(listenerClass, order);
        return order;
    }

    protected <N extends Notification> void assignSequence(final N notification) {
        if (notification == null) {
            throw new IllegalArgumentException("notification");
        }

        if (notification.getSequence() != 0) {
            return;
        }

        notification.setSequence(sequence.incrementAndGet());
    }
}
