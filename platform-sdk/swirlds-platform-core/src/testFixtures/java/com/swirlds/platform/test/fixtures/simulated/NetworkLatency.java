// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.simulated;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import org.hiero.consensus.model.node.NodeId;

/**
 * Generates random latencies between nodes in the provided address book
 */
public class NetworkLatency {
    private final Map<NodeId, Latency> delays = new HashMap<>();

    private NetworkLatency(@NonNull final HashMap<NodeId, Latency> delays) {
        Objects.requireNonNull(delays);
        this.delays.putAll(delays);
    }

    /**
     * Creates a {@link NetworkLatency} model where the latencies between all peers are the same.
     *
     * @param nodeIds the nodeIds of all nodes in the network
     * @param latency the latency between each pair of peers
     * @return the {@link NetworkLatency} object
     */
    public static @NonNull NetworkLatency uniformLatency(
            @NonNull final Set<NodeId> nodeIds, @NonNull final Duration latency) {
        Objects.requireNonNull(nodeIds);
        Objects.requireNonNull(latency);
        final HashMap<NodeId, Latency> delays = new HashMap<>();
        for (final NodeId nodeId : nodeIds) {
            delays.put(nodeId, new Latency(latency));
        }
        return new NetworkLatency(delays);
    }

    /**
     * Creates a hub-and-spoke {@link NetworkLatency} model with randomized latencies. Each node is assigned a randomly
     * generated delay. The delay between any two peers for a one-way trip is the sum nodes' delays. Some spokes may be
     * long, others may be short, but all messages sent to a from a particular peer include that peer's set delay. This
     * differs from real life in that it does not allow two nodes to have low latency with each other while also having
     * high latency with other nodes.
     *
     * @param nodeIds  the nodeIds of all nodes in the network
     * @param maxDelay the maximum one-way delay between 2 peers
     * @param random   source of randomness
     * @return the {@link NetworkLatency} object
     */
    public @NonNull static NetworkLatency randomLatency(
            @NonNull final Set<NodeId> nodeIds, @NonNull final Duration maxDelay, @NonNull final Random random) {
        Objects.requireNonNull(nodeIds);
        Objects.requireNonNull(maxDelay);
        Objects.requireNonNull(random);
        HashMap<NodeId, Latency> delays = new HashMap<>();
        for (final NodeId nodeId : nodeIds) {
            final Latency latency = new Latency(
                    Duration.ofMillis(random.nextInt((int) maxDelay.dividedBy(2).toMillis())));
            delays.put(nodeId, latency);
        }
        return new NetworkLatency(delays);
    }

    /**
     * Overrides the latency for a particular node
     *
     * @param nodeId  the node whose latency we are setting
     * @param latency the latency to set
     */
    public void setLatency(@NonNull final NodeId nodeId, @NonNull final Latency latency) {
        Objects.requireNonNull(nodeId);
        Objects.requireNonNull(latency);
        delays.put(nodeId, latency);
    }

    /**
     * @param from the ID of the first node
     * @param to   the ID of the second node
     * @return the delay between the two nodes
     */
    public @NonNull Duration getLatency(@NonNull final NodeId from, @NonNull final NodeId to) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        return delays.get(from).delay().plus(delays.get(to).delay());
    }
}
