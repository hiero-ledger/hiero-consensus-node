// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.network;

import com.swirlds.metrics.api.FloatFormats;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.hiero.consensus.metrics.RunningAverageMetric;
import org.hiero.consensus.metrics.SpeedometerMetric;
import org.hiero.consensus.metrics.extensions.CountPerSecond;
import org.hiero.consensus.metrics.statistics.AtomicAverage;
import org.hiero.consensus.metrics.statistics.AverageAndMin;
import org.hiero.consensus.model.node.NodeId;

/**
 * Collection of metrics related to the network
 */
public class NetworkMetrics {

    private static final String PING_CATEGORY = "ping";
    private static final String BPSS_CATEGORY = "bpss";
    private static final double PING_DECAY = 0.1;

    private static final RunningAverageMetric.Config AVG_PING_CONFIG = new RunningAverageMetric.Config(
                    Metrics.PLATFORM_CATEGORY, "ping")
            .withDescription("average time for a round trip message between 2 computers (in microseconds)")
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
    private final ConcurrentHashMap<NodeId, AverageAndMin> nodePingMetric = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<NodeId, AtomicAverage> nodePingValue = new ConcurrentHashMap<>();
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
     * @param metrics a reference to the metrics-system
     * @param selfId  this node's id
     * @param peers   list of all peers to pre-create dynamic metrics
     * @throws IllegalArgumentException if {@code platform} is {@code null}
     */
    public NetworkMetrics(@NonNull final Metrics metrics, @NonNull final NodeId selfId, final List<PeerInfo> peers) {
        this.selfId = Objects.requireNonNull(selfId, "The selfId must not be null.");
        this.metrics = Objects.requireNonNull(metrics, "The metrics must not be null.");

        avgPing = metrics.getOrCreate(AVG_PING_CONFIG);
        bytesPerSecondSent = metrics.getOrCreate(BYTES_PER_SECOND_SENT_CONFIG);
        avgConnsCreated = metrics.getOrCreate(AVG_CONNS_CREATED_CONFIG);

        precreateDynamicMetrics(peers, selfId);
    }

    /**
     * Out metric csv report needs all the metrics upfront to not get confused
     *
     * @param peers  list of all peers to pre-create dynamic metrics
     * @param selfId do not create metrics for selfId
     */
    private void precreateDynamicMetrics(final List<PeerInfo> peers, final NodeId selfId) {
        for (final PeerInfo peer : peers) {
            if (peer.nodeId().equals(selfId)) {
                continue;
            }
            final NodeId nodeId = peer.nodeId();
            recordPingTime(nodeId, 0);
            getDisconnectMetric(nodeId);
            getAverageBytesPerSecondSentMetric(nodeId);
        }
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

        final long pingMicros = TimeUnit.NANOSECONDS.toMicros(pingNanos);
        nodePingMetric
                .computeIfAbsent(
                        node,
                        nodeId -> new AverageAndMin(
                                metrics,
                                PING_CATEGORY,
                                String.format("ping_us_%02d", nodeId.id()),
                                String.format(
                                        "microseconds to send node %02d a ping message and receive a reply",
                                        nodeId.id()),
                                FloatFormats.FORMAT_10_2,
                                PING_DECAY,
                                9_999_999))
                .update(pingMicros);

        nodePingValue
                .computeIfAbsent(node, nodeId -> new AtomicAverage(PING_DECAY))
                .update(pingMicros);
    }

    /**
     * Updates the metrics.
     * <p>
     * This method will be called by {@link com.swirlds.metrics.api.Metrics} and is not intended to be called from
     * anywhere else.
     */
    public void update() {
        // calculate the value for otherStatPing (the average of all, not including self)
        double sum = 0;
        int count = 0;
        for (final AtomicAverage average : nodePingValue.values()) {
            sum += average.get();
            count++;
        }

        if (count >= 1) {
            final double pingValue = sum / (count); // pingValue is in microseconds
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

                getAverageBytesPerSecondSentMetric(otherId).update(bytesSent);

                if (!conn.connected()) {
                    iterator.remove();
                }
            }
        }
        bytesPerSecondSent.update(totalBytesSent);
        avgConnsCreated.update(connsCreated.sum());
    }

    private SpeedometerMetric getAverageBytesPerSecondSentMetric(final NodeId otherId) {
        return avgBytePerSecSent.computeIfAbsent(
                otherId,
                nodeId -> metrics.getOrCreate(new SpeedometerMetric.Config(
                                BPSS_CATEGORY, String.format("bytes_per_sec_sent_%02d", nodeId.id()))
                        .withDescription(String.format("bytes per second sent to node %02d", nodeId.id()))
                        .withFormat(FloatFormats.FORMAT_16_2)));
    }

    /**
     * Records the occurrence of a disconnect.
     *
     * @param connection the connection that was closed.
     */
    public void recordDisconnect(@NonNull final Connection connection) {
        final NodeId otherId = Objects.requireNonNull(connection, "connection must not be null.")
                .getOtherId();

        getDisconnectMetric(otherId).count();
    }

    private CountPerSecond getDisconnectMetric(final NodeId otherId) {
        return disconnectFrequency.computeIfAbsent(
                otherId,
                nodeId -> new CountPerSecond(
                        metrics,
                        new CountPerSecond.Config(
                                        Metrics.PLATFORM_CATEGORY,
                                        String.format("disconnects_per_sec_%02d", nodeId.id()))
                                .withDescription(
                                        String.format("number of disconnects per second from node %02d", nodeId.id()))
                                .withFormat(FloatFormats.FORMAT_10_0)));
    }
}
