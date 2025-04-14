// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.embedded.fakes;

import static java.util.Objects.requireNonNull;

import com.swirlds.common.notification.Listener;
import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.common.notification.NotificationResult;
import com.swirlds.platform.listeners.PlatformStatusChangeListener;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import org.hiero.consensus.model.notification.Notification;
import org.hiero.consensus.model.utility.StandardFuture;

public class FakeNotificationEngine implements NotificationEngine {
    public final List<PlatformStatusChangeListener> statusChangeListeners = new CopyOnWriteArrayList<>();

    @Override
    public void initialize() {
        throw new UnsupportedOperationException("Not used by Hedera");
    }

    @Override
    public void shutdown() {
        throw new UnsupportedOperationException("Not used by Hedera");
    }

    @Override
    public <L extends Listener<N>, N extends Notification> Future<NotificationResult<N>> dispatch(
            Class<L> listenerClass,
            N notification,
            StandardFuture.CompletionCallback<NotificationResult<N>> notificationsCompletedCallback) {
        throw new UnsupportedOperationException("Not used by Hedera");
    }

    @Override
    public <L extends Listener<?>> boolean register(@NonNull final Class<L> listenerClass, @NonNull final L callback) {
        requireNonNull(listenerClass);
        requireNonNull(callback);
        if (listenerClass == PlatformStatusChangeListener.class) {
            return statusChangeListeners.add((PlatformStatusChangeListener) callback);
        }
        return false;
    }

    @Override
    public <L extends Listener<?>> boolean unregister(Class<L> listenerClass, L callback) {
        throw new UnsupportedOperationException("Not used by Hedera");
    }

    @Override
    public void unregisterAll() {
        throw new UnsupportedOperationException("Not used by Hedera");
    }
}
