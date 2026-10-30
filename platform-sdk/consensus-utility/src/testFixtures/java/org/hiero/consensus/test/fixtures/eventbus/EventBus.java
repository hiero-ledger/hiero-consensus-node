// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.test.fixtures.eventbus;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;

/**
 * A simple event bus implementation that allows components to subscribe to channels and publish messages.
 * Each instance of EventBus is associated with a specific {@link NodeId} to isolate eventbuses between different
 * nodes.
 */
public class EventBus {

    private static final Logger log = LogManager.getLogger();

    private static final Map<NodeId, EventBus> INSTANCES = new ConcurrentHashMap<>();

    private final Map<String, List<Consumer<String>>> subscribers = new ConcurrentHashMap<>();

    /**
     * Private constructor to prevent direct instantiation.
     */
    private EventBus() {}

    /**
     * Get an instance of EventBus associated with the given node.
     *
     * @param nodeId the ID of the node whose EventBus is requested
     * @return the EventBus instance associated with the given node
     */
    @NonNull
    public static EventBus getInstance(@NonNull final NodeId nodeId) {
        requireNonNull(nodeId);
        return INSTANCES.computeIfAbsent(nodeId, k -> new EventBus());
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
