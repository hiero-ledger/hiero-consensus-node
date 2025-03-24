// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network;

import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.common.metrics.extensions.CountPerSecond;
import com.swirlds.metrics.api.FloatFormats;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import org.hiero.consensus.model.node.NodeId;

/**
 * Collection of metrics related to the network
 */
public class NetworkMetrics {

    private static final String PING_CATEGORY = "ping";
    private static final String BPSS_CATEGORY = "bpss";

    private static final RunningAverageMetric.Config AVG_PING_CONFIG = new RunningAverageMetric.Config(
                    Metrics.PLATFORM_CATEGORY, "ping")
            .withDescription("average time for a round trip message between 2 computers (in milliseconds)")
            .withFormat(FloatFormats.FORMAT_7_0);
    private static final SpeedometerMetric.Config BYTES_PER_SECOND_SENT_CONFIG = new SpeedometerMetric.Config(
                    Metrics.INTERNAL_CATEGORY, "bytes_per_sec_sent")
            .withDescription("number of bytes sent per second over the network (total for this member)")
            .withFormat(FloatFormats.FORMAT_16_2);
    private static final RunningAverageMetric.Config AVG_CONNS_CREATED_CONFIG = new RunningAverageMetric.Config(
                    Metrics.PLATFORM_CATEGORY, "conns")
            .withDescription("number of times a TLS connections was created")
            .withFormat(FloatFormats.FORMAT_10_0)
            .withHalfLife(0.0);

    /**
     * this node's id
     */
    private final NodeId selfId;
    /**
     * all connections of this platform
     */
    private final Queue<Connection> connections = new ConcurrentLinkedQueue<>();
    /**
     * total number of connections created so far (both caller and listener)
     */
    private final LongAdder connsCreated = new LongAdder();

    /**
     * the average ping time for each node
     */
    private final ConcurrentHashMap<NodeId, RunningAverageMetric> avgPingMilliseconds = new ConcurrentHashMap<>();
    /**
     * the average number of bytes sent per second for each node
     */
    private final ConcurrentHashMap<NodeId, SpeedometerMetric> avgBytePerSecSent = new ConcurrentHashMap<>();
    /**
     * the average ping to all nodes
     */
    private final RunningAverageMetric avgPing;
    /**
     * the total bytes per second to all nodes
     */
    private final SpeedometerMetric bytesPerSecondSent;
    /**
     * the average number of connections created per second
     */
    private final RunningAverageMetric avgConnsCreated;
    /**
     * Number of disconnects per second per peer in the address book.
     */
    private final ConcurrentHashMap<NodeId, CountPerSecond> disconnectFrequency = new ConcurrentHashMap<>();

    private final Metrics metrics;

    /**
     * Constructor of {@code NetworkMetrics}
     *
     * @param metrics  a reference to the metrics-system
     * @param selfId   this node's id
     * @throws IllegalArgumentException if {@code platform} is {@code null}
     */
    public NetworkMetrics(@NonNull final Metrics metrics, @NonNull final NodeId selfId) {
        this.selfId = Objects.requireNonNull(selfId, "The selfId must not be null.");
        this.metrics = Objects.requireNonNull(metrics, "The metrics must not be null.");

        avgPing = metrics.getOrCreate(AVG_PING_CONFIG);
        bytesPerSecondSent = metrics.getOrCreate(BYTES_PER_SECOND_SENT_CONFIG);
        avgConnsCreated = metrics.getOrCreate(AVG_CONNS_CREATED_CONFIG);
    }

    /**
     * Notifies the stats that a new connection has been established
     *
     * @param connection a new connection
     */
    public void connectionEstablished(@Nullable final Connection connection) {
        if (connection == null) {
            return;
        }
        connections.add(connection);
        connsCreated.increment(); // count new connections
    }

    /**
     * Record the ping time to this particular node
     *
     * @param node      the node to which the latency is referring to
     * @param pingNanos the ping time, in nanoseconds
     */
    public void recordPingTime(@NonNull final NodeId node, final long pingNanos) {
        Objects.requireNonNull(node, "The node must not be null.");
        avgPingMilliseconds
                .computeIfAbsent(
                        node,
                        nodeId -> metrics.getOrCreate(new RunningAverageMetric.Config(
                                        PING_CATEGORY, String.format("ping_ms_%02d", nodeId.id()))
                                .withDescription(String.format(
                                        "milliseconds to send node %02d a byte and receive a reply", nodeId.id()))
                                .withFormat(FloatFormats.FORMAT_4_2)))
                .update((pingNanos) / 1_000_000.0);
    }

    /**
     * Updates the metrics.
     * <p>
     * This method will be called by {@link com.swirlds.metrics.api.Metrics} and is not intended to be called from anywhere else.
     */
    public void update() {
        // calculate the value for otherStatPing (the average of all, not including self)
        double sum = 0;
        int count = 0;
        for (final RunningAverageMetric metric : avgPingMilliseconds.values()) {
            if (metric != null) {
                sum += metric.get();
                count++;
            }
        }

        if (count > 1) {
            // don't average in the times[selfId]==0, so subtract 1 from the count
            final double pingValue = sum / (count - 1); // pingValue is in milliseconds
            avgPing.update(pingValue);
        } else {
            // we are not yet connected to any other node
            avgPing.update(0);
        }

        long totalBytesSent = 0;
        for (final Iterator<Connection> iterator = connections.iterator(); iterator.hasNext(); ) {
            final Connection conn = iterator.next();
            if (conn != null) {
                final long bytesSent = conn.getDos().getConnectionByteCounter().getAndResetCount();
                totalBytesSent += bytesSent;
                final NodeId otherId = conn.getOtherId();

                avgBytePerSecSent
                        .computeIfAbsent(
                                otherId,
                                nodeId -> metrics.getOrCreate(new SpeedometerMetric.Config(
                                                BPSS_CATEGORY, String.format("bytes_per_sec_sent_%02d", nodeId.id()))
                                        .withDescription(
                                                String.format("bytes per second sent to node %02d", nodeId.id()))
                                        .withFormat(FloatFormats.FORMAT_16_2)))
                        .update(bytesSent);

                if (!conn.connected()) {
                    iterator.remove();
                }
            }
        }
        bytesPerSecondSent.update(totalBytesSent);
        avgConnsCreated.update(connsCreated.sum());
    }

    /**
     * Returns the time for a round-trip message to each member (in milliseconds).
     * <p>
     * This is an exponentially-weighted average of recent ping times.
     *
     * @return the average times, for each member, in milliseconds
     */
    @NonNull
    public Map<NodeId, Double> getAvgPingMilliseconds() {
        final Map<NodeId, Double> times = new HashMap<>();
        avgPingMilliseconds.forEach((nodeId, metric) -> times.put(nodeId, metric.get()));
        times.put(selfId, 0.0);
        return times;
    }

    /**
     * Records the occurrence of a disconnect.
     *
     * @param connection the connection that was closed.
     */
    public void recordDisconnect(@NonNull final Connection connection) {
        final NodeId otherId = Objects.requireNonNull(connection, "connection must not be null.")
                .getOtherId();

        disconnectFrequency
                .computeIfAbsent(
                        otherId,
                        nodeId -> new CountPerSecond(
                                metrics,
                                new CountPerSecond.Config(
                                                Metrics.PLATFORM_CATEGORY,
                                                String.format("disconnects_per_sec_%02d", nodeId.id()))
                                        .withDescription(String.format(
                                                "number of disconnects per second from node %02d", nodeId.id()))
                                        .withFormat(FloatFormats.FORMAT_10_0)))
                .count();
    }
}
