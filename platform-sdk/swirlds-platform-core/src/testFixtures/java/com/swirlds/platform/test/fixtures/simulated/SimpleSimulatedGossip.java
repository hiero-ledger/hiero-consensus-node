// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.simulated;

import com.swirlds.base.time.Time;
import com.swirlds.platform.test.fixtures.simulated.config.NetworkConfig;
import com.swirlds.platform.test.fixtures.simulated.config.NodeConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.stream.Collectors;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.node.NodeId;

/**
 * A simple gossip simulation where events are distributed with a delay
 */
public class SimpleSimulatedGossip {
    private final Time time;
    /** Map from node id to that node's message handler */
    private final Map<NodeId, GossipMessageHandler> nodes;

    private final Map<NodeId, Deque<Payload>> inTransit;
    private final Map<NodeId, Deque<GossipMessage>> delivered;
    private final Map<NodeId, Deque<GossipMessage>> sentBy;
    private final NetworkLatency latency;

    /**
     * Creates an instance to simulate gossip
     *
     * @param numNodes the number of nodes in the network
     * @param latency  the latencies between nodes
     * @param time     the current time
     */
    public SimpleSimulatedGossip(final int numNodes, @NonNull final NetworkLatency latency, @NonNull final Time time) {
        this.latency = Objects.requireNonNull(latency);
        this.time = Objects.requireNonNull(time);

        nodes = new HashMap<>(numNodes);
        inTransit = new HashMap<>(numNodes);
        delivered = new HashMap<>(numNodes);
        sentBy = new HashMap<>(numNodes);
    }

    /**
     * Set the instance of the node to send events to
     *
     * @param node the node to send events to
     */
    public void setNode(@NonNull final GossipMessageHandler node) {
        Objects.requireNonNull(node);
        nodes.put(node.getNodeId(), node);
        inTransit.put(node.getNodeId(), new ArrayDeque<>());
        delivered.put(node.getNodeId(), new ArrayDeque<>());
        sentBy.put(node.getNodeId(), new ArrayDeque<>());
    }

    /**
     * Gossips messages to the message recipients. Messages will be delivered once the applicable latency has elapsed.
     *
     * @param messages the messages to gossip
     */
    public void gossipPayloads(final @NonNull List<GossipMessage> messages) {
        Objects.requireNonNull(messages);
        messages.forEach(this::gossipPayload);
    }

    /**
     * Get all the messages (not just events) delivered to a node via gossip
     *
     * @param nodeId the id of the node
     * @return all messages delivered to the node
     */
    public @NonNull Deque<GossipMessage> getDeliveredTo(@NonNull final NodeId nodeId) {
        Objects.requireNonNull(nodeId);
        return delivered.get(nodeId);
    }

    /**
     * Returns all the messages of a certain type delivered to a node.
     *
     * @param nodeId the node id of the receiver
     * @param clazz  the class of messages to return
     * @param <T>    the message type
     * @return the list of sent messages
     */
    @SuppressWarnings("unchecked")
    public <T extends SelfSerializable> @NonNull Deque<T> getDeliveredTo(
            @NonNull final NodeId nodeId, @NonNull final Class<T> clazz) {
        Objects.requireNonNull(nodeId);
        Objects.requireNonNull(clazz);
        return getDeliveredTo(nodeId).stream()
                .filter(msg -> clazz.isAssignableFrom(msg.message().getClass()))
                .map(msg -> (T) msg.message())
                .collect(Collectors.toCollection(LinkedList::new));
    }

    /**
     * Get all the messages (not just events) sent by a node via gossip
     *
     * @param nodeId the id of the node
     * @return all messages sent by the node
     */
    public @NonNull Deque<GossipMessage> getSentBy(@NonNull final NodeId nodeId) {
        return sentBy.get(nodeId);
    }

    /**
     * Returns all the messages of a certain type sent by a node.
     *
     * @param nodeId the node id of the sender
     * @param clazz  the class of messages to return
     * @param <T>    the message type
     * @return the list of sent messages
     */
    @SuppressWarnings("unchecked")
    public <T extends SelfSerializable> @NonNull Deque<T> getSentBy(
            @NonNull final NodeId nodeId, @NonNull final Class<T> clazz) {
        Objects.requireNonNull(nodeId);
        Objects.requireNonNull(clazz);
        return getSentBy(nodeId).stream()
                .filter(msg -> clazz.isAssignableFrom(msg.message().getClass()))
                .map(msg -> (T) msg.message())
                .collect(Collectors.toCollection(LinkedList::new));
    }

