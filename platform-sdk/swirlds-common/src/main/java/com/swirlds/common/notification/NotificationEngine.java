// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.notification;

import com.swirlds.common.notification.internal.AsyncNotificationEngine;
import com.swirlds.common.threading.manager.ThreadManager;
import java.util.concurrent.Future;
import org.hiero.consensus.model.notification.Notification;
import org.hiero.consensus.model.utility.StandardFuture;

/**
 * Provides a lightweight and extensible (event) notification engine supporting both synchronous and asynchronous event
 * models along with unordered or ordered delivery of notifications.
 */
public interface NotificationEngine {

    /**
     * Build a new notification engine.
     *
     * @param threadManager
     * 		the thread manager for this node
     * @return a new notification engine
     */
    static NotificationEngine buildEngine(final ThreadManager threadManager) {
        return new AsyncNotificationEngine(threadManager);
    }

    /**
     * Prepares the engine for use and acquires any necessary threads or external resources.
     */
    void initialize();

    /**
     * Shuts down all underlying resources acquired during initialization and operation. This includes any threads or
     * other external resources that must be explicitly released.
     */
    void shutdown();

    /**
     * Dispatches a {@link Notification} instance to the listeners of the specified type.
     *
     * If the listener class uses {@link DispatchMode#SYNC} then this method will block until all registered listeners
     * have been notified and the {@link Future} returned by this method will already be complete.
     *
     * However, If the listener class uses {@link DispatchMode#ASYNC} then this method will return immediately after the
     * dispatch request has been given to the dispatcher. Only when the returned {@link Future} is resolved will all the
     * registered listeners have been notified. Any exceptions thrown during listener notification will be provided by
     * the {@link NotificationResult#getExceptions()} method.
     *
     * @param listenerClass
     * 		the type of listener to which the given notification should be sent
     * @param notification
     * 		the notification to be sent to all registered listeners
     * @param <L>
     * 		the type of the {@link Listener} class
     * @param <N>
     * 		the type of the {@link Notification} class
     * @return a {@link Future} that upon resolution provides the status of the dispatch request as {@link
     *        NotificationResult} instance
     */
    default <L extends Listener<N>, N extends Notification> Future<NotificationResult<N>> dispatch(
            final Class<L> listenerClass, final N notification) {
        return dispatch(listenerClass, notification, null);
    }

    /**
     * Dispatches a {@link Notification} instance to the listeners of the specified type.
     *
     * If the listener class uses {@link DispatchMode#SYNC} then this method will block until all registered listeners
     * have been notified and the {@link Future} returned by this method will already be complete.
     *
     * However, If the listener class uses {@link DispatchMode#ASYNC} then this method will return immediately after the
     * dispatch request has been given to the dispatcher. Only when the returned {@link Future} is resolved will all the
     * registered listeners have been notified. Any exceptions thrown during listener notification will be provided by
     * the {@link NotificationResult#getExceptions()} method.
     *
     * @param listenerClass
     * 		the type of listener to which the given notification should be sent
     * @param notification
     * 		the notification to be sent to all registered listeners
     * @param <L>
     * 		the type of the {@link Listener} class
     * @param <N>
     * 		the type of the {@link Notification} class
     * @param notificationsCompletedCallback
     * 		an optional callback that is invoked when the returned future
     * 		is completed, ignored if null.
     * @return a {@link Future} that upon resolution provides the status of the dispatch request as {@link
     *        NotificationResult} instance
     */
    <L extends Listener<N>, N extends Notification> Future<NotificationResult<N>> dispatch(
            final Class<L> listenerClass,
            final N notification,
            final StandardFuture.CompletionCallback<NotificationResult<N>> notificationsCompletedCallback);

    /**
     * Registers a concrete {@link Listener} implementation with the underlying dispatcher.
     *
     * @param listenerClass
     * 		the type of listener for which the concrete implementation should be registered
     * @param callback
     * 		the concrete listener implementation to be registered
     * @param <L>
     * 		the type of the {@link Listener} class
     * @return true if the listener was successfully registered with the dispatcher; otherwise false
     */
    <L extends Listener<?>> boolean register(final Class<L> listenerClass, final L callback);

    /**
     * Removes a concrete {@link Listener} implementation from the underlying dispatcher.
     *
     * @param listenerClass
     * 		the type of listener for which the concrete implementation should be unregistered
     * @param callback
     * 		the concrete listener implementation to be unregistered
     * @param <L>
     * 		the type of the {@link Listener} class
     * @return true if the listener was successfully unregistered from the dispatcher; otherwise false
     */
    <L extends Listener<?>> boolean unregister(final Class<L> listenerClass, final L callback);

    // FUTURE WORK this method can removed once the notification engine is managed by the PlatformContext

    /**
     * <p>
     * Unregister ALL listeners.
     * </p>
     *
     * <p>
     * DANGER: calling this method on a running system could have disastrous consequences.
     * Think very carefully before calling this method.
     * </p>
     */
    void unregisterAll();
}
