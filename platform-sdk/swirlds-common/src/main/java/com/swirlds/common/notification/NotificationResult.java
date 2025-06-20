// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.notification;

import java.util.ArrayList;
import java.util.List;
import org.hiero.consensus.model.notification.Notification;

/**
 * Provides information and errors as the result of a given {@link NotificationEngine#dispatch(Class, Notification)}
 * call.
 *
 * @param <N>
 * 		the type of the {@link Notification} class
 */
public class NotificationResult<N extends Notification> {

    /**
     * the original notification that was sent to each listener
     */
    private final N notification;

    /**
     * the total number of registered and available listeners at the time of the dispatch
     */
    private final int totalListeners;

    /**
     * the list of exceptions, if any, that were thrown by the listeners
     */
    private final List<Throwable> exceptions;

    /**
     * Creates a new instance with no exceptions and the given number of registered listeners.
     *
     * @param notification
     * 		the original notification that was sent to each listener
     * @param totalListeners
     * 		the total number of registered listeners
     */
    public NotificationResult(final N notification, final int totalListeners) {
        this(notification, totalListeners, null);
    }

    /**
     * Creates a new instance with the provided list of exceptions and the given number of registered listeners.
     *
     * @param notification
     * 		the original notification that was sent to each listener
     * @param totalListeners
     * 		the total number of registered listeners
     * @param exceptions
     * 		the list of exceptions that occurred during listener invocation
     */
    public NotificationResult(final N notification, final int totalListeners, final List<Throwable> exceptions) {
        if (notification == null) {
            throw new IllegalArgumentException("notification");
        }

        this.notification = notification;
        this.totalListeners = totalListeners;
        this.exceptions = (exceptions != null) ? exceptions : new ArrayList<>();
    }

    /**
     * Getter that returns the original notification that was sent to each listener.
     *
     * @return the original notification that was sent to each listener
     */
    public N getNotification() {
        return notification;
    }

    /**
     * Getter that returns the total number of registered listeners at the time of dispatch.
     *
     * @return the total number of registered listeners
     */
    public int getTotalListeners() {
        return totalListeners;
    }

    /**
     * Getter that returns a list of {@link Throwable} instances that were thrown during listener invocation.
     *
     * @return the list of exceptions, if any, thrown during listener invocation
     */
    public List<Throwable> getExceptions() {
        return exceptions;
    }

    /**
     * Getter that returns the total number of failed listener invocations.
     *
     * @return the total number of failed listener invocations
     */
    public int getFailureCount() {
        return exceptions.size();
    }

    /**
     * Adds an {@link Exception} to the internal {@link List} of exceptions.
     *
     * @param ex
     * 		the exception to be added
     */
    public void addException(final Throwable ex) {
        exceptions.add(ex);
    }
}