    /**
     * Gossip an event to other nodes with the provided latency. This method will not actually do any gossiping, it will
     * only enqueue the events to send them later with {@link #distribute()}
     *
     * @param message the message to gossip
     */
    public void gossipPayload(@NonNull final GossipMessage message) {
        Objects.requireNonNull(message);
        sentBy.get(message.senderId()).add(message);
        if (message.recipientId() == null) {
            sendToAllPeers(message);
        } else {
            sendToPeer(message);
        }
    }

    private void sendToPeer(@NonNull final GossipMessage message) {
        final NodeId recipient = message.recipientId();
        final Duration delay = latency.getLatency(message.senderId(), recipient);
        inTransit.get(recipient).add(new Payload(message, time.now().plus(delay)));
    }

    private void sendToAllPeers(@NonNull final GossipMessage message) {
        Objects.requireNonNull(message);
        for (final NodeId nodeId : nodes.keySet()) {
            if (nodeId != message.senderId()) {
                final Duration delay = latency.getLatency(message.senderId(), nodeId);
                inTransit.get(nodeId).add(new Payload(message, time.now().plus(delay)));
            }
        }
    }

    /**
     * Distribute any previously gossipped events to other nodes if they are ready for arrival
     */
    public void distribute() {
        for (final Entry<NodeId, Deque<Payload>> entry : inTransit.entrySet()) {
            final Deque<Payload> queue = entry.getValue();
            final NodeId nodeId = entry.getKey();
            for (final Iterator<Payload> iterator = queue.iterator(); iterator.hasNext(); ) {
                final Payload payload = iterator.next();
                if (!time.now().isBefore(payload.arrivalTime())) {
                    nodes.get(nodeId)
                            .handleMessageFromWire(payload.gossipMessage().message(), payload.gossipMessage.senderId());
                    delivered.get(nodeId).add(payload.gossipMessage);
                    iterator.remove();
                }
            }
        }
    }

    /**
     * Applies a network configuration to this gossip simulation.
     *
     * @param networkConfig the configuration to apply
     */
    public void applyConfig(@NonNull final NetworkConfig networkConfig) {
        for (final Map.Entry<NodeId, NodeConfig> entry :
                networkConfig.nodeConfigs().entrySet()) {
            final NodeId nodeId = entry.getKey();
            final NodeConfig nodeConfig = entry.getValue();
            if (!nodeConfig.customLatency().isZero()) {
                latency.setLatency(nodeId, nodeConfig.customLatency());
            }
        }
    }

    /**
     * Prints all the queues of messages to std out.
     */
    public void printQueues() {
        final StringBuilder sb = new StringBuilder();

        printInTransit(sb);
        printDelivered(sb);
        //        printSentBy(sb);

        System.out.println(sb);
    }

    private void printSentBy(@NonNull final StringBuilder sb) {
        for (final Entry<NodeId, Deque<GossipMessage>> entry : sentBy.entrySet()) {
            final Deque<GossipMessage> queue = entry.getValue();
            sb.append(String.format("Messages sent by %s (%s messages)%n", entry.getKey(), queue.size()));
            for (final GossipMessage msg : queue) {
                sb.append("\t").append(msg).append("\n");
            }
            sb.append("\n");
        }
    }

    private void printDelivered(@NonNull final StringBuilder sb) {
        for (final Entry<NodeId, Deque<GossipMessage>> entry : delivered.entrySet()) {
            final Deque<GossipMessage> queue = entry.getValue();
            sb.append(String.format("Messages delivered to %s (%s messages)%n", entry.getKey(), queue.size()));
            for (final GossipMessage msg : queue) {
                sb.append("\t").append(msg).append("\n");
            }
            sb.append("\n");
        }
    }

    private void printInTransit(@NonNull final StringBuilder sb) {
        for (final Entry<NodeId, Deque<Payload>> entry : inTransit.entrySet()) {
            final Deque<Payload> queue = entry.getValue();
            sb.append(String.format("Messages in transit to %s (%s messages)%n", entry.getKey(), queue.size()));
            for (final Payload payload : queue) {
                sb.append("\t").append(payload).append("\n");
            }
            sb.append("\n");
        }
    }

    /**
     * A payload to gossip at a set time.
     *
     * @param gossipMessage the message to gossip
     * @param arrivalTime   the time to send the message
     */
    public record Payload(@NonNull GossipMessage gossipMessage, @NonNull Instant arrivalTime) {

        @Override
        public String toString() {
            return gossipMessage.toString();
        }
    }
}
