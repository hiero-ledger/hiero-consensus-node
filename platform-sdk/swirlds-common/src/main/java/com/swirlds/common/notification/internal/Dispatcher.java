// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.notification.internal;

import com.swirlds.common.notification.DispatchException;
import com.swirlds.common.notification.Listener;
import com.swirlds.common.notification.NotificationResult;
import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import com.swirlds.common.threading.manager.ThreadManager;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.function.Consumer;
import org.hiero.consensus.model.notification.Notification;

public class Dispatcher<L extends Listener> {

    private static final int THREAD_STOP_WAIT_MS = 5000;
    private static final String COMPONENT_NAME = "dispatch";

    private final PriorityBlockingQueue<DispatchTask<?, ?>> asyncDispatchQueue;

    private final String listenerClassName;

    private final Object mutex;

    private final List<L> listeners;

    private volatile Thread dispatchThread;

    private volatile boolean running;

    /**
     * Responsible for creating and managing threads used by this object.
     */
    private final ThreadManager threadManager;

    /**
     * Create a new dispatcher.
     *
     * @param threadManager
     * 		responsible for creating and managing threads used for dispatches
     * @param listenerClass
     * 		the dispatch type
     */
    public Dispatcher(final ThreadManager threadManager, final Class<L> listenerClass) {
        this.threadManager = threadManager;
        this.mutex = new Object();
        this.listeners = new CopyOnWriteArrayList<>();
        this.asyncDispatchQueue = new PriorityBlockingQueue<>();
        this.listenerClassName = listenerClass.getSimpleName();
    }

    public Object getMutex() {
        return mutex;
    }

    public synchronized boolean isRunning() {
        return running && dispatchThread != null && dispatchThread.isAlive();
    }

    public synchronized void start() {
        if (dispatchThread != null && dispatchThread.isAlive()) {
            stop();
        }

        dispatchThread = new ThreadConfiguration(threadManager)
                .setComponent(COMPONENT_NAME)
                .setThreadName(String.format("notify %s", listenerClassName))
                .setRunnable(this::worker)
                .build();

        running = true;
        dispatchThread.start();
    }

    public synchronized void stop() {
        running = false;

        if (asyncDispatchQueue.size() == 0) {
            dispatchThread.interrupt();
        }

        try {
            dispatchThread.join(THREAD_STOP_WAIT_MS);

            if (dispatchThread.isAlive() && !dispatchThread.isInterrupted()) {
                dispatchThread.interrupt();
            }

            dispatchThread = null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public <N extends Notification> void notifySync(
            final N notification, final Consumer<NotificationResult<N>> callback) {
        handleDispatch(notification, false, callback);
    }

    public <N extends Notification> void notifyAsync(
            final N notification, final Consumer<NotificationResult<N>> callback) {
        if (!isRunning()) {
            start();
        }

        asyncDispatchQueue.put(new DispatchTask<>(notification, callback));
    }

    public synchronized boolean addListener(final L listener) {
        return listeners.add(listener);
    }

    public synchronized boolean removeListener(final L listener) {
        return listeners.remove(listener);
    }

    private <N extends Notification> void handleDispatch(
            final N notification, final boolean throwOnError, final Consumer<NotificationResult<N>> callback) {

        final NotificationResult<N> result = new NotificationResult<>(notification, listeners.size());

        for (final L l : listeners) {
            try {
                @SuppressWarnings("unchecked")
                final Listener<N> listener = (Listener<N>) l;
                listener.notify(notification);
            } catch (final Throwable ex) {
                if (throwOnError) {
                    throw new DispatchException(ex);
                }

                result.addException(ex);
            }
        }

        if (callback != null) {
            callback.accept(result);
        }
    }

    private void worker() {
        try {
            while (running || asyncDispatchQueue.size() > 0) {
                @SuppressWarnings("unchecked")
                final DispatchTask<Listener<Notification>, Notification> task =
                        (DispatchTask<Listener<Notification>, Notification>) asyncDispatchQueue.take();

                handleDispatch(task.getNotification(), false, task.getCallback());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
