// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.logging.internal;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hiero.otter.fixtures.logging.StructuredLog;
import org.hiero.otter.fixtures.result.LogSubscriber;
import org.hiero.otter.fixtures.result.SubscriberAction;

/**
 * An in-memory subscription manager that allows subscribers to receive {@link StructuredLog} entries.
 *
 * <p>This manager maintains a list of subscribers and notifies them when new log entries are available. Subscribers can
 * unsubscribe by returning {@link SubscriberAction#UNSUBSCRIBE} from their callback method.
 */
public enum InMemorySubscriptionManager {
    INSTANCE;

    private final List<LogSubscriber> subscribers = new CopyOnWriteArrayList<>();

    /**
     * Subscribes a new {@link LogSubscriber} to receive log entries.
     *
     * @param subscriber the subscriber to add
     */
    public void subscribe(@NonNull final LogSubscriber subscriber) {
        requireNonNull(subscriber);
        subscribers.add(subscriber);
    }

    /**
     * Notifies all subscribers with the provided {@link StructuredLog} entry.
     * Subscribers can unsubscribe by returning {@link SubscriberAction#UNSUBSCRIBE}.
     *
     * @param log the log entry to notify subscribers about
     */
    public void notifySubscribers(@NonNull final StructuredLog log) {
        requireNonNull(log);
        subscribers.removeIf(subscriber -> subscriber.onLogEntry(log) == SubscriberAction.UNSUBSCRIBE);
    }

    /**
     * Resets the subscription manager by clearing all subscribers.
     * This is typically used to reset the state between tests.
     */
    public void reset() {
        subscribers.clear();
    }
}
