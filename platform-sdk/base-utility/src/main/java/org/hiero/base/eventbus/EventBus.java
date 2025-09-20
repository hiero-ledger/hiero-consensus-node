// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.eventbus;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A simple event bus implementation that allows components to subscribe to channels and publish messages.
 * Each instance of EventBus is associated with a specific key (e.g., NodeId) to isolate eventbuses between different
 * contexts.
 */
public class EventBus {

    private static final Logger log = LogManager.getLogger();

    private static final Map<Object, EventBus> INSTANCES = new ConcurrentHashMap<>();

    private final Map<String, List<Consumer<String>>> subscribers = new ConcurrentHashMap<>();

    /**
     * Private constructor to prevent direct instantiation.
     */
    private EventBus() {}

    /**
     * Get an instance of EventBus associated with the given key. The key is typically the {@code NodeId}.
     *
     * @param key the key to identify the EventBus instance
     * @return the EventBus instance associated with the given key
     */
    @NonNull
    public static EventBus getInstance(@NonNull final Object key) {
        requireNonNull(key);
        return INSTANCES.computeIfAbsent(key, k -> new EventBus());
    }

    /**
     * Subscribe to a channel with a callback function
     *
     * @param channel the channel to subscribe to
     * @param callback the callback function to execute when a message is published to the channel
     * @throws NullPointerException if {@code channel} or {@code callback} is {@code null}
     */
    public void subscribe(@NonNull final String channel, @NonNull final Consumer<String> callback) {
        requireNonNull(channel);
        requireNonNull(callback);

        subscribers.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>()).add(callback);
    }

    /**
     * Unsubscribe a specific callback from a channel
     *
     * @param channel the channel to unsubscribe from
     * @param callback the callback function to remove
     * @return {@code true} if the callback was found and removed, {@code false} otherwise
     * @throws NullPointerException if {@code channel} or {@code callback} is {@code null}
     */
    public boolean unsubscribe(@NonNull final String channel, @NonNull final Consumer<String> callback) {
        requireNonNull(channel);
        requireNonNull(callback);

        final List<Consumer<String>> channelSubscribers = subscribers.get(channel);
        if (channelSubscribers != null) {
            final boolean removed = channelSubscribers.remove(callback);
            if (channelSubscribers.isEmpty()) {
                subscribers.remove(channel);
            }
            return removed;
        }
        return false;
    }

    /**
     * Unsubscribe all callbacks from a channel
     *
     * @param channel the channel to clear
     * @throws NullPointerException if {@code channel} is {@code null}
     */
    public void unsubscribeAll(@NonNull final String channel) {
        requireNonNull(channel);

        subscribers.remove(channel);
    }

    /**
     * Publish a message to a channel
     *
     * @param channel the channel to publish to
     * @param message the message payload
     * @throws NullPointerException if {@code channel} or {@code message} is {@code null}
     */
    public void publish(@NonNull final String channel, @NonNull final String message) {
        requireNonNull(channel);
        requireNonNull(message);

        final List<Consumer<String>> channelSubscribers = subscribers.get(channel);
        if (channelSubscribers != null) {
            for (final Consumer<String> callback : channelSubscribers) {
                try {
                    callback.accept(message);
                } catch (final Exception e) {
                    // Log the error but continue with other subscribers
                    log.error("Error executing callback for channel '{}'", channel, e);
                }
            }
        }
    }

    /**
     * Shutdown the EventBus by clearing all subscribers and removing the instance from the global map.
     */
    public void shutdown() {
        subscribers.clear();
        INSTANCES.values().removeIf(instance -> instance == this);
    }
}
